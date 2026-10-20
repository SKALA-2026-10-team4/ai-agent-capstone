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


_STUB_DIFF = "--- a/inventory-check/SKILL.md\n+++ b/inventory-check/SKILL.md\n@@ -1 +1 @@\n-old\n+new\n"


def _fake_structured_llm(schema):
    name = schema.__name__
    if "Hypothes" in name:
        val = SimpleNamespace(hypotheses=[
            SimpleNamespace(hunk_index=0, summary="inventory-check 출력 필드명 변경", confidence=0.95),
        ])
    elif "Intent" in name:
        val = SimpleNamespace(
            text="결제 서비스가 inventory.reserved 필드를 필요로 함",
            assertions=["payment.status == 'PAID'"],
        )
    elif "Candidates" in name:
        val = SimpleNamespace(candidates=[
            SimpleNamespace(kind="local",    patches=[SimpleNamespace(skill="inventory-check", base_version=4, diff=_STUB_DIFF)]),
            SimpleNamespace(kind="contract", patches=[SimpleNamespace(skill="inventory-check", base_version=4, diff=_STUB_DIFF), SimpleNamespace(skill="payment", base_version=4, diff=_STUB_DIFF)]),
            SimpleNamespace(kind="flow",     patches=[SimpleNamespace(skill="inventory-check", base_version=4, diff=_STUB_DIFF)]),
        ])
    elif "RankExplan" in name:
        val = SimpleNamespace(explanations=[
            SimpleNamespace(id="c-local-01",    explanation="g-01~g-06 및 재현 시나리오 통과"),
            SimpleNamespace(id="c-contract-01", explanation="계약 일관성 확보 후 통과"),
            SimpleNamespace(id="c-flow-01",     explanation="전체 흐름 재구성 후 통과"),
        ])
    else:
        val = SimpleNamespace()
    return _FakeChain(val)


@pytest.fixture(autouse=True)
def patch_llm(monkeypatch):
    monkeypatch.setattr("skillops_agent.nodes.make_structured_llm", _fake_structured_llm)


# ── 재시도용 Stub: 처음 ablation 은 실패, 이후는 통과 ─────────────────────────

class _OnceFailAblationStub(StubMCPTools):
    def __init__(self):
        super().__init__()
        self._ablation_calls = 0

    async def run_scenarios(self, ws_id: str, scenario_ids: list[str], k: int = 2) -> list[dict]:
        # ablation 은 scenario_id "f-01" 하나만 실행한다
        if scenario_ids == ["f-01"]:
            self._ablation_calls += 1
            if self._ablation_calls == 1:
                return [{"scenario_id": "f-01", "passed": False, "runs": [], "failures": ["첫 ablation 실패"]}]
        return await StubMCPTools.run_scenarios(self, ws_id, scenario_ids, k)


class _AlwaysFailAblationStub(StubMCPTools):
    async def run_scenarios(self, ws_id: str, scenario_ids: list[str], k: int = 2) -> list[dict]:
        if scenario_ids == ["f-01"]:
            return [{"scenario_id": "f-01", "passed": False, "runs": [], "failures": ["ablation 항상 실패"]}]
        return await StubMCPTools.run_scenarios(self, ws_id, scenario_ids, k)


def _initial_state(run_id: str = "r_stub_F1") -> dict:
    return {"trigger": {"type": "failure", "run_id": run_id, "skill": None, "version": None}, "max_steps": 40}


# ── State 필드 정의 확인 ──────────────────────────────────────────────────────


def test_agent_state_has_all_fields():
    required = {
        "trigger", "trace", "diff", "skill_versions",
        "hypotheses", "ablation_runs", "root_cause", "hypothesis_retry",
        "intent", "candidates", "candidate_retry", "recommended",
        "proposal", "approval_status", "approval_token", "smoke_passed",
        "step_count", "max_steps", "events", "error",
    }
    assert required.issubset(AgentState.__annotations__.keys())


# ── Graph Node 등록 확인 ──────────────────────────────────────────────────────


def test_graph_has_all_nodes():
    expected = {
        "init", "fetch_evidence", "hypothesize", "ablate", "check_ablation",
        "extract_intent", "generate_candidates", "verify_candidates", "check_valid",
        "rank_and_explain", "save_proposal", "wait_for_approval",
        "promote_workspace", "smoke_check", "rollback",
    }
    registered = set(graph.nodes.keys())
    assert expected.issubset(registered)


# ── 한 바퀴 통합 실행 ─────────────────────────────────────────────────────────


@pytest.mark.asyncio
async def test_graph_runs_to_approval_interrupt():
    """init → ... → save_proposal → wait_for_approval(interrupt) 까지 실행된다."""
    config = {"configurable": {"thread_id": "test-cycle-1"}}
    result = await graph.ainvoke(_initial_state(), config=config)

    # interrupt 로 중단됐거나 approval_status 가 아직 없는 상태
    events = result.get("events", [])
    node_names = [e["node"] for e in events]

    assert "init" in node_names
    assert "fetch_evidence" in node_names
    assert "hypothesize" in node_names
    assert "ablate" in node_names
    assert "save_proposal" in node_names


@pytest.mark.asyncio
async def test_graph_resumes_after_approval():
    """승인 응답으로 재개하면 promote_workspace 까지 실행된다."""
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
    assert "promote_workspace" in node_names
    assert "smoke_check" in node_names


@pytest.mark.asyncio
async def test_graph_ends_on_rejection():
    """거부 응답이면 promote 없이 종료된다."""
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
    # 재시도가 발생했으므로 hypothesize 가 2회 이상 실행되어야 한다
    assert node_names.count("hypothesize") >= 2
    # 두 번째 ablation 은 통과하므로 root_cause 가 확정되어야 한다
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

    async def run_scenarios(self, ws_id: str, scenario_ids: list[str], k: int = 2) -> list[dict]:
        if len(scenario_ids) == 1:  # ablation 단건 실행 → 통과
            return [{"scenario_id": scenario_ids[0], "passed": True, "runs": [f"r_ok_{i}" for i in range(k)], "failures": []}]
        # verify: 골든 + 재현 → 전부 실패
        return [{"scenario_id": sid, "passed": False, "runs": [], "failures": ["검증 실패"]} for sid in scenario_ids]


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


# ── PB-005: smoke 실패 → rollback 경로 ───────────────────────────────────────


class _SmokeFailStub(StubMCPTools):
    """ablation/verify 는 통과, smoke_check 에서 사용하는 ws_id='live' 실행은 실패."""

    async def run_scenarios(self, ws_id: str, scenario_ids: list[str], k: int = 2) -> list[dict]:
        if ws_id == "live":
            return [{"scenario_id": sid, "passed": False, "runs": [], "failures": ["스모크 실패"]} for sid in scenario_ids]
        return await StubMCPTools.run_scenarios(self, ws_id, scenario_ids, k)


@pytest.mark.asyncio
async def test_smoke_failure_triggers_rollback(monkeypatch):
    """promote 후 smoke 실패 시 rollback 노드가 실행된다."""
    stub = _SmokeFailStub()
    monkeypatch.setattr("skillops_agent.nodes.get_tools", lambda: stub)
    config = {"configurable": {"thread_id": "test-smoke-fail-pb005-1"}}

    # interrupt 까지 실행
    await graph.ainvoke(_initial_state(), config=config)

    # 승인으로 재개
    final = await graph.ainvoke(
        Command(resume={"status": "approved", "token": "tok_smoke_test"}),
        config=config,
    )

    node_names = [e["node"] for e in final.get("events", [])]
    assert "smoke_check" in node_names
    assert "rollback" in node_names
    assert "promote_workspace" in node_names
