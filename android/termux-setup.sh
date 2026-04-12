#!/data/data/com.termux/files/usr/bin/bash
# Music Remover — Termux local server setup
# Run this once in Termux to install everything needed.
# Uses proot-distro (Ubuntu) since onnxruntime needs standard Linux wheels.

set -e

echo "🎵 Music Remover — Termux Setup"
echo "================================"
echo ""

# Update Termux packages
echo "→ Updating Termux packages..."
pkg update -y && pkg upgrade -y

# Install proot-distro
echo "→ Installing proot-distro..."
pkg install -y proot-distro

# Install Ubuntu (skip if already installed)
echo "→ Installing Ubuntu (this may take a few minutes)..."
if proot-distro list | grep -q 'ubuntu.*installed'; then
    echo "   Ubuntu already installed, skipping."
else
    proot-distro install ubuntu
fi

# Set up inside Ubuntu
echo "→ Setting up Python environment inside Ubuntu..."
proot-distro login ubuntu -- bash -c '
    set -e
    apt update -y && apt upgrade -y
    apt install -y python3 python3-pip python3-venv ffmpeg git

    echo "→ Creating virtual environment..."
    python3 -m venv ~/venv

    echo "→ Installing Python packages..."
    . ~/venv/bin/activate
    pip install fastapi uvicorn yt-dlp python-multipart "audio-separator[cpu]"

    # Clone the repo for the API code
    if [ -d ~/music-remover ]; then
        cd ~/music-remover && git pull
    else
        git clone https://github.com/Ignema/youtube-music-remover.git ~/music-remover
    fi

    echo ""
    echo "✅ Setup complete!"
'

# Create start script
cat > "$HOME/start-music-remover.sh" << 'EOF'
#!/data/data/com.termux/files/usr/bin/bash
echo "🎵 Starting Music Remover server on port 8000..."
echo "   Open the app and select 'Local (Termux)' in settings."
echo "   Press Ctrl+C to stop."
proot-distro login ubuntu -- bash -c '. ~/venv/bin/activate && cd ~/music-remover && uvicorn api.main:app --host 127.0.0.1 --port 8000'
EOF
chmod +x "$HOME/start-music-remover.sh"

echo ""
echo "✅ All done!"
echo ""
echo "To start the server, run:"
echo "  ~/start-music-remover.sh"
echo ""
echo "Then open the Music Remover app and select 'Local (Termux)' in settings."
