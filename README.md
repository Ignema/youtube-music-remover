# Murem — YouTube Music Remover

[![Open In Colab](https://colab.research.google.com/assets/colab-badge.svg)](https://colab.research.google.com/github/Ignema/youtube-music-remover/blob/master/vocals.ipynb)
[![Hugging Face Spaces](https://img.shields.io/badge/%F0%9F%A4%97%20Hugging%20Face-Spaces-blue)](https://huggingface.co/spaces/melnema/youtube-music-remover)

Remove background music from YouTube videos while keeping the vocals and dialogue intact.

Uses AI-powered audio separation to isolate vocal tracks. Available as a Jupyter notebook, web UI, API server, and Android app.

## Android App (Murem)

Download the latest APK from [Releases](https://github.com/Ignema/youtube-music-remover/releases).

Features:
- Paste a YouTube URL or pick a local video file
- Share directly from the YouTube app
- Choose between 3 AI models and audio quality settings
- Audio-only mode (exports MP3 vocals)
- On-device processing via Termux (no server needed)
- Remote server support for GPU-accelerated processing
- In-app video player, history, notifications
- Material You dynamic theming

### Quick Start (Remote Server)

1. Install the APK on your phone
2. On your PC, clone and start the server:
```bash
git clone https://github.com/Ignema/youtube-music-remover.git
cd youtube-music-remover
uvx --with fastapi --with yt-dlp --with python-multipart --with "audio-separator[gpu]" uvicorn api.main:app --host 0.0.0.0 --port 8000
```

3. In the app, go to Settings → set your PC's IP as the server URL
4. Paste a YouTube link and tap Remove Music

### Quick Start (On-Device with Termux)

1. Install [Termux from F-Droid](https://f-droid.org/packages/com.termux/)
2. In Termux, enable external apps: edit `~/.termux/termux.properties` and set `allow-external-apps = true`
3. In the Murem app, go to Settings → Permissions → grant all
4. Settings → Termux Controls → tap Install (wait for it to finish in Termux)
5. Tap Start → the server starts in the background
6. Go home and process videos

### Docker (Easiest for PC)

```bash
cd api
docker build -t murem .
docker run -p 8000:8000 murem
```

## Notebook / Web UI

### Requirements

- Python 3.8+
- FFmpeg
- CUDA GPU (optional, speeds up processing)

### Installation

```bash
git clone https://github.com/Ignema/youtube-music-remover.git
cd youtube-music-remover
uv venv && uv pip install -e .
```

Install FFmpeg: `winget install ffmpeg` (Windows) / `sudo apt install ffmpeg` (Linux) / `brew install ffmpeg` (Mac)

### Usage

**Notebook:**
```bash
jupyter notebook vocals.ipynb
```

**Web UI (Gradio):**
```bash
python app.py
```

**API Server:**
```bash
uvicorn api.main:app --host 0.0.0.0 --port 8000
```

## API Endpoints

| Method | Path | Description |
|--------|------|-------------|
| GET | `/api/models` | List available models and bitrates |
| GET | `/api/info?url=` | Get YouTube video metadata |
| POST | `/api/process` | Start processing a YouTube URL |
| POST | `/api/upload` | Upload and process a local file |
| POST | `/api/batch` | Queue multiple URLs |
| GET | `/api/status/{job_id}` | Get job status and progress |
| GET | `/api/download/{job_id}` | Download the result |
| WS | `/ws/{job_id}` | WebSocket for real-time progress |

## Available Models

| Model | Description |
|-------|-------------|
| `UVR-MDX-NET-Inst_HQ_3.onnx` | Default. Balanced quality and speed |
| `Kim_Vocal_2.onnx` | Higher quality vocal extraction |
| `UVR_MDXNET_KARA_2.onnx` | Karaoke-style separation |

## License

MIT License. See [LICENSE](LICENSE) for details.

## Disclaimer

This tool is for educational and personal use only. You are responsible for complying with YouTube's Terms of Service, copyright laws, and obtaining necessary permissions before processing content you don't own.
