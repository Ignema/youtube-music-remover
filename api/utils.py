"""Shared utilities for Murem."""

import re
import subprocess
import logging

logger = logging.getLogger("murem")

# Strict YouTube URL/ID patterns
YT_ID_RE = re.compile(r"^[a-zA-Z0-9_-]{11}$")
YT_URL_PATTERNS = [
    re.compile(r"(?:https?://)?(?:www\.)?youtu\.be/([a-zA-Z0-9_-]{11})"),
    re.compile(r"(?:https?://)?(?:www\.)?youtube\.com/watch\?v=([a-zA-Z0-9_-]{11})"),
    re.compile(r"(?:https?://)?(?:www\.)?youtube\.com/shorts/([a-zA-Z0-9_-]{11})"),
]


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
