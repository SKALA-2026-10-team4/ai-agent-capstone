from fastapi.testclient import TestClient

from skillops_agent.main import app

client = TestClient(app)


def test_health_ok():
    res = client.get("/health")
    assert res.status_code == 200
    assert res.json()["status"] == "ok"


def test_diagnose_accepts_failure_trigger():
    res = client.post("/diagnose", json={"type": "failure", "run_id": "r_01"})
    assert res.status_code == 202
    assert res.json()["accepted"] is True


def test_diagnose_rejects_unknown_type():
    res = client.post("/diagnose", json={"type": "nope"})
    assert res.status_code == 422
