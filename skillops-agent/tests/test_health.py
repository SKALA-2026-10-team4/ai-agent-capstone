"""skillops-agent/skillops_agent/main.py 의 HTTP 경로가 기본 계약을 지키는지 확인하는 테스트다.

/health 의 200, /diagnose 의 202 와 조회할 수 있는 thread_id, 잘못되거나 모호한 trigger 의 422, 같은
change_id 재전송의 중복 처리와 다른 내용의 409, 진행 조회 경로(/runs/{thread_id}/events 와 /runs)를
본다. service/src/main/java/com/capstone4/skillops/diagnosis/DiagnosisController.java 가 이 계약에
기대어 요청을 중계한다. _run_pipeline 은 대역으로 바꿔 실제 graph 를 돌리지 않는다.
"""

import pytest
from fastapi.testclient import TestClient

import skillops_agent.main as _main_module
from skillops_agent.main import app

client = TestClient(app)


@pytest.fixture(autouse=True)
def patch_pipeline(monkeypatch):
    """모든 테스트에서 _run_pipeline 을 아무것도 하지 않는 함수로 바꿔 실제 파이프라인을 막는다."""

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
    """모호하거나 지원하지 않는 trigger 는 422 다.

    필수 참조가 없거나, 실행 번호를 읽을 수 없거나, 정의하지 않은 칸이 있거나, diagnosis_only 에
    의도 조건이 없는 경우다.
    """
    assert client.post("/diagnose", json=payload).status_code == 422


def test_duplicate_change_returns_same_thread_without_rescheduling(monkeypatch):
    """같은 change_id 와 같은 내용의 재전송은 같은 thread_id 와 duplicate=true 를 돌려준다.

    파이프라인은 한 번만 예약하고, 진행 조회는 /events 경로로만 되며, 같은 change_id 에 다른 내용이
    오면 409 다.
    """
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
    # 공개 진행 조회 경로는 /events 하나이고, 헷갈리는 /runs/{thread_id} 별칭은 두지 않는다.
    assert run == 404
    progress = client.get("/runs/" + first.json()["thread_id"] + "/events")
    assert progress.status_code == 200
    assert progress.json()["diagnosis_status"] == "QUEUED"
    assert len(client.get("/runs").json()["items"]) == 1
    changed = {**payload, "candidate_snapshot_id": "11111111-1111-4111-8111-111111111111"}
    assert client.post("/diagnose", json=changed).status_code == 409
