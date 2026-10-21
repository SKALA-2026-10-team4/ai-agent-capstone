"""PB-002/003 인수 조건: 문서의 Node·분기·State 가 일치하고 한 바퀴 돈다."""

from types import SimpleNamespace

import pytest
from langgraph.types import Command

from skillops_agent.graph import graph
from skillops_agent.state import AgentState
from skillops_agent.tools import StubMCPTools


# ── FakeLLM: 실제 API 없이 구조화 출력을 돌려준다 ────────────────────────────

class _FakeChain:
    def __init__(self, val):
        self._val = val

    async def ainvoke(self, *args, **kwargs):
        return self._val


_STUB_PATCH = "--- a/inventory-check/SKILL.md\n+++ b/inventory-check/SKILL.md\n@@ -1 +1 @@\n-old\n+new\n"


def _fake_structured_llm(schema):
    name = schema.__name__
    if "Hypothes" in name:
        val = SimpleNamespace(hypotheses=[
            SimpleNamespace(change_no=0, summary="inventory-check 출력 필드명 변경", confidence=0.95),
        ])
    elif "Intent" in name:
        val = SimpleNamespace(
            text="결제 서비스가 inventory.reserved 필드를 필요로 함",
            checks=["payment.status == 'PAID'"],
        )
    elif "Candidates" in name:
        val = SimpleNamespace(candidates=[
            SimpleNamespace(kind="same_skill",    patches=[SimpleNamespace(skill="inventory-check", base_version=4, patch=_STUB_PATCH)]),
            SimpleNamespace(kind="linked_skills", patches=[SimpleNamespace(skill="inventory-check", base_version=4, patch=_STUB_PATCH), SimpleNamespace(skill="payment", base_version=4, patch=_STUB_PATCH)]),
            SimpleNamespace(kind="run_order",     patches=[SimpleNamespace(skill="inventory-check", base_version=4, patch=_STUB_PATCH)]),
        ])
    elif "RankExplan" in name:
        val = SimpleNamespace(explanations=[
            SimpleNamespace(candidate_no=1, explanation="g-01~g-06 및 재현 시나리오 통과"),
            SimpleNamespace(candidate_no=2, explanation="계약 일관성 확보 후 통과"),
            SimpleNamespace(candidate_no=3, explanation="전체 흐름 재구성 후 통과"),
        ])
    else:
        val = SimpleNamespace()
    return _FakeChain(val)


@pytest.fixture(autouse=True)
def patch_llm(monkeypatch):
    monkeypatch.setattr("skillops_agent.nodes.make_structured_llm", _fake_structured_llm)


@pytest.fixture(autouse=True)
def patch_tools(monkeypatch):
    monkeypatch.setattr("skillops_agent.nodes.get_tools", lambda: StubMCPTools())


# ── 재시도용 Stub: 처음 ablation 은 실패, 이후는 통과 ─────────────────────────

class _OnceFailAblationStub(StubMCPTools):
    def __init__(self):
        super().__init__()
        self._ablation_calls = 0

    async def run_scenarios(
        self, workspace_id: str, scenario_ids: list[str], repeat: int = 2, **kwargs
    ) -> dict:
        if scenario_ids == ["f-01"]:
            self._ablation_calls += 1
            if self._ablation_calls == 1:
                return {"results": [{"scenario_id": "f-01", "passed": False, "all_passed": False, "run_ids": [], "failures": ["첫 ablation 실패"]}]}
        return await StubMCPTools.run_scenarios(self, workspace_id, scenario_ids, repeat)


class _AlwaysFailAblationStub(StubMCPTools):
    async def run_scenarios(
        self, workspace_id: str, scenario_ids: list[str], repeat: int = 2, **kwargs
    ) -> dict:
        if scenario_ids == ["f-01"]:
            return {"results": [{"scenario_id": "f-01", "passed": False, "all_passed": False, "run_ids": [], "failures": ["ablation 항상 실패"]}]}
        return await StubMCPTools.run_scenarios(self, workspace_id, scenario_ids, repeat)


def _initial_state(run_id: str = "r_stub_F1") -> dict:
    return {"trigger": {"type": "failure", "run_id": run_id, "skill": None, "version": None}, "max_steps": 40}


# ── State 필드 정의 확인 ──────────────────────────────────────────────────────


def test_agent_state_has_all_fields():
    required = {
        "trigger", "trace", "diff", "skill_versions", "snapshot_id",
        "hypotheses", "ablation_runs", "root_cause", "hypothesis_retry",
        "intent", "candidates", "candidate_retry", "recommended",
        "proposal", "approval_status", "approval_token", "smoke_passed",
        "step_count", "max_steps", "events", "error",
    }
    assert required.issubset(AgentState.__annotations__.keys())


# ── Graph Node 등록 확인 ──────────────────────────────────────────────────────


def test_graph_has_all_nodes():
    # v1: promote_workspace / smoke_check / rollback 제거됨
    expected = {
        "init", "fetch_evidence", "hypothesize", "ablate", "check_ablation",
        "extract_intent", "generate_candidates", "verify_candidates", "check_valid",
        "rank_and_explain", "save_proposal", "wait_for_approval",
    }
    registered = set(graph.nodes.keys())
    assert expected.issubset(registered)


# ── 한 바퀴 통합 실행 ─────────────────────────────────────────────────────────


@pytest.mark.asyncio
async def test_graph_runs_to_approval_interrupt():
    """init → ... → save_proposal → wait_for_approval(interrupt) 까지 실행된다."""
    config = {"configurable": {"thread_id": "test-cycle-1"}}
    result = await graph.ainvoke(_initial_state(), config=config)

    events = result.get("events", [])
    node_names = [e["node"] for e in events]

    assert "init" in node_names
    assert "fetch_evidence" in node_names
    assert "hypothesize" in node_names
    assert "ablate" in node_names
    assert "save_proposal" in node_names


@pytest.mark.asyncio
async def test_graph_resumes_after_approval():
    """승인 응답으로 재개하면 approval_status 가 approved 로 종료된다.
    v1: promote_workspace / smoke_check 는 서비스가 처리한다.
    """
    config = {"configurable": {"thread_id": "test-cycle-2"}}

    # interrupt 까지 실행
    await graph.ainvoke(_initial_state(), config=config)

    # 승인으로 재개
    final = await graph.ainvoke(
        Command(resume={"status": "approved", "token": "tok_test_123"}),
        config=config,
    )

    assert final.get("approval_status") == "approved"
    node_names = [e["node"] for e in final.get("events", [])]
    # v1: 서비스가 promote 담당 → agent 에서 호출하지 않음
    assert "promote_workspace" not in node_names
    assert "smoke_check" not in node_names


@pytest.mark.asyncio
async def test_graph_ends_on_rejection():
    """거부 응답이면 proposal 없이 종료된다."""
    config = {"configurable": {"thread_id": "test-cycle-3"}}
    await graph.ainvoke(_initial_state(), config=config)

    final = await graph.ainvoke(
        Command(resume={"status": "rejected", "token": None}),
        config=config,
    )

    assert final.get("approval_status") == "rejected"
    node_names = [e["node"] for e in final.get("events", [])]
    assert "promote_workspace" not in node_names


# ── root_cause 와 proposal 이 State 에 남는지 확인 ────────────────────────────


@pytest.mark.asyncio
async def test_state_has_root_cause_and_proposal_after_run():
    config = {"configurable": {"thread_id": "test-cycle-4"}}
    result = await graph.ainvoke(_initial_state(), config=config)

    assert result.get("root_cause") is not None
    assert result.get("root_cause", {}).get("skill") is not None
    assert result.get("proposal") is not None
    assert result.get("proposal", {}).get("id") is not None


# ── skill_saved 트리거도 처리 ─────────────────────────────────────────────────


@pytest.mark.asyncio
async def test_graph_handles_skill_saved_trigger():
    config = {"configurable": {"thread_id": "test-cycle-5"}}
    state = {
        "trigger": {"type": "skill_saved", "run_id": None, "skill": "inventory-check", "version": 5},
        "max_steps": 40,
    }
    result = await graph.ainvoke(state, config=config)
    events = result.get("events", [])
    assert len(events) > 0


# ── PB-003: 재시도·소진 경로 ──────────────────────────────────────────────────


@pytest.mark.asyncio
async def test_hypothesis_retries_after_ablation_failure(monkeypatch):
    """ablation 이 한 번 기각되면 hypothesis_retry 가 증가하고 hypothesize 가 재실행된다."""
    stub = _OnceFailAblationStub()
    monkeypatch.setattr("skillops_agent.nodes.get_tools", lambda: stub)
    config = {"configurable": {"thread_id": "test-retry-pb003-1"}}
    result = await graph.ainvoke(_initial_state(), config=config)

    node_names = [e["node"] for e in result.get("events", [])]
    assert node_names.count("hypothesize") >= 2
    assert result.get("root_cause") is not None


@pytest.mark.asyncio
async def test_hypothesis_exhaustion_escalates_to_end(monkeypatch):
    """3회 재시도 소진 시 escalate(END) 로 종료되고 proposal 이 저장되지 않는다."""
    stub = _AlwaysFailAblationStub()
    monkeypatch.setattr("skillops_agent.nodes.get_tools", lambda: stub)
    config = {"configurable": {"thread_id": "test-exhaustion-pb003-1"}}
    result = await graph.ainvoke(_initial_state(), config=config)

    assert result.get("hypothesis_retry") == 3
    node_names = [e["node"] for e in result.get("events", [])]
    assert "save_proposal" not in node_names


# ── PB-004: 후보 검증 실패·소진 경로 ─────────────────────────────────────────


class _AllInvalidVerifyStub(StubMCPTools):
    """ablation 은 통과, verify_candidates 는 모두 실패."""

    async def run_scenarios(
        self, workspace_id: str, scenario_ids: list[str], repeat: int = 2, **kwargs
    ) -> dict:
        if len(scenario_ids) == 1:  # ablation 단건 실행 → 통과
            return {"results": [{"scenario_id": scenario_ids[0], "passed": True, "all_passed": True, "run_ids": [f"r_ok_{i}" for i in range(repeat)], "failures": []}]}
        # verify: 골든 + 재현 → 전부 실패
        return {"results": [{"scenario_id": sid, "passed": False, "all_passed": False, "run_ids": [], "failures": ["검증 실패"]} for sid in scenario_ids]}


@pytest.mark.asyncio
async def test_candidate_retries_after_all_invalid(monkeypatch):
    """후보 전체 invalid 시 candidate_retry 가 증가하고 generate_candidates 가 재실행된다."""
    stub = _AllInvalidVerifyStub()
    monkeypatch.setattr("skillops_agent.nodes.get_tools", lambda: stub)
    config = {"configurable": {"thread_id": "test-cand-retry-pb004-1"}}
    result = await graph.ainvoke(_initial_state(), config=config)

    node_names = [e["node"] for e in result.get("events", [])]
    assert node_names.count("generate_candidates") >= 2
    assert result.get("candidate_retry", 0) > 0


@pytest.mark.asyncio
async def test_candidate_exhaustion_saves_no_valid_fix_proposal(monkeypatch):
    """3회 소진 시 no_valid_fix 로 save_proposal 까지 도달한다."""
    stub = _AllInvalidVerifyStub()
    monkeypatch.setattr("skillops_agent.nodes.get_tools", lambda: stub)
    config = {"configurable": {"thread_id": "test-cand-exhaust-pb004-1"}}
    result = await graph.ainvoke(_initial_state(), config=config)

    assert result.get("candidate_retry") == 3
    node_names = [e["node"] for e in result.get("events", [])]
    assert "save_proposal" in node_names


# ── PB-006: 실행 제어·진행 이벤트 ────────────────────────────────────────────


@pytest.mark.asyncio
async def test_step_limit_stops_ablation_loop(monkeypatch):
    """max_steps 초과 시 ablation 루프가 limit_exceeded 로 종료된다."""
    stub = _AlwaysFailAblationStub()
    monkeypatch.setattr("skillops_agent.nodes.get_tools", lambda: stub)
    config = {"configurable": {"thread_id": "test-step-limit-ablation-1"}}
    state = {"trigger": {"type": "failure", "run_id": "r_stub_F1", "skill": None, "version": None}, "max_steps": 5}
    result = await graph.ainvoke(state, config=config)

    node_names = [e["node"] for e in result.get("events", [])]
    assert "save_proposal" not in node_names
    assert result.get("hypothesis_retry", 0) < 3


@pytest.mark.asyncio
async def test_step_limit_stops_candidate_loop(monkeypatch):
    """max_steps 초과 시 후보 루프가 no_valid_fix 로 조기 종료된다."""
    stub = _AllInvalidVerifyStub()
    monkeypatch.setattr("skillops_agent.nodes.get_tools", lambda: stub)
    config = {"configurable": {"thread_id": "test-step-limit-cand-1"}}
    state = {"trigger": {"type": "failure", "run_id": "r_stub_F1", "skill": None, "version": None}, "max_steps": 8}
    result = await graph.ainvoke(state, config=config)

    node_names = [e["node"] for e in result.get("events", [])]
    assert "save_proposal" in node_names


@pytest.mark.asyncio
async def test_events_accumulate_across_nodes():
    """실행된 모든 노드가 events 에 기록된다."""
    config = {"configurable": {"thread_id": "test-events-pb006-1"}}
    result = await graph.ainvoke(_initial_state(), config=config)

    events = result.get("events", [])
    node_names = [e["node"] for e in events]
    for e in events:
        assert "at" in e
        assert "node" in e
        assert "msg" in e
    assert "init" in node_names
    assert "fetch_evidence" in node_names
    # init 은 step_count=0 이지만 event 는 1개 추가하므로 step_count = len(events) - 1
    assert result.get("step_count", 0) == len(events) - 1
