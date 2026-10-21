import pytest
from fastapi.testclient import TestClient

import skillops_agent.main as _main_module
from skillops_agent.main import app

client = TestClient(app)


@pytest.fixture(autouse=True)
def patch_pipeline(monkeypatch):
    async def _noop(req, thread_id):
        pass

    monkeypatch.setattr(_main_module, "_run_pipeline", _noop)


def test_health_ok():
    res = client.get("/health")
    assert res.status_code == 200
    assert res.json()["status"] == "ok"


def test_diagnose_accepts_failure_trigger():
    res = client.post(
        "/diagnose", json={"type": "failure", "run_id": "33333333-3333-4333-8333-333333333333"}
    )
    assert res.status_code == 202
    assert res.json()["accepted"] is True


def test_diagnose_rejects_unknown_type():
    res = client.post("/diagnose", json={"type": "nope"})
    assert res.status_code == 422


def test_diagnose_returns_queryable_thread_id():
    res = client.post(
        "/diagnose",
        json={
            "type": "failure",
            "execution_id": "33333333-3333-4333-8333-333333333333",
            "baseline_snapshot_id": "11111111-1111-4111-8111-111111111111",
            "candidate_snapshot_id": "22222222-2222-4222-8222-222222222222",
            "changed_paths": ["skills/inventory-check/SKILL.md"],
            "change_id": "change-1",
        },
    )
    assert res.status_code == 202
    assert res.json()["thread_id"]
    assert res.json()["trigger"]["changed_paths"] == ["skills/inventory-check/SKILL.md"]


@pytest.mark.parametrize(
    "payload",
    [
        {"type": "failure"},
        {"type": "skill_saved", "skill": "payment", "version": 2},
        {"type": "failure", "execution_id": "r_fake"},
        {
            "type": "failure",
            "execution_id": "33333333-3333-4333-8333-333333333333",
            "unexpected": True,
        },
        {
            "type": "failure",
            "execution_id": "33333333-3333-4333-8333-333333333333",
            "diagnosis_only": True,
        },
    ],
)
def test_ambiguous_or_unsupported_trigger_is_rejected(payload):
    assert client.post("/diagnose", json=payload).status_code == 422


def test_duplicate_change_returns_same_thread_without_rescheduling(monkeypatch):
    calls = []

    async def start(req, thread_id):
        calls.append(thread_id)

    monkeypatch.setattr(_main_module, "_run_pipeline", start)
    payload = {
        "type": "skill_saved",
        "change_id": "one-change",
        "candidate_snapshot_id": "22222222-2222-4222-8222-222222222222",
    }
    first = client.post("/diagnose", json=payload)
    second = client.post("/diagnose", json=payload)
    assert first.json()["thread_id"] == second.json()["thread_id"]
    assert second.json()["duplicate"] is True
    assert len(calls) == 1
    run = client.get("/runs/" + first.json()["thread_id"]).status_code
    assert run == 404  # the public progress path is /events, not a misleading alias
    progress = client.get("/runs/" + first.json()["thread_id"] + "/events")
    assert progress.status_code == 200
    assert progress.json()["diagnosis_status"] == "QUEUED"
    assert len(client.get("/runs").json()["items"]) == 1
    changed = {**payload, "candidate_snapshot_id": "11111111-1111-4111-8111-111111111111"}
    assert client.post("/diagnose", json=changed).status_code == 409
