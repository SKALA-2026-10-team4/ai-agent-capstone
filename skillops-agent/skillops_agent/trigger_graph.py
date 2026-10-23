"""호출(트리거) 진단 그래프를 조립한다. 기획안 F4(호출 진단)와 F6(개선안 생성·재검증)을 맡는다.

회귀 진단 그래프(graph.py)와 따로 compile 한다. 노드 오류 처리(guarded)와 오류 시 END 로 가는
간선(_stop_on_error)은 graph.py 의 것을 그대로 쓴다.

    load_eval_set ─┬─ 질의 있음 ─────────────────────┬→ measure_baseline ─┬─ 고칠 것 없음 → report
                   └─ 질의 없음 → generate_eval_queries ┘                   └─ diagnose
    diagnose → propose → reverify ─┬─ 통과 & 목표 재현율 도달 → report
                                   ├─ 시도 < MAX_TRIGGER_ATTEMPTS → diagnose
                                   └─ 시도 소진 → report (통과한 안 중 가장 좋은 안)

skillops-agent/scripts/run_trigger.py 와 skillops-agent/tests/test_trigger_graph.py 가
trigger_graph 를 ainvoke 한다.
"""

from langgraph.checkpoint.memory import MemorySaver
from langgraph.graph import END, StateGraph

from . import trigger_metrics as metrics
from . import trigger_nodes
from .config import MAX_TRIGGER_ATTEMPTS
from .graph import _always, _stop_on_error, guarded
from .trigger_state import TriggerState


def route_loaded(state: TriggerState) -> str:
    """평가 질의가 있으면 바로 재고, 없으면 먼저 만든다."""
    return "measure" if state["cases"] else "generate"


def route_baseline(state: TriggerState) -> str:
    """원래 description 으로 이미 다 맞히면 진단하지 않는다."""
    return "clean" if metrics.no_issue(state["baseline"]) else "diagnose"


def route_reverify(state: TriggerState) -> str:
    """마지막 안이 통과하고 목표 재현율에 닿으면 끝내고, 시도가 남았으면 다시 진단한다."""
    if trigger_nodes.reached_target(state["attempts"][-1]):
        return "passed"
    if len(state["attempts"]) >= MAX_TRIGGER_ATTEMPTS:
        return "exhausted"
    return "retry"


ENTRY = "load_eval_set"
TRIGGER_FLOW = {
    "load_eval_set": (
        route_loaded,
        {"measure": "measure_baseline", "generate": "generate_eval_queries"},
    ),
    "generate_eval_queries": "measure_baseline",
    "measure_baseline": (route_baseline, {"clean": "report", "diagnose": "diagnose"}),
    "diagnose": "propose",
    "propose": "reverify",
    "reverify": (
        route_reverify,
        {"passed": "report", "retry": "diagnose", "exhausted": "report"},
    ),
    "report": END,
}


def build_trigger_graph(checkpointer=None):
    """TRIGGER_FLOW 대로 노드와 간선을 조립한다. graph.build_graph 와 같은 방식이다."""
    builder = StateGraph(TriggerState)
    for name in TRIGGER_FLOW:
        builder.add_node(name, guarded(getattr(trigger_nodes, name)))
    builder.set_entry_point(ENTRY)
    for source, step in TRIGGER_FLOW.items():
        if step == END:
            builder.add_edge(source, END)
            continue
        decide, targets = step if isinstance(step, tuple) else (_always(step), {step: step})
        builder.add_conditional_edges(source, _stop_on_error(decide), {**targets, END: END})
    return builder.compile(
        checkpointer=checkpointer if checkpointer is not None else MemorySaver()
    )


trigger_graph = build_trigger_graph()
