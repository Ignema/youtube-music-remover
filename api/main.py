"""FastAPI backend for Murem — YouTube Music Remover.

Security notes:
- CORS is set to allow all origins for local/personal use.
  If exposing to the internet, restrict `allow_origins` to your domain.
- db_set() builds column names from **kwargs keys, but these are only ever
  called with hardcoded keys from within this module — never from user input.
"""

import asyncio
import json
import logging
import re
import shutil
import sqlite3
import subprocess
import tempfile
import threading
import time
import uuid
from contextlib import asynccontextmanager
from pathlib import Path
from typing import Optional

from fastapi import FastAPI, HTTPException, Request, UploadFile, File, Form, WebSocket
from fastapi.middleware.cors import CORSMiddleware
from fastapi.responses import FileResponse
from pydantic import BaseModel, field_validator

from api.utils import extract_video_id, normalize_url, is_valid_url, run_cmd, YT_ID_RE

try:
    from slowapi import Limiter
    from slowapi.util import get_remote_address
    limiter = Limiter(key_func=get_remote_address)
    _has_limiter = True
except ImportError:
    limiter = None
    _has_limiter = False


def rate_limit(limit_string: str):
    """Decorator that applies rate limiting if slowapi is available, no-op otherwise."""
    def decorator(func):
        if _has_limiter and limiter:
            return limiter.limit(limit_string)(func)
        return func
    return decorator

# --- Logging ---
logging.basicConfig(
    level=logging.INFO,
    format="%(asctime)s [%(levelname)s] %(message)s",
)
logger = logging.getLogger("murem")

# --- Constants ---
MODELS = [
    "UVR-MDX-NET-Inst_HQ_3.onnx",
    "Kim_Vocal_2.onnx",
    "UVR_MDXNET_KARA_2.onnx",
    "vocals_mel_band_roformer.ckpt",
    "model_bs_roformer_ep_317_sdr_12.9755.ckpt",
]
BITRATES = ["128k", "192k", "320k"]

# --- GPU Detection ---
def has_gpu() -> bool:
    """Check if CUDA GPU is available for onnxruntime."""
    try:
        import onnxruntime
        return "CUDAExecutionProvider" in onnxruntime.get_available_providers()
    except Exception:
        return False

HAS_GPU = has_gpu()

# Time estimate multipliers (seconds of processing per second of audio)
# Based on empirical measurements
TIME_MULTIPLIERS = {
    # MDX-Net models (ONNX) — fast
    "onnx_gpu": 0.3,
    "onnx_cpu": 2.0,
    # Roformer models (CKPT) — slower but better quality
    "ckpt_gpu": 0.8,
    "ckpt_cpu": 6.0,
}

def estimate_time(duration_seconds: float, model: str) -> int:
    """Estimate processing time in seconds based on video duration and model."""
    if duration_seconds <= 0:
        return 0
    is_roformer = model.endswith(".ckpt")
    if is_roformer:
        mult = TIME_MULTIPLIERS["ckpt_gpu"] if HAS_GPU else TIME_MULTIPLIERS["ckpt_cpu"]
    else:
        mult = TIME_MULTIPLIERS["onnx_gpu"] if HAS_GPU else TIME_MULTIPLIERS["onnx_cpu"]
    # Add ~10s overhead for download/merge
    return int(duration_seconds * mult) + 10
JOB_TTL_HOURS = 24  # Auto-cleanup jobs older than this

# --- SQLite Job Store ---

DB_PATH = Path("jobs.db")
_db_lock = threading.Lock()


def _get_conn() -> sqlite3.Connection:
    conn = sqlite3.connect(str(DB_PATH))
    conn.row_factory = sqlite3.Row
    return conn


def init_db():
    with _db_lock:
        conn = _get_conn()
        conn.execute("""
            CREATE TABLE IF NOT EXISTS jobs (
                id TEXT PRIMARY KEY,
                status TEXT DEFAULT 'queued',
                progress INTEGER DEFAULT 0,
                error TEXT,
                output_path TEXT,
                filename TEXT,
                metadata TEXT,
                eta_seconds INTEGER DEFAULT 0,
                created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
            )
        """)
        # Migrate: add columns if missing
        existing = {row[1] for row in conn.execute("PRAGMA table_info(jobs)").fetchall()}
        if "metadata" not in existing:
            conn.execute("ALTER TABLE jobs ADD COLUMN metadata TEXT")
        if "eta_seconds" not in existing:
            conn.execute("ALTER TABLE jobs ADD COLUMN eta_seconds INTEGER DEFAULT 0")
        conn.commit()
        conn.close()


def db_set(job_id: str, **kwargs):
    """Update job fields. Keys are hardcoded by callers — never from user input."""
    allowed_keys = {"status", "progress", "error", "output_path", "filename", "metadata", "eta_seconds"}
    for k in kwargs:
        if k not in allowed_keys:
            raise ValueError(f"Invalid job field: {k}")
    with _db_lock:
        conn = _get_conn()
        sets = ", ".join(f"{k} = ?" for k in kwargs)
        vals = list(kwargs.values()) + [job_id]
        conn.execute(f"UPDATE jobs SET {sets} WHERE id = ?", vals)
        conn.commit()
        conn.close()


def db_get(job_id: str) -> Optional[dict]:
    with _db_lock:
        conn = _get_conn()
        row = conn.execute("SELECT * FROM jobs WHERE id = ?", (job_id,)).fetchone()
        conn.close()
        return dict(row) if row else None


def db_create(job_id: str):
    with _db_lock:
        conn = _get_conn()
        conn.execute("INSERT INTO jobs (id) VALUES (?)", (job_id,))
        conn.commit()
        conn.close()


def cleanup_old_jobs():
    """Remove jobs and output files older than JOB_TTL_HOURS."""
    logger.info("Cleaning up old jobs...")
    with _db_lock:
        conn = _get_conn()
        old = conn.execute(
            "SELECT id, output_path FROM jobs WHERE created_at < datetime('now', ?)",
            (f"-{JOB_TTL_HOURS} hours",),
        ).fetchall()
        for row in old:
            path = row["output_path"]
            if path and Path(path).exists():
                Path(path).unlink(missing_ok=True)
                logger.info(f"Deleted output: {path}")
        conn.execute(
            "DELETE FROM jobs WHERE created_at < datetime('now', ?)",
            (f"-{JOB_TTL_HOURS} hours",),
        )
        conn.commit()
        conn.close()
    logger.info(f"Cleaned up {len(old)} old job(s)")


def cleanup_orphaned_temp_dirs():
    """Remove stale temp directories from crashed processes."""
    temp_root = Path(tempfile.gettempdir())
    count = 0
    for d in temp_root.iterdir():
        if d.is_dir() and d.name.startswith("tmp"):
            # Only clean dirs older than 1 hour
            try:
                age = time.time() - d.stat().st_mtime
                if age > 3600:
                    shutil.rmtree(d, ignore_errors=True)
                    count += 1
            except OSError:
                pass
    if count:
        logger.info(f"Cleaned up {count} orphaned temp dir(s)")

# --- WebSocket connections (thread-safe) ---

_ws_lock = threading.Lock()
ws_clients: dict[str, list[WebSocket]] = {}
ws_loop: Optional[asyncio.AbstractEventLoop] = None


def update_job(job_id: str, **kwargs):
    """Update job in DB and notify WebSocket clients."""
    db_set(job_id, **kwargs)
    logger.info(f"Job {job_id[:8]}: {kwargs}")
    data = {"job_id": job_id, **kwargs}
    with _ws_lock:
        clients = list(ws_clients.get(job_id, []))
    dead = []
    for ws in clients:
        try:
            if ws_loop and ws_loop.is_running():
                asyncio.run_coroutine_threadsafe(ws.send_json(data), ws_loop)
        except Exception:
            dead.append(ws)
    # Remove dead connections
    if dead:
        with _ws_lock:
            for ws in dead:
                if job_id in ws_clients and ws in ws_clients[job_id]:
                    ws_clients[job_id].remove(ws)


def is_cancelled(job_id: str) -> bool:
    """Check if a job has been cancelled."""
    job = db_get(job_id)
    return job is not None and job["status"] == "cancelled"


# --- Lifespan (replaces deprecated on_event) ---

@asynccontextmanager
async def lifespan(app: FastAPI):
    global ws_loop
    ws_loop = asyncio.get_event_loop()
    init_db()
    cleanup_old_jobs()
    cleanup_orphaned_temp_dirs()
    logger.info("Murem API started")

    # Periodic cleanup task
    async def periodic_cleanup():
        while True:
            await asyncio.sleep(6 * 3600)  # Every 6 hours
            cleanup_old_jobs()
            prune_dead_ws_clients()

    cleanup_task = asyncio.create_task(periodic_cleanup())
    yield
    cleanup_task.cancel()
    logger.info("Murem API shutting down")


def prune_dead_ws_clients():
    """Remove closed WebSocket connections from the client list."""
    with _ws_lock:
        for job_id in list(ws_clients.keys()):
            ws_clients[job_id] = [
                ws for ws in ws_clients[job_id]
                if not getattr(ws, "client_state", None)
                or ws.client_state.name != "DISCONNECTED"
            ]
            if not ws_clients[job_id]:
                del ws_clients[job_id]
    logger.debug("Pruned dead WebSocket clients")


app = FastAPI(title="Murem API", lifespan=lifespan)

if _has_limiter:
    app.state.limiter = limiter
    from slowapi.errors import RateLimitExceeded
    from slowapi import _rate_limit_exceeded_handler
    app.add_exception_handler(RateLimitExceeded, _rate_limit_exceeded_handler)

app.add_middleware(
    CORSMiddleware,
    # NOTE: For local/personal use. Restrict origins if exposing to the internet.
    allow_origins=["*"],
    allow_methods=["*"],
    allow_headers=["*"],
)

# --- Input Validation ---


class ProcessRequest(BaseModel):
    url: str
    model: str = "Kim_Vocal_2.onnx"
    batch_size: int = 4
    audio_only: bool = False
    bitrate: str = "192k"

    @field_validator("url")
    @classmethod
    def validate_url(cls, v: str) -> str:
        v = v.strip()
        if not v:
            raise ValueError("URL is required")
        if not is_valid_url(v):
            raise ValueError("Invalid URL — enter a video URL or YouTube video ID")
        return v

    @field_validator("model")
    @classmethod
    def validate_model(cls, v: str) -> str:
        # Allow known models and any custom model filename (audio-separator validates it)
        if not v or not v.strip():
            raise ValueError("Model name is required")
        return v.strip()

    @field_validator("bitrate")
    @classmethod
    def validate_bitrate(cls, v: str) -> str:
        if v not in BITRATES:
            raise ValueError(f"Invalid bitrate. Choose from: {BITRATES}")
        return v

    @field_validator("batch_size")
    @classmethod
    def validate_batch_size(cls, v: int) -> int:
        if not 1 <= v <= 8:
            raise ValueError("Batch size must be 1-8")
        return v

# --- Processing Pipelines ---

def separate_and_merge(job_id: str, video_file: Path, audio_file: Path,
                       model: str, batch_size: int, title: str,
                       temp_dir: Path, output_dir: Path,
                       audio_only: bool = False, bitrate: str = "192k") -> bool:
    """Shared logic: separate vocals → merge/export → output."""
    if is_cancelled(job_id):
        shutil.rmtree(temp_dir, ignore_errors=True)
        return False

    # Check if model needs downloading (first-time use)
    model_dir = Path.home() / "audio-separator-models"
    model_path = model_dir / model
    if not model_path.exists():
        update_job(job_id, status="downloading_model", progress=25)

    update_job(job_id, status="separating", progress=30)

    sep_proc = subprocess.Popen(
        [
            "audio-separator", str(audio_file),
            "--model_filename", model,
            "--mdx_batch_size", str(batch_size),
            "--output_dir", str(temp_dir),
            "--output_format", "WAV",
        ],
        stdout=subprocess.PIPE, stderr=subprocess.PIPE, text=True,
    )
    pct_re = re.compile(r"(\d+)%\|")
    download_re = re.compile(r"Downloading|downloading|model.*download", re.IGNORECASE)
    max_sep_pct = 0
    model_downloaded = model_path.exists()
    for line in iter(sep_proc.stderr.readline, ""):
        # Detect model download phase
        if not model_downloaded and download_re.search(line):
            update_job(job_id, status="downloading_model", progress=25)
        elif not model_downloaded and max_sep_pct == 0:
            # Check if model appeared (download finished)
            if model_path.exists():
                model_downloaded = True
                update_job(job_id, status="separating", progress=30)
        m = pct_re.search(line)
        if m:
            if not model_downloaded:
                model_downloaded = True
                update_job(job_id, status="separating", progress=30)
            sep_pct = int(m.group(1))
            max_sep_pct = max(max_sep_pct, sep_pct)
            update_job(job_id, progress=30 + int(max_sep_pct * 0.4))
        # Check cancellation during separation
        if is_cancelled(job_id):
            sep_proc.kill()
            sep_proc.wait()
            shutil.rmtree(temp_dir, ignore_errors=True)
            return False
    sep_proc.wait()
    if sep_proc.returncode != 0:
        if is_cancelled(job_id):
            shutil.rmtree(temp_dir, ignore_errors=True)
            return False
        update_job(job_id, status="error", error="Separation failed")
        return False

    if is_cancelled(job_id):
        shutil.rmtree(temp_dir, ignore_errors=True)
        return False

    vocals = [
        f for f in temp_dir.iterdir()
        if "vocals" in f.name.lower() and f.suffix == ".wav"
    ]
    if not vocals:
        update_job(job_id, status="error", error="Vocals file not found")
        return False
    vocals_file = vocals[0]

    safe_title = re.sub(r'[\\/:*?"<>|]', "_", title)
    update_job(job_id, status="merging", progress=75)

    if audio_only:
        final_output = output_dir / f"{safe_title}-vocals-only.mp3"
        ok, _, err = run_cmd(
            "ffmpeg", "-i", str(vocals_file),
            "-c:a", "libmp3lame", "-b:a", bitrate, "-y", str(final_output),
        )
    else:
        final_output = output_dir / f"{safe_title}-vocals-only.mp4"
        ok, _, err = run_cmd(
            "ffmpeg", "-i", str(video_file), "-i", str(vocals_file),
            "-c:v", "copy", "-c:a", "aac", "-b:a", bitrate,
            "-map", "0:v:0", "-map", "1:a:0", "-shortest", "-y", str(final_output),
        )

    if not ok:
        update_job(job_id, status="error", error=f"Merge failed: {err}")
        return False

    shutil.rmtree(temp_dir, ignore_errors=True)
    update_job(job_id, status="done", progress=100,
               output_path=str(final_output), filename=final_output.name)
    logger.info(f"Job {job_id[:8]} complete: {final_output.name}")
    return True


def process_video(job_id: str, url: str, model: str, batch_size: int,
                  audio_only: bool = False, bitrate: str = "192k"):
    """Pipeline for YouTube URLs: download → separate → merge."""
    temp_dir = Path(tempfile.mkdtemp())
    output_dir = Path("output")
    output_dir.mkdir(exist_ok=True)

    try:
        update_job(job_id, status="downloading", progress=10)
        dl_url = normalize_url(url)

        # Use --print-json to get metadata during download (same auth session)
        ok, dl_stdout, err = run_cmd(
            "yt-dlp", "-f", "bv*,ba", "--print-json",
            "-o", f"{temp_dir}/%(format_id)s.%(ext)s", dl_url,
        )
        if not ok:
            update_job(job_id, status="error", error=f"Download failed: {err}")
            return

        # Parse metadata from download output
        title = url
        if dl_stdout:
            try:
                # --print-json may output multiple JSON objects for multiple formats
                # Take the last complete one
                for line in reversed(dl_stdout.strip().split("\n")):
                    line = line.strip()
                    if line.startswith("{"):
                        data = json.loads(line)
                        title = data.get("title", title)
                        meta = {
                            "title": data.get("title", ""),
                            "channel": data.get("channel", data.get("uploader", "")),
                            "duration": int(data.get("duration", 0) or 0),
                            "thumbnail": data.get("thumbnail", ""),
                            "view_count": data.get("view_count", 0),
                            "upload_date": data.get("upload_date", ""),
                            "description": (data.get("description", "") or "")[:300],
                        }
                        db_set(job_id, metadata=json.dumps(meta),
                               eta_seconds=estimate_time(data.get("duration", 0) or 0, model))
                        break
            except Exception:
                pass

        if is_cancelled(job_id):
            shutil.rmtree(temp_dir, ignore_errors=True)
            return

        video_files = [
            f for f in temp_dir.iterdir()
            if f.suffix in (".mp4", ".webm", ".mkv")
        ]
        if not video_files:
            update_job(job_id, status="error", error="No video file found")
            return
        video_file = max(video_files, key=lambda f: f.stat().st_size)

        audio_files = [
            f for f in temp_dir.iterdir()
            if f != video_file and f.suffix in (".opus", ".m4a", ".webm", ".mp3", ".wav")
        ]
        if not audio_files:
            # Single muxed file (TikTok, Twitter, etc.) — extract audio
            update_job(job_id, status="extracting", progress=15)
            extracted_audio = temp_dir / "extracted_audio.wav"
            ok_ext, _, err_ext = run_cmd(
                "ffmpeg", "-i", str(video_file),
                "-vn", "-acodec", "pcm_s16le", "-ar", "44100", "-ac", "2",
                "-y", str(extracted_audio),
            )
            if not ok_ext or not extracted_audio.exists():
                update_job(job_id, status="error", error=f"Audio extraction failed: {err_ext}")
                return
            audio_files = [extracted_audio]

        separate_and_merge(job_id, video_file, audio_files[0], model, batch_size,
                           title, temp_dir, output_dir, audio_only, bitrate)
    except Exception as e:
        logger.exception(f"Job {job_id[:8]} failed")
        update_job(job_id, status="error", error=str(e))
        if temp_dir.exists():
            shutil.rmtree(temp_dir, ignore_errors=True)


def process_upload(job_id: str, file_path: Path, original_name: str,
                   model: str, batch_size: int,
                   audio_only: bool = False, bitrate: str = "192k"):
    """Pipeline for uploaded files: extract audio → separate → merge."""
    temp_dir = file_path.parent
    output_dir = Path("output")
    output_dir.mkdir(exist_ok=True)

    try:
        update_job(job_id, status="extracting", progress=15)
        audio_file = temp_dir / "extracted_audio.wav"
        ok, _, err = run_cmd(
            "ffmpeg", "-i", str(file_path),
            "-vn", "-acodec", "pcm_s16le", "-ar", "44100", "-ac", "2",
            "-y", str(audio_file),
        )
        if not ok:
            update_job(job_id, status="error", error=f"Audio extraction failed: {err}")
            return

        if is_cancelled(job_id):
            shutil.rmtree(temp_dir, ignore_errors=True)
            return

        separate_and_merge(job_id, file_path, audio_file, model, batch_size,
                           Path(original_name).stem, temp_dir, output_dir,
                           audio_only, bitrate)
    except Exception as e:
        logger.exception(f"Job {job_id[:8]} failed")
        update_job(job_id, status="error", error=str(e))
        if temp_dir.exists():
            shutil.rmtree(temp_dir, ignore_errors=True)

# --- Routes ---

@app.get("/health")
def health_check():
    """Health check endpoint for monitoring."""
    return {"status": "ok", "version": "2.0.0", "gpu": HAS_GPU}


@app.get("/api/models")
def list_models():
    return {"models": MODELS, "bitrates": BITRATES}


@app.get("/api/info")
@rate_limit("20/minute")
async def video_info(request: Request, url: str):
    """Get video metadata without downloading. Works with any yt-dlp supported URL."""
    url = url.strip()
    if not is_valid_url(url):
        raise HTTPException(400, "Invalid URL")
    dl_url = normalize_url(url)

    import asyncio
    loop = asyncio.get_event_loop()
    ok, stdout, err = await loop.run_in_executor(
        None, lambda: run_cmd("yt-dlp", "--dump-json", "--no-download", dl_url, timeout=15)
    )
    if not ok:
        raise HTTPException(400, f"Failed to fetch info: {err}")
    try:
        data = json.loads(stdout)
    except Exception:
        raise HTTPException(500, "Failed to parse video info")

    return {
        "title": data.get("title", ""),
        "channel": data.get("channel", data.get("uploader", "")),
        "duration": int(data.get("duration", 0) or 0),
        "thumbnail": data.get("thumbnail", ""),
        "view_count": data.get("view_count", 0),
        "upload_date": data.get("upload_date", ""),
        "description": (data.get("description", "") or "")[:300],
    }


@app.post("/api/process")
@rate_limit("10/minute")
def start_processing(request: Request, req: ProcessRequest):
    job_id = str(uuid.uuid4())
    db_create(job_id)
    logger.info(f"New job {job_id[:8]} for URL: {req.url[:50]}")

    threading.Thread(
        target=process_video,
        args=(job_id, req.url, req.model, req.batch_size, req.audio_only, req.bitrate),
        daemon=True,
    ).start()

    return {"job_id": job_id}


@app.post("/api/batch")
@rate_limit("5/minute")
def batch_processing(
    request: Request,
    urls: list[str],
    model: str = "Kim_Vocal_2.onnx",
    batch_size: int = 4,
    audio_only: bool = False,
    bitrate: str = "192k",
):
    """Queue multiple URLs for sequential processing."""
    if not model or not model.strip():
        raise HTTPException(400, "Model name is required")

    job_ids = []
    for url in urls:
        if not is_valid_url(url.strip()):
            raise HTTPException(400, f"Invalid URL: {url}")
        job_id = str(uuid.uuid4())
        db_create(job_id)
        job_ids.append(job_id)

    logger.info(f"Batch: {len(job_ids)} jobs queued")

    def run_batch():
        for jid, url in zip(job_ids, urls):
            process_video(jid, url, model, batch_size, audio_only, bitrate)

    threading.Thread(target=run_batch, daemon=True).start()
    return {"job_ids": job_ids}


@app.post("/api/upload")
@rate_limit("10/minute")
async def upload_processing(
    request: Request,
    file: UploadFile = File(...),
    model: str = Form("Kim_Vocal_2.onnx"),
    batch_size: int = Form(4),
    audio_only: bool = Form(False),
    bitrate: str = Form("192k"),
):
    if not model or not model.strip():
        raise HTTPException(400, "Model name is required")
    if bitrate not in BITRATES:
        raise HTTPException(400, "Invalid bitrate")

    job_id = str(uuid.uuid4())
    db_create(job_id)
    update_job(job_id, status="uploading", progress=5)
    logger.info(f"Upload job {job_id[:8]}: {file.filename}")

    temp_dir = Path(tempfile.mkdtemp())
    suffix = Path(file.filename or "video.mp4").suffix or ".mp4"
    file_path = temp_dir / f"upload{suffix}"
    with open(file_path, "wb") as f:
        f.write(await file.read())

    threading.Thread(
        target=process_upload,
        args=(job_id, file_path, file.filename or "video.mp4", model, batch_size,
              audio_only, bitrate),
        daemon=True,
    ).start()

    return {"job_id": job_id}


@app.get("/api/status/{job_id}")
def get_status(job_id: str):
    job = db_get(job_id)
    if not job:
        raise HTTPException(404, "Job not found")
    result = dict(job)
    # Parse metadata JSON string into object for the response
    if result.get("metadata"):
        try:
            result["metadata"] = json.loads(result["metadata"])
        except Exception:
            pass
    return result


@app.post("/api/cancel/{job_id}")
def cancel_job(job_id: str):
    """Cancel a running job. The processing thread will stop at the next checkpoint."""
    job = db_get(job_id)
    if not job:
        raise HTTPException(404, "Job not found")
    if job["status"] in ("done", "error", "cancelled"):
        return {"status": job["status"]}
    db_set(job_id, status="cancelled")
    logger.info(f"Job {job_id[:8]} cancelled")
    return {"status": "cancelled"}


@app.get("/api/download/{job_id}")
def download_result(job_id: str):
    job = db_get(job_id)
    if not job:
        raise HTTPException(404, "Job not found")
    if job["status"] != "done":
        raise HTTPException(400, "Job not ready")
    path = job["output_path"]
    if not path or not Path(path).exists():
        raise HTTPException(404, "Output file not found")
    media = "audio/mpeg" if path.endswith(".mp3") else "video/mp4"
    return FileResponse(path, media_type=media, filename=job["filename"])


@app.websocket("/ws/{job_id}")
async def websocket_progress(websocket: WebSocket, job_id: str):
    await websocket.accept()
    with _ws_lock:
        if job_id not in ws_clients:
            ws_clients[job_id] = []
        ws_clients[job_id].append(websocket)
    try:
        job = db_get(job_id)
        if job:
            await websocket.send_json(job)
        while True:
            await websocket.receive_text()
    except Exception:
        pass
    finally:
        with _ws_lock:
            if job_id in ws_clients and websocket in ws_clients[job_id]:
                ws_clients[job_id].remove(websocket)
