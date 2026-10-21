import pytest


@pytest.fixture(autouse=True)
def offline_credentials(monkeypatch, tmp_path):
    # Only enables client construction; no test calls a paid model.
    monkeypatch.setenv("OPENAI_API_KEY", "test-not-a-real-key")

    monkeypatch.setenv("SKILLOPS_RUN_DB", str(tmp_path / "runs.sqlite3"))
