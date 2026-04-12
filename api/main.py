"""FastAPI backend for YouTube Music Remover."""

import re
import shutil
import subprocess
import tempfile
import uuid
from pathlib import Path

from fastapi import FastAPI, HTTPException, UploadFile, File, Form
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

# In-memory job store
jobs: dict[str, dict] = {}

MODELS = [
    "UVR-MDX-NET-Inst_HQ_3.onnx",
    "Kim_Vocal_2.onnx",
    "UVR_MDXNET_KARA_2.onnx",
]


class ProcessRequest(BaseModel):
    url: str
    model: str = "UVR-MDX-NET-Inst_HQ_3.onnx"
    batch_size: int = 4


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


def separate_and_merge(job: dict, video_file: Path, audio_file: Path,
                       model: str, batch_size: int, title: str,
                       temp_dir: Path, output_dir: Path):
    """Shared logic: separate vocals → merge → output."""
    # Separate vocals — stream progress from tqdm output
    job["status"] = "separating"
    job["progress"] = 30
    sep_proc = subprocess.Popen(
        [
            "audio-separator", str(audio_file),
            "--model_filename", model,
            "--mdx_batch_size", str(batch_size),
            "--output_dir", str(temp_dir),
            "--output_format", "WAV",
        ],
        stdout=subprocess.PIPE,
        stderr=subprocess.PIPE,
        text=True,
    )
    pct_re = re.compile(r"(\d+)%\|")
    max_sep_pct = 0
    for line in iter(sep_proc.stderr.readline, ""):
        m = pct_re.search(line)
        if m:
            sep_pct = int(m.group(1))
            max_sep_pct = max(max_sep_pct, sep_pct)
            job["progress"] = 30 + int(max_sep_pct * 0.4)
    sep_proc.wait()
    if sep_proc.returncode != 0:
        job["status"] = "error"
        job["error"] = "Separation failed"
        return False

    vocals = [f for f in temp_dir.iterdir() if "vocals" in f.name.lower() and f.suffix == ".wav"]
    if not vocals:
        job["status"] = "error"
        job["error"] = "Vocals file not found"
        return False
    vocals_file = vocals[0]

    # Merge
    job["status"] = "merging"
    job["progress"] = 75
    safe_title = re.sub(r'[\\/:*?"<>|]', "_", title)
    final_output = output_dir / f"{safe_title}-vocals-only.mp4"

    ok, _, err = run_cmd(
        "ffmpeg", "-i", str(video_file), "-i", str(vocals_file),
        "-c:v", "copy", "-c:a", "aac", "-b:a", "192k",
        "-map", "0:v:0", "-map", "1:a:0", "-shortest", "-y",
        str(final_output),
    )
    if not ok:
        job["status"] = "error"
        job["error"] = f"Merge failed: {err}"
        return False

    shutil.rmtree(temp_dir)
    job["status"] = "done"
    job["progress"] = 100
    job["output_path"] = str(final_output)
    job["filename"] = final_output.name
    return True


def process_video(job_id: str, url: str, model: str, batch_size: int):
    """Pipeline for YouTube URLs: download → separate → merge."""
    job = jobs[job_id]
    temp_dir = Path(tempfile.mkdtemp())
    output_dir = Path("output")
    output_dir.mkdir(exist_ok=True)

    try:
        job["status"] = "downloading"
        job["progress"] = 10
        video_id = extract_video_id(url)
        yt_url = f"https://www.youtube.com/watch?v={video_id}"

        ok, _, err = run_cmd(
            "yt-dlp", "-f", "bv*,ba",
            "-o", f"{temp_dir}/%(format_id)s.%(ext)s", yt_url,
        )
        if not ok:
            job["status"] = "error"
            job["error"] = f"Download failed: {err}"
            return

        video_files = [f for f in temp_dir.iterdir() if f.suffix in (".mp4", ".webm", ".mkv")]
        if not video_files:
            job["status"] = "error"
            job["error"] = "No video file found"
            return
        video_file = max(video_files, key=lambda f: f.stat().st_size)

        audio_files = [
            f for f in temp_dir.iterdir()
            if f != video_file and f.suffix in (".opus", ".m4a", ".webm", ".mp3")
        ]
        if not audio_files:
            job["status"] = "error"
            job["error"] = "No audio file found"
            return
        audio_file = audio_files[0]

        # Get title
        ok, title, _ = run_cmd("yt-dlp", "--print", "title", yt_url)
        title = title.strip() if ok and title else video_id

        separate_and_merge(job, video_file, audio_file, model, batch_size,
                           title, temp_dir, output_dir)

    except Exception as e:
        job["status"] = "error"
        job["error"] = str(e)
        if temp_dir.exists():
            shutil.rmtree(temp_dir)


def process_upload(job_id: str, file_path: Path, original_name: str,
                   model: str, batch_size: int):
    """Pipeline for uploaded files: extract audio → separate → merge."""
    job = jobs[job_id]
    temp_dir = file_path.parent
    output_dir = Path("output")
    output_dir.mkdir(exist_ok=True)

    try:
        # Extract audio from uploaded video
        job["status"] = "extracting"
        job["progress"] = 15
        audio_file = temp_dir / "extracted_audio.wav"
        ok, _, err = run_cmd(
            "ffmpeg", "-i", str(file_path),
            "-vn", "-acodec", "pcm_s16le", "-ar", "44100", "-ac", "2",
            "-y", str(audio_file),
        )
        if not ok:
            job["status"] = "error"
            job["error"] = f"Audio extraction failed: {err}"
            return

        title = Path(original_name).stem

        separate_and_merge(job, file_path, audio_file, model, batch_size,
                           title, temp_dir, output_dir)

    except Exception as e:
        job["status"] = "error"
        job["error"] = str(e)
        if temp_dir.exists():
            shutil.rmtree(temp_dir)


# --- Routes ---

@app.get("/api/models")
def list_models():
    return {"models": MODELS}


@app.get("/api/info")
def video_info(url: str):
    """Get YouTube video metadata without downloading."""
    video_id = extract_video_id(url.strip())
    yt_url = f"https://www.youtube.com/watch?v={video_id}"

    import json as _json
    ok, stdout, err = run_cmd(
        "yt-dlp", "--dump-json", "--no-download", yt_url,
    )
    if not ok:
        raise HTTPException(400, f"Failed to fetch info: {err}")

    try:
        data = _json.loads(stdout)
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
    if not 1 <= req.batch_size <= 8:
        raise HTTPException(400, "Batch size must be 1-8")

    job_id = str(uuid.uuid4())
    jobs[job_id] = {"status": "queued", "progress": 0}

    import threading
    t = threading.Thread(
        target=process_video,
        args=(job_id, req.url, req.model, req.batch_size),
        daemon=True,
    )
    t.start()

    return {"job_id": job_id}


@app.post("/api/upload")
async def upload_processing(
    file: UploadFile = File(...),
    model: str = Form("UVR-MDX-NET-Inst_HQ_3.onnx"),
    batch_size: int = Form(4),
):
    if model not in MODELS:
        raise HTTPException(400, "Invalid model")
    if not 1 <= batch_size <= 8:
        raise HTTPException(400, "Batch size must be 1-8")

    job_id = str(uuid.uuid4())
    jobs[job_id] = {"status": "uploading", "progress": 5}

    # Save uploaded file to temp dir
    temp_dir = Path(tempfile.mkdtemp())
    suffix = Path(file.filename or "video.mp4").suffix or ".mp4"
    file_path = temp_dir / f"upload{suffix}"
    with open(file_path, "wb") as f:
        content = await file.read()
        f.write(content)

    import threading
    t = threading.Thread(
        target=process_upload,
        args=(job_id, file_path, file.filename or "video.mp4", model, batch_size),
        daemon=True,
    )
    t.start()

    return {"job_id": job_id}


@app.get("/api/status/{job_id}")
def get_status(job_id: str):
    if job_id not in jobs:
        raise HTTPException(404, "Job not found")
    return jobs[job_id]


@app.get("/api/download/{job_id}")
def download_result(job_id: str):
    if job_id not in jobs:
        raise HTTPException(404, "Job not found")
    job = jobs[job_id]
    if job["status"] != "done":
        raise HTTPException(400, "Job not ready")
    return FileResponse(
        job["output_path"],
        media_type="video/mp4",
        filename=job["filename"],
    )
