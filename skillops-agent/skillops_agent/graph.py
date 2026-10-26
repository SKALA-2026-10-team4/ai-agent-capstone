"""B 의 진단 그래프를 현재 C 계약으로 조립한다. 제안서는 C 의 save_proposal 로 저장한다.

흐름은 이 모듈의 FLOW 표 하나에 모여 있다. 정상 경로는 init, fetch_evidence, hypothesize,
ablate, check_ablation, extract_intent, generate_candidates, verify_candidates, check_valid,
rank_and_explain, save_proposal 순서이고, 노드 함수는 skillops-agent/skillops_agent/nodes.py 에
같은 이름으로 있다. build_graph 가 FLOW 의 이름으로 그 함수를 찾아 guarded 로 감싸 등록한다.
partial 목록은 collect_partial_sources, collect_partial_diffs에서 증거를 저장하고 끝난다.
분기는 세 곳이다.
- fetch_evidence 뒤에서는 route_evidence 가 진단을 이을지(diagnose) 끝낼지(stop) 정한다. 수정본이
  오류 없이 돌지만 담당자 의도가 실행되지 않으면(diagnosis.finding=INTENT_NOT_REALIZED) 원인 실험을
  건너뛰고 extract_intent 로 간다(intent_gap).
- check_ablation 뒤에서는 route_ablation 이 원인 확정(confirmed), 결과 불일치 원인 실험의 확정
  실패(stop, VALIDATION_FAILED), 가설 재시도(retry), 재시도 소진(escalate), 단계
  한도(limit_exceeded)를 가른다.
- 담당자 수정이 어떤 업무 도구도 만들지 않는 값을 요구하면(diagnosis.finding=NEEDS_TOOL,
  docs/plans/2026-10-26-behavior-edit-root-fix.md 2.11) 원인 확정이나 확정 실패 뒤 needs_tool 로
  extract_intent·generate_candidates·verify_candidates 를 건너뛰고 save_proposal 로 간다.
  fetch_evidence 가 이미 원인을 정한 의도 미실현이면 fetch_evidence 뒤에서 바로 간다.
- check_valid 뒤에서는 route_valid 가 유효 후보(has_valid), 후보 재시도(retry),
  포기(no_valid_fix)를 가른다.
어느 노드든 error 를 남기면 판정 없이 END 로 간다. guarded 가 노드 예외를 error 로 바꾸고, 상태에
pipeline_thread_id 가 있으면 노드마다 진행 상황을 RunStore 에 저장한다.

흐름을 바꿀 때는 다음 곳을 고친다.
- 단계를 하나 추가하려면 nodes.py 에 노드 이름과 같은 함수(state 를 받아 바꾼 키만 dict 로
  돌려준다)를 만들고 같은 파일의 _progress 로 반환값을 만든다. 그다음 FLOW 에 새 이름과 다음
  노드를 짝지은 항목을 넣고, 앞 노드의 값이 새 이름을 가리키게 바꾼다. 테스트가 nodes 모듈의
  get_tools 와 make_structured_llm 을 바꿔 끼우므로 외부를 부르는 노드는 nodes.py 에 둔다.
- 새 상태 키는 skillops-agent/skillops_agent/state.py 의 AgentState 에 선언하고, 실행마다 초기화할
  값이면 empty_run_fields 에도 넣는다. empty_run_fields 를 바꾸면
  skillops-agent/tests/test_pipeline_defaults.py 의 기본값 표와
  skillops-agent/tests/test_flow_characterization.py 의 RunStore 키 목록도 고친다.
- 분기를 추가하려면 이 모듈에 라벨 글을 돌려주는 판정 함수를 만들고, FLOW 값으로
  (판정 함수, {라벨: 다음 노드}) 를 넣는다.
- 재시도 한도와 단계 한도는 skillops-agent/skillops_agent/config.py 의 MAX_HYPOTHESIS_RETRIES,
  MAX_CANDIDATE_RETRIES, MAX_STEPS_DEFAULT 를 바꾼다. 판정 순서는 route_ablation 과 route_valid
  에 있다. 값을 바꿀 때 함께 고칠 테스트는 config.py 의 각 상수 주석에 있다.
- 흐름을 바꾸면 skillops-agent/tests/test_flow_characterization.py 의 HAPPY_NODES,
  HYPOTHESIS_LOOP, REPAIR_LOOP, 진행 메시지, step_count 단언, LATER_ERRORS 의 노드 수를 함께
  고친다. 새 노드가 LLM 을 부르면 그 파일의 default_outputs 와 skillops-agent/tests/test_graph.py
  의 fake_llm 에 출력 클래스 이름별 고정 출력을 더한다. 새 도구를 부르면
  skillops-agent/skillops_agent/tools.py 의 MCPTools 와 skillops-agent/skillops_agent/stub_tools.py
  의 StubMCPTools 에 같은 이름과 매개변수의 메서드를 함께 만든다.
  skillops-agent/tests/test_stub_tools.py 가 두 클래스의 대칭을 검사한다.

skillops-agent/skillops_agent/main.py 의 _run_pipeline 이 모듈 전역 graph 를 ainvoke 로 실행한다.
테스트와 skillops-agent/scripts/ 의 run_demo.py, run_real_llm.py, b_c_contract_smoke.py 도 같은
graph 를 쓴다.
"""

import inspect
from functools import wraps

from langgraph.checkpoint.memory import MemorySaver
from langgraph.graph import END, StateGraph

from . import nodes
from .config import MAX_CANDIDATE_RETRIES, MAX_HYPOTHESIS_RETRIES, MAX_STEPS_DEFAULT
from .run_store import RunStore
from .state import INTENT_NOT_REALIZED, NEEDS_TOOL, AgentState, DiagnosisStatus


def error_text(exc):
    """guarded 가 상태의 error 에 남길 문구('<예외 타입>: <메시지>')를 만든다.

    anyio task group 을 쓰는 MCP SDK 처럼 여러 예외를 BaseExceptionGroup 으로 묶어 올리면 하위
    예외를 하나씩 풀어 '; ' 로 잇는다. 그래서 기록에는 묶음 이름 대신 실제 원인이 남는다. guarded 만
    부른다. 그래프 밖에서 난 예외는 skillops-agent/skillops_agent/main.py 의 _run_pipeline 이 따로
    기록한다.
    """
    if isinstance(exc, BaseExceptionGroup):
        return "; ".join(error_text(child) for child in exc.exceptions)
    return f"{type(exc).__name__}: {exc}"


def guarded(fn):
    """노드 함수를 감싸 모든 노드에 같은 오류 처리와 진행 저장을 붙인다.

    동기 노드(init, check_ablation, check_valid)와 async 노드를 모두 async 함수로 맞춘다. 노드가
    Exception 을 던지면 {error, diagnosis_status: ERROR} 를 대신 돌려주고, 다음 간선의
    _stop_on_error 가 이를 보고 END 로 보낸다. 그래서 노드는 계약 위반을 ContractError 로 던지기만
    하면 된다. 실패한 노드는 step_count 와 진행 이벤트를 남기지 않는다.
    상태에 pipeline_thread_id 가 있으면 노드 결과 dict 를
    skillops-agent/skillops_agent/run_store.py 의 RunStore.update 로 바로 병합 저장한다. 화면이 진행
    중에도 events 를 볼 수 있는 이유다. build_graph 가 FLOW 의 모든 노드를 등록할 때 부른다.
    """

    # @wraps 로 run 이 원래 노드 함수의 __name__, docstring, 타입 표시를 물려받는다. 그래서
    # inspect.signature 나 __name__ 으로 살펴봐도 원래 노드 함수로 보인다.
    @wraps(fn)
    async def run(state):
        try:
            if inspect.iscoroutinefunction(fn):
                result = await fn(state)
            else:
                result = fn(state)
        except Exception as exc:
            result = {"error": error_text(exc), "diagnosis_status": DiagnosisStatus.ERROR}
        if state.get("pipeline_thread_id"):
            RunStore().update(state["pipeline_thread_id"], result)
        return result

    return run


# ── 분기 판정 ──────────────────────────────────────────────────────────────────
# 판정 함수는 상태를 읽어 라벨 글을 돌려주고, 라벨과 다음 노드의 짝은 FLOW 에 있다.
# 판정에 쓰는 값(root_cause, hypothesis_retry, candidate_retry)은
# skillops-agent/skillops_agent/nodes.py 의 check_ablation 과 check_valid 노드가 상태에 남긴다.


def _step_limit_reached(state: AgentState) -> bool:
    """노드 실행 단계가 max_steps 에 닿았는지 본다.

    PB-006 단계 한도다. step_count 는 skillops-agent/skillops_agent/nodes.py 의 _progress 가
    노드마다 1씩 올리고, max_steps 가 없으면 skillops-agent/skillops_agent/config.py 의
    MAX_STEPS_DEFAULT 를 쓴다. route_ablation 과 route_valid 가 재시도 한도 다음에 본다.
    """
    return state.get("step_count", 0) >= state.get("max_steps", MAX_STEPS_DEFAULT)


def _needs_tool(state: AgentState) -> bool:
    """도구가 필요한 수정(diagnosis.finding=NEEDS_TOOL)인지 본다.

    skillops-agent/skillops_agent/nodes.py 의 fetch_evidence 가 담당자가 새로 넣은 값을 어떤 업무
    도구도 만들지 않을 때 남긴다(docs/plans/2026-10-26-behavior-edit-root-fix.md 2.11). 참이면 원인
    실험 뒤 후보 생성 없이 save_proposal 로 간다. route_evidence 와 route_ablation 이 부른다.
    """
    return (state.get("diagnosis") or {}).get("finding") == NEEDS_TOOL


def route_evidence(state: AgentState) -> str:
    """증거 수집 뒤 판정이다. 종료 상태가 정해졌거나 변경이 없으면 stop, 아니면 diagnose 다.

    partial evidence가 있으면 원문·diff 수집 경로로만 보낸다. 그 경로는 자동 수정을 호출하지 않는다.
    fetch_evidence 가 VALIDATED, BASELINE_FAILED, VALIDATION_FAILED, NO_SKILL_CHANGES,
    INTENT_VIOLATION, EVALUATED 처럼 RUNNING 이 아닌 상태를 남겼으면 진단할 것이 없으므로 END 로
    간다. 사전 검증이 결론을 낸 저장 직후 요청과 diagnosis_only 진단이 여기서 끝난다. 사전 검증이
    실패한 실행을 찾았으면 상태가 RUNNING 그대로라 진단을 잇는다. diagnose 는 hypothesize 로 간다.
    사전 검증이 의도 미실현(diagnosis.finding=INTENT_NOT_REALIZED)을 남겼으면 fetch_evidence 가
    root_cause 까지 정했으므로 intent_gap 으로 extract_intent 에 바로 간다. 실행 오류 없이 결과만
    다른 사전 검증도 RUNNING 이라 diagnose 로 원인 실험을 잇는다. 단계 한도는 보지 않는다. 도구가
    필요한 수정(_needs_tool)은 원인 실험을 그대로 하므로 diagnose 로 가고, 의도 미실현처럼
    fetch_evidence 가 이미 root_cause 를 정했으면 needs_tool 로 save_proposal 에 바로 간다.
    """
    if state.get("diagnosis_status") != DiagnosisStatus.RUNNING:
        return "stop"
    if state.get("evidence"):
        return "partial"
    if not state.get("changes"):
        return "stop"
    if _needs_tool(state) and state.get("root_cause"):
        return "needs_tool"
    if (state.get("diagnosis") or {}).get("finding") == INTENT_NOT_REALIZED:
        return "intent_gap"
    return "diagnose"


def route_ablation(state: AgentState) -> str:
    """원인 실험 뒤 판정이다. 원인 확정, 진단 종료, 재시도 한도, 단계 한도, 재시도 순서로 본다.

    root_cause 가 있으면 confirmed(extract_intent), check_ablation 이 진단 상태를 RUNNING 이 아닌
    값(결과 불일치 원인 실험의 VALIDATION_FAILED)으로 바꿨으면 stop(END), hypothesis_retry 가
    skillops-agent/skillops_agent/config.py 의 MAX_HYPOTHESIS_RETRIES 에 닿으면
    escalate(save_proposal), 단계 한도에 닿으면
    limit_exceeded(save_proposal), 아니면 retry(hypothesize)다. 원인 확정을 먼저 보므로 원인이
    확정되면 단계 한도를 넘었어도 진단을 잇는다. 원인 없이 save_proposal 로 가면 no_valid_fix
    제안서가 저장된다. 도구가 필요한 수정(_needs_tool)이면 원인 확정과 진단 종료(VALIDATION_FAILED)
    모두 needs_tool(save_proposal)이다. SKILL.md 후보로는 고칠 수 없으므로 의도 추출·후보 생성을
    건너뛰고, 원인을 확정하지 못해도 개발 요청서를 저장한다.
    """
    if state.get("root_cause"):
        return "needs_tool" if _needs_tool(state) else "confirmed"
    if state.get("diagnosis_status", DiagnosisStatus.RUNNING) != DiagnosisStatus.RUNNING:
        return "needs_tool" if _needs_tool(state) else "stop"
    if state["hypothesis_retry"] >= MAX_HYPOTHESIS_RETRIES:
        return "escalate"
    if _step_limit_reached(state):
        return "limit_exceeded"
    return "retry"


def route_valid(state: AgentState) -> str:
    """후보 검증 뒤 판정이다. 유효 후보, 재시도 한도, 단계 한도, 재시도 순서로 본다.

    valid 가 참인 후보가 하나라도 있으면 has_valid(rank_and_explain)다. candidate_retry 가
    skillops-agent/skillops_agent/config.py 의 MAX_CANDIDATE_RETRIES 에 닿거나 단계 한도에 닿으면
    no_valid_fix(save_proposal)이고, 아니면 retry(generate_candidates)다.
    """
    if any(c.get("valid") for c in state["candidates"]):
        return "has_valid"
    if state["candidate_retry"] >= MAX_CANDIDATE_RETRIES:
        return "no_valid_fix"
    if _step_limit_reached(state):
        return "no_valid_fix"
    return "retry"


# ── 흐름표 ─────────────────────────────────────────────────────────────────────
# 키는 노드 이름이고 값은 다음 세 가지 가운데 하나다.
# - 값이 다음 노드 이름(글)이면 늘 그 노드로 간다.
# - 값이 (판정 함수, {라벨: 다음 노드}) 묶음이면 판정 함수가 돌려준 라벨의 노드로 간다.
# - 값이 END 면 그래프를 끝낸다.
# error 가 남으면 어느 간선이든 END 로 간다. 노드 이름은
# skillops-agent/skillops_agent/nodes.py 의 함수 이름과 같아야 한다(build_graph 가 getattr 로
# 찾는다). 단계를 넣거나 순서를 바꿀 때 그래프 쪽에서는 이 표만 고친다.

# 그래프 시작 노드다. init 이 실행 상태를 처음 값으로 채운다.
ENTRY = "init"
FLOW = {
    "init": "fetch_evidence",
    "fetch_evidence": (route_evidence, {
        "diagnose": "hypothesize", "intent_gap": "extract_intent",
        "needs_tool": "save_proposal",
        "partial": "collect_partial_sources", "stop": END,
    }),
    "collect_partial_sources": "collect_partial_diffs",
    "collect_partial_diffs": END,
    "hypothesize": "ablate",
    "ablate": "check_ablation",
    "check_ablation": (
        route_ablation,
        {
            "confirmed": "extract_intent",
            "needs_tool": "save_proposal",
            "stop": END,
            "retry": "hypothesize",
            "escalate": "save_proposal",
            "limit_exceeded": "save_proposal",
        },
    ),
    "extract_intent": "generate_candidates",
    "generate_candidates": "verify_candidates",
    "verify_candidates": "check_valid",
    "check_valid": (
        route_valid,
        {
            "has_valid": "rank_and_explain",
            "retry": "generate_candidates",
            "no_valid_fix": "save_proposal",
        },
    ),
    "rank_and_explain": "save_proposal",
    "save_proposal": END,
}


def _stop_on_error(decide):
    """노드가 error 를 남겼으면 END, 아니면 decide 의 판정을 돌려주는 간선 판정을 만든다.

    build_graph 가 END 로 바로 가는 간선을 뺀 모든 간선에 씌운다. 그래서 다음 노드가 하나로 정해진
    간선에서도 앞 노드가 error 를 남기면 다음 노드를 부르지 않고 곧바로 그래프를 끝낸다.
    """

    # 상태의 error 를 먼저 보고, 없을 때만 decide 에 판정을 맡긴다.
    def route(state):
        return END if state.get("error") else decide(state)

    return route


def _always(target):
    """늘 같은 다음 노드를 고르는 판정을 만든다.

    build_graph 가 FLOW 값이 노드 이름(글)일 때 써서 분기 없는 간선도 조건부 간선으로 만든다. 그래야
    그 간선에도 _stop_on_error 를 씌울 수 있다.
    """
    return lambda _state: target


def build_graph(checkpointer=None):
    """FLOW 표대로 노드와 간선을 조립해 compile 한 그래프를 돌려준다.

    노드 함수는 nodes 모듈에서 FLOW 키와 같은 이름으로 찾아 guarded 로 감싼다. END 가 아닌 모든
    간선은 _stop_on_error 를 씌운 조건부 간선으로 만들고 path map 에 END 를 더한다. checkpointer 를
    주지 않으면 MemorySaver 를 쓰므로 graph.ainvoke 의 config 에 configurable.thread_id 가 있어야
    한다. 이 모듈의 graph 전역을 만들 때만 부른다.
    """
    builder = StateGraph(AgentState)
    for name in FLOW:
        builder.add_node(name, guarded(getattr(nodes, name)))
    builder.set_entry_point(ENTRY)
    for source, step in FLOW.items():
        if step == END:
            builder.add_edge(source, END)
            continue
        decide, targets = step if isinstance(step, tuple) else (_always(step), {step: step})
        builder.add_conditional_edges(source, _stop_on_error(decide), {**targets, END: END})
    return builder.compile(
        checkpointer=checkpointer if checkpointer is not None else MemorySaver()
    )


# 앱 전체가 함께 쓰는 compile 된 그래프다. skillops-agent/skillops_agent/main.py, 테스트,
# skillops-agent/scripts/ 의 run_demo.py, run_real_llm.py, b_c_contract_smoke.py 가 import 해
# ainvoke 한다.
graph = build_graph()
