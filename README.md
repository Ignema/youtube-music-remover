# YouTube Music Remover

[![Open In Colab](https://colab.research.google.com/assets/colab-badge.svg)](https://colab.research.google.com/github/Ignema/youtube-music-remover/blob/master/vocals.ipynb)
[![Hugging Face Spaces](https://img.shields.io/badge/%F0%9F%A4%97%20Hugging%20Face-Spaces-blue)](https://huggingface.co/spaces/melnema/youtube-music-remover)

Remove background music from YouTube videos while keeping the vocals and dialogue intact.

This tool uses AI-powered audio separation to isolate vocal tracks from videos. Useful when you want to hear what someone's saying without background music interfering.

## Requirements

- Python 3.8 or higher
- FFmpeg
- CUDA-compatible GPU (optional, speeds up processing)

## Installation

**1. Clone the repository**
```bash
git clone https://github.com/Ignema/youtube-music-remover.git
cd youtube-music-remover
```

**2. Install uv** (faster alternative to pip)
```bash
# Windows
powershell -ExecutionPolicy ByPass -c "irm https://astral.sh/uv/install.ps1 | iex"

# Mac/Linux
curl -LsSf https://astral.sh/uv/install.sh | sh
```

**3. Set up the environment**
```bash
uv venv
uv pip install -e .
```

**4. Activate the virtual environment**
- Windows: `.venv\Scripts\activate`
- Mac/Linux: `source .venv/bin/activate`

**5. Install FFmpeg**
- Windows: `winget install ffmpeg`
- Linux: `sudo apt install ffmpeg`
- Mac: `brew install ffmpeg`

## Usage

Launch Jupyter:
```bash
jupyter notebook vocals.ipynb
```

In the Configuration cell, set `VIDEO_INPUT` to your YouTube URL or video ID, then run all cells sequentially.

The processed video will be saved to `./output/` with the format `[Video Title]-vocals-only.mp4`.

## Configuration

Available settings in the notebook:

- `VIDEO_INPUT` - YouTube URL or 11-character video ID
- `OUTPUT_DIR` - Where to save the output (default: `./output`)
- `MODEL` - AI model for separation (default: `UVR-MDX-NET-Inst_HQ_3.onnx`)
- `BATCH_SIZE` - Processing batch size from 1-8 (higher = faster but needs more VRAM)
- `DEBUG` - Show detailed command output when troubleshooting

## Available Models

- `UVR-MDX-NET-Inst_HQ_3.onnx` - Default, good balance of quality and speed
- `Kim_Vocal_2.onnx` - Higher quality vocal extraction
- `UVR_MDXNET_KARA_2.onnx` - Karaoke-style separation

## License

This project is licensed under the MIT License. See the LICENSE file for full details.

## Disclaimer

This tool is provided as-is for educational and personal use only. I am not responsible for how you use this software or any consequences that result from its use. 

You are solely responsible for:
- Complying with YouTube's Terms of Service
- Respecting copyright laws and intellectual property rights
- Obtaining necessary permissions before processing content you don't own
- Any legal issues that arise from your use of this tool

Use this software responsibly and ethically.
