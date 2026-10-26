"""skillops-agent/skillops_agent/main.py 의 현재 동작을 고정하는 특성 테스트다.

graph 는 RecordingGraph 대역으로 바꾸고, RunStore 는 skillops-agent/tests/conftest.py 가 넣은 임시
DB 를 쓴다. 보호하는 동작은 다음과 같다.
- _run_pipeline 이 초기 state 와 thread config, recursion_limit 으로 graph 를 부르고 결과를
  RunStore 에 저장하며, proposal.id 가 있을 때만 승인 대기에 등록한다. graph 나 저장이 실패하면
  '<타입>: <메시지>' ERROR 를 남긴다.
- _resume_pipeline 은 Command(resume={status, token}) 와 thread config 만 graph 에 넘긴다.
  /approve 는 등록된 제안서의 재개를 한 번만 예약하고, 모르는 제안서는 404, 잘못된 status 는
  422 로 막는다.
- /health, /runs, /runs/{thread_id}/events 가 정해진 본문을 돌려주고, 앱이 시작할 때 recover 가
  돈다.
- DiagnoseRequest 가 실행 번호를 정규화하고, 규칙을 정해진 순서로 보며, 정의하지 않은 칸을 거부한다.
- /health 를 뺀 경로가 서비스 토큰을 검사한다.
"""

import pytest
from fastapi.testclient import TestClient
from langgraph.types import Command

import skillops_agent.main as main
from skillops_agent import nodes
from skillops_agent.run_store import RunStore

# 테스트에 쓰는 고정 UUID 와 그 r_ 별칭이다. OTHER_RUN_ID 는 EXECUTION 과 다른 실행이다.
EXECUTION = "33333333-3333-4333-8333-333333333333"
BASELINE = "11111111-1111-4111-8111-111111111111"
CANDIDATE = "22222222-2222-4222-8222-222222222222"
RUN_ID = "r_" + EXECUTION.replace("-", "")
OTHER_RUN_ID = "r_" + "4" * 32
# skillops-agent/skillops_agent/run_store.py 의 INTERRUPTED_ERROR 와 같은 문구다.
INTERRUPTED = "Agent restarted during execution; this run was interrupted."
# skillops-agent/skillops_agent/main.py 의 DiagnoseRequest.model_dump() 키와 그 순서다.
TRIGGER_KEYS = [
    "type",
    "execution_id",
    "baseline_snapshot_id",
    "candidate_snapshot_id",
    "changed_paths",
    "change_id",
    "entry_skill_id",
    "diagnosis_only",
    "intent_checks",
    "run_id",
    "skill",
    "version",
    "intent",
    "rag_exclude",
]

client = TestClient(main.app)


@pytest.fixture(autouse=True)
def isolated_module_state(monkeypatch):
    """승인 대기 매핑을 비우고 서비스 토큰 검사를 끈다."""
    monkeypatch.setattr(main, "_pending_approvals", {})
    monkeypatch.setattr(main, "SERVICE_TOKEN", "")


@pytest.fixture
def scheduled(monkeypatch):
    """/diagnose 가 예약한 파이프라인을 실제로 돌리지 않고 (req, thread_id) 만 기록한다."""
    calls = []

    async def record(req, thread_id):
        calls.append((req, thread_id))

    monkeypatch.setattr(main, "_run_pipeline", record)
    return calls


class RecordingGraph:
    """ainvoke 인자를 기록하고 정해 둔 결과를 돌려주거나 예외를 던지는 graph 대역이다."""

    def __init__(self, result=None, error=None):
        self.calls = []
        self.result = result
        self.error = error

    async def ainvoke(self, *args, **kwargs):
        self.calls.append((args, kwargs))
        if self.error:
            raise self.error
        return self.result


# ── _run_pipeline ──────────────────────────────────────────────────────────────


async def test_run_pipeline_invokes_graph_with_initial_state_and_limits(monkeypatch):
    """초기 state 와 thread config, recursion_limit 으로 graph 를 부르고, 결과를 저장해 등록한다."""
    req = main.DiagnoseRequest(type="failure", run_id=RUN_ID)
    thread, _ = RunStore().accept(req.model_dump())
    result = {"diagnosis_status": "EVALUATED", "events": [{"msg": "끝"}], "proposal": {"id": "p-1"}}
    fake = RecordingGraph(result=result)
    monkeypatch.setattr(main, "graph", fake)
    assert await main._run_pipeline(req, thread) is None
    [(args, kwargs)] = fake.calls
    assert args == ({"trigger": req.model_dump(), "max_steps": 40, "pipeline_thread_id": thread},)
    assert list(args[0]) == ["trigger", "max_steps", "pipeline_thread_id"]
    assert kwargs == {"config": {"configurable": {"thread_id": thread}, "recursion_limit": 100}}
    assert list(kwargs["config"]) == ["configurable", "recursion_limit"]
    run = RunStore().get(thread)
    assert (run["diagnosis_status"], run["events"], run["proposal"]) == (
        "EVALUATED",
        [{"msg": "끝"}],
        {"id": "p-1"},
    )
    assert main._pending_approvals == {"p-1": thread}


@pytest.mark.parametrize(
    "result",
    [
        {"diagnosis_status": "VALIDATED"},
        {"diagnosis_status": "VALIDATED", "proposal": None},
        {"diagnosis_status": "EVALUATED", "proposal": {}},
        {"diagnosis_status": "EVALUATED", "proposal": {"id": ""}},
    ],
    ids=["no-proposal", "none", "empty", "blank-id"],
)
async def test_run_pipeline_registers_only_proposals_with_id(monkeypatch, result):
    """proposal.id 가 없으면 승인 대기에 등록하지 않는다."""
    req = main.DiagnoseRequest(type="skill_saved", candidate_snapshot_id=CANDIDATE)
    thread, _ = RunStore().accept(req.model_dump())
    monkeypatch.setattr(main, "graph", RecordingGraph(result=result))
    await main._run_pipeline(req, thread)
    assert RunStore().get(thread)["diagnosis_status"] == result["diagnosis_status"]
    assert main._pending_approvals == {}


@pytest.mark.parametrize(
    ("error", "text"),
    [
        (RuntimeError("boom"), "RuntimeError: boom"),
        (ValueError("한글 오류"), "ValueError: 한글 오류"),
        (ExceptionGroup("tools", [ValueError("x")]), "ExceptionGroup: tools (1 sub-exception)"),
    ],
    ids=["runtime", "korean", "group"],
)
async def test_run_pipeline_records_graph_failure_as_error(monkeypatch, error, text):
    """graph 예외는 '<타입>: <메시지>' 로 ERROR 를 저장한다. ExceptionGroup 도 풀지 않는다."""
    req = main.DiagnoseRequest(type="failure", execution_id=EXECUTION)
    thread, _ = RunStore().accept(req.model_dump())
    monkeypatch.setattr(main, "graph", RecordingGraph(error=error))
    assert await main._run_pipeline(req, thread) is None
    run = RunStore().get(thread)
    assert (run["diagnosis_status"], run["error"]) == ("ERROR", text)
    assert run["events"] == []
    assert main._pending_approvals == {}


async def test_run_pipeline_records_store_failure_as_error(monkeypatch):
    """결과 저장이 실패해도 같은 try 안이라 ERROR 로 남고 등록하지 않는다."""
    req = main.DiagnoseRequest(type="failure", execution_id=EXECUTION)
    thread, _ = RunStore().accept(req.model_dump())
    result = {"diagnosis_status": "EVALUATED", "proposal": {"id": "p-1"}, "bad": object()}
    monkeypatch.setattr(main, "graph", RecordingGraph(result=result))
    await main._run_pipeline(req, thread)
    run = RunStore().get(thread)
    assert (run["diagnosis_status"], run["error"]) == (
        "ERROR",
        "TypeError: Object of type object is not JSON serializable",
    )
    assert "proposal" not in run
    assert main._pending_approvals == {}


async def test_resume_pipeline_sends_approval_command_without_recursion_limit(monkeypatch):
    """승인 재개는 Command(resume={status, token}) 와 thread config 만 넘긴다."""
    fake = RecordingGraph(result={"approval_status": "approved"})
    monkeypatch.setattr(main, "graph", fake)
    req = main.ApproveRequest(proposal_id="p-1", status="rejected", token="tok")
    assert await main._resume_pipeline(req, "thread-1") is None
    [(args, kwargs)] = fake.calls
    assert len(args) == 1
    assert isinstance(args[0], Command)
    assert args[0] == Command(resume={"status": "rejected", "token": "tok"})
    assert list(args[0].resume) == ["status", "token"]
    assert kwargs == {"config": {"configurable": {"thread_id": "thread-1"}}}


# ── /approve ───────────────────────────────────────────────────────────────────


def test_approve_unknown_proposal_is_404():
    """등록되지 않은 제안서는 404 다."""
    res = client.post("/approve", json={"proposal_id": "p-x", "status": "approved"})
    assert res.status_code == 404
    assert res.json() == {"detail": "Proposal 'p-x' not found or already processed"}


def test_approve_registered_proposal_schedules_resume_once(monkeypatch):
    """등록된 제안서는 200 과 함께 재개를 한 번 예약하고, 두 번째 호출은 404 다."""
    resumed = []

    async def record(req, thread_id):
        resumed.append((req, thread_id))

    monkeypatch.setattr(main, "_resume_pipeline", record)
    main._pending_approvals["p-1"] = "thread-1"
    res = client.post("/approve", json={"proposal_id": "p-1", "status": "approved", "token": "t"})
    assert res.status_code == 200
    assert list(res.json().items()) == [
        ("accepted", True),
        ("proposal_id", "p-1"),
        ("status", "approved"),
    ]
    assert [(r.proposal_id, r.status, r.token, t) for r, t in resumed] == [
        ("p-1", "approved", "t", "thread-1")
    ]
    assert main._pending_approvals == {}
    again = client.post("/approve", json={"proposal_id": "p-1", "status": "approved"})
    assert again.status_code == 404
    assert len(resumed) == 1


def test_approve_rejects_unknown_status():
    """status 는 approved 와 rejected 만 받고, 거절된 요청은 승인 대기 매핑을 건드리지 않는다."""
    main._pending_approvals["p-1"] = "thread-1"
    res = client.post("/approve", json={"proposal_id": "p-1", "status": "maybe"})
    assert res.status_code == 422
    assert main._pending_approvals == {"p-1": "thread-1"}


# ── 진행 조회 ──────────────────────────────────────────────────────────────────


def test_health_body():
    """/health 는 고정 본문이다."""
    res = client.get("/health")
    assert res.status_code == 200
    assert list(res.json().items()) == [("status", "ok"), ("service", "skillops-agent")]


def test_unknown_run_events_is_404():
    """모르는 실행의 events 는 404 다."""
    res = client.get("/runs/missing/events")
    assert res.status_code == 404
    assert res.json() == {"detail": "Diagnosis not found"}


def test_run_events_and_list_return_stored_runs(scheduled):
    """/runs/{id}/events 는 RunStore.get 결과를 그대로, /runs 는 {items: list()} 를 돌려준다."""
    first = client.post(
        "/diagnose", json={"type": "skill_saved", "candidate_snapshot_id": CANDIDATE}
    ).json()
    RunStore().update(first["thread_id"], {"diagnosis_status": "RUNNING", "events": [{"m": "가"}]})
    stored = RunStore().get(first["thread_id"])
    events = client.get(f"/runs/{first['thread_id']}/events")
    assert events.status_code == 200
    assert list(events.json().items()) == list(stored.items())
    runs = client.get("/runs")
    assert runs.status_code == 200
    assert runs.json() == {"items": [stored]}


def test_startup_marks_interrupted_runs():
    """앱이 시작하면 RunStore().recover() 로 진행 중이던 실행을 ERROR 로 바꾼다."""
    thread, _ = RunStore().accept({"change_id": "startup"})
    with TestClient(main.app):
        pass
    run = RunStore().get(thread)
    assert (run["diagnosis_status"], run["error"]) == ("ERROR", INTERRUPTED)


# ── /diagnose 요청 검증 ─────────────────────────────────────────────────────────


def test_run_id_alias_becomes_hyphenated_execution_id(scheduled):
    """run_id 별칭만 보내면 execution_id 는 하이픈 UUID 로, run_id 는 원문으로 저장하고 응답한다."""
    res = client.post("/diagnose", json={"type": "failure", "run_id": RUN_ID})
    assert res.status_code == 202
    body = res.json()
    assert list(body) == ["accepted", "thread_id", "duplicate", "trigger"]
    assert (body["accepted"], body["duplicate"]) == (True, False)
    assert list(body["trigger"].items()) == [
        ("type", "failure"),
        ("execution_id", EXECUTION),
        ("baseline_snapshot_id", None),
        ("candidate_snapshot_id", None),
        ("changed_paths", None),
        ("change_id", None),
        ("entry_skill_id", "order-flow"),
        ("diagnosis_only", False),
        ("intent_checks", []),
        ("run_id", RUN_ID),
        ("skill", None),
        ("version", None),
        ("intent", None),
        ("rag_exclude", []),
    ]
    stored = RunStore().get(body["thread_id"])
    assert list(stored["trigger"].items()) == list(body["trigger"].items())
    [(req, thread)] = scheduled
    assert thread == body["thread_id"]
    assert req.model_dump() == body["trigger"]


@pytest.mark.parametrize(
    "payload",
    [
        {"type": "failure", "execution_id": RUN_ID, "run_id": EXECUTION},
        {"type": "failure", "execution_id": EXECUTION, "run_id": RUN_ID},
        {"type": "failure", "execution_id": RUN_ID},
    ],
    ids=["alias-in-execution-id", "alias-in-run-id", "alias-only-execution-id"],
)
def test_matching_execution_and_run_ids_are_normalized(scheduled, payload):
    """execution_id 와 run_id 가 같은 실행이면 execution_id 를 하이픈 UUID 로 맞춘다."""
    res = client.post("/diagnose", json=payload)
    assert res.status_code == 202
    trigger = res.json()["trigger"]
    assert trigger["execution_id"] == EXECUTION
    assert trigger["run_id"] == payload.get("run_id")


@pytest.mark.parametrize(
    "payload",
    [
        {"type": "skill_saved", "candidate_snapshot_id": CANDIDATE},
        {
            "type": "skill_saved",
            "candidate_snapshot_id": CANDIDATE,
            "baseline_snapshot_id": BASELINE,
            "changed_paths": ["skills/payment/SKILL.md"],
            "change_id": "c-1",
            "skill": "payment",
            "version": 2,
        },
        {
            "type": "failure",
            "execution_id": EXECUTION,
            "diagnosis_only": True,
            "intent_checks": ["payment.status == 'PAID'"],
        },
        {
            "type": "skill_saved",
            "candidate_snapshot_id": CANDIDATE,
            "run_id": RUN_ID,
            "diagnosis_only": True,
            "intent_checks": ["a == 1"],
        },
    ],
    ids=["skill-saved", "skill-saved-full", "diagnosis-only", "diagnosis-only-run-id"],
)
def test_valid_triggers_are_accepted_and_scheduled(scheduled, payload):
    """규칙을 모두 지키면 202 로 받고 파이프라인을 한 번 예약한다."""
    res = client.post("/diagnose", json=payload)
    assert res.status_code == 202
    assert res.json()["trigger"] == {
        **dict.fromkeys(TRIGGER_KEYS),
        "entry_skill_id": "order-flow",
        "diagnosis_only": False,
        "intent_checks": [],
        "rag_exclude": [],
        **payload,
        "execution_id": EXECUTION if ("execution_id" in payload or "run_id" in payload) else None,
    }
    assert len(scheduled) == 1


@pytest.mark.parametrize(
    ("payload", "message"),
    [
        (
            {"type": "failure", "execution_id": EXECUTION, "run_id": OTHER_RUN_ID},
            "execution_id and run_id differ",
        ),
        (
            {"type": "skill_saved", "execution_id": EXECUTION, "run_id": OTHER_RUN_ID},
            "execution_id and run_id differ",
        ),
        (
            {"type": "failure", "execution_id": EXECUTION, "run_id": "nope"},
            "execution_id must be a UUID or r_ + UUID hex",
        ),
        (
            {"type": "failure", "execution_id": "r_fake"},
            "execution_id must be a UUID or r_ + UUID hex",
        ),
        ({"type": "skill_saved", "run_id": "bad"}, "execution_id must be a UUID or r_ + UUID hex"),
        ({"type": "failure"}, "failure requires execution_id (run_id alias accepted)"),
        (
            {"type": "failure", "baseline_snapshot_id": "not-uuid", "diagnosis_only": True},
            "failure requires execution_id (run_id alias accepted)",
        ),
        (
            {"type": "skill_saved", "baseline_snapshot_id": "not-uuid"},
            "skill_saved requires candidate_snapshot_id",
        ),
        (
            {
                "type": "failure",
                "execution_id": EXECUTION,
                "baseline_snapshot_id": "not-uuid",
                "diagnosis_only": True,
            },
            "badly formed hexadecimal UUID string",
        ),
        (
            {"type": "skill_saved", "candidate_snapshot_id": "not-uuid"},
            "badly formed hexadecimal UUID string",
        ),
        (
            {"type": "failure", "execution_id": EXECUTION, "diagnosis_only": True},
            "diagnosis_only requires execution_id and intent_checks",
        ),
        (
            {
                "type": "skill_saved",
                "candidate_snapshot_id": CANDIDATE,
                "diagnosis_only": True,
                "intent_checks": ["a == 1"],
            },
            "diagnosis_only requires execution_id and intent_checks",
        ),
    ],
    ids=[
        "ids-differ",
        "ids-differ-before-type-rule",
        "run-id-unreadable",
        "execution-id-unreadable",
        "run-id-only-unreadable",
        "failure-needs-execution",
        "failure-rule-before-uuid",
        "skill-saved-rule-before-uuid",
        "uuid-before-diagnosis-only",
        "candidate-not-uuid",
        "diagnosis-only-needs-checks",
        "diagnosis-only-needs-execution",
    ],
)
def test_invalid_references_return_the_first_failing_rule(scheduled, payload, message):
    """규칙을 정해진 순서로 보고, 처음 실패한 규칙의 메시지를 422 로 돌려준다.

    순서는 불일치 검사와 정규화, type 별 필수 참조, snapshot UUID, diagnosis_only 입력이다.
    """
    res = client.post("/diagnose", json=payload)
    assert res.status_code == 422
    [error] = res.json()["detail"]
    assert (error["type"], error["loc"], error["msg"]) == (
        "value_error",
        ["body"],
        f"Value error, {message}",
    )
    assert scheduled == []
    assert RunStore().list_all() == []


def test_unknown_fields_are_rejected(scheduled):
    """정의되지 않은 필드는 422 다."""
    res = client.post("/diagnose", json={"type": "failure", "execution_id": EXECUTION, "x": 1})
    assert res.status_code == 422
    assert scheduled == []


# ── 서비스 토큰 ────────────────────────────────────────────────────────────────


# 서비스 토큰이 필요한 경로와 요청 본문이다. /health 는 여기에 없다.
PROTECTED = [
    ("get", "/runs", None),
    ("get", "/runs/missing/events", None),
    ("post", "/approve", {"proposal_id": "p-1", "status": "approved"}),
    ("post", "/diagnose", {"type": "failure", "execution_id": EXECUTION}),
]


@pytest.mark.parametrize(("method", "path", "body"), PROTECTED)
@pytest.mark.parametrize("header", [None, "wrong"])
def test_protected_routes_require_the_service_token(
    monkeypatch, scheduled, method, path, body, header
):
    """SERVICE_TOKEN 이 설정되면 토큰이 없거나 틀린 요청은 401 이다."""
    monkeypatch.setattr(main, "SERVICE_TOKEN", "secret")
    headers = {"x-service-token": header} if header else {}
    res = client.request(method, path, json=body, headers=headers)
    assert res.status_code == 401
    assert res.json() == {"detail": "Invalid service token"}
    assert scheduled == []


def test_correct_token_and_health_pass(monkeypatch, scheduled):
    """맞는 토큰이면 통과하고 /health 는 토큰이 필요 없다."""
    monkeypatch.setattr(main, "SERVICE_TOKEN", "secret")
    assert client.get("/runs", headers={"x-service-token": "secret"}).json() == {"items": []}
    assert client.get("/health").status_code == 200


# ── 담당자 의도(intent)와 rag_exclude ───────────────────────────────────────────
# docs/plans/2026-10-26-behavior-edit-root-fix.md 2.3. intent 는 {text, expectations} 이고 text 는
# 1~500자, expectations 는 0~10개, scope 는 조건 문법 v2 한 줄이나 null, checks 는 1~10개다. 칸
# 이름과 타입이 틀리면 422 지만, 문법·scope 키·checks 개수가 틀린 항목은 받고 진단에서 뺀다(2.10
# V3-2).

OWNER_INTENT = {
    "text": "선입금 주문만 결제한다",
    "expectations": [
        {
            "scope": "order.payment_method == 'card'",
            "checks": ["order.final_status in ['HOLD', 'REJECTED']", "payment.status == null"],
        },
        {"scope": None, "checks": ["order.total >= 0"]},
    ],
}


def test_intent_and_rag_exclude_are_kept_in_trigger(scheduled):
    """의도와 rag_exclude 는 받은 모양 그대로 trigger 에 남는다. scope 를 빼면 null 이다."""
    intent = {**OWNER_INTENT, "expectations": [{"checks": ["order.shipments == 0"]}]}
    payload = {
        "type": "skill_saved",
        "candidate_snapshot_id": CANDIDATE,
        "intent": intent,
        "rag_exclude": ["case-S2"],
    }
    res = client.post("/diagnose", json=payload)
    assert res.status_code == 202
    trigger = res.json()["trigger"]
    assert trigger["intent"] == {
        "text": OWNER_INTENT["text"],
        "expectations": [{"scope": None, "checks": ["order.shipments == 0"]}],
    }
    assert trigger["rag_exclude"] == ["case-S2"]
    assert list(trigger)[-2:] == ["intent", "rag_exclude"]
    res = client.post("/diagnose", json={**payload, "change_id": "c-9", "intent": OWNER_INTENT})
    assert res.status_code == 202
    assert res.json()["trigger"]["intent"] == OWNER_INTENT


def test_text_only_intent_has_empty_expectations(scheduled):
    res = client.post(
        "/diagnose",
        json={"type": "skill_saved", "candidate_snapshot_id": CANDIDATE, "intent": {"text": "x"}},
    )
    assert res.status_code == 202
    assert res.json()["trigger"]["intent"] == {"text": "x", "expectations": []}


@pytest.mark.parametrize(
    ("intent", "message"),
    [
        ({"text": ""}, "at least 1 character"),
        ({"text": "   "}, "intent text must not be blank"),
        ({"text": "x" * 501}, "at most 500 characters"),
        ({"expectations": []}, "Field required"),
        ({"text": "x", "extra": 1}, "Extra inputs are not permitted"),
        (
            {"text": "x", "expectations": [{"checks": ["a == 1"]}] * 11},
            "at most 10 items",
        ),
        (
            {"text": "x", "expectations": [{"checks": ["a == 1"], "why": "?"}]},
            "Extra inputs are not permitted",
        ),
        ({"text": "x", "expectations": [{"scope": 1, "checks": ["a == 1"]}]}, "valid string"),
    ],
    ids=[
        "empty-text",
        "blank-text",
        "long-text",
        "missing-text",
        "unknown-intent-field",
        "too-many-expectations",
        "unknown-expectation-field",
        "scope-not-string",
    ],
)
def test_invalid_intent_is_rejected(scheduled, intent, message):
    res = client.post(
        "/diagnose",
        json={"type": "skill_saved", "candidate_snapshot_id": CANDIDATE, "intent": intent},
    )
    assert res.status_code == 422
    assert any(message in error["msg"] for error in res.json()["detail"])
    assert scheduled == []


@pytest.mark.parametrize(
    "item",
    [
        {"scope": None, "checks": []},
        {"checks": ["a == 1"] * 11},
        {"scope": "order.total>=1", "checks": ["a == 1"]},
        {"checks": ["order.total >= 'big'"]},
        {"scope": "payment.status == 'X'", "checks": ["a == 1"]},
    ],
    ids=[
        "empty-checks",
        "too-many-checks",
        "unreadable-scope",
        "unreadable-check",
        "scope-key-not-allowed",
    ],
)
def test_unreadable_intent_items_do_not_reject_the_request(scheduled, item):
    """읽을 수 없는 expectation 하나가 /diagnose 전체를 422 로 막지 않는다(V3-2).

    받은 모양 그대로 trigger 에 남고, nodes 의 _owner_intent 가 그 항목을 빼고 dropped 로 남긴다.
    """
    intent = {"text": "x", "expectations": [{"scope": None, "checks": ["a == 1"]}, item]}
    res = client.post(
        "/diagnose",
        json={"type": "skill_saved", "candidate_snapshot_id": CANDIDATE, "intent": intent},
    )
    assert res.status_code == 202
    trigger = res.json()["trigger"]
    assert trigger["intent"]["expectations"][1] == {"scope": None, **item}
    assert len(scheduled) == 1
    owner = nodes._owner_intent({"trigger": trigger})
    assert owner["expectations"][0] == {"scope": None, "checks": ["a == 1"]}
    assert owner["dropped"]


def test_rag_exclude_must_be_a_list_of_strings(scheduled):
    res = client.post(
        "/diagnose",
        json={"type": "skill_saved", "candidate_snapshot_id": CANDIDATE, "rag_exclude": "case-S2"},
    )
    assert res.status_code == 422
    assert scheduled == []
