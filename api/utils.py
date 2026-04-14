"""Shared utilities for Murem."""

import re
import subprocess
import logging

logger = logging.getLogger("murem")

# YouTube URL/ID patterns (for backward compat and YouTube-specific features)
YT_ID_RE = re.compile(r"^[a-zA-Z0-9_-]{11}$")
YT_URL_PATTERNS = [
    re.compile(r"(?:https?://)?(?:www\.)?youtu\.be/([a-zA-Z0-9_-]{11})"),
    re.compile(r"(?:https?://)?(?:www\.)?youtube\.com/watch\?v=([a-zA-Z0-9_-]{11})"),
    re.compile(r"(?:https?://)?(?:www\.)?youtube\.com/shorts/([a-zA-Z0-9_-]{11})"),
]

# General URL pattern — matches any http/https URL
URL_RE = re.compile(r"^https?://\S+$")


def extract_video_id(input_str: str) -> str:
    """Extract YouTube video ID from URL or return as-is if already an ID."""
    input_str = input_str.strip()
    if YT_ID_RE.match(input_str):
        return input_str
    for pattern in YT_URL_PATTERNS:
        match = pattern.search(input_str)
        if match:
            return match.group(1)
    return input_str


def normalize_url(input_str: str) -> str:
    """Normalize input to a URL that yt-dlp can handle.

    Accepts: YouTube video IDs, YouTube URLs, or any yt-dlp supported URL.
    """
    input_str = input_str.strip()
    # Bare YouTube video ID
    if YT_ID_RE.match(input_str):
        return f"https://www.youtube.com/watch?v={input_str}"
    # Already a URL
    if URL_RE.match(input_str):
        return input_str
    # Try YouTube ID extraction from partial URLs
    vid = extract_video_id(input_str)
    if YT_ID_RE.match(vid):
        return f"https://www.youtube.com/watch?v={vid}"
    # Pass through — let yt-dlp figure it out
    return input_str


def is_valid_url(input_str: str) -> bool:
    """Check if input looks like a valid URL or YouTube video ID."""
    input_str = input_str.strip()
    if not input_str:
        return False
    if YT_ID_RE.match(input_str):
        return True
    if URL_RE.match(input_str):
        return True
    # Check if it contains a YouTube ID
    vid = extract_video_id(input_str)
    return YT_ID_RE.match(vid) is not None


def run_cmd(*cmd, cwd=None, timeout=None) -> tuple[bool, str, str]:
    """Run a subprocess command and return (success, stdout, stderr)."""
    logger.debug(f"Running: {' '.join(cmd)}")
    try:
        result = subprocess.run(
            cmd, cwd=cwd, stdout=subprocess.PIPE, stderr=subprocess.PIPE, text=True,
            timeout=timeout,
        )
        return result.returncode == 0, result.stdout, result.stderr
    except subprocess.TimeoutExpired:
        return False, "", "Command timed out"
