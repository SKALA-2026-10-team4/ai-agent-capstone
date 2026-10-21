"""PB-007: Agentic 요소 실행 증거 시나리오 테스트.

각 후보의 검증 결과에 실행 증거(run_id, tools_called)가 올바르게 기록되는지 확인한다.
"""

from types import SimpleNamespace

import pytest
from langgraph.types import Command

from skillops_agent.graph import graph
from skillops_agent.tools import StubMCPTools


# ── FakeLLM (test_graph.py 와 동일 패턴) ──────────────────────────────────────

_STUB_DIFF = "--- a/inventory-check/SKILL.md\n+++ b/inventory-check/SKILL.md\n@@ -1 +1 @@\n-old\n+new\n"


class _FakeChain:
    def __init__(self, val):
        self._val = val

    async def ainvoke(self, *args, **kwargs):
        return self._val


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
            SimpleNamespace(kind="same_skill", patches=[SimpleNamespace(skill="inventory-check", base_version=4, patch=_STUB_DIFF)]),
        ])
    elif "RankExplan" in name:
        val = SimpleNamespace(explanations=[
            SimpleNamespace(candidate_no=1, explanation="재현 시나리오 및 골든 시나리오 통과"),
        ])
    else:
        val = SimpleNamespace()
    return _FakeChain(val)


@pytest.fixture(autouse=True)
def patch_llm(monkeypatch):
    monkeypatch.setattr("skillops_agent.nodes.make_structured_llm", _fake_structured_llm)


def _initial_state():
    return {
        "trigger": {"type": "failure", "run_id": "r_stub_F1", "skill": None, "version": None},
        "max_steps": 40,
    }


# ── 증거 완전성 ───────────────────────────────────────────────────────────────


@pytest.mark.asyncio
async def test_valid_candidate_has_run_id_evidence():
    """유효 후보의 evidence_run_ids 에 run_id 가 포함된다."""
    config = {"configurable": {"thread_id": "scenario-evidence-run-ids-1"}}
    result = await graph.ainvoke(_initial_state(), config=config)

    candidates = result.get("candidates", [])
    valid = [c for c in candidates if c.get("valid")]
    assert valid, "유효 후보가 최소 1개 있어야 합니다"

    for c in valid:
        assert c.get("evidence_run_ids"), f"후보 {c['candidate_no']}의 evidence_run_ids 가 비어 있습니다"
        for ev in c["evidence_run_ids"]:
            assert isinstance(ev, str) and len(ev) > 0


@pytest.mark.asyncio
async def test_valid_candidate_has_tools_called_evidence():
    """유효 후보의 results.tools_called 에 도구 호출 목록이 기록된다."""
    config = {"configurable": {"thread_id": "scenario-tools-called-1"}}
    result = await graph.ainvoke(_initial_state(), config=config)

    candidates = result.get("candidates", [])
    valid = [c for c in candidates if c.get("valid")]
    assert valid, "유효 후보가 최소 1개 있어야 합니다"

    for c in valid:
        results = c.get("results") or {}
        tools_called = results.get("tools_called", [])
        assert "check_inventory" in tools_called, (
            f"후보 {c['candidate_no']}의 tools_called 에 check_inventory 가 없습니다: {tools_called}"
        )


@pytest.mark.asyncio
async def test_ablation_run_ids_recorded_in_root_cause():
    """ablation 으로 확정된 root_cause 의 proof_run_ids 에 run_id 가 있다."""
    config = {"configurable": {"thread_id": "scenario-ablation-evidence-1"}}
    result = await graph.ainvoke(_initial_state(), config=config)

    root_cause = result.get("root_cause") or {}
    proof_run_ids = root_cause.get("proof_run_ids", [])
    assert proof_run_ids, "root_cause.proof_run_ids 가 비어 있습니다"
    for run_id in proof_run_ids:
        assert isinstance(run_id, str) and len(run_id) > 0


# ── 전체 Agentic 루프 증거 검증 ───────────────────────────────────────────────


@pytest.mark.asyncio
async def test_full_agentic_loop_evidence_chain():
    """진단 → 검증 → 승인 전 과정에서 증거가 State 에 보존된다."""
    config = {"configurable": {"thread_id": "scenario-full-loop-1"}}

    result = await graph.ainvoke(_initial_state(), config=config)

    assert result.get("root_cause") is not None
    assert result.get("intent") is not None
    assert result.get("proposal") is not None

    proposal = result["proposal"]
    assert proposal.get("id")
    assert proposal.get("status") == "proposed"
    assert proposal.get("recommended_candidate_no") is not None

    candidates = result.get("candidates", [])
    valid = [c for c in candidates if c.get("valid")]
    assert valid
    for c in valid:
        assert c.get("evidence_run_ids")
        assert (c.get("results") or {}).get("tools_called") is not None

    # 승인 후 검증
    final = await graph.ainvoke(
        Command(resume={"status": "approved", "token": "tok_scenario_001"}),
        config=config,
    )

    assert final.get("approval_status") == "approved"
    # v1: promote_workspace / smoke_check 는 서비스가 처리하므로 Agent events 에 없음
    node_names = [e["node"] for e in final.get("events", [])]
    assert "promote_workspace" not in node_names
    assert "smoke_check" not in node_names


@pytest.mark.asyncio
async def test_proposal_evidence_integrity():
    """저장된 proposal 의 candidates 에 evidence_run_ids 가 모두 포함된다."""
    config = {"configurable": {"thread_id": "scenario-proposal-integrity-1"}}
    result = await graph.ainvoke(_initial_state(), config=config)

    proposal = result.get("proposal") or {}
    for c in proposal.get("candidates", []):
        if c.get("valid"):
            assert c.get("evidence_run_ids"), f"proposal 내 후보 {c['candidate_no']}의 evidence_run_ids 가 없습니다"
