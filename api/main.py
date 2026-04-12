"""FastAPI backend for YouTube Music Remover."""

import json
import re
import shutil
import sqlite3
import subprocess
import tempfile
import threading
import uuid
from pathlib import Path
from typing import Optional

from fastapi import FastAPI, HTTPException, UploadFile, File, Form, WebSocket
from fastapi.middleware.cors import CORSMiddleware
from fastapi.responses import FileResponse
from pydantic import BaseModel

app = FastAPI(title="YouTube Music Remover API")

app.add_middleware(
    CORSMiddleware,
    allow_origins=["*"],
    allow_methods=["*"],
    allow_headers=["*"],
)

MODELS = [
    "UVR-MDX-NET-Inst_HQ_3.onnx",
    "Kim_Vocal_2.onnx",
    "UVR_MDXNET_KARA_2.onnx",
]
BITRATES = ["128k", "192k", "320k"]

# --- SQLite Job Store ---

DB_PATH = Path("jobs.db")


def init_db():
    conn = sqlite3.connect(str(DB_PATH))
    conn.execute("""
        CREATE TABLE IF NOT EXISTS jobs (
            id TEXT PRIMARY KEY,
            status TEXT DEFAULT 'queued',
            progress INTEGER DEFAULT 0,
            error TEXT,
            output_path TEXT,
            filename TEXT,
            created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
        )
    """)
    conn.commit()
    conn.close()


def db_set(job_id: str, **kwargs):
    conn = sqlite3.connect(str(DB_PATH))
    sets = ", ".join(f"{k} = ?" for k in kwargs)
    vals = list(kwargs.values()) + [job_id]
    conn.execute(f"UPDATE jobs SET {sets} WHERE id = ?", vals)
    conn.commit()
    conn.close()


def db_get(job_id: str) -> Optional[dict]:
    conn = sqlite3.connect(str(DB_PATH))
    conn.row_factory = sqlite3.Row
    row = conn.execute("SELECT * FROM jobs WHERE id = ?", (job_id,)).fetchone()
    conn.close()
    return dict(row) if row else None


def db_create(job_id: str):
    conn = sqlite3.connect(str(DB_PATH))
    conn.execute("INSERT INTO jobs (id) VALUES (?)", (job_id,))
    conn.commit()
    conn.close()


init_db()


# --- WebSocket connections ---
ws_clients: dict[str, list[WebSocket]] = {}


async def notify_ws(job_id: str, data: dict):
    for ws in ws_clients.get(job_id, []):
        try:
            await ws.send_json(data)
        except Exception:
            pass


def update_job(job_id: str, **kwargs):
    """Update job in DB and notify WebSocket clients."""
    db_set(job_id, **kwargs)
    # Fire-and-forget WS notification
    import asyncio
    data = {"job_id": job_id, **kwargs}
    for ws in ws_clients.get(job_id, []):
        try:
            asyncio.run_coroutine_threadsafe(ws.send_json(data), ws_loop)
        except Exception:
            pass


ws_loop = None


@app.on_event("startup")
async def startup():
    global ws_loop
    import asyncio
    ws_loop = asyncio.get_event_loop()


# --- Helpers ---

class ProcessRequest(BaseModel):
    url: str
    model: str = "UVR-MDX-NET-Inst_HQ_3.onnx"
    batch_size: int = 4
    audio_only: bool = False
    bitrate: str = "192k"


def extract_video_id(input_str: str) -> str:
    if re.match(r"^[a-zA-Z0-9_-]{11}$", input_str):
        return input_str
    for pattern in [
        r"youtu\.be/([a-zA-Z0-9_-]{11})",
        r"watch\?v=([a-zA-Z0-9_-]{11})",
        r"/shorts/([a-zA-Z0-9_-]{11})",
    ]:
        match = re.search(pattern, input_str)
        if match:
            return match.group(1)
    return input_str


def run_cmd(*cmd, cwd=None):
    result = subprocess.run(
        cmd, cwd=cwd, stdout=subprocess.PIPE, stderr=subprocess.PIPE, text=True
    )
    return result.returncode == 0, result.stdout, result.stderr


def separate_and_merge(job_id: str, video_file: Path, audio_file: Path,
                       model: str, batch_size: int, title: str,
                       temp_dir: Path, output_dir: Path,
                       audio_only: bool = False, bitrate: str = "192k"):
    """Shared logic: separate vocals → merge/export → output."""
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
    max_sep_pct = 0
    for line in iter(sep_proc.stderr.readline, ""):
        m = pct_re.search(line)
        if m:
            sep_pct = int(m.group(1))
            max_sep_pct = max(max_sep_pct, sep_pct)
            update_job(job_id, progress=30 + int(max_sep_pct * 0.4))
    sep_proc.wait()
    if sep_proc.returncode != 0:
        update_job(job_id, status="error", error="Separation failed")
        return False

    vocals = [f for f in temp_dir.iterdir() if "vocals" in f.name.lower() and f.suffix == ".wav"]
    if not vocals:
        update_job(job_id, status="error", error="Vocals file not found")
        return False
    vocals_file = vocals[0]

    safe_title = re.sub(r'[\\/:*?"<>|]', "_", title)
    update_job(job_id, status="merging", progress=75)

    if audio_only:
        # Export vocals as audio file
        final_output = output_dir / f"{safe_title}-vocals-only.mp3"
        ok, _, err = run_cmd(
            "ffmpeg", "-i", str(vocals_file),
            "-c:a", "libmp3lame", "-b:a", bitrate, "-y",
            str(final_output),
        )
        media_type = "audio/mpeg"
    else:
        # Merge video + vocals
        final_output = output_dir / f"{safe_title}-vocals-only.mp4"
        ok, _, err = run_cmd(
            "ffmpeg", "-i", str(video_file), "-i", str(vocals_file),
            "-c:v", "copy", "-c:a", "aac", "-b:a", bitrate,
            "-map", "0:v:0", "-map", "1:a:0", "-shortest", "-y",
            str(final_output),
        )
        media_type = "video/mp4"

    if not ok:
        update_job(job_id, status="error", error=f"Merge failed: {err}")
        return False

    shutil.rmtree(temp_dir)
    update_job(job_id, status="done", progress=100,
               output_path=str(final_output), filename=final_output.name)
    return True


def process_video(job_id: str, url: str, model: str, batch_size: int,
                  audio_only: bool = False, bitrate: str = "192k"):
    """Pipeline for YouTube URLs."""
    temp_dir = Path(tempfile.mkdtemp())
    output_dir = Path("output")
    output_dir.mkdir(exist_ok=True)

    try:
        update_job(job_id, status="downloading", progress=10)
        video_id = extract_video_id(url)
        yt_url = f"https://www.youtube.com/watch?v={video_id}"

        ok, _, err = run_cmd(
            "yt-dlp", "-f", "bv*,ba",
            "-o", f"{temp_dir}/%(format_id)s.%(ext)s", yt_url,
        )
        if not ok:
            update_job(job_id, status="error", error=f"Download failed: {err}")
            return

        video_files = [f for f in temp_dir.iterdir() if f.suffix in (".mp4", ".webm", ".mkv")]
        if not video_files:
            update_job(job_id, status="error", error="No video file found")
            return
        video_file = max(video_files, key=lambda f: f.stat().st_size)

        audio_files = [
            f for f in temp_dir.iterdir()
            if f != video_file and f.suffix in (".opus", ".m4a", ".webm", ".mp3")
        ]
        if not audio_files:
            update_job(job_id, status="error", error="No audio file found")
            return

        ok, title, _ = run_cmd("yt-dlp", "--print", "title", yt_url)
        title = title.strip() if ok and title else video_id

        separate_and_merge(job_id, video_file, audio_files[0], model, batch_size,
                           title, temp_dir, output_dir, audio_only, bitrate)
    except Exception as e:
        update_job(job_id, status="error", error=str(e))
        if temp_dir.exists():
            shutil.rmtree(temp_dir)


def process_upload(job_id: str, file_path: Path, original_name: str,
                   model: str, batch_size: int,
                   audio_only: bool = False, bitrate: str = "192k"):
    """Pipeline for uploaded files."""
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

        separate_and_merge(job_id, file_path, audio_file, model, batch_size,
                           Path(original_name).stem, temp_dir, output_dir,
                           audio_only, bitrate)
    except Exception as e:
        update_job(job_id, status="error", error=str(e))
        if temp_dir.exists():
            shutil.rmtree(temp_dir)


# --- Routes ---

@app.get("/api/models")
def list_models():
    return {"models": MODELS, "bitrates": BITRATES}


@app.get("/api/info")
def video_info(url: str):
    """Get YouTube video metadata without downloading."""
    video_id = extract_video_id(url.strip())
    yt_url = f"https://www.youtube.com/watch?v={video_id}"

    ok, stdout, err = run_cmd("yt-dlp", "--dump-json", "--no-download", yt_url)
    if not ok:
        raise HTTPException(400, f"Failed to fetch info: {err}")
    try:
        data = json.loads(stdout)
    except Exception:
        raise HTTPException(500, "Failed to parse video info")

    return {
        "title": data.get("title", ""),
        "channel": data.get("channel", data.get("uploader", "")),
        "duration": data.get("duration", 0),
        "thumbnail": data.get("thumbnail", ""),
        "view_count": data.get("view_count", 0),
        "upload_date": data.get("upload_date", ""),
        "description": (data.get("description", "") or "")[:300],
    }


@app.post("/api/process")
def start_processing(req: ProcessRequest):
    if req.model not in MODELS:
        raise HTTPException(400, "Invalid model")
    if req.bitrate not in BITRATES:
        raise HTTPException(400, "Invalid bitrate")

    job_id = str(uuid.uuid4())
    db_create(job_id)

    threading.Thread(
        target=process_video,
        args=(job_id, req.url, req.model, req.batch_size, req.audio_only, req.bitrate),
        daemon=True,
    ).start()

    return {"job_id": job_id}


@app.post("/api/batch")
def batch_processing(urls: list[str], model: str = "UVR-MDX-NET-Inst_HQ_3.onnx",
                     batch_size: int = 4, audio_only: bool = False,
                     bitrate: str = "192k"):
    """Queue multiple URLs for sequential processing."""
    if model not in MODELS:
        raise HTTPException(400, "Invalid model")

    job_ids = []
    for url in urls:
        job_id = str(uuid.uuid4())
        db_create(job_id)
        job_ids.append(job_id)

    def run_batch():
        for jid, url in zip(job_ids, urls):
            process_video(jid, url, model, batch_size, audio_only, bitrate)

    threading.Thread(target=run_batch, daemon=True).start()
    return {"job_ids": job_ids}


@app.post("/api/upload")
async def upload_processing(
    file: UploadFile = File(...),
    model: str = Form("UVR-MDX-NET-Inst_HQ_3.onnx"),
    batch_size: int = Form(4),
    audio_only: bool = Form(False),
    bitrate: str = Form("192k"),
):
    if model not in MODELS:
        raise HTTPException(400, "Invalid model")

    job_id = str(uuid.uuid4())
    db_create(job_id)
    update_job(job_id, status="uploading", progress=5)

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
    return job


@app.get("/api/download/{job_id}")
def download_result(job_id: str):
    job = db_get(job_id)
    if not job:
        raise HTTPException(404, "Job not found")
    if job["status"] != "done":
        raise HTTPException(400, "Job not ready")
    path = job["output_path"]
    if not Path(path).exists():
        raise HTTPException(404, "Output file not found")
    media = "audio/mpeg" if path.endswith(".mp3") else "video/mp4"
    return FileResponse(path, media_type=media, filename=job["filename"])


@app.websocket("/ws/{job_id}")
async def websocket_progress(websocket: WebSocket, job_id: str):
    await websocket.accept()
    if job_id not in ws_clients:
        ws_clients[job_id] = []
    ws_clients[job_id].append(websocket)
    try:
        # Send current status immediately
        job = db_get(job_id)
        if job:
            await websocket.send_json(job)
        # Keep alive until client disconnects
        while True:
            await websocket.receive_text()
    except Exception:
        pass
    finally:
        ws_clients.get(job_id, []).remove(websocket) if websocket in ws_clients.get(job_id, []) else None
