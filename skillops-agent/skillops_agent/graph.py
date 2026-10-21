"""LangGraph StateGraph 정의 (v1).

v1 변경: promote_workspace / smoke_check / rollback → 서비스 담당.
Agent 는 save_proposal → wait_for_approval 이후 END 로 종료한다.
"""

from langgraph.checkpoint.memory import MemorySaver
from langgraph.graph import END, StateGraph

from . import nodes
from .state import AgentState

builder = StateGraph(AgentState)

# ── Node 등록 (12개) ───────────────────────────────────────────────────────────
builder.add_node("init",                nodes.init)
builder.add_node("fetch_evidence",      nodes.fetch_evidence)
builder.add_node("hypothesize",         nodes.hypothesize)
builder.add_node("ablate",              nodes.ablate)
builder.add_node("check_ablation",      nodes.check_ablation)
builder.add_node("extract_intent",      nodes.extract_intent)
builder.add_node("generate_candidates", nodes.generate_candidates)
builder.add_node("verify_candidates",   nodes.verify_candidates)
builder.add_node("check_valid",         nodes.check_valid)
builder.add_node("rank_and_explain",    nodes.rank_and_explain)
builder.add_node("save_proposal",       nodes.save_proposal)
builder.add_node("wait_for_approval",   nodes.wait_for_approval)

# ── 진입점 ────────────────────────────────────────────────────────────────────
builder.set_entry_point("init")

# ── 고정 간선 ──────────────────────────────────────────────────────────────────
builder.add_edge("init",                "fetch_evidence")
builder.add_edge("fetch_evidence",      "hypothesize")
builder.add_edge("hypothesize",         "ablate")
builder.add_edge("ablate",              "check_ablation")
builder.add_edge("extract_intent",      "generate_candidates")
builder.add_edge("generate_candidates", "verify_candidates")
builder.add_edge("verify_candidates",   "check_valid")
builder.add_edge("rank_and_explain",    "save_proposal")
builder.add_edge("save_proposal",       "wait_for_approval")

# ── 조건 분기 ─────────────────────────────────────────────────────────────────
builder.add_conditional_edges(
    "check_ablation",
    nodes.route_ablation,
    {"confirmed": "extract_intent", "retry": "hypothesize", "escalate": END, "limit_exceeded": END},
)

builder.add_conditional_edges(
    "check_valid",
    nodes.route_valid,
    {"has_valid": "rank_and_explain", "retry": "generate_candidates", "no_valid_fix": "save_proposal"},
)

# v1: 승인/거부 모두 END — promote/smoke/rollback 은 서비스가 처리한다
builder.add_conditional_edges(
    "wait_for_approval",
    nodes.route_approval,
    {"approved": END, "rejected": END},
)

# ── 컴파일 ────────────────────────────────────────────────────────────────────
graph = builder.compile(checkpointer=MemorySaver())
