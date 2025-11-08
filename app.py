import gradio as gr
import subprocess
import shutil
import re
import os
from pathlib import Path
import tempfile


def check_ffmpeg():
    """Check if FFmpeg is installed"""
    return shutil.which("ffmpeg") is not None


def extract_video_id(input_str):
    """Extract video ID from URL or return as-is if already an ID"""
    if re.match(r'^[a-zA-Z0-9_-]{11}$', input_str):
        return input_str
    
    patterns = [
        r'youtu\.be/([a-zA-Z0-9_-]{11})',
        r'watch\?v=([a-zA-Z0-9_-]{11})',
        r'/shorts/([a-zA-Z0-9_-]{11})'
    ]
    
    for pattern in patterns:
        match = re.search(pattern, input_str)
        if match:
            return match.group(1)
    
    return input_str


def run_command(*cmd, cwd=None):
    """Execute command and return success status"""
    try:
        result = subprocess.run(
            cmd,
            cwd=cwd,
            stdout=subprocess.PIPE,
            stderr=subprocess.PIPE,
            check=False,
            text=True
        )
        return result.returncode == 0, result.stdout, result.stderr
    except Exception as e:
        return False, "", str(e)


def process_video(video_input, model_choice, batch_size, progress=gr.Progress()):
    """Process a YouTube video and extract vocals"""
    
    # Check FFmpeg
    if not check_ffmpeg():
        return gr.update(value=None), "❌ FFmpeg not found! Please install FFmpeg first."
    
    if not video_input or not video_input.strip():
        return gr.update(value=None), "❌ Please enter a YouTube URL or video ID"
    
    video_id = extract_video_id(video_input.strip())
    url = f"https://www.youtube.com/watch?v={video_id}"
    
    # Create temporary directory
    temp_dir = Path(tempfile.mkdtemp())
    output_dir = Path("output")
    output_dir.mkdir(exist_ok=True)
    
    try:
        # Step 1: Download
        progress(0.1, desc="Downloading video...")
        success, stdout, stderr = run_command(
            "yt-dlp",
            "-f", "bv*,ba",
            "-o", f"{temp_dir}/%(format_id)s.%(ext)s",
            url
        )
        
        if not success:
            return gr.update(value=None), f"❌ Download failed: {stderr}"
        
        # Find downloaded files
        video_files = [f for f in temp_dir.iterdir() if f.suffix in ['.mp4', '.webm', '.mkv']]
        if not video_files:
            return gr.update(value=None), "❌ No video file found after download"
        
        video_file = max(video_files, key=lambda f: f.stat().st_size)
        
        audio_files = [f for f in temp_dir.iterdir() 
                      if f != video_file and f.suffix in ['.opus', '.m4a', '.webm', '.mp3']]
        if not audio_files:
            return gr.update(value=None), "❌ No audio file found after download"
        
        audio_file = audio_files[0]
        
        # Step 2: Separate vocals
        progress(0.4, desc="Separating vocals...")
        success, stdout, stderr = run_command(
            "audio-separator",
            str(audio_file),
            "--model_filename", model_choice,
            "--mdx_batch_size", str(batch_size),
            "--output_dir", str(temp_dir),
            "--output_format", "WAV"
        )
        
        if not success:
            return gr.update(value=None), f"❌ Vocal separation failed: {stderr}"
        
        # Find vocals file
        vocals_files = [f for f in temp_dir.iterdir() 
                       if 'vocals' in f.name.lower() and f.suffix == '.wav']
        if not vocals_files:
            return gr.update(value=None), "❌ Vocals file not found after separation"
        
        vocals_file = vocals_files[0]
        
        # Step 3: Get video title
        progress(0.7, desc="Getting video title...")
        success, title, stderr = run_command("yt-dlp", "--print", "title", url)
        if not success or not title:
            title = video_id
        else:
            title = title.strip()
            title = re.sub(r'[\\/:*?"<>|]', '_', title)
        
        final_output = output_dir / f"{title}-vocals-only.mp4"
        
        # Step 4: Merge
        progress(0.8, desc="Merging video with vocals...")
        success, stdout, stderr = run_command(
            "ffmpeg",
            "-i", str(video_file),
            "-i", str(vocals_file),
            "-c:v", "copy",
            "-c:a", "aac",
            "-b:a", "192k",
            "-map", "0:v:0",
            "-map", "1:a:0",
            "-shortest",
            "-y",
            str(final_output)
        )
        
        if not success:
            return gr.update(value=None), f"❌ Merge failed: {stderr}"
        
        # Cleanup
        shutil.rmtree(temp_dir)
        
        progress(1.0, desc="Done!")
        return gr.update(value=str(final_output)), f"✅ Success! Video saved to: {final_output.name}"
        
    except Exception as e:
        # Cleanup on error
        if temp_dir.exists():
            shutil.rmtree(temp_dir)
        return gr.update(value=None), f"❌ Error: {str(e)}"


# PWA head HTML
pwa_head = """
<meta name="theme-color" content="#ff7c00">
<meta name="apple-mobile-web-app-capable" content="yes">
<meta name="apple-mobile-web-app-status-bar-style" content="black-translucent">
<meta name="apple-mobile-web-app-title" content="Music Remover">
<meta name="viewport" content="width=device-width, initial-scale=1, maximum-scale=1, user-scalable=no">
"""

# Create Gradio interface
with gr.Blocks(title="YouTube Music Remover", theme=gr.themes.Soft(), head=pwa_head) as demo:
    gr.Markdown(
        """
        # 🎬 YouTube Music Remover
        
        Strip background music from YouTube videos while keeping vocals and dialogue intact.
        """
    )
    
    with gr.Row():
        with gr.Column():
            video_input = gr.Textbox(
                label="YouTube URL or Video ID",
                placeholder="Enter YouTube URL or 11-character video ID",
                lines=1
            )
            
            model_choice = gr.Dropdown(
                choices=[
                    "UVR-MDX-NET-Inst_HQ_3.onnx",
                    "Kim_Vocal_2.onnx",
                    "UVR_MDXNET_KARA_2.onnx"
                ],
                value="UVR-MDX-NET-Inst_HQ_3.onnx",
                label="AI Model",
                info="Choose the separation model"
            )
            
            batch_size = gr.Slider(
                minimum=1,
                maximum=8,
                value=4,
                step=1,
                label="Batch Size",
                info="Higher = faster but needs more VRAM"
            )
            
            process_btn = gr.Button("Process Video", variant="primary", size="lg")
        
        with gr.Column():
            output_video = gr.Video(label="Output Video", height=600)
            status_text = gr.Textbox(label="Status", lines=3)
    
    gr.Markdown(
        """
        ### 📝 Notes
        - First run will download the AI model (may take a few minutes)
        - Processing time depends on video length and your hardware
        - GPU acceleration is used automatically if available
        - Output files are saved to the `output/` directory
        
        ### ⚠️ Disclaimer
        This tool is for educational and personal use only. Respect copyright laws and YouTube's Terms of Service.
        """
    )
    
    process_btn.click(
        fn=process_video,
        inputs=[video_input, model_choice, batch_size],
        outputs=[output_video, status_text]
    )


if __name__ == "__main__":
    demo.launch(
        share=False, 
        favicon_path="static/icon.svg",
        pwa=True
    )