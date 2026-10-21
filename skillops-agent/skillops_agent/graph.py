"""B diagnosis graph using the current C contract; external proposal saving is unavailable."""

import inspect
from functools import wraps

from langgraph.checkpoint.memory import MemorySaver
from langgraph.graph import END, StateGraph

from . import nodes
from .run_store import RunStore
from .state import AgentState


def error_text(exc):
    if isinstance(exc, BaseExceptionGroup):
        return "; ".join(error_text(child) for child in exc.exceptions)
    return f"{type(exc).__name__}: {exc}"


def guarded(fn):
    @wraps(fn)
    async def run(state):
        try:
            if inspect.iscoroutinefunction(fn):
                result = await fn(state)
            else:
                result = fn(state)
        except Exception as exc:
            result = {"error": error_text(exc), "diagnosis_status": "ERROR"}
        if state.get("pipeline_thread_id"):
            RunStore().update(state["pipeline_thread_id"], result)
        return result

    return run


def next_node(target):
    def route(state):
        return END if state.get("error") else target

    return route


builder = StateGraph(AgentState)
for name in (
    "init",
    "fetch_evidence",
    "hypothesize",
    "ablate",
    "check_ablation",
    "extract_intent",
    "generate_candidates",
    "verify_candidates",
    "check_valid",
    "rank_and_explain",
    "save_proposal",
):
    builder.add_node(name, guarded(getattr(nodes, name)))
builder.set_entry_point("init")
builder.add_conditional_edges("init", next_node("fetch_evidence"))


def evidence_route(state):
    if state.get("error") or state.get("diagnosis_status") != "RUNNING":
        return END
    if not state.get("changes"):
        return END
    return "hypothesize"


builder.add_conditional_edges("fetch_evidence", evidence_route)
for source, target in (
    ("hypothesize", "ablate"),
    ("ablate", "check_ablation"),
    ("extract_intent", "generate_candidates"),
    ("generate_candidates", "verify_candidates"),
    ("verify_candidates", "check_valid"),
    ("rank_and_explain", "save_proposal"),
):
    builder.add_conditional_edges(source, next_node(target))


def ablation_route(state):
    if state.get("error"):
        return END
    route = nodes.route_ablation(state)
    if route in ("escalate", "limit_exceeded"):
        return "save_proposal"
    return "extract_intent" if route == "confirmed" else "hypothesize"


def valid_route(state):
    if state.get("error"):
        return END
    route = nodes.route_valid(state)
    return {
        "has_valid": "rank_and_explain",
        "retry": "generate_candidates",
        "no_valid_fix": "save_proposal",
    }[route]


builder.add_conditional_edges("check_ablation", ablation_route)
builder.add_conditional_edges("check_valid", valid_route)
builder.add_edge("save_proposal", END)
graph = builder.compile(checkpointer=MemorySaver())
