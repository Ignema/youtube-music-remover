"""Tests for Murem API."""

import shutil
import subprocess

import pytest
from api.utils import extract_video_id, has_video_stream


class TestExtractVideoId:
    def test_bare_id(self):
        assert extract_video_id("dQw4w9WgXcQ") == "dQw4w9WgXcQ"

    def test_full_url(self):
        url = "https://www.youtube.com/watch?v=dQw4w9WgXcQ"
        assert extract_video_id(url) == "dQw4w9WgXcQ"

    def test_short_url(self):
        assert extract_video_id("https://youtu.be/dQw4w9WgXcQ") == "dQw4w9WgXcQ"

    def test_shorts_url(self):
        url = "https://www.youtube.com/shorts/dQw4w9WgXcQ"
        assert extract_video_id(url) == "dQw4w9WgXcQ"

    def test_with_whitespace(self):
        assert extract_video_id("  dQw4w9WgXcQ  ") == "dQw4w9WgXcQ"

    def test_invalid_returns_as_is(self):
        assert extract_video_id("not-a-video") == "not-a-video"

    def test_empty_string(self):
        assert extract_video_id("") == ""

    def test_url_with_extra_params(self):
        result = extract_video_id("https://www.youtube.com/watch?v=dQw4w9WgXcQ&t=42")
        assert result == "dQw4w9WgXcQ"


def _ffmpeg(*args):
    subprocess.run(["ffmpeg", "-v", "error", "-y", *args], check=True, timeout=60)


@pytest.mark.skipif(shutil.which("ffmpeg") is None, reason="ffmpeg not installed")
class TestHasVideoStream:
    def test_video_file(self, tmp_path):
        out = tmp_path / "video.mp4"
        _ffmpeg("-f", "lavfi", "-i", "testsrc=size=64x64:rate=10",
                "-f", "lavfi", "-i", "sine=frequency=440",
                "-t", "1", "-pix_fmt", "yuv420p", "-shortest", str(out))
        assert has_video_stream(out) is True

    def test_audio_file(self, tmp_path):
        out = tmp_path / "audio.mp3"
        _ffmpeg("-f", "lavfi", "-i", "sine=frequency=440:duration=1",
                "-c:a", "libmp3lame", str(out))
        assert has_video_stream(out) is False

    def test_audio_file_with_cover_art(self, tmp_path):
        cover = tmp_path / "cover.png"
        _ffmpeg("-f", "lavfi", "-i", "color=c=red:size=64x64",
                "-frames:v", "1", str(cover))
        out = tmp_path / "cover.mp3"
        _ffmpeg("-f", "lavfi", "-i", "sine=frequency=440:duration=1", "-i", str(cover),
                "-map", "0", "-map", "1", "-c:a", "libmp3lame", "-c:v", "mjpeg",
                "-disposition:v", "attached_pic", str(out))
        assert has_video_stream(out) is False


class TestDbOperations:
    """Test SQLite job store operations."""

    def setup_method(self):
        """Use a fresh in-memory approach by resetting the DB."""
        import api.main as m
        # Use a temp DB for tests
        self._orig_path = m.DB_PATH
        m.DB_PATH = type(m.DB_PATH)("test_jobs.db")
        m.init_db()

    def teardown_method(self):
        import api.main as m
        if m.DB_PATH.exists():
            m.DB_PATH.unlink()
        m.DB_PATH = self._orig_path

    def test_create_and_get(self):
        import api.main as m
        m.db_create("test-123")
        job = m.db_get("test-123")
        assert job is not None
        assert job["id"] == "test-123"
        assert job["status"] == "queued"

    def test_update_job(self):
        import api.main as m
        m.db_create("test-456")
        m.db_set("test-456", status="processing", progress=50)
        job = m.db_get("test-456")
        assert job["status"] == "processing"
        assert job["progress"] == 50

    def test_get_nonexistent(self):
        import api.main as m
        assert m.db_get("nonexistent") is None

    def test_invalid_key_rejected(self):
        import api.main as m
        m.db_create("test-789")
        with pytest.raises(ValueError, match="Invalid job field"):
            m.db_set("test-789", malicious_field="drop table")


@pytest.mark.skipif(shutil.which("ffmpeg") is None, reason="ffmpeg not installed")
class TestUploadEndpoint:
    """POST /api/upload copies the file in bounded chunks (#3).

    Inputs without a video stream run audio only (#1).
    """

    CHUNK = 1024 * 1024

    def setup_method(self):
        import api.main as m
        self._orig_path = m.DB_PATH
        m.DB_PATH = type(m.DB_PATH)("test_upload_jobs.db")
        m.init_db()

    def teardown_method(self):
        import api.main as m
        if m.DB_PATH.exists():
            m.DB_PATH.unlink()
        m.DB_PATH = self._orig_path

    def _upload(self, monkeypatch, path, content_type):
        """Uploads [path]; separation is replaced by a recorder, so no model runs."""
        import threading
        from fastapi.testclient import TestClient
        # The form parser creates Starlette's UploadFile, not FastAPI's subclass
        from starlette.datastructures import UploadFile
        import api.main as m

        reads = []
        orig_read = UploadFile.read

        async def spy_read(self, size=-1):
            reads.append(size)
            return await orig_read(self, size)

        monkeypatch.setattr(UploadFile, "read", spy_read)

        seen = {}
        done = threading.Event()

        def fake_separate(job_id, video_file, audio_file, model, batch_size, title,
                          temp_dir, output_dir, audio_only=False, bitrate="192k"):
            seen.update(stored=video_file.read_bytes(), wav=audio_file.is_file(),
                        audio_only=audio_only, title=title)
            shutil.rmtree(temp_dir, ignore_errors=True)
            done.set()
            return True

        monkeypatch.setattr(m, "separate_and_merge", fake_separate)
        with open(path, "rb") as f:
            resp = TestClient(m.app).post(
                "/api/upload",
                files={"file": (path.name, f, content_type)},
                data={"bitrate": "192k"},
            )
        assert resp.status_code == 200
        assert done.wait(60), "process_upload did not reach separate_and_merge"
        return seen, reads

    def test_mp3_upload_is_streamed_and_audio_only(self, tmp_path, monkeypatch):
        song = tmp_path / "song.mp3"
        # ~1.2 MB, so the copy needs more than one chunk
        _ffmpeg("-f", "lavfi", "-i", "sine=frequency=440:duration=30",
                "-c:a", "libmp3lame", "-b:a", "320k", str(song))
        assert song.stat().st_size > self.CHUNK

        seen, reads = self._upload(monkeypatch, song, "audio/mpeg")

        assert seen["stored"] == song.read_bytes()
        assert seen["wav"] is True
        assert seen["audio_only"] is True
        assert seen["title"] == "song"
        assert len(reads) > 1
        assert all(0 < size <= self.CHUNK for size in reads)

    def test_video_upload_keeps_video(self, tmp_path, monkeypatch):
        clip = tmp_path / "clip.mp4"
        _ffmpeg("-f", "lavfi", "-i", "testsrc=size=64x64:rate=10",
                "-f", "lavfi", "-i", "sine=frequency=440",
                "-t", "1", "-pix_fmt", "yuv420p", "-shortest", str(clip))

        seen, _ = self._upload(monkeypatch, clip, "video/mp4")

        assert seen["stored"] == clip.read_bytes()
        assert seen["audio_only"] is False


class TestHealthEndpoint:
    def test_health(self):
        from fastapi.testclient import TestClient
        import api.main as m
        client = TestClient(m.app)
        resp = client.get("/health")
        assert resp.status_code == 200
        data = resp.json()
        assert data["status"] == "ok"
        assert "version" in data

    def test_models(self):
        from fastapi.testclient import TestClient
        import api.main as m
        client = TestClient(m.app)
        resp = client.get("/api/models")
        assert resp.status_code == 200
        data = resp.json()
        assert "models" in data
        assert len(data["models"]) == 5
        assert "bitrates" in data
