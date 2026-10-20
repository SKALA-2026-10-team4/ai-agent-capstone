"""PB-002 인수 조건: 문서의 Node·분기·State 가 일치하고 한 바퀴 돈다."""

import asyncio

import pytest
from langgraph.types import Command

from skillops_agent.graph import graph
from skillops_agent.state import AgentState


def _initial_state(run_id: str = "r_stub_F1") -> dict:
    return {"trigger": {"type": "failure", "run_id": run_id, "skill": None, "version": None}, "max_steps": 40}


# ── State 필드 정의 확인 ──────────────────────────────────────────────────────


def test_agent_state_has_all_fields():
    required = {
        "trigger", "trace", "diff", "skill_versions",
        "hypotheses", "ablation_runs", "root_cause", "hypothesis_retry",
        "intent", "candidates", "candidate_retry",
        "proposal", "approval_status", "approval_token",
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
