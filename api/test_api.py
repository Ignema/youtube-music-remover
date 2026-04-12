"""Tests for Murem API."""

import pytest
from api.utils import extract_video_id


class TestExtractVideoId:
    def test_bare_id(self):
        assert extract_video_id("dQw4w9WgXcQ") == "dQw4w9WgXcQ"

    def test_full_url(self):
        assert extract_video_id("https://www.youtube.com/watch?v=dQw4w9WgXcQ") == "dQw4w9WgXcQ"

    def test_short_url(self):
        assert extract_video_id("https://youtu.be/dQw4w9WgXcQ") == "dQw4w9WgXcQ"

    def test_shorts_url(self):
        assert extract_video_id("https://www.youtube.com/shorts/dQw4w9WgXcQ") == "dQw4w9WgXcQ"

    def test_with_whitespace(self):
        assert extract_video_id("  dQw4w9WgXcQ  ") == "dQw4w9WgXcQ"

    def test_invalid_returns_as_is(self):
        assert extract_video_id("not-a-video") == "not-a-video"

    def test_empty_string(self):
        assert extract_video_id("") == ""

    def test_url_with_extra_params(self):
        result = extract_video_id("https://www.youtube.com/watch?v=dQw4w9WgXcQ&t=42")
        assert result == "dQw4w9WgXcQ"


class TestDbOperations:
    """Test SQLite job store operations."""

    def setup_method(self):
        """Use a fresh in-memory approach by resetting the DB."""
        import api.main as m
        import os
        # Use a temp DB for tests
        self._orig_path = m.DB_PATH
        m.DB_PATH = type(m.DB_PATH)("test_jobs.db")
        m.init_db()

    def teardown_method(self):
        import api.main as m
        import os
        if m.DB_PATH.exists():
            os.unlink(m.DB_PATH)
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
        assert len(data["models"]) == 3
        assert "bitrates" in data
