"""FastAPI backend for YouTube Music Remover."""

import re
import shutil
import subprocess
import tempfile
import uuid
from pathlib import Path

from fastapi import FastAPI, HTTPException
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


def process_video(job_id: str, url: str, model: str, batch_size: int):
    """Run the full pipeline: download → separate → merge."""
    job = jobs[job_id]
    temp_dir = Path(tempfile.mkdtemp())
    output_dir = Path("output")
    output_dir.mkdir(exist_ok=True)

    try:
        # Download
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

        # Separate vocals
        job["status"] = "separating"
        job["progress"] = 40
        ok, _, err = run_cmd(
            "audio-separator", str(audio_file),
            "--model_filename", model,
            "--mdx_batch_size", str(batch_size),
            "--output_dir", str(temp_dir),
            "--output_format", "WAV",
        )
        if not ok:
            job["status"] = "error"
            job["error"] = f"Separation failed: {err}"
            return

        vocals = [f for f in temp_dir.iterdir() if "vocals" in f.name.lower() and f.suffix == ".wav"]
        if not vocals:
            job["status"] = "error"
            job["error"] = "Vocals file not found"
            return
        vocals_file = vocals[0]

        # Get title
        job["status"] = "merging"
        job["progress"] = 70
        ok, title, _ = run_cmd("yt-dlp", "--print", "title", yt_url)
        if not ok or not title:
            title = video_id
        else:
            title = re.sub(r'[\\/:*?"<>|]', "_", title.strip())

        final_output = output_dir / f"{title}-vocals-only.mp4"

        # Merge
        ok, _, err = run_cmd(
            "ffmpeg", "-i", str(video_file), "-i", str(vocals_file),
            "-c:v", "copy", "-c:a", "aac", "-b:a", "192k",
            "-map", "0:v:0", "-map", "1:a:0", "-shortest", "-y",
            str(final_output),
        )
        if not ok:
            job["status"] = "error"
            job["error"] = f"Merge failed: {err}"
            return

        shutil.rmtree(temp_dir)
        job["status"] = "done"
        job["progress"] = 100
        job["output_path"] = str(final_output)
        job["filename"] = final_output.name

    except Exception as e:
        job["status"] = "error"
        job["error"] = str(e)
        if temp_dir.exists():
            shutil.rmtree(temp_dir)


# --- Routes ---

@app.get("/api/models")
def list_models():
    return {"models": MODELS}


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
