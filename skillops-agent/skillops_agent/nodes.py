"""정상 경로 노드 11개와 partial 증거 수집 노드 2개 및 도우미를 모은다.

skillops-agent/skillops_agent/graph.py 의 FLOW 표에 노드 이름이 있고, 같은 파일의 build_graph 가
그 이름으로 이 모듈의 함수를 찾아(getattr) 등록한다. 이때 guarded 가 각 노드를 감싸 예외를 ERROR
상태로 바꾸고 진행 상황을 RunStore 에 저장한다. 분기 판정(route_evidence, route_ablation,
route_valid)도 graph.py 에 있고, 이 모듈의 check_ablation 과 check_valid 는 판정에 쓸 값만 상태에
남긴다. 노드를 더하는 순서는 graph.py 의 모듈 docstring 에 적었다.

각 Node 는 다음을 지킨다.
- AgentState 를 받아 바꾼 키만 dict 로 돌려준다. 리듀서가 없어 LangGraph 가 그 키를 덮어쓴다.
- init 을 뺀 모든 노드는 _progress 로 step_count 를 1 올리고(PB-006 단계 한도), ProgressEvent 를
  events 에 붙인다.
- C 계약이 깨지면 ContractError 를 던진다. 판정은 C 실행 결과로만 하고 LLM 출력은 제안으로만 쓴다.

외부 호출은 두 이름을 거친다.
- get_tools() 는 skillops-agent/skillops_agent/tools.py 의 MCPTools 를 돌려준다. stub 설정이면
  skillops-agent/skillops_agent/stub_tools.py 의 StubMCPTools 다. diff_skill,
  get_dependency_graph, create_workspace, apply_patch, run_scenarios, save_proposal 은 service 의
  @McpTool 클래스(service/src/main/java/com/capstone4/skillops/mcp/ 의 SkillDiffTools,
  DependencyGraphTools, WorkspaceTools, ProposalTools)로 간다. get_trace, get_snapshot,
  list_skills, get_skill 은 주문 Agent 의 조회 API 로 가며, 받는 컨트롤러는
  order-agent/backend/src/main/java/com/capstone4/orderagent/controller/EvidenceController.java 다.
- make_structured_llm() 은 skillops-agent/skillops_agent/llm.py 에서 가져온 함수이며 구조화 출력
  LLM 체인을 만든다.
테스트는 이 모듈의 get_tools 와 make_structured_llm 을 바꿔 끼우고,
skillops-agent/scripts/run_demo.py 와 skillops-agent/scripts/b_c_contract_smoke.py 는
make_structured_llm 만 바꿔 끼운다. 그래서 두 이름은 호출할 때마다 이 모듈 전역에서 찾는다. 외부
호출을 하는 노드를 다른 모듈로 옮기면 이 교체가 듣지 않는다.

v1 계약에 맞추며 다음을 바꿨다.
- promote_workspace, smoke_check, rollback 노드는 서비스가 처리하므로 지웠다.
- 모든 필드 이름을 v1 스펙(change_no, workspace_id, fixes_failure 등)으로 바꿨다.
- 실행 기록은 get_trace 응답의 events[] 를 읽는다.

반복 횟수, 재시도 한도, 시작 skill, 순위 설명 인용 수 같은 튜닝 값은
skillops-agent/skillops_agent/config.py 에 있다. 프롬프트 문구는 skillops-agent/prompts/*.md 에,
순위 기준은 _rank_key 에, 후보 합격 식은 _judge 와 _intent_kept 에 있다.

행동 변경 수정본(docs/plans/2026-10-26-behavior-edit-root-fix.md 2.3~2.6)을 위해 다음을 더했다.
- 요청에 담당자 의도(trigger.intent)가 있으면 사전 검증의 수정본 세트와 후보 검증에
  intent_expectations 를 붙인다. 기준본 세트와 원인 실험에는 붙이지 않는다.
- 수정본이 오류 없이 돌고 의도 때문에만 기준 주문이 떨어지면 diagnosis.finding 을
  INTENT_NOT_REALIZED 로 남기고 원인 실험 없이 extract_intent 로 간다(graph.py 의 intent_gap). scope
  가 null 인 의도는 의도 범위 표시(intent_scoped)가 없으므로, 의도 범위 밖에서 떨어진 주문은 의도
  없이 한 번 더 돌려 가린다(2.10 V3-3). 의도 없이도 결과가 다르면 그 기준
  주문(repro_scenario_ids)으로 원인 실험을 잇고, 그 실험이 가설 없음·대조 실행 흔들림으로 끝나면
  VALIDATION_FAILED 다(V3-8).
- 담당자가 의도 글만 주면 fetch_evidence 가 저장 직후 사전 검증 전에 LLM 으로 expectations 를 한 번
  만든다(_infer_expectations, 출처 inferred). 실패 실행 진단에서는 extract_intent 가 만든다. Agent
  가 만든 expectations 는 최소 요건(scope 마다 맞는 기준 주문, scope 밖 기준 주문, 기준본이 이미
  통과하지 않음, V3-4)을 지켜야 쓰고, 못 지키거나 만들지 못하면 의도 시험 없이 진행하며
  diagnosis.intent_note 에 이유를 남긴다. 담당자가 준 expectations 가운데 읽을 수 없는 것은 빼고
  같은 칸에 남긴다(V3-2).
- 의도 expectations(담당자가 준 것 또는 Agent 가 만든 것)가 있으면 generate_candidates 가 LLM 과
  별개로 결정적 encode_rule 후보를 최대 두 개 만든다(skillops-agent/skillops_agent/skill_rules.py,
  V3-6). 의도가 있으면 되돌리기와 계약 약화 후보는 코드가 불합격시키고, 의도가 없으면 두 값은 판정과
  순위에 쓰지 않는다.

도구가 필요한 수정(docs/plans/2026-10-26-behavior-edit-root-fix.md 2.11)을 위해 다음을 더했다.
- fetch_evidence 가 변경을 모은 뒤 주문 Agent 의 업무 도구 목록(get_tool_catalog,
  GET /api/tools)으로 담당자가 새로 넣은 값 가운데 어떤 도구도 만들지 않는 것을 찾는다
  (_capability_gaps, 판정 규칙은 skillops-agent/skillops_agent/capability.py). 도구 목록을 읽지
  못하면 판정을 건너뛰고 예전과 같다.
- 빈틈이 있고 진단을 이으면 diagnosis.finding 을 NEEDS_TOOL 로 두고 capability_gaps 와 개발 요청서
  초안(development_request)을 상태에 남긴다. 사전 검증과 원인 실험은 그대로 하고, 원인 확정 뒤에는
  의도 추출·후보 생성·후보 검증 없이(LLM 후보 호출 없음) save_proposal 로 간다(graph.py 의
  needs_tool). 원인을 확정하지 못해도 개발 요청서를 담아 저장한다. 의도가 있든 없든 같다.
- proposal_payload 가 원인 실험 근거로 임시 대안의 proof_run_ids 를 채운 development_request 를
  제안서에 더한다. 빈틈이 없는 진단의 제안서에는 이 칸이 없다.
"""

from dataclasses import dataclass, field
from datetime import UTC, datetime

from pydantic import BaseModel as _PydanticModel

from . import capability, rag, skill_rules
from .config import (
    ABLATION_REPEAT,
    DEFAULT_ENTRY_SKILL_ID,
    GOLDEN_IDS,
    GOLDEN_ORDERS,
    INTENT_CHECK_REPEAT,
    MAX_CANDIDATE_RETRIES,
    MAX_HYPOTHESIS_RETRIES,
    MAX_STEPS_DEFAULT,
    PREFLIGHT_REPEAT,
    RANK_FALLBACK_EVIDENCE_COUNT,
    VERIFY_REPEAT,
)
from .contracts import (
    CHECK_FAILURE_PREFIX,
    INTENT_MAX_CHECKS,
    INTENT_MAX_EXPECTATIONS,
    RESERVED_KEYS,
    SCOPE_KEYS,
    ContractError,
    all_passed,
    check_diff_evidence,
    check_manifest,
    check_problem,
    check_skill_listing,
    condition_holds,
    condition_problem,
    context_keys,
    error_event,
    event_data,
    execution_uuid,
    failures_of,
    file_hashes,
    patch_contract_ok,
    patch_flow_ok,
    patch_metadata_ok,
    replay_scenario_id,
    reverse_hunk,
    run_ids_of,
    skill_of_file,
    tools_called,
    unified_patch,
    validate_report,
)
from .llm import make_structured_llm
from .partial_evidence import collect_diffs, collect_sources, prepare_partial_evidence
from .prompts import load as load_prompt
from .state import (
    INTENT_NOT_REALIZED,
    NEEDS_TOOL,
    AblationRun,
    AgentState,
    Candidate,
    CandidateResults,
    DiagnosisStatus,
    Hypothesis,
    Intent,
    Patch,
    ProgressEvent,
    empty_run_fields,
)
from .tools import MCPToolError, get_tools

# ── LLM 출력 스키마 ────────────────────────────────────────────────────────────
# 아래 10개 Pydantic 클래스는 OpenAI 구조화 출력의 JSON schema 가 된다. 출력 클래스 다섯 개
# (_HypothesesOutput, _ExpectationsOutput, _IntentOutput, _CandidatesOutput,
# _RankExplanationsOutput)는 _ask_llm 이 make_structured_llm 에 넘기고, 나머지 다섯 개는 그 안에
# 들어가는 항목이다. 클래스 이름, 필드 이름,
# docstring, Field 설명이 모두 schema 에 들어가 LLM 출력을 바꾸므로 docstring 을 달지 않고 설명은
# 클래스 위 주석으로만 쓴다. 출력 클래스 이름을 바꾸면 테스트와 skillops-agent/scripts/ 의 가짜
# LLM 도 깨진다. 가짜 LLM 은 schema.__name__ 으로 고정 출력을 고르며, 나중에 더한 칸(expectations)이
# 없는 가짜 출력도 받도록 노드는 그 칸을 getattr 로 읽는다.


# 원인 가설 하나다. change_no 는 fetch_evidence 가 붙인 변경 번호이고, ablate 가 이 번호로 되돌릴
# hunk 를 찾는다.
class _HypothesisItem(_PydanticModel):
    change_no: int  # v1 계약에서 hunk_index 가 change_no 로 바뀌었다.
    summary: str
    confidence: float


# hypothesize 노드의 LLM 출력이다. 가설 목록 순서가 원인 실험 순서가 된다.
class _HypothesesOutput(_PydanticModel):
    hypotheses: list[_HypothesisItem]


# 담당자 의도 expectation 하나다. scope 는 시험 주문의 처음 값을 보는 조건 한 줄 또는 null 이고,
# checks 는 scope 에 맞는 주문이 지켜야 할 조건이다(docs/plans/2026-10-26-behavior-edit-root-fix.md
# 2.3). _split_expectations 가 거른다.
class _ExpectationItem(_PydanticModel):
    scope: str | None
    checks: list[str]


# 담당자가 의도 글만 주었을 때 fetch_evidence 가 사전 검증 전에 받는 LLM 출력이다
# (_infer_expectations). 항목은 _usable_expectations 가 거르고 상한을 넘는 것을 버린다.
class _ExpectationsOutput(_PydanticModel):
    expectations: list[_ExpectationItem]


# extract_intent 노드의 LLM 출력이다. checks 는 C 조건 문법 한 줄씩이고 _split_checks 가 거른다.
# expectations 는 요청에 담당자 의도 글만 있고 expectations 가 없을 때만 쓴다(출처 inferred).
class _IntentOutput(_PydanticModel):
    text: str
    checks: list[str]  # v1 계약에서 assertions 가 checks 로 바뀌었다.
    expectations: list[_ExpectationItem]


# 후보가 고칠 파일 하나다. LLM 은 수정 후 전체 원문만 내고 patch 는 _patches_from_spec 이 만든다.
class _PatchSpec(_PydanticModel):
    skill: str
    full_text: str  # 수정 후 전체 파일이다. B 가 고정 원문 기준으로 patch 를 만든다.


# 수정 후보 하나다. kind 를 코드가 검사하지 않으므로 LLM 이 낸 값이 그대로 후보에 들어간다.
class _CandidateSpec(_PydanticModel):
    kind: str  # same_skill, linked_skills, run_order, encode_rule 가운데 하나를 기대한다.

    patches: list[_PatchSpec]


# generate_candidates 노드의 LLM 출력이다. 후보 개수와 종류는 프롬프트가 정하고 코드에는 상한이
# 없다.
class _CandidatesOutput(_PydanticModel):
    candidates: list[_CandidateSpec]


# 유효 후보 하나의 설명이다. candidate_no 로 후보를 가리킨다.
class _ExplanationItem(_PydanticModel):
    candidate_no: int  # v1 계약에서 문자열 id 가 정수 candidate_no 로 바뀌었다.
    explanation: str


# rank_and_explain 노드의 LLM 출력이다. 순위는 코드가 정하고 LLM 은 설명만 낸다.
class _RankExplanationsOutput(_PydanticModel):
    explanations: list[_ExplanationItem]


# ── check 평가 헬퍼 ────────────────────────────────────────────────────────────


async def _run_scenarios(tools, workspace_id, ids, repeat, checks=None, expectations=None):
    """C run_scenarios 를 부르고 결과를 계약대로 검증한다. 반복 횟수는 단계가 정한다.

    MCP run_scenarios 도구(service/src/main/java/com/capstone4/skillops/mcp/WorkspaceTools.java 의
    runScenarios)를 부르고, skillops-agent/skillops_agent/contracts.py 의 validate_report 로 응답을
    검사한 뒤 시나리오별 결과 목록을 돌려준다. ids 가 비면 도구를 부르지 않고 ContractError 를
    던진다. 빈 목록은 all_passed 에서 통과로 읽히므로 이 검사가 시나리오 없는 합격을 막는다.
    repeat 는 부르는 단계가 skillops-agent/skillops_agent/config.py 의 PREFLIGHT_REPEAT,
    INTENT_CHECK_REPEAT, ABLATION_REPEAT, VERIFY_REPEAT 가운데 하나로 정하고, checks 는 extra_checks
    로 보낸다. expectations(담당자 의도 [{scope, checks}])는 값이 있을 때만 intent_expectations 로
    보내므로 의도가 없는 요청의 도구 호출 모양은 예전과 같다. 사전 검증(_preflight), 의도 조건
    검사(_evaluate_intent_checks), 원인 실험(_reproduce_failure, _ablate_change), 후보 검증
    (_verify_candidate)이 모두 이 함수를 거친다.
    """
    if not ids:
        raise ContractError("at least one configured scenario is required")
    options = {"repeat": repeat, "extra_checks": checks}
    if expectations:
        options["intent_expectations"] = expectations
    report = await tools.run_scenarios(workspace_id, ids, **options)
    return validate_report(report, workspace_id, ids, repeat)


# ── 담당자 의도 헬퍼 ───────────────────────────────────────────────────────────


def _owner_intent(state: AgentState) -> dict | None:
    """요청(trigger)에 담당자 의도가 있으면 {text, expectations, dropped}, 없으면 None 이다.

    skillops-agent/skillops_agent/main.py 의 DiagnoseRequest.intent 를 model_dump 한 값에서 읽는다.
    이 값이 있을 때만 의도 시험(intent_expectations)을 붙이고 되돌리기·계약 약화·빈 의도를
    불합격으로 본다 (docs/plans/2026-10-26-behavior-edit-root-fix.md 2.6 적용 범위). /diagnose 는
    읽을 수 없는 담당자 expectation 을 422 로 막지 않으므로(V3-2) 여기서 _owner_expectations 로 걸러
    expectations 에는 쓸 수 있는 것만, dropped 에는 버린 것({check, reason})을 둔다. trigger 는
    고치지 않는다.
    """
    intent = (state.get("trigger") or {}).get("intent")
    if not (isinstance(intent, dict) and intent.get("text")):
        return None
    kept, dropped = _owner_expectations(intent.get("expectations"))
    return {**intent, "expectations": kept, "dropped": dropped}


def _owner_expectations(items) -> tuple[list[dict], list[dict]]:
    """담당자 expectations 를 쓸 수 있는 것과 버린 것({check, reason})으로 나눈다.

    scope 는 null 이거나 시험 주문의 처음 값(contracts.SCOPE_KEYS)을 보는 읽을 수 있는 조건이어야
    하고(_scope_problem), 아니면 그 expectation 을 통째로 버린다. checks 는
    문법(condition_problem)만 본다. 값 이름은 service 가 실행 문맥으로 읽으므로 보지 않는다. 읽을 수
    없는 check 는 그 줄만 버리고, 남은 check 가 없으면 expectation 을 버린다. 개수
    상한(INTENT_MAX_EXPECTATIONS, INTENT_MAX_CHECKS)을 넘는 것도 버린다. 버린 scope 는 check 칸에
    'scope: <scope>' 를 쓴다. _owner_intent 가 부른다.
    """
    kept, dropped = [], []
    for item in items or []:
        if not isinstance(item, dict):
            reason = "expectation 은 {scope, checks} 모양입니다"
            dropped.append({"check": str(item), "reason": reason})
            continue
        scope = item.get("scope")
        problem = _scope_problem(scope)
        if problem:
            dropped.append({"check": f"scope: {scope}", "reason": problem})
            continue
        checks = []
        for check in item.get("checks") or []:
            problem = condition_problem(check)
            if problem:
                dropped.append({"check": str(check), "reason": problem})
            elif len(checks) >= INTENT_MAX_CHECKS:
                reason = f"checks 는 항목마다 {INTENT_MAX_CHECKS}개까지만 씁니다"
                dropped.append({"check": check, "reason": reason})
            else:
                checks.append(check)
        if not checks:
            reason = "읽을 수 있는 checks 가 없습니다"
            dropped.append({"check": f"scope: {scope}", "reason": reason})
        elif len(kept) >= INTENT_MAX_EXPECTATIONS:
            reason = f"expectations 는 {INTENT_MAX_EXPECTATIONS}개까지만 씁니다"
            dropped.append({"check": f"scope: {scope}", "reason": reason})
        else:
            kept.append({"scope": scope, "checks": checks})
    return kept, dropped


def _golden_scope_values() -> tuple[dict[str, dict], bool]:
    """회귀 세트(GOLDEN_IDS) 기준 주문의 처음 값과, 처음 값을 모르는 기준 주문이 있는지다.

    처음 값은 config.GOLDEN_ORDERS 의 values({SCOPE_KEYS 이름: 값})다. GOLDEN_IDS 에 있는데 표에
    없는 주문은 처음 값을 모르므로 두 번째 값이 참이다. _scope_guard 와 _expectation_matched 가
    쓴다.
    """
    known = {i: GOLDEN_ORDERS[i]["values"] for i in GOLDEN_IDS if i in GOLDEN_ORDERS}
    return known, any(i not in GOLDEN_ORDERS for i in GOLDEN_IDS)


def _scope_holds(scope: str, values: dict) -> bool:
    """scope 가 기준 주문의 처음 값(values)에서 맞는지 본다. service 의 scope 채점과 같은 뜻이다."""
    return condition_holds(scope, values) is True


def _intent_expectations(state: AgentState) -> list[dict]:
    """후보 검증의 run_scenarios 에 붙일 expectations 다. 요청에 의도가 없으면 빈 목록이다.

    extract_intent 가 남긴 intent.expectations(담당자가 준 것 또는 Agent 가 만든 것)를 쓴다.
    verify_candidates 와 _encode_rule_candidates 가 부른다. 사전 검증은 아직 extract_intent 전이라
    trigger 의 expectations 나 _infer_expectations 가 만든 것을 fetch_evidence 가 직접 넘긴다.
    """
    if _owner_intent(state) is None:
        return []
    return list((state.get("intent") or {}).get("expectations") or [])


def _scope_keys(expectations: list[dict]) -> list[str]:
    """expectations 의 scope 가 보는 값 이름이다. weakens_contract 판정에 쓴다."""
    keys = [skill_rules.condition_key(e.get("scope")) for e in expectations if e.get("scope")]
    return [key for key in keys if key]


def _unscoped_checks(expectations: list[dict]) -> list[str]:
    """scope 가 없는 expectation 의 checks 를 이어 붙인다.

    제안서 intent.checks 의 하위 호환 값이다.
    """
    return [check for e in expectations if not e.get("scope") for check in e.get("checks") or []]


# ── 공통 헬퍼 ──────────────────────────────────────────────────────────────────


def _now() -> str:
    """진행 이벤트의 at 에 넣을 현재 UTC 시각을 ISO 8601 글로 만든다. _event 가 부른다."""
    return datetime.now(UTC).isoformat()


def _event(node: str, msg: str) -> ProgressEvent:
    """진행 이벤트(ProgressEvent) 하나를 만든다.

    run_id 는 지금 늘 None 이다. frontend/src/views/DiagnosisView.vue 가 events 의 at 과 msg 를
    그대로 보여 주므로 msg 는 운영자가 읽는 문장이다. init 과 _progress 가 부른다.
    """
    return ProgressEvent(at=_now(), node=node, msg=msg, run_id=None)


def _progress(state: AgentState, node: str, msg: str, **updates) -> dict:
    """step_count 증가와 진행 이벤트에 이 노드가 바꾼 상태 키를 붙인 반환값을 만든다.

    step_count 를 1 올리고, 기존 events 뒤에 이 노드의 진행 이벤트를 붙인 새 목록을 넣고, 노드가
    바꾼 상태 키(updates)를 덧붙인다. AgentState 에 리듀서가 없어 LangGraph 는 돌려준 events 로
    목록 전체를 바꾸므로 기존 목록에 이어 붙인 새 목록을 돌려줘야 한다.
    skillops-agent/skillops_agent/graph.py 의 guarded 가 이 dict 를 RunStore 에 얕게 병합해
    저장하고, 같은 파일의 _step_limit_reached 가 step_count 를 max_steps 와 비교한다(PB-006).
    init 을 뺀 모든 노드가 이 함수로 반환값을 만든다. 노드를 새로 만들 때도 이 함수를 쓴다.
    """
    return {
        "step_count": state["step_count"] + 1,
        "events": state["events"] + [_event(node, msg)],
        **updates,
    }


async def _ask_llm(
    prompt_name: str, schema, *, with_style: bool = False, references: str = "", **fields
):
    """프롬프트를 채워 LLM 에 묻는다. make_structured_llm 은 교체되므로 호출 때 찾는다.

    skillops-agent/prompts/<prompt_name>.md 를 읽어 fields 로 {trace} 같은 자리를 str.format 으로
    채운 뒤, schema(이 모듈의 LLM 출력 Pydantic 클래스)로 구조화 출력을 받는다. with_style 이 참이면
    노드 프롬프트를 읽은 다음 skillops-agent/prompts/style.md 를 읽어 {style} 자리에 넣는다. 이 읽는
    순서는 리팩토링 전 코드와 같고, skillops-agent/tests/test_refactor_equivalence.py 가 고정한다.
    테스트와 scripts 가 이 모듈의 make_structured_llm 이름을 바꿔 끼우므로 import 시점이 아니라
    호출할 때 찾는다. 프롬프트의 자리와 fields 이름이 어긋나면 str.format 이 KeyError 를 던지고
    skillops-agent/skillops_agent/graph.py 의 guarded 가 ERROR 로 남긴다. hypothesize,
    extract_intent, generate_candidates, rank_and_explain 이 부른다. references 는
    skillops-agent/skillops_agent/rag.py 의 references 가 고른 참고 자료 글이다. 채운 프롬프트
    맨 끝에 그대로 붙이므로 str.format 을 거치지 않아 자료 안의 중괄호가 자리로 읽히지 않는다.
    """
    template = load_prompt(prompt_name)
    if with_style:
        fields["style"] = load_prompt("style")
    text = template.format(**fields) + references
    return await make_structured_llm(schema).ainvoke(text)


def _rag_progress(state: AgentState, node: str, ids: list[str]) -> dict:
    """노드가 프롬프트에 붙인 참고 자료 id 를 rag_references 에 노드 이름으로 남긴다.

    RunStore 기록에 남아 어떤 근거로 가설·의도·후보를 만들었는지 나중에 볼 수 있다. 같은 노드가
    재시도로 다시 돌면 마지막 회차의 id 로 바꾼다.
    """
    return {"rag_references": {**(state.get("rag_references") or {}), node: ids}}


def _retry_feedback(retry: int, items: list | None, to_lines) -> str:
    """재시도 때만 앞선 실패 줄을 만들어 잇는다. 첫 시도이거나 목록·줄이 없으면 '없음'을 돌려준다.

    items 는 앞선 회차의 목록이고, to_lines 는 그 목록을 프롬프트 줄로 바꾸는 함수다. 첫 시도에서는
    목록 원소를 읽지 않아야 리팩토링 전 코드와 평가 순서가 같다. 먼저 재시도 회차인지 보고, 다음으로
    목록이 비었는지 본 뒤에 줄을 만들고, 만든 줄이 있을 때만 잇는다. hypothesize 와
    generate_candidates 가 프롬프트의 {previous_failures} 자리에 넣는다.
    """
    if retry > 0 and items:
        lines = to_lines(items)
        if lines:
            return "\n".join(lines)
    return "없음"


def _change_by_no(changes: list[dict], change_no: int) -> dict | None:
    """change_no 가 같은 diff 변경을 찾는다. 없으면 None 이다.

    ablate 가 가설의 change_no 를 검사할 때 부른다. check_ablation 과 proposal_payload 는 없는
    경우의 동작(StopIteration, 키를 늦게 읽기)을 리팩토링 전 코드와 맞추려고 next 를 직접 쓴다.
    """
    return next((c for c in changes if c["change_no"] == change_no), None)


def _confirmed_run(ablation_runs: list[AblationRun]) -> AblationRun | None:
    """원인을 되돌려 통과한 첫 ablation 실험을 찾는다. 없으면 None 이다.

    실험 목록은 LLM 가설 순서이므로 가설 순서가 원인 확정의 우선순위가 된다. 실행 ID 가 없는 실험은
    근거가 없으므로 고르지 않는다. check_ablation 과 _known_values 가 부른다.
    """
    return next((r for r in ablation_runs if r["confirmed"] and r["run_ids"]), None)


# ── Node 1: init ───────────────────────────────────────────────────────────────


def init(state: AgentState) -> dict:
    """1번 노드다. 실행마다 상태를 처음 값으로 채운다.

    step_count 를 0으로 두고, max_steps 는 초기 상태의 값을 쓰며 없으면
    skillops-agent/skillops_agent/config.py 의 MAX_STEPS_DEFAULT 를 쓴다.
    skillops-agent/skillops_agent/main.py 의 _initial_state 는 MAX_STEPS_DEFAULT 를 넣는다. 나머지
    실행 상태 키는 skillops-agent/skillops_agent/state.py 의 empty_run_fields 로 채우고
    diagnosis_status 를 RUNNING 으로 둔다. 모든 키를 미리 채우는 이유는 둘이다. 뒤 노드와 분기
    판정이 state['hypothesis_retry'] 처럼 키를 바로 읽고, 사전 검증에서 일찍 끝나도 RunStore 기록에
    모든 키가 기본값으로 남아야 한다.
    skillops-agent/skillops_agent/graph.py 의 FLOW 가 이 함수를 init 노드로 등록하고 guarded 가 감싸
    부른다. 같은 파일의 ENTRY 가 이 노드를 시작점으로 정하고, 다음 노드는 fetch_evidence 다.
    """
    return {
        "step_count": 0,
        "max_steps": state.get("max_steps", MAX_STEPS_DEFAULT),
        "events": [_event("init", "파이프라인 시작")],
        **empty_run_fields(),
    }


# ── Node 2: fetch_evidence ────────────────────────────────────────────────────


@dataclass(frozen=True)
class _Snapshots:
    """fetch_evidence 가 고정한 실행과 두 snapshot 이다. 함수끼리 주고받으며 상태에는 넣지 않는다.

    _fix_snapshots 가 만들고 _collect_changes, _preflight, _preflight_stop, fetch_evidence 가
    읽는다.
    """

    execution: str | None  # 진단할 실행 UUID 다. 저장 직후 사전 검증이면 None 이다.
    trace: dict | None  # execution 의 get_trace 응답이다.
    candidate: str  # 후보 snapshot ID 다.
    baseline: str  # 기준 snapshot ID 다.
    after: dict  # 후보 snapshot manifest 다.
    before: dict  # 기준 snapshot manifest 다.


@dataclass(frozen=True)
class _PreflightOutcome:
    """저장 직후 사전 검증 결과다.

    _preflight 가 만들어 fetch_evidence 에 넘긴다. stop 이 있으면 fetch_evidence 가 그 상태
    (BASELINE_FAILED)로 노드를 끝내고, validated 가 참이면 diagnosis_status 를 VALIDATED 로 둔다.
    execution 이 있으면 그 실패 실행으로 진단을 이어 간다. repro_ids 가 있으면 실행 오류 없이 떨어진
    기준 주문이다. intent_gap 이 참이면 의도 범위 주문만 떨어진 의도 미실현이고, 거짓이면 결과
    불일치라 그 주문으로 원인 실험을 잇는다.
    """

    stop: dict | None = None  # 조기 종료 때 남길 상태(_preflight_stop 의 결과)다.
    execution: str | None = None  # 진단을 이어 갈 실패 실행 UUID 다.
    trace: dict | None = None  # 그 실행의 get_trace 응답이다.
    run_ids: list[str] = field(default_factory=list)  # 후보 snapshot 사전 검증 실행 ID 다.
    validated: bool = False  # 후보 snapshot 이 회귀 세트를 모두 통과했는지다.
    repro_ids: list[str] = field(default_factory=list)  # 실행 오류 없이 떨어진 기준 주문 id 다.
    # 떨어진 주문이 모두 의도 때문에만 떨어졌는지다. 의도 범위(intent_scoped) 주문이거나, scope 가
    # null 인 expectation 의 checks 때문에 의도와 함께일 때만 떨어진 주문이다(V3-3).
    intent_gap: bool = False
    failures: list[dict] = field(default_factory=list)  # 후보 snapshot 사전 검증의 실패 목록이다.


async def _fix_snapshots(t, trigger: dict) -> _Snapshots:
    """trigger 와 실행 기록으로 후보·기준 snapshot 을 정하고 manifest 를 검증한다.

    실패 진단이면 trigger 의 execution_id(또는 run_id 별칭)로 get_trace 를 불러 실행 당시 snapshot
    을 후보로 삼는다. trigger 에 후보 snapshot 이 함께 오면 둘이 같아야 한다. 기준 snapshot 은
    trigger 값이 먼저이고, 없으면 후보 manifest 의 metadata.baseline_snapshot_id 를 쓴다. 버전
    숫자로 기준을 짐작하지 않는다. 두 snapshot ID 가 UUID 인지, trace 의 snapshot_hash 가 후보
    manifest 해시와 같은지도 본다. 하나라도 어긋나면 ContractError 를 던져 잘못된 증거로 진단하지
    않게 한다. 검사 순서와 오류 문구는 skillops-agent/tests/test_flow_characterization.py 의
    EVIDENCE_ERRORS 가 고정한다. get_trace 와 get_snapshot 은 주문 Agent 조회 API 로 간다.
    fetch_evidence 가 부른다.
    """
    candidate = trigger.get("candidate_snapshot_id")
    baseline = trigger.get("baseline_snapshot_id")
    execution = trigger.get("execution_id") or trigger.get("run_id")
    trace = None
    if execution:
        execution = execution_uuid(execution)
        trace = await t.get_trace(execution)
        if candidate and candidate != trace.get("snapshot_id"):
            raise ContractError("trigger snapshot and execution snapshot differ")
        candidate = trace.get("snapshot_id")
    if not candidate:
        raise ContractError("candidate_snapshot_id or an execution with snapshot is required")
    manifest = check_manifest(await t.get_snapshot(candidate), candidate, "candidate")
    metadata = manifest.get("metadata", {})
    baseline = baseline or metadata.get("baseline_snapshot_id")
    if not baseline:
        raise ContractError("baseline_snapshot_id is required; version cannot identify it")
    before = check_manifest(await t.get_snapshot(baseline), baseline, "baseline")
    for side in (baseline, candidate):
        execution_uuid(side)
    if trace and trace.get("snapshot_hash") != manifest["hash"]:
        raise ContractError("trace and candidate snapshot hash differ")
    return _Snapshots(
        execution=execution,
        trace=trace,
        candidate=candidate,
        baseline=baseline,
        after=manifest,
        before=before,
    )


async def _collect_changes(t, snaps: _Snapshots, announced) -> tuple[list[dict], list[dict]]:
    """두 manifest 의 실제 차이를 diff_skill 로 읽고 hunk 마다 change_no 를 붙인다.

    파일 해시가 다른 경로만 바뀐 파일로 본다. trigger 의 changed_paths(announced)가 오면 실제
    차이와 같아야 한다. skills/<skill>/SKILL.md 가 아닌 파일과 추가되거나 지워진 skill 은 되돌리기
    실험 방법이 없어 ContractError 로 멈춘다. 파일마다 MCP diff_skill 도구
    (service/src/main/java/com/capstone4/skillops/mcp/SkillDiffTools.java 의 diffSkill)로 C hunk 를
    받고, skillops-agent/skillops_agent/contracts.py 의 check_diff_evidence 로 두 snapshot 과 파일
    해시를 대조한다. change 는 C hunk 의 칸에 change_no(1부터)와 skill 을 덧붙인 dict 다. 돌려주는
    값은 (diff_skill 응답 목록, change 목록)이다. fetch_evidence 가 부른다.
    """
    # 실패 위치나 버전 계산으로 짐작하지 않고 두 manifest 의 실제 차이만 변경으로 본다.
    old = file_hashes(snaps.before)
    new = file_hashes(snaps.after)
    paths = sorted(path for path in old.keys() | new.keys() if old.get(path) != new.get(path))
    if announced is not None and set(announced) != set(paths):
        raise ContractError("changed_paths does not match the two snapshot manifests")
    changes = []
    diffs = []
    for path in paths:
        skill = skill_of_file(path)
        if skill is None:
            raise ContractError(f"resource/additional path diagnosis is not supported: {path}")
        if path not in old or path not in new:
            raise ContractError("added/removed skills need a separate ablation policy")
        diff = await t.diff_skill(skill, snaps.baseline, snaps.candidate)
        hunks = check_diff_evidence(
            diff,
            before={
                "snapshot_id": snaps.baseline,
                "file_hash": old[path],
                "snapshot_hash": snaps.before["hash"],
            },
            after={
                "snapshot_id": snaps.candidate,
                "file_hash": new[path],
                "snapshot_hash": snaps.after["hash"],
            },
        )
        diffs.append(diff)
        for hunk in hunks:
            changes.append({**hunk, "change_no": len(changes) + 1, "skill": skill})
    return diffs, changes


def _preflight_stop(status: str, snaps: _Snapshots, run_ids, explanation, failures) -> dict:
    """사전 검증에서 진단을 멈출 때 남기는 상태다. 증거 키(trace·changes 등)는 넣지 않는다.

    BASELINE_FAILED 가 쓴다. 증거 키를 넣지 않으므로 init 이 채운 기본값이 그대로 남는다.
    frontend/src/views/DiagnosisView.vue 가 diagnosis.explanation 과 failures, preflight_run_ids 를
    보여 준다. _preflight 가 부른다. 결과 불일치는 여기서 멈추지 않고 원인 실험을 잇는다
    (_preflight_diagnosis).
    """
    return {
        "diagnosis_status": status,
        "snapshot_id": snaps.candidate,
        "baseline_snapshot_id": snaps.baseline,
        "preflight_run_ids": run_ids,
        "diagnosis": {"explanation": explanation, "failures": failures},
    }


async def _preflight(t, snaps: _Snapshots, expectations: list[dict]) -> _PreflightOutcome:
    """저장 직후 사전 검증이다. 기준 세트를 먼저 보고, 후보가 실패하면 그 결과로 진단을 잇는다.

    실행 없이 들어온 요청(type=skill_saved)에 쓴다. 회귀 세트와 반복 횟수는
    skillops-agent/skillops_agent/config.py 의 GOLDEN_IDS 와 PREFLIGHT_REPEAT 이다. expectations 는
    요청의 담당자 의도(담당자가 준 것 또는 의도 글로 _infer_expectations 가 만든 것)이며 후보
    세트에만 붙인다. 기준본은 의도를 실현하지 않으므로 붙이면 늘
    BASELINE_FAILED 가 된다(docs/plans/2026-10-26-behavior-edit-root-fix.md 2.5).
    단계는 네 가지다.
    1. 기준 snapshot workspace 에서 회귀 세트를 의도 없이 돌린다. 하나라도 실패하면 재고 같은 검증
       환경 문제일 수 있으므로 수정본 결함으로 판정하지 않고 BASELINE_FAILED 로 멈춘다.
    2. 후보 snapshot workspace 에서 같은 세트를 의도와 함께 돌려 모두 통과하면 validated 로 끝낸다.
       이때는 LLM 을 부르지 않는다.
    3. 실패한 주문마다 첫 실패 실행의 trace 를 차례로 읽는다. completion 이 FAILED 인 실행이 있으면
       그 실행이 후보 파일로 돌았는지 확인하고 그 실행을 진단 대상으로 넘긴다(재현 주문 f-<실행>).
    4. 모두 실행 오류 없이 결과만 다르면 떨어진 기준 주문을 repro_ids 로 넘긴다. 의도 범위
       (intent_scoped) 주문은 의도 때문에 떨어진 것이다. 의도 범위 밖에서 떨어진 주문은 scope 가
       null 인 expectation 의 checks(모든 주문에 더해지고 intent_scoped 가 거짓) 때문일 수 있으므로,
       의도가 있으면 그 주문만 의도 없이 한 번 더 돌린다(V3-3). 의도 없이도 떨어지는 주문은 결과
       불일치이고, 의도와 함께일 때만 떨어지는 주문은 의도 미실현이다. 결과 불일치가 하나라도 있으면
       그 주문으로 원인 실험을 잇고, 없으면 intent_gap 이다.
    상태 문자열은 frontend/src/utils/diagnosisView.js 의 라벨과 맞아야 한다. fetch_evidence 가
    부른다.
    """
    baseline_ws = await t.create_workspace(snapshot_id=snaps.baseline)
    baseline_results = await _run_scenarios(
        t, baseline_ws["workspace_id"], GOLDEN_IDS, PREFLIGHT_REPEAT
    )
    if not all_passed(baseline_results):
        return _PreflightOutcome(
            stop=_preflight_stop(
                DiagnosisStatus.BASELINE_FAILED,
                snaps,
                run_ids_of(baseline_results),
                (
                    "기준 세트의 주문도 검증을 통과하지 못했습니다. "
                    "재고 등 검증 환경을 확인하세요. 수정본 결함으로 확정하지 않습니다."
                ),
                failures_of(baseline_results),
            )
        )
    ws = await t.create_workspace(snapshot_id=snaps.candidate)
    results = await _run_scenarios(
        t, ws["workspace_id"], GOLDEN_IDS, PREFLIGHT_REPEAT, expectations=expectations
    )
    ids = run_ids_of(results)
    if all_passed(results):
        # trigger 에서 온 실행 번호("" 또는 None)와 trace(None)를 그대로 남긴다.
        # 리팩토링 전 코드와 같다.
        return _PreflightOutcome(
            execution=snaps.execution, trace=snaps.trace, run_ids=ids, validated=True
        )
    failed = [result for result in results if not result["passed"]]
    for result in failed:
        execution = execution_uuid(result["failures"][0]["run_id"])
        trace = await t.get_trace(execution)
        if trace.get("completion") != "FAILED":
            continue
        # C 는 workspace 실행에 파생 snapshot 을 따로 발행한다. 그래서 snapshot ID 가 아니라 파일
        # 해시를 모두 후보 manifest 와 대조한다.
        replay_manifest = await t.get_snapshot(trace["snapshot_id"])
        if file_hashes(replay_manifest) != file_hashes(snaps.after):
            raise ContractError("preflight executed different candidate files")
        return _PreflightOutcome(execution=execution, trace=trace, run_ids=ids)
    # 실행 오류 없이 결과만 다르다. 재현 주문 f-<실행> 은 FAILED 실행에만 쓰므로 기준 주문을 넘긴다.
    gap = [r["scenario_id"] for r in failed if r.get("intent_scoped")]
    outside = [r["scenario_id"] for r in failed if not r.get("intent_scoped")]
    if expectations and outside:
        # scope 가 null 인 checks 는 범위 밖 주문에도 더해진다. 의도 없이 다시 돌려 그때도 떨어지는
        # 주문만 결과 불일치로 본다. 나머지는 의도와 함께일 때만 떨어진 의도 미실현이다.
        plain = await _run_scenarios(t, ws["workspace_id"], outside, PREFLIGHT_REPEAT)
        still = {r["scenario_id"] for r in plain if not r["passed"]}
        gap += [i for i in outside if i not in still]
        outside = [i for i in outside if i in still]
    gap = [i for i in GOLDEN_IDS if i in gap]
    return _PreflightOutcome(
        run_ids=ids,
        repro_ids=outside or gap,
        intent_gap=bool(expectations) and not outside,
        failures=failures_of(results),
    )


def _preflight_diagnosis(outcome: _PreflightOutcome) -> dict:
    """실행 오류 없이 떨어진 사전 검증을 diagnosis 로 남긴다. 진단 상태는 RUNNING 그대로다.

    의도 미실현이면 finding 이 INTENT_NOT_REALIZED 이고 skillops-agent/skillops_agent/graph.py 의
    route_evidence 가 intent_gap 으로 보낸다. 결과 불일치면 finding 없이 설명과 실패만 남기고, 원인
    실험으로도 원인을 확정하지 못하면 check_ablation 이 VALIDATION_FAILED 로 바꾼다. frontend 가
    explanation 과 failures 를 보여 준다. fetch_evidence 가 부른다.
    """
    names = ", ".join(outcome.repro_ids)
    if outcome.intent_gap:
        return {
            "finding": INTENT_NOT_REALIZED,
            "explanation": (
                "수정본은 실행 오류 없이 끝나지만 담당자 의도가 실행되지 않습니다. "
                f"의도 범위의 기준 주문 {names} 이 의도한 결과와 다릅니다."
            ),
            "failures": outcome.failures,
        }
    return {
        "explanation": (
            "기대 결과와 실제 업무 결과가 다릅니다. "
            f"실행 오류 없이 결과만 달라진 기준 주문 {names} 으로 원인 실험을 진행합니다."
        ),
        "failures": outcome.failures,
    }


def _incomplete_edit_root_cause(
    change: dict, skill_versions: dict, run_ids: list[str], repro_ids: list[str]
) -> dict:
    """의도 미실현의 root_cause 다. 원인 실험 없이 담당자가 바꾼 첫 변경을 원인으로 둔다.

    수정본이 오류 없이 돌지만 의도가 실행되지 않으므로 되돌리기 실험이 아니라 사전 검증의 의도
    시험이 근거다. proof_run_ids 는 수정본 사전 검증 실행, repro_scenario_ids 는 떨어진 의도 범위
    기준 주문이다. 키 순서는 _root_cause 와 같고 끝에 kind 와 repro_scenario_ids 가 붙는다.
    fetch_evidence 가 부른다.
    """
    return {
        "skill": change["skill"],
        "change_no": change["change_no"],
        "hunk_id": change["hunk_id"],
        "from_version": skill_versions[change["skill"]],
        "to_version": skill_versions[change["skill"]],
        "summary": change["before"] + " → " + change["after"],
        "proof_run_ids": list(run_ids),
        "kind": "incomplete_edit",
        "repro_scenario_ids": list(repro_ids),
    }


async def _evaluate_intent_checks(t, trigger: dict, execution: str | None, candidate: str) -> dict:
    """diagnosis_only 진단에서 재현 시나리오에 확인 조건을 붙여 돌리고 의도 위반 여부를 정한다.

    후보 snapshot workspace 에서 재현 시나리오(f-<execution>)에 trigger 의 intent_checks 를
    extra_checks 로 붙여 돌린다. 실패 사유에 CHECK_FAILURE_PREFIX('확인 조건 실패:')가 있으면
    INTENT_VIOLATION, 없으면 EVALUATED 로 정하고 근거 실행과 실패를 diagnosis 에 담는다. 후보를
    만들지 않으며, skillops-agent/skillops_agent/graph.py 의 route_evidence 가 RUNNING 이 아닌
    상태를 보고 END 로 보낸다. 실행이나 확인 조건이 없으면 ContractError 를 던진다. 저장 직후 사전
    검증이 통과해 실행이 없을 때도 그렇다. 반복 횟수는 skillops-agent/skillops_agent/config.py 의
    INTENT_CHECK_REPEAT 이다. fetch_evidence 가 부른다.
    """
    checks = trigger.get("intent_checks", [])
    if not execution or not checks:
        raise ContractError("diagnosis_only requires an execution and intent_checks")
    ws = await t.create_workspace(snapshot_id=candidate)
    results = await _run_scenarios(
        t, ws["workspace_id"], [replay_scenario_id(execution)], INTENT_CHECK_REPEAT, checks=checks
    )
    failed_checks = failures_of(results)
    # 확인 조건이 맞지 않은 실패만 의도 위반의 근거가 된다. 실행 오류 같은 다른 실패는 세지 않는다.
    violated = any(CHECK_FAILURE_PREFIX in f["reason"] for f in failed_checks)
    return {
        "diagnosis_status": (
            DiagnosisStatus.INTENT_VIOLATION if violated else DiagnosisStatus.EVALUATED
        ),
        "diagnosis": {
            "intent_checks": checks,
            "failures": failed_checks,
            "evidence_run_ids": run_ids_of(results),
            "explanation": "변경 정책의 확인 조건과 실제 실행 결과가 일치하지 않습니다."
            if violated
            else "실행 결과를 확인했습니다. 의도 위반 확정 여부는 failures를 확인하세요.",
        },
    }


# ── 의도 글로 expectations 만들기 (사전 검증 전) ────────────────────────────────
# 담당자는 보통 의도 글(intent.text)만 적는다. expectations 가 없으면 사전 검증에 붙일 의도 시험이
# 없어 본문만 고친 수정본도 VALIDATED 로 끝난다. 그래서 fetch_evidence 가 저장 직후 요청에서만
# LLM 에 한 번 물어 expectations 를 만들고(출처 inferred) 수정본 사전 검증에 붙인다. 만든 값은
# 상태의 intent 에 두어 extract_intent 가 다시 만들지 않고 제안서도 출처 inferred 로 보낸다.


def _literal(value) -> str:
    """기준 주문 표의 값을 조건 문법의 값 모양으로 쓴다. 글자는 작은따옴표, 불리언은 true·false."""
    if isinstance(value, bool):
        return "true" if value else "false"
    if value is None:
        return "null"
    if isinstance(value, int | float):
        return str(value)
    return f"'{value}'"


def _golden_orders_text() -> str:
    """기준 주문 표(config 의 GOLDEN_ORDERS)를 GOLDEN_IDS 순서의 마크다운 표로 펼친다.

    칸은 주문 id, 설명, 의도 없이 돌렸을 때의 기대 최종 상태, contracts.SCOPE_KEYS 의 처음 값이다.
    GOLDEN_ORDERS 에 없는 주문은 '처음 값 모름' 줄로 남겨 LLM 이 그 주문을 짐작하지 않게 한다.
    _infer_expectations 가 프롬프트의 {golden_orders} 자리에 넣는다.
    """
    header = ["주문", "설명", "지금 기대 최종 상태", *SCOPE_KEYS]
    lines = ["| " + " | ".join(header) + " |", "|" + " --- |" * len(header)]
    for scenario in GOLDEN_IDS:
        order = GOLDEN_ORDERS.get(scenario)
        if order is None:
            cells = [scenario, "처음 값 모름", *(["?"] * (len(header) - 2))]
        else:
            values = [_literal(order["values"].get(key)) for key in SCOPE_KEYS]
            cells = [scenario, order["name"], order["final_status"], *values]
        lines.append("| " + " | ".join(cells) + " |")
    return "\n".join(lines)


def _changes_text(changes: list[dict]) -> str:
    """변경 hunk 목록을 프롬프트 글로 바꾼다. 변경마다 머리줄과 C 가 준 unified hunk 다."""
    blocks = []
    for change in changes:
        part = f" ({change['part']})" if change.get("part") else ""
        head = f"### change {change['change_no']} · {change['skill']}{part}"
        blocks.append(f"{head}\n{change.get('diff') or ''}".rstrip())
    return "\n\n".join(blocks) or "(없음)"


async def _frontmatter_text(t, snapshot_id: str, skills) -> str:
    """후보 snapshot 의 skill 마다 frontmatter 원문을 get_skill 로 읽어 프롬프트 글로 잇는다.

    원문이 그 snapshot 의 것이 아니거나 글이 아니면 ContractError 를 던진다. _infer_expectations 가
    부르고 예외를 잡아 의도 시험 없이 진행한다.
    """
    blocks = []
    for skill in skills:
        source = await t.get_skill(snapshot_id, skill)
        if source.get("snapshot_id") != snapshot_id or not isinstance(source.get("content"), str):
            raise ContractError("candidate source does not match the fixed snapshot")
        block = skill_rules.frontmatter_text(source["content"])
        blocks.append(f"### {skill}\n{block if block is not None else '(frontmatter 없음)'}")
    return "\n\n".join(blocks) or "(없음)"


def _declared_names(listing: dict) -> set[str]:
    """list_skills 응답의 모든 단계가 선언한 inputs 와 outputs 값 이름이다.

    _known_values(extract_intent)와 _infer_expectations(사전 검증 전)가 확인 조건의 값 이름 후보로
    쓴다(계획 1절의 D1).
    """
    names = set()
    for item in listing.get("items", []):
        names.update(v for v in item.get("inputs") or [] if isinstance(v, str))
        names.update(v for v in item.get("outputs") or [] if isinstance(v, str))
    return names


def _usable_expectations(items, known: list[str]) -> tuple[list[dict], list[dict]]:
    """Agent 가 만든 expectations 를 쓸 수 있는 것과 버린 것({check, reason})으로 나눈다.

    _split_expectations 로 scope(시험 주문의 처음 값)와 checks(문법, 값 이름)를 거른 뒤
    docs/plans/2026-10-26-behavior-edit-root-fix.md 2.3 의 상한을 넘는 것을 버린다. 상한은
    contracts 의 INTENT_MAX_EXPECTATIONS(expectations 수)와 INTENT_MAX_CHECKS(항목 하나의 checks
    수)다. 넘친 expectation 은 check 칸에 'scope: <scope>' 를 쓴다. _infer_expectations 와
    extract_intent 가 부른다.
    """
    kept, dropped = _split_expectations(items, known)
    capped = []
    for expectation in kept:
        if len(capped) >= INTENT_MAX_EXPECTATIONS:
            dropped.append(
                {
                    "check": f"scope: {expectation['scope']}",
                    "reason": f"expectations 는 {INTENT_MAX_EXPECTATIONS}개까지만 씁니다",
                }
            )
            continue
        checks = expectation["checks"]
        dropped.extend(
            {"check": check, "reason": f"checks 는 항목마다 {INTENT_MAX_CHECKS}개까지만 씁니다"}
            for check in checks[INTENT_MAX_CHECKS:]
        )
        capped.append({"scope": expectation["scope"], "checks": checks[:INTENT_MAX_CHECKS]})
    return capped, dropped


def _intent_note(reason: str, dropped: list[dict] | None = None, preflight: bool = True) -> dict:
    """의도 글을 시험 조건으로 바꾸지 못했을 때 diagnosis 에 더할 칸이다.

    intent_note 는 운영자가 읽는 설명이고, 버린 조건이 있으면 intent_dropped_checks 로 함께 남긴다.
    preflight 가 참이면 사전 검증 전 추론(_infer_expectations)의 설명이고, 거짓이면 extract_intent
    가 후보 검증 전에 만들지 못한 설명이다. _with_intent_note 가 diagnosis 에 넣는다.
    """
    if preflight:
        text = (
            "담당자 의도 글을 시험 조건(expectations)으로 바꾸지 못해 의도 시험 없이 사전 "
            f"검증했습니다. {reason}. 의도대로 동작하는지 확인하려면 저장할 때 기대 결과를 함께 "
            "적어 주세요."
        )
    else:
        text = (
            "담당자 의도 글을 시험 조건(expectations)으로 바꾸지 못했습니다. "
            f"{reason}. 시험 조건이 없으면 어떤 후보도 의도를 지켰다고 판정하지 않습니다. "
            "저장할 때 기대 결과를 함께 적어 주세요."
        )
    note = {"intent_note": text}
    if dropped:
        note["intent_dropped_checks"] = dropped
    return note


def _owner_dropped_note(dropped: list[dict]) -> dict:
    """담당자가 준 expectations 가운데 읽을 수 없어 뺀 것이 있을 때 diagnosis 에 더할 칸이다.

    /diagnose 는 이런 항목을 422 로 막지 않고 빼고 진행한다(V3-2). fetch_evidence 가 만든다.
    """
    return {
        "intent_note": (
            f"담당자가 준 기대 결과 가운데 조건 {len(dropped)}개를 읽을 수 없어 빼고 진단했습니다. "
            "뺀 조건과 이유는 intent_dropped_checks 에 있습니다."
        ),
        "intent_dropped_checks": dropped,
    }


def _with_intent_note(diagnosis: dict | None, note: dict) -> dict:
    """diagnosis 에 의도 설명(_intent_note, _owner_dropped_note)을 더한 새 dict 다.

    화면(frontend/src/views/DiagnosisView.vue)은 diagnosis.explanation 과 failures 를 보여 주므로
    설명을 explanation 끝에도 잇는다. explanation 이 없으면(VALIDATED 등) 설명이 곧
    explanation 이다. diagnosis 에 이미 설명이 있으면 intent_note 와 intent_dropped_checks 를 앞의
    것 뒤에 잇는다. fetch_evidence, extract_intent, check_ablation 이 부른다.
    """
    base = diagnosis or {}
    merged = {**base, **note}
    if base.get("intent_note") and base["intent_note"] != note["intent_note"]:
        merged["intent_note"] = f"{base['intent_note']} {note['intent_note']}"
    dropped = list(base.get("intent_dropped_checks") or []) + [
        d for d in note.get("intent_dropped_checks") or []
        if d not in (base.get("intent_dropped_checks") or [])
    ]
    if dropped:
        merged["intent_dropped_checks"] = dropped
    explanation = base.get("explanation")
    if explanation and note["intent_note"] in explanation:
        merged["explanation"] = explanation
    else:
        merged["explanation"] = (
            f"{explanation} {note['intent_note']}" if explanation else note["intent_note"]
        )
    merged.setdefault("failures", [])
    return merged


def _dropped_expectation(expectation: dict, reason: str) -> dict:
    """expectation 하나를 통째로 버릴 때 dropped_checks 에 남길 {check, reason} 이다."""
    return {"check": f"scope: {expectation.get('scope')}", "reason": reason}


def _scope_guard(expectations: list[dict]) -> tuple[list[dict], list[dict], str | None]:
    """Agent 가 만든 expectations 의 scope 를 기준 주문 표로 검사한다(V3-4 의 a, b).

    (a) scope 가 있는 expectation 마다 회귀 세트의 기준 주문 하나 이상과 맞아야 하고, 아니면 그
    expectation 을 버린다. (b) 남은 scope 가운데 어느 것에도 맞지 않는 기준 주문이 하나 이상 있어야
    한다. 모든 기준 주문이 scope 안이면 기존 기대 결과로 채점하는 주문이 없어 회귀를 볼 수 없으므로
    남은 것을 모두 버린다. 처음 값을 모르는 기준 주문이 있으면 판정할 수 없는 쪽은 버리지 않는다.
    돌려주는 값은 (남은 것, 버린 것, 모두 버린 이유)다. _guard_inferred 가 부른다.
    """
    known, unknown = _golden_scope_values()
    kept, dropped = [], []
    for expectation in expectations:
        scope = expectation.get("scope")
        if scope and not unknown and not any(_scope_holds(scope, v) for v in known.values()):
            reason = "scope 에 맞는 기준 주문이 없어 이 기대 결과를 시험할 수 없습니다"
            dropped.append(_dropped_expectation(expectation, reason))
            continue
        kept.append(expectation)
    scopes = [e["scope"] for e in kept if e.get("scope")]
    inside_all = all(any(_scope_holds(s, v) for s in scopes) for v in known.values())
    if scopes and not unknown and inside_all:
        reason = (
            "모든 기준 주문이 scope 안에 들어 기존 기대 결과로 채점하는 기준 주문이 남지 "
            "않습니다"
        )
        return [], dropped + [_dropped_expectation(e, reason) for e in kept], reason
    return kept, dropped, None


async def _baseline_vacuity(t, baseline: str | None, expectations: list[dict]) -> str | None:
    """기준본도 expectations 를 모두 통과하면 버릴 이유를, 아니면 None 을 돌려준다(V3-4 의 c).

    기준 snapshot workspace 에서 회귀 세트를 expectations 와 함께 PREFLIGHT_REPEAT 번 돌린다.
    기준본이 이미 지키는 기대 결과는 수정본이 의도를 실현했는지 가려내지 못하므로(늘 참인 의도)
    버린다. 도구나 계약 오류가 나도 확인하지 못한 기대 결과로 보고 버린다. _guard_inferred 가
    부른다.
    """
    try:
        ws = await t.create_workspace(snapshot_id=baseline)
        results = await _run_scenarios(
            t, ws["workspace_id"], GOLDEN_IDS, PREFLIGHT_REPEAT, expectations=expectations
        )
    except Exception as exc:
        return f"기준본으로 기대 결과를 확인하는 중 {type(exc).__name__} 오류가 났습니다"
    if all_passed(results):
        return "기준본도 이 기대 결과를 모두 통과해 수정본이 의도를 실현했는지 가려내지 못합니다"
    return None


async def _guard_inferred(
    t, baseline: str | None, expectations: list[dict]
) -> tuple[list[dict], list[dict], str | None]:
    """Agent 가 만든(inferred) expectations 의 최소 요건을 본다(V3-4).

    _scope_guard 의 (a)·(b) 를 먼저 보고, 남은 것이 있으면 _baseline_vacuity 로 (c) 기준본이 이미
    모두 통과하는지 본다. 돌려주는 값은 (남은 것, 버린 것, 모두 버린 이유)다. 남은 것이 없으면 의도
    시험이 없는 것(빈 의도)으로 보고 부르는 쪽이 intent_note 를 남긴다. 담당자가 준 expectations 는
    이 검사를 하지 않는다. _infer_expectations 와 extract_intent 가 부른다.
    """
    kept, dropped, reason = _scope_guard(expectations)
    if kept and reason is None:
        reason = await _baseline_vacuity(t, baseline, kept)
        if reason:
            dropped += [_dropped_expectation(e, reason) for e in kept]
            kept = []
    return kept, dropped, reason


async def _infer_expectations(
    t, owner: dict, snaps: _Snapshots, versions: dict, listing: dict, changes: list[dict]
) -> tuple[Intent | None, dict | None]:
    """담당자가 의도 글만 주었을 때 사전 검증 전에 LLM 으로 expectations 를 한 번 만든다.

    skillops-agent/prompts/infer_expectations.md 에 의도 글, 변경 hunk(_changes_text), 후보
    snapshot 의 frontmatter(_frontmatter_text), 기준 주문 표(_golden_orders_text), scope 값 이름
    (contracts.SCOPE_KEYS), checks 값 이름(선언 inputs·outputs 와 예약 키)을 넣어
    _ExpectationsOutput 으로 받고 _usable_expectations 로 거른 뒤 _guard_inferred 로 최소 요건(V3-4:
    scope 마다 맞는 기준 주문, scope 밖 기준 주문, 기준본이 이미 통과하지 않음)을 본다. 돌려주는
    값은 (intent, note) 다. 쓸 수 있는 expectation 이 하나라도 있으면 intent 는 출처 inferred 의
    Intent 이고 note 는 None 이다. 원문 조회나 LLM 이 실패하거나 쓸 수 있는 것이 없으면 intent 는
    None 이고 note 는 _intent_note 의 칸이다. 이 단계의 어떤 예외도 진단을 멈추지 않는다. 그때
    fetch_evidence 는 예전처럼 의도 시험 없이 사전 검증한다. RAG 참고 자료는 붙이지 않는다.
    fetch_evidence 가 저장 직후 요청에서만 부른다.
    """
    known = sorted(_declared_names(listing) | set(RESERVED_KEYS))
    try:
        result = await _ask_llm(
            "infer_expectations",
            _ExpectationsOutput,
            owner_intent=owner["text"],
            diff=_changes_text(changes),
            skill_frontmatter=await _frontmatter_text(t, snaps.candidate, versions),
            golden_orders=_golden_orders_text(),
            scope_keys=", ".join(SCOPE_KEYS),
            value_names=", ".join(known),
        )
        expectations, dropped = _usable_expectations(getattr(result, "expectations", None), known)
    except Exception as exc:
        return None, _intent_note(f"기대 결과를 만드는 중 {type(exc).__name__} 오류가 났습니다")
    reason = None
    if expectations:
        expectations, guarded, reason = await _guard_inferred(t, snaps.baseline, expectations)
        dropped += guarded
    if not expectations:
        reason = reason or "LLM 이 쓸 수 있는 기대 결과를 내지 않았습니다"
        if dropped:
            reason += f"(버린 조건 {len(dropped)}개)"
        return None, _intent_note(reason, dropped)
    intent = Intent(
        text=owner["text"],
        checks=_unscoped_checks(expectations),
        dropped_checks=dropped,
        confirmed=None,
        source="inferred",
        expectations=expectations,
    )
    return intent, None


def _inference_message(intent: Intent | None) -> str:
    """의도 글로 expectations 를 만든 fetch_evidence 의 진행 메시지다."""
    base = "실제 snapshot과 실행 증거 수집"
    if intent is None:
        return f"{base}, 의도 기대 결과 추론 실패(의도 시험 없이 사전 검증)"
    return f"{base}, 의도 기대 결과 {len(intent['expectations'])}개 추론"


# ── 도구가 필요한 수정 (개발 요청서) ───────────────────────────────────────────
# 담당자 수정이 어떤 업무 도구도 만들지 않는 값을 요구하면 SKILL.md 수정만으로는 고칠 수 없다.
# 그때는 후보 생성(LLM 재시도)을 건너뛰고 개발 요청서와 임시 대안을 담은 no_valid_fix 제안서를 낸다
# (docs/plans/2026-10-26-behavior-edit-root-fix.md 2.11). 판정 규칙은
# skillops-agent/skillops_agent/capability.py 에 있다.


async def _skill_text(t, snapshot_id: str, skill: str) -> str:
    """snapshot 에 고정된 skill 원문을 get_skill 로 읽는다.

    그 snapshot 의 글이 아니거나 글자가 아니면 ContractError 다. _capability_gaps 가 부른다.
    """
    source = await t.get_skill(snapshot_id, skill)
    if source.get("snapshot_id") != snapshot_id or not isinstance(source.get("content"), str):
        raise ContractError("skill source does not match the fixed snapshot")
    return source["content"]


async def _flow_steps(t, trigger: dict, snapshot_id: str) -> list[str]:
    """후보 snapshot 의 단계 실행 순서(의존성 그래프 steps)다. 읽지 못하면 빈 목록이다.

    개발 요청서의 방법(options)에서 앞 단계를 가릴 때 쓴다. 빈 목록이면 capability 가 도구 목록
    순서를 쓴다. 시작 skill 은 _repair_inputs 와 같다.
    """
    try:
        view = await t.get_dependency_graph(
            snapshot_id=snapshot_id,
            entry_skill_id=trigger.get("entry_skill_id", DEFAULT_ENTRY_SKILL_ID),
        )
    except Exception:
        return []
    steps = view.get("steps") if isinstance(view, dict) else None
    return [s for s in steps if isinstance(s, str)] if isinstance(steps, list) else []


async def _capability_gaps(t, trigger: dict, snaps: _Snapshots, changes: list[dict]) -> dict | None:
    """담당자가 새로 넣은 값 가운데 어떤 업무 도구도 만들지 않는 것을 찾는다.

    빈틈이 있으면 {gaps, request} 이고, gaps 는 capability.find_gaps 의 항목, request 는 근거 실행이
    빈 개발 요청서 초안(capability.development_request)이다. 빈틈이 없거나 판단할 수 없으면 None
    이다. 순서는 다음과 같다. 변경이 없거나 diagnosis_only 면 보지 않는다. 주문 Agent 도구 목록
    (get_tool_catalog)을 읽지 못하면(None, 예외, 그 메서드가 없는 대역) 판정을 건너뛴다. frontmatter
    hunk 에 도구가 내지 않는 값 이름이 없으면(capability.may_need_tool) 원문을 읽지 않고 끝내므로
    빈틈이 없는 진단의 도구 호출은 예전과 같다. 그 밖에는 frontmatter 가 바뀐 skill 의 기준본·수정본
    원문을 get_skill 로 읽어 판정하고, 빈틈이 있으면 흐름 순서(_flow_steps)와 임시 대안 patch 로
    초안을 만든다. 이 단계의 어떤 예외도 진단을 멈추지 않는다. fetch_evidence 가 부른다.
    """
    if not changes or trigger.get("diagnosis_only"):
        return None
    try:
        catalog = await t.get_tool_catalog()
        if not catalog or not capability.may_need_tool(catalog, changes):
            return None
        sources = {}
        for skill in capability.frontmatter_skills(changes):
            sources[skill] = (
                await _skill_text(t, snaps.baseline, skill),
                await _skill_text(t, snaps.candidate, skill),
            )
        gaps = capability.find_gaps(catalog, sources)
        if not gaps:
            return None
        steps = await _flow_steps(t, trigger, snaps.candidate)
        patch = capability.interim_patch(changes, gaps, sources)
        request = capability.development_request(catalog, gaps, steps, patch, [])
    except Exception:
        return None
    return {"gaps": gaps, "request": request}


def _gap_keys(gaps: list[dict]) -> str:
    """빈틈 키를 겹치지 않게 ', ' 로 잇는다. 진행 메시지와 diagnosis 설명에 쓴다."""
    return ", ".join(dict.fromkeys(gap["key"] for gap in gaps))


def _needs_tool_diagnosis(diagnosis: dict | None, gaps: list[dict]) -> dict:
    """diagnosis 에 finding=NEEDS_TOOL 과 도구가 필요하다는 설명을 더한 새 dict 다.

    앞 설명(결과 불일치, 의도 미실현, 의도 설명 등)은 그대로 두고 끝에 잇는다. finding 은 덮는다.
    frontend 가 finding 을 '도구 개발 필요'로 보여 준다. fetch_evidence 와 _validation_failed 가
    부른다.
    """
    sentence = (
        f"담당자 수정이 어떤 업무 도구도 만들지 않는 값({_gap_keys(gaps)})을 요구합니다. SKILL.md "
        "수정만으로는 고칠 수 없어 수정 후보를 만들지 않고 개발 요청서와 임시 대안을 남깁니다."
    )
    base = diagnosis or {}
    explanation = base.get("explanation")
    if explanation and sentence not in explanation:
        sentence = f"{explanation} {sentence}"
    elif explanation:
        sentence = explanation
    return {**base, "finding": NEEDS_TOOL, "explanation": sentence,
            "failures": base.get("failures", [])}


def _needs_tool_update(update: dict, owner: dict, found: dict, run_ids: list[str]) -> dict:
    """fetch_evidence 가 빈틈을 찾았을 때 상태에 더할 칸이다.

    capability_gaps 는 늘 남긴다. 사전 검증이 VALIDATED 처럼 진단을 끝냈으면 그 결론을 바꾸지
    않는다. 진단을 이으면 diagnosis.finding 을 NEEDS_TOOL 로 두고 개발 요청서 초안에 수정본 사전
    검증 실행(run_ids)을 evidence_run_ids 로 넣는다. extract_intent 를 건너뛰므로 담당자
    expectations 가 있으면 그 의도(출처 owner)를 여기서 남겨 제안서에 싣는다.
    """
    fields = {"capability_gaps": found["gaps"]}
    if update.get("diagnosis_status", DiagnosisStatus.RUNNING) != DiagnosisStatus.RUNNING:
        return fields
    fields["diagnosis"] = _needs_tool_diagnosis(update.get("diagnosis"), found["gaps"])
    fields["development_request"] = {**found["request"], "evidence_run_ids": list(run_ids)}
    if owner.get("expectations") and "intent" not in update:
        fields["intent"] = _owner_intent_value(owner)
    return fields


async def fetch_evidence(state: AgentState) -> dict:
    """2번 노드다. 진단의 증거를 모은다.

    실행과 두 snapshot 을 고정하고(_fix_snapshots), 후보 snapshot 의 skill 목록과 버전을 list_skills
    로 읽는다. 목록이 partial이면 metadata 증거를 반환하고 전용 수집 경로로 보낸다.
    완전한 목록은 check_skill_listing으로 검사하고 실제 변경을 hunk로 모은다(_collect_changes).
    실행이 없으면(type=skill_saved)
    사전 검증(_preflight)으로 BASELINE_FAILED, VALIDATED 가운데 하나로 끝내거나 실패한 실행으로
    진단을 잇는다. 그 전에 담당자 의도가 글만 있고 변경이 있으면 _infer_expectations 로
    expectations 를 한 번 만들어 수정본 사전 검증에 붙이고 상태의 intent 에 둔다(진행 메시지에
    추론 결과를 덧붙인다). 만들지 못하면 의도 시험 없이 사전 검증하고 diagnosis 에 intent_note 를
    더한다. 담당자가 expectations 를 주었거나 의도가 없으면 LLM 을 부르지 않는다. 실행 오류 없이
    결과만 다르면 그 기준 주문을 repro_scenario_ids 로 남기고, 의도
    범위 주문만 떨어졌으면 의도 미실현 root_cause(kind=incomplete_edit)도 남긴다. 변경이 없으면
    NO_SKILL_CHANGES 를 남기고, diagnosis_only 요청이면 의도 조건 검사(_evaluate_intent_checks)로
    결론을 낸다.
    변경을 모은 직후 담당자가 새로 넣은 값 가운데 어떤 업무 도구도 만들지 않는 것을
    찾고(_capability_gaps), 있으면 capability_gaps 를 남긴다. 그때 진단을 이으면
    diagnosis.finding 을 NEEDS_TOOL 로, development_request 에 개발 요청서 초안을 두고 진행
    메시지에 그 값을 덧붙인다(_needs_tool_update). 사전 검증은 빈틈과 상관없이 그대로 한다.
    skillops-agent/skillops_agent/graph.py 의 FLOW 가 이 함수를 fetch_evidence 노드로 등록하고
    guarded 가 감싸 부른다. 다음 노드는 같은 파일의 route_evidence 가 정하며, diagnosis_status 가
    RUNNING 이고 changes 가 있을 때만 hypothesize(의도 미실현이면 extract_intent, 의도 미실현이면서
    도구가 필요하면 save_proposal)로 간다.
    """
    trigger = state["trigger"]
    progress = _progress(state, "fetch_evidence", "실제 snapshot과 실행 증거 수집")
    async with get_tools() as t:
        snaps = await _fix_snapshots(t, trigger)
        listing = await t.list_skills(snaps.candidate)
        if listing.get("partial") or listing.get("parse_errors"):
            evidence = prepare_partial_evidence(listing, snaps.before, snaps.after)
            return {
                **progress, "evidence": evidence,
                "trace": snaps.trace, "snapshot_id": snaps.candidate,
                "baseline_snapshot_id": snaps.baseline, "execution_id": snaps.execution,
                "skill_versions": {item["skill_id"]: item["version"] for item in listing["items"]},
            }
        versions = check_skill_listing(listing, snaps.candidate, snaps.after["hash"])
        diffs, changes = await _collect_changes(t, snaps, trigger.get("changed_paths"))
        # 어떤 업무 도구도 만들지 않는 값을 요구하는 수정인지 본다(2.11). 모르면 None 이다.
        needs_tool = await _capability_gaps(t, trigger, snaps, changes)
        update = {}
        execution, trace, ids = snaps.execution, snaps.trace, []
        owner = _owner_intent(state) or {}
        # 담당자가 준 expectations 가운데 읽을 수 없어 뺀 것이 있으면 그 설명을 남긴다(V3-2).
        notes = [_owner_dropped_note(owner["dropped"])] if owner.get("dropped") else []
        # 실행 없이 들어온 저장 직후 요청은 사전 검증 결과로 진단을 이을지부터 정한다.
        if not execution:
            expectations = list(owner.get("expectations") or [])
            # 의도 글만 있으면 사전 검증 전에 expectations 를 한 번 만든다. 실패하면 예전처럼 간다.
            if owner and not expectations and changes:
                inferred, note = await _infer_expectations(
                    t, owner, snaps, versions, listing, changes
                )
                if inferred:
                    update["intent"] = inferred
                    expectations = inferred["expectations"]
                if note:
                    notes.append(note)
                progress = _progress(state, "fetch_evidence", _inference_message(inferred))
            outcome = await _preflight(t, snaps, expectations)
            if outcome.stop:
                stop = outcome.stop
                for note in notes:
                    stop = {**stop, "diagnosis": _with_intent_note(stop["diagnosis"], note)}
                if needs_tool:
                    stop = {**stop, "capability_gaps": needs_tool["gaps"]}
                return {**progress, **update, **stop}
            if outcome.validated:
                update.update(
                    diagnosis_status=DiagnosisStatus.VALIDATED, preflight_run_ids=outcome.run_ids
                )
            execution, trace, ids = outcome.execution, outcome.trace, outcome.run_ids
            if outcome.repro_ids:
                update.update(
                    repro_scenario_ids=outcome.repro_ids,
                    diagnosis=_preflight_diagnosis(outcome),
                )
                if outcome.intent_gap and changes:
                    update["root_cause"] = _incomplete_edit_root_cause(
                        changes[0], versions, outcome.run_ids, outcome.repro_ids
                    )
        for note in notes:
            update["diagnosis"] = _with_intent_note(update.get("diagnosis"), note)
        update.update(
            trace=trace,
            diff={"files": diffs, "hunks": changes},
            changes=changes,
            skill_versions=versions,
            snapshot_id=snaps.candidate,
            baseline_snapshot_id=snaps.baseline,
            execution_id=execution,
            preflight_run_ids=ids,
        )
        if needs_tool:
            update.update(_needs_tool_update(update, owner, needs_tool, ids))
            message = progress["events"][-1]["msg"]
            progress = _progress(
                state, "fetch_evidence",
                f"{message}, 도구 개발 필요({_gap_keys(needs_tool['gaps'])})",
            )
        # 변경이 없으면 진단할 대상이 없다. VALIDATED 와 diagnosis_only 는 각자 상태를 정한다.
        if (
            not changes
            and not trigger.get("diagnosis_only")
            and update.get("diagnosis_status") != DiagnosisStatus.VALIDATED
        ):
            update["diagnosis_status"] = DiagnosisStatus.NO_SKILL_CHANGES
        # diagnosis_only(S5)는 후보를 만들지 않고 의도 조건 검사로 끝낸다.
        if trigger.get("diagnosis_only"):
            update.update(await _evaluate_intent_checks(t, trigger, execution, snaps.candidate))
    return {**progress, **update}


def _partial_stop(evidence: dict) -> dict:
    """partial 증거 수집을 오류 설명과 함께 끝내며 자동 수정 경로로 보내지 않는다."""
    return {
        "evidence": evidence,
        "diagnosis_status": DiagnosisStatus.ERROR,
        "error": "ContractError: candidate skill metadata is incomplete",
        "diagnosis": {
            "explanation": (
                "Skill 메타데이터가 불완전하여 증거만 수집했습니다. "
                "자동 수정과 실행은 수행하지 않습니다."
            ),
            "evidence_only": True,
            "collection_status": evidence["collection_status"],
            "collection_error_count": len(evidence["collection_errors"]),
        },
    }


async def collect_partial_sources(state: AgentState) -> dict:
    """partial 원문 수집 결과를 독립 노드로 반환해 diff 호출 전에 영구 저장한다."""
    progress = _progress(state, "collect_partial_sources", "불완전한 Skill 목록의 원문 증거 수집")
    async with get_tools() as tools:
        evidence, stopped = await collect_sources(tools, state["evidence"])
    return {**progress, **(_partial_stop(evidence) if stopped else {"evidence": evidence})}


async def collect_partial_diffs(state: AgentState) -> dict:
    """partial의 가능한 diff를 저장하고 LLM·workspace·patch 호출 없이 종료한다."""
    progress = _progress(
        state, "collect_partial_diffs", "불완전한 Skill 목록의 diff 증거 수집 후 종료"
    )
    async with get_tools() as tools:
        evidence, diff, changes = await collect_diffs(
            tools, state["evidence"], state["trigger"].get("changed_paths")
        )
    return {**progress, **_partial_stop(evidence), "diff": diff, "changes": changes}


# ── Node 3: hypothesize ───────────────────────────────────────────────────────


def _rejected_hypothesis_lines(ablation_runs: list[AblationRun]) -> list[str]:
    """기각된 ablation 실험을 재시도 프롬프트에 넣을 줄로 바꾼다.

    줄 모양은 'change N: ablation 재현 실패' 다. 상태의 ablation_runs 는 ablate 가 회차마다 통째로
    바꾸므로 직전 회차의 기각만 들어간다. hypothesize 가 부른다.
    """
    return [
        f"change {r['change_no']}: ablation 재현 실패" for r in ablation_runs if not r["confirmed"]
    ]


async def hypothesize(state: AgentState) -> dict:
    """3번 노드다. LLM 에게 원인 가설을 묻는다.

    skillops-agent/prompts/hypothesize.md 에 실행 기록(trace), 변경 목록, 재시도 피드백을 넣고
    _HypothesesOutput 으로 받는다. 프롬프트의 {diff} 자리에는 상태의 diff 가 아니라 changes 목록을
    넣는다. 가설은 change_no 로 변경 하나를 가리키고, 목록 순서가 ablate 의 실험 순서이자 원인 확정
    우선순위가 된다. 가설이 하나도 없으면 LLM 의 빈 응답을 가설 기각으로 세지 않고 ContractError 로
    멈춘다. 재시도 회차는 hypothesis_retry 이며 check_ablation 이 올린다.
    skillops-agent/skillops_agent/graph.py 의 FLOW 가 이 함수를 hypothesize 노드로 등록하고 guarded
    가 감싸 부른다. fetch_evidence(diagnose)와 check_ablation(retry) 다음에 오고, 다음 노드는
    ablate 다. 실행 오류 없이 결과만 다른 사전 검증이라 실행 기록이 없으면 {trace} 자리에 사전
    검증의 diagnosis(떨어진 기준 주문의 실패 사유)를 넣는다. 이 결과 불일치
    진단(_mismatch_path)에서는 가설이 없어도 ERROR 로 멈추지 않고 빈 목록을 남겨 ablate 가
    VALIDATION_FAILED 로 끝내게 한다(V3-8).
    """
    trace = state.get("trace") or state.get("diagnosis") or {}
    retry = state.get("hypothesis_retry", 0)
    previous_failures = _retry_feedback(
        retry, state.get("ablation_runs"), _rejected_hypothesis_lines
    )
    references, reference_ids = rag.references(state)
    result = await _ask_llm(
        "hypothesize",
        _HypothesesOutput,
        references=references,
        trace=trace,
        diff=state.get("changes", []),
        previous_failures=previous_failures,
    )

    hypotheses: list[Hypothesis] = [
        Hypothesis(change_no=h.change_no, summary=h.summary, confidence=h.confidence)
        for h in (result.hypotheses if result else [])
    ]
    if not hypotheses and not _mismatch_path(state):
        raise ContractError("LLM returned no hypotheses")

    message = f"가설 {len(hypotheses)}개 생성 (retry={retry})"
    return _progress(
        state, "hypothesize", message, hypotheses=hypotheses,
        **_rag_progress(state, "hypothesize", reference_ids),
    )


# ── Node 4: ablate ────────────────────────────────────────────────────────────


async def _reproduce_failure(
    t, snapshot_id: str, repro_ids: list[str], trace: dict | None, mismatch: bool = False
) -> list[str] | None:
    """수정 없는 후보에서 같은 오류 category 가 다시 나는지 보고 control 실행 ID 를 돌려준다.

    대조 실험(control)이다. 수정 없는 후보 snapshot workspace 에서 재현 시나리오를 돌려 모두
    통과하면 원인 실험의 기준이 사라지므로 ContractError 로 멈춘다. 결과 불일치 진단(mismatch 가
    참)이면 사전 검증 뒤 결과가 흔들린 것이므로 멈추지 않고 None 을 돌려주고, ablate 가
    VALIDATION_FAILED 로 끝낸다(V3-8). 실패 실행으로 시작한 진단(trace
    가 있음)은 원래 실행의 EXECUTION_ERROR category 가 실패 사유에 없으면 다른 오류가 재현된 것으로
    보고 멈춘다. category 는 통과 여부보다 먼저 읽으므로, 실행 기록에 EXECUTION_ERROR 가 없으면
    skillops-agent/skillops_agent/contracts.py 의 event_data 가 'trace event.data must be an object'
    ContractError 를 먼저 던진다. 실행 오류 없이 결과만 다른 기준 주문을 재현으로 쓰는 진단은 trace
    가 없어 category 대조를 건너뛴다. 원인 실험은 의도 expectations 를 붙이지 않는다. 되돌린
    기준본도 의도를 실현하지 않으므로 붙이면 원인을 확정할 수 없다. 돌려주는 control 실행 ID 는
    root_cause.proof_run_ids 의 앞부분이 된다. 반복 횟수는 skillops-agent/skillops_agent/config.py
    의 ABLATION_REPEAT 이다. ablate 가 부른다.
    """
    control = await t.create_workspace(snapshot_id=snapshot_id)
    control_results = await _run_scenarios(t, control["workspace_id"], repro_ids, ABLATION_REPEAT)
    category = event_data(error_event(trace)).get("category") if trace else None
    if all_passed(control_results):
        if mismatch:
            return None
        raise ContractError("unmodified candidate no longer reproduces the failure")
    if category and not any(category in f["reason"] for f in failures_of(control_results)):
        raise ContractError("replay reproduced a different error category")
    return run_ids_of(control_results)


async def _ablate_change(
    t, snapshot_id: str, repro_ids: list[str], change: dict, control_ids: list[str]
) -> AblationRun:
    """변경 hunk 하나만 되돌린 새 workspace 에서 재현 시나리오를 다시 돌린다.

    실험마다 새 workspace 를 만들어 실험끼리 섞이지 않게 한다.
    skillops-agent/skillops_agent/contracts.py 의 reverse_hunk 로 뒤집은 patch 를 workspace 가
    알려 준 현재 skill 버전(base_version)으로 apply_patch 에 보낸다. 적용 응답이 다른 workspace 의
    것이거나 changed_skills 가 비면 ContractError 로 멈춘다. 재현 시나리오가 모두 통과하면
    confirmed 가 참이다. ablate 가 가설마다 부른다.
    """
    ws = await t.create_workspace(snapshot_id=snapshot_id)
    skill = change["skill"]
    patch = Patch(
        skill=skill,
        base_version=ws["skill_versions"][skill],
        patch=reverse_hunk(change["diff"]),
    )
    applied = await t.apply_patch(ws["workspace_id"], [patch])
    if applied.get("workspace_id") != ws["workspace_id"] or not applied.get("changed_skills"):
        raise ContractError("ablation patch was not applied")
    # 실험용 오류 목록의 내용은 허용하되 서버가 검사를 생략한 응답은 거부한다.
    patch_contract_ok(applied)
    patch_metadata_ok(applied)
    patch_flow_ok(applied)
    results = await _run_scenarios(t, ws["workspace_id"], repro_ids, ABLATION_REPEAT)
    return AblationRun(
        change_no=change["change_no"],
        workspace_id=ws["workspace_id"],
        run_ids=run_ids_of(results),
        control_run_ids=control_ids,
        confirmed=all_passed(results),
    )


async def ablate(state: AgentState) -> dict:
    """4번 노드다. 가설마다 원인 실험(ablation)을 한다.

    먼저 수정 없는 후보에서 실패가 재현되는지 확인하고(_reproduce_failure), 가설 순서대로 그 change
    만 되돌려 재현 시나리오를 다시 돌린다(_ablate_change). 재현 시나리오는 상태의
    repro_scenario_ids(실행 오류 없이 결과만 다른 기준 주문)가 먼저이고, 없으면
    skillops-agent/skillops_agent/contracts.py 의 replay_scenario_id 가 만드는 f-<execution> 이다.
    둘 다 없거나 가설이 모르는 change_no 를 가리키면 ContractError 로 멈춘다. 결과는 ablation_runs
    를 통째로 바꾸므로 이전 회차 실험은 남지 않는다. 결과 불일치 진단(_mismatch_path)에서 가설이
    없거나 대조 실행이 통과(결과 흔들림)하면 원인을 확정할 수 없으므로 ERROR 가 아니라
    VALIDATION_FAILED 로 끝낸다(V3-8, _validation_failed). skillops-agent/skillops_agent/graph.py 의
    FLOW 가 이 함수를 ablate 노드로 등록하고 guarded 가 감싸 부른다. 다음 노드는 check_ablation
    이다.
    """
    execution = state.get("execution_id")
    repro_ids = list(state.get("repro_scenario_ids") or [])
    mismatch = _mismatch_path(state)
    if not repro_ids:
        if not execution:
            raise ContractError("ablation requires an actual execution")
        repro_ids = [replay_scenario_id(execution)]
    if mismatch and not state["hypotheses"]:
        explanation = "원인 가설을 세우지 못해 원인 변경을 확정하지 못했습니다."
        return _progress(
            state, "ablate", "가설 없음, 원인 실험 중단", ablation_runs=[],
            **_validation_failed(state, explanation),
        )
    runs = []
    async with get_tools() as t:
        control_ids = await _reproduce_failure(
            t, state["snapshot_id"], repro_ids, state.get("trace"), mismatch
        )
        if control_ids is None:
            explanation = (
                "수정본을 다시 돌리자 그 기준 주문이 통과해 결과가 흔들리므로 원인 변경을 확정하지 "
                "못했습니다."
            )
            return _progress(
                state, "ablate", "대조 실행 통과(결과 흔들림), 원인 실험 중단", ablation_runs=[],
                **_validation_failed(state, explanation),
            )
        for hypothesis in state["hypotheses"]:
            change = _change_by_no(state["changes"], hypothesis["change_no"])
            if change is None:
                raise ContractError("hypothesis references an unknown change_no")
            runs.append(
                await _ablate_change(t, state["snapshot_id"], repro_ids, change, control_ids)
            )
    return _progress(state, "ablate", f"실제 hunk 복원 실험 {len(runs)}개", ablation_runs=runs)


# ── Node 5: check_ablation (라우터) ──────────────────────────────────────────


def _root_cause(
    change: dict, run: AblationRun, skill_versions: dict, repro_ids: list[str] | None = None
) -> dict:
    """확정된 변경과 그 실험으로 root_cause 를 만든다. 키 순서는 프롬프트·제안서에 드러난다.

    proof_run_ids 는 대조 실행(control) 뒤에 원인을 되돌린 실행을 이어 붙인다. from_version 과
    to_version 은 둘 다 후보 snapshot 의 skill 버전(skill_versions)이며 기준 snapshot 버전을 따로
    읽지 않는다. summary 는 hunk 의 before 와 after 를 잇는다. 기준 주문을 재현으로 쓴 원인
    실험(repro_ids)이면 kind=change 와 repro_scenario_ids 를 덧붙여 service 가 fixes_failure 를 그
    주문으로 다시 계산하게 한다. f-<실행> 재현이면 두 칸을 넣지 않는다(service 는 장부의 f-
    시나리오를 쓴다). check_ablation 이 부른다.
    """
    root_cause = {
        "skill": change["skill"],
        "change_no": change["change_no"],
        "hunk_id": change["hunk_id"],
        "from_version": skill_versions[change["skill"]],
        "to_version": skill_versions[change["skill"]],
        "summary": change["before"] + " → " + change["after"],
        "proof_run_ids": run["control_run_ids"] + run["run_ids"],
    }
    if repro_ids:
        root_cause.update(kind="change", repro_scenario_ids=list(repro_ids))
    return root_cause


def _mismatch_path(state: AgentState) -> bool:
    """실패 실행 없이(execution_id 없음) 기준 주문을 재현으로 쓰는 결과 불일치 진단인지 본다.

    hypothesize, ablate, _mismatch_exhausted 가 이 진단에서만 ERROR 대신 VALIDATION_FAILED 로
    끝낸다(V3-8).
    """
    return not state.get("execution_id") and bool(state.get("repro_scenario_ids"))


def _validation_failed(state: AgentState, reason: str) -> dict:
    """결과 불일치 원인 실험이 원인을 확정하지 못하고 끝날 때 남기는 상태 칸이다.

    diagnosis_status 는 VALIDATION_FAILED 이고, diagnosis 는 공통 설명 뒤에 reason 을 잇고 사전
    검증의 실패 목록을 그대로 둔다. 사전 검증 전 의도 추론 실패나 담당자 조건을 뺀
    설명(intent_note)은 결론이 바뀌어도 남긴다. 도구가 필요한 수정(finding=NEEDS_TOOL)이면 finding
    과 그 설명도 남겨 skillops-agent/skillops_agent/graph.py 의 route_ablation 이 개발 요청서 저장
    (needs_tool)으로 보낸다. ablate 와 check_ablation 이 부른다.
    """
    previous = state.get("diagnosis") or {}
    diagnosis = {
        "explanation": (
            "기대 결과와 실제 업무 결과가 다릅니다. 실행 오류 없이 끝났고, " + reason
        ),
        "failures": previous.get("failures", []),
    }
    note = {
        key: previous[key] for key in ("intent_note", "intent_dropped_checks") if key in previous
    }
    if note.get("intent_note"):
        diagnosis = _with_intent_note(diagnosis, note)
    if previous.get("finding") == NEEDS_TOOL:
        # 도구가 필요한 수정은 원인을 확정하지 못해도 개발 요청서를 저장하므로 finding 을 남긴다.
        diagnosis = _needs_tool_diagnosis(diagnosis, state.get("capability_gaps") or [])
    return {"diagnosis_status": DiagnosisStatus.VALIDATION_FAILED, "diagnosis": diagnosis}


def _mismatch_exhausted(state: AgentState, retry: int) -> bool:
    """결과 불일치 원인 실험이 원인을 확정하지 못한 채 끝나는지 본다.

    결과 불일치 진단(_mismatch_path)에서, 이번 기각으로 hypothesis_retry 가 config 의
    MAX_HYPOTHESIS_RETRIES 에 닿거나 step_count 가 max_steps 에 닿으면 참이다. 판정식은
    skillops-agent/skillops_agent/graph.py 의 route_ablation 과 _step_limit_reached 와 같다.
    check_ablation 이 부른다.
    """
    if not _mismatch_path(state):
        return False
    steps = state.get("step_count", 0) + 1
    return retry >= MAX_HYPOTHESIS_RETRIES or steps >= state.get("max_steps", MAX_STEPS_DEFAULT)


def check_ablation(state: AgentState) -> dict:
    """5번 노드다. 원인 실험 결과를 분기 판정에 쓸 값으로 바꾼다.

    통과한 첫 실험(_confirmed_run)이 있으면 root_cause 를 남기고, 없으면 hypothesis_retry 를 1
    올린다. 실행 오류 없이 결과만 다른 기준 주문으로 한 원인 실험이 한도까지 원인을 확정하지
    못하면(_mismatch_exhausted) diagnosis_status 를 VALIDATION_FAILED 로 바꿔 제안서 없이 끝낸다.
    skillops-agent/skillops_agent/graph.py 의 FLOW 가 이 함수를 check_ablation 노드로 등록하고
    guarded 가 감싸 부른다. 다음 노드는 같은 파일의 route_ablation 이 정한다. root_cause 가 있으면
    extract_intent, 진단 상태가 RUNNING 이 아니면 END, 재시도 한도나 단계 한도에 닿으면
    save_proposal, 아니면 hypothesize 로 간다. 도구가 필요한 수정(finding=NEEDS_TOOL)이면 원인
    확정과 진단 종료 모두 save_proposal 로 간다. 재시도 한도는
    skillops-agent/skillops_agent/config.py 의 MAX_HYPOTHESIS_RETRIES 다. ablate 가 이미
    VALIDATION_FAILED 로 원인 실험을 멈췄으면(가설 없음, 대조 실행 흔들림) 상태를 바꾸지 않고 진행
    기록만 남긴다.
    """
    if state.get("diagnosis_status", DiagnosisStatus.RUNNING) != DiagnosisStatus.RUNNING:
        return _progress(state, "check_ablation", "원인 실험 중단")
    run = _confirmed_run(state["ablation_runs"])
    if run is None:
        retry = state["hypothesis_retry"] + 1
        if _mismatch_exhausted(state, retry):
            reason = "변경을 하나씩 되돌린 원인 실험으로도 원인 변경을 확정하지 못했습니다."
            return _progress(
                state,
                "check_ablation",
                "가설 기각",
                hypothesis_retry=retry,
                **_validation_failed(state, reason),
            )
        return _progress(state, "check_ablation", "가설 기각", hypothesis_retry=retry)
    # 없으면 StopIteration 이 난다(리팩토링 전 코드와 같다). ablate 가 change_no 를 먼저 검사하므로
    # 그래프 실행에서는 닿지 않는다.
    change = next(c for c in state["changes"] if c["change_no"] == run["change_no"])
    return _progress(
        state,
        "check_ablation",
        f"원인 실험 통과: {change['skill']}",
        root_cause=_root_cause(
            change, run, state["skill_versions"], state.get("repro_scenario_ids")
        ),
    )


# ── Node 6: extract_intent ────────────────────────────────────────────────────


async def _known_values(state: AgentState) -> list[str]:
    """확인 조건에 쓸 수 있는 값 이름을 모은다.

    세 곳을 합쳐 정렬한다.
    1. 원인을 되돌려 통과한 ablation 실험의 첫 실행 기록. 실패한 실행에는 고장 난 단계 뒤의 값이
       없으므로 get_trace 로 읽어 skillops-agent/skillops_agent/contracts.py 의 context_keys 로
       모은다. 통과 실험이 없으면(의도 미실현 경로 등) 원래 실행 기록에서 모은다.
    2. 수정본 snapshot 의 모든 단계가 선언한 inputs 와 outputs(list_skills). 실행 기록만 보면
       담당자가 새로 쓴 값 이름이 버려진다(계획 1절의 D1).
    3. C 가 채우는 예약 키(contracts.RESERVED_KEYS: order.final_status, order.shipments).
    extract_intent 가 프롬프트의 {context_keys} 와 조건 사전 검사에 쓴다.
    """
    passing = _confirmed_run(state.get("ablation_runs", []))
    snapshot_id = state.get("snapshot_id")
    async with get_tools() as t:
        if passing is None:
            names = set(context_keys(state.get("trace") or {}))
        else:
            names = set(context_keys(await t.get_trace(passing["run_ids"][0])))
        if snapshot_id:
            names.update(_declared_names(await t.list_skills(snapshot_id)))
    return sorted(names | set(RESERVED_KEYS))


def _split_checks(checks: list[str], known: list[str]) -> tuple[list[str], list[dict]]:
    """C 가 읽을 수 있는 조건과 버릴 조건({check, reason})을 나눈다.

    LLM 이 낸 순서대로 skillops-agent/skillops_agent/contracts.py 의 check_problem 으로 한 줄씩
    검사한다. 이유 문구는 제안서의 dropped_checks 에 남는다. extract_intent 가 부른다.
    """
    known_keys = set(known)
    kept, dropped = [], []
    for check in checks:
        problem = check_problem(check, known_keys)
        if problem:
            dropped.append({"check": check, "reason": problem})
        else:
            kept.append(check)
    return kept, dropped


def _scope_problem(scope) -> str | None:
    """expectation 의 scope 를 쓸 수 있으면 None, 아니면 버리는 이유다.

    scope 는 null 이거나 시험 주문의 처음 값(contracts.SCOPE_KEYS 다섯 키 그대로, 하위 경로 아님)을
    보는 읽을 수 있는 조건이어야 한다. Agent 가 만든 것은 _split_expectations, 담당자가 준 것은
    _owner_expectations 가 부른다.
    """
    if scope is None:
        return None
    problem = condition_problem(scope)
    if problem:
        return problem
    if skill_rules.condition_key(scope) not in SCOPE_KEYS:
        return "scope 는 시험 주문의 처음 값(" + ", ".join(SCOPE_KEYS) + ")만 볼 수 있습니다"
    return None


def _split_expectations(items, known: list[str]) -> tuple[list[dict], list[dict]]:
    """Agent 가 만든 expectations 에서 C 가 읽을 수 있는 것과 버릴 조건을 나눈다.

    scope 를 읽을 수 없으면 그 expectation 을 통째로 버리고, checks 는 _split_checks 로 거른다. 남은
    checks 가 없는 expectation 도 버린다. 버린 것은 {check, reason} 모양으로 dropped_checks 에
    남기며, scope 를 버리면 check 칸에 'scope: <scope>' 를 쓴다. 담당자가 준 expectations 는 값
    이름을 보지 않는 _owner_expectations 가 따로 거른다. _usable_expectations 가 부른다.
    """
    kept, dropped = [], []
    for item in items or []:
        scope = getattr(item, "scope", None)
        problem = _scope_problem(scope)
        if problem:
            dropped.append({"check": f"scope: {scope}", "reason": problem})
            continue
        checks, bad = _split_checks(list(getattr(item, "checks", None) or []), known)
        dropped.extend(bad)
        if checks:
            kept.append({"scope": scope, "checks": checks})
    return kept, dropped


def _owner_intent_value(owner: dict) -> Intent:
    """담당자가 준 expectations 를 그대로 쓴 intent 다. LLM 을 부르지 않는다.

    owner 는 _owner_intent 가 거른 값이라 expectations 에는 쓸 수 있는 것만 있고, 읽을 수 없어 뺀
    것은 dropped_checks 로 옮긴다. checks 칸은 scope 없는 expectation 의 조건을 하위 호환으로 옮겨
    둔다. extract_intent 가 부른다.
    """
    expectations = [
        {"scope": e.get("scope"), "checks": list(e.get("checks") or [])}
        for e in owner.get("expectations") or []
    ]
    return Intent(
        text=owner["text"],
        checks=_unscoped_checks(expectations),
        dropped_checks=list(owner.get("dropped") or []),
        confirmed=None,
        source="owner",
        expectations=expectations,
    )


async def extract_intent(state: AgentState) -> dict:
    """6번 노드다. 변경 의도를 정하고 C 가 검사할 수 있는 확인 조건만 남긴다.

    요청에 담당자 expectations 가 있으면 LLM 을 부르지 않고 그대로 쓴다(_owner_intent_value, 출처
    owner). fetch_evidence 가 사전 검증 전에 의도 글로 expectations 를 만들어 두었으면 그 intent
    (출처 inferred)를 그대로 쓰고 LLM 을 다시 부르지 않는다. 아니면
    skillops-agent/prompts/extract_intent.md 에 담당자 의도 글, root_cause, diff, 쓸
    수 있는 값 이름(_known_values), 문체 규칙(skillops-agent/prompts/style.md)을 넣어 _IntentOutput
    으로 받는다(출처 inferred). 조건은 _split_checks 로 걸러 checks 와 dropped_checks 로 남긴다.
    요청에 의도 글이 있으면 LLM 의 expectations 와, scope 없는 checks 를 모은 scope 없는 expectation
    하나를 _usable_expectations 로 거르고 상한(10개, 항목마다 checks 10개)을 넘는 것을 버린다. 남은
    것은 _guard_inferred 로 최소 요건(V3-4)을 보고, 하나도 남지 않으면 diagnosis 에 intent_note 를
    남긴다(빈 의도라 keeps_intent 가 거짓이 된다). 의도가 없는 요청은 expectations 를 비워 두고
    checks 만 예전처럼 재현 시나리오의 extra_checks 로 쓴다. text 는 의도 글이 있으면 그 글이고,
    없고 LLM 이 아무것도 돌려주지 않으면 '의도 추출 실패'로 두고 조건 없이 진행한다. confirmed 는
    None 으로 두고, 담당자가 승인하면 service 가 저장된 제안서에서 True 로 바꾼다.
    skillops-agent/skillops_agent/graph.py 의 FLOW 가 이 함수를 extract_intent 노드로 등록하고
    guarded 가 감싸 부른다. check_ablation(원인 확정)이나 fetch_evidence(의도 미실현) 다음에 오고,
    다음 노드는 generate_candidates 다.
    """
    owner = _owner_intent(state)
    if owner and owner.get("expectations"):
        intent = _owner_intent_value(owner)
        message = f"담당자 의도 사용 (기대 {len(intent['expectations'])}개)"
        return _progress(state, "extract_intent", message, intent=intent)
    inferred = state.get("intent") or {}
    if owner and inferred.get("source") == "inferred" and inferred.get("expectations"):
        message = (
            f"사전 검증 전에 추론한 의도 사용 (기대 {len(inferred['expectations'])}개, "
            f"버린 조건 {len(inferred.get('dropped_checks') or [])}개)"
        )
        return _progress(state, "extract_intent", message, intent=inferred)

    diff = state.get("diff") or {}
    root_cause = state.get("root_cause") or {}
    known = await _known_values(state)
    references, reference_ids = rag.references(state)
    result = await _ask_llm(
        "extract_intent",
        _IntentOutput,
        references=references,
        owner_intent=owner["text"] if owner else "(없음)",
        root_cause=root_cause,
        diff=diff,
        context_keys=", ".join(known) or "(없음)",
        with_style=True,
    )

    # C 가 읽지 못하는 조건이 하나라도 있으면 run_scenarios 전체가 거절된다.
    # 그래서 미리 걸러서 버린 조건을 이유와 함께 남긴다.
    kept, dropped = _split_checks(result.checks if result else [], known)
    expectations = []
    updates = {}
    if owner:
        items = list(getattr(result, "expectations", None) or [])
        if kept:
            items.append(_ExpectationItem(scope=None, checks=kept))
        # 상한을 넘는 intent_expectations 는 service 가 INVALID_INPUT 으로 거절하므로 사전 검증 전
        # 추론과 같은 규칙으로 거르고 자른다.
        expectations, dropped_scoped = _usable_expectations(items, known)
        dropped = list(owner.get("dropped") or []) + dropped + dropped_scoped
        reason = None
        if expectations:
            async with get_tools() as t:
                expectations, guarded, reason = await _guard_inferred(
                    t, state.get("baseline_snapshot_id"), expectations
                )
            dropped += guarded
        if not expectations:
            reason = reason or "LLM 이 쓸 수 있는 기대 결과를 내지 않았습니다"
            note = _intent_note(reason, dropped, preflight=False)
            updates["diagnosis"] = _with_intent_note(state.get("diagnosis"), note)
        kept = _unscoped_checks(expectations)

    intent = Intent(
        text=owner["text"] if owner else (result.text if result else "의도 추출 실패"),
        checks=kept,
        dropped_checks=dropped,
        confirmed=None,
        source="inferred",
        expectations=expectations,
    )

    message = f"의도 추출 완료 (조건 {len(kept)}개, 버린 조건 {len(dropped)}개)"
    if owner:
        message = f"의도 추출 완료 (기대 {len(expectations)}개, 버린 조건 {len(dropped)}개)"
    return _progress(
        state, "extract_intent", message, intent=intent, **updates,
        **_rag_progress(state, "extract_intent", reference_ids),
    )


# ── Node 7: generate_candidates ──────────────────────────────────────────────


def _failed_candidate_lines(candidates: list[Candidate]) -> list[str]:
    """무효 후보의 검증 결과를 재시도 프롬프트에 넣을 줄로 바꾼다.

    직전 회차의 무효 후보마다 fixes_failure, keeps_old_orders, keeps_intent, validation_error 를 한
    줄에 적어 LLM 이 같은 실수를 되풀이하지 않게 한다. 형식 오류나 적용 거절로 results 가 없는
    후보는 값이 None 으로 찍힌다. generate_candidates 가 부른다.
    """
    lines = []
    for c in candidates:
        r = c.get("results") or {}
        if not c.get("valid"):
            lines.append(
                f"{c['candidate_no']}({c['kind']}): fixes_failure={r.get('fixes_failure')}, "
                f"keeps_old_orders={r.get('keeps_old_orders')}, "
                f"keeps_intent={r.get('keeps_intent')}, error={c.get('validation_error')}"
            )
    return lines


async def _repair_inputs(t, state: AgentState) -> tuple[dict, dict]:
    """후보 생성에 넣을 의존성 그래프와 고정 snapshot 의 skill 원문을 읽는다.

    MCP get_dependency_graph 도구
    (service/src/main/java/com/capstone4/skillops/mcp/DependencyGraphTools.java)는 trigger 의
    entry_skill_id 에서 시작하고, 값이 없으면 skillops-agent/skillops_agent/config.py 의
    DEFAULT_ENTRY_SKILL_ID 를 쓴다. skill 원문은 skill_versions 의 모든 skill 을 후보 snapshot 에서
    get_skill(주문 Agent 조회 API)로 읽는다. 원문이 고정 snapshot 의 것이 아니거나 글이 아니면
    ContractError 로 멈춘다. LLM 이 오래된 diff 가 아니라 지금 원문을 고치게 하려는 것이다. 돌려주는
    값은 (의존성 그래프, {skill: {content, version}}) 이다. generate_candidates 가 부른다.
    """
    snapshot_id = state["snapshot_id"]
    dep_graph = await t.get_dependency_graph(
        snapshot_id=snapshot_id,
        entry_skill_id=state["trigger"].get("entry_skill_id", DEFAULT_ENTRY_SKILL_ID),
    )

    sources = {}
    for skill in state["skill_versions"]:
        source = await t.get_skill(snapshot_id, skill)
        if source.get("snapshot_id") != snapshot_id or not isinstance(source.get("content"), str):
            raise ContractError("candidate source does not match the fixed snapshot")
        sources[skill] = {"content": source["content"], "version": source["version"]}
    return dep_graph, sources


def _patches_from_spec(spec: _CandidateSpec, sources: dict) -> tuple[list[Patch], str | None]:
    """수정 후 원문을 현재 원문 기준 patch 로 바꾼다. 형식 오류면 앞의 patch 와 문구를 준다.

    LLM 은 수정 후 전체 원문(full_text)만 내고, patch 는 B 가
    skillops-agent/skillops_agent/contracts.py 의 unified_patch 로 고정 snapshot 원문 기준으로
    만든다. 모르는 skill 이나 같은 skill 을 두 번 고친 후보, '---' 머리말로 시작하지 않는 원문,
    현재 원문과 달라진 내용이 없는 후보는 형식 오류 문구를 함께 돌려준다. 형식 오류가 나면 그때까지
    만든 patch 만 남긴다. 형식 오류 후보는 _verify_candidate 가 workspace 없이 무효로 돌려준다.
    generate_candidates 가 부른다.
    """
    patches = []
    seen = set()
    for revision in spec.patches:
        if revision.skill not in sources or revision.skill in seen:
            return patches, "수정 대상이 없거나 같은 파일을 여러 번 제안했습니다."
        seen.add(revision.skill)
        source = sources[revision.skill]
        if not revision.full_text.strip() or not revision.full_text.startswith("---\n"):
            return patches, "수정 후 전체 SKILL.md 원문이 필요합니다."
        patch = unified_patch(revision.skill, source["content"], revision.full_text)
        if patch:
            patches.append(Patch(skill=revision.skill, base_version=source["version"], patch=patch))
    if not patches:
        return patches, "현재 수정본과 달라진 내용이 없는 후보입니다."
    return patches, None


def _new_candidate(
    candidate_no: int,
    kind: str,
    patches: list[Patch],
    validation_error: str | None,
    flags: dict | None = None,
) -> Candidate:
    """검증 전 후보를 만든다. 키 순서는 프롬프트·제안서에 그대로 드러난다.

    workspace, results, valid, evidence_run_ids, rank, explanation 은 비워 두고 verify_candidates 와
    rank_and_explain 이 채운다. flags 는 _candidate_flags 가 만든 {is_revert, weakens_contract} 이며
    없으면 둘 다 거짓이다. generate_candidates 가 부른다.
    """
    flags = flags or {}
    return Candidate(
        candidate_no=candidate_no,
        kind=kind,
        workspace=None,
        patches=patches,
        results=None,
        valid=None,
        evidence_run_ids=[],
        rank=None,
        explanation=None,
        is_revert=bool(flags.get("is_revert")),
        weakens_contract=bool(flags.get("weakens_contract")),
        validation_error=validation_error,
    )


async def _baseline_sources(t, state: AgentState) -> dict[str, str]:
    """담당자가 바꾼 skill 의 기준 snapshot 원문을 읽는다. is_revert 판정에 쓴다.

    바뀐 skill 은 changes 의 skill 이고, 원문은 baseline_snapshot_id 에서 get_skill 로 읽는다.
    원문이 그 snapshot 의 것이 아니거나 글이 아니면 ContractError 로 멈춘다. 변경이나 기준 snapshot
    이 없으면 빈 dict 다. generate_candidates 가 부른다.
    """
    baseline = state.get("baseline_snapshot_id")
    skills = sorted({c["skill"] for c in state.get("changes") or [] if c.get("skill")})
    texts = {}
    for skill in skills if baseline else []:
        source = await t.get_skill(baseline, skill)
        if source.get("snapshot_id") != baseline or not isinstance(source.get("content"), str):
            raise ContractError("baseline source does not match the fixed snapshot")
        texts[skill] = source["content"]
    return texts


def _candidate_flags(
    revised: dict[str, str], sources: dict, baseline: dict[str, str], state: AgentState
) -> dict:
    """후보 하나의 is_revert 와 weakens_contract 를 코드로 정한다.

    revised 는 후보가 고친 {skill: 수정 후 원문}이다. 판정 규칙은
    skillops-agent/skillops_agent/skill_rules.py 의 is_revert, weakens_contract 에 있다. 의도 scope
    키는 요청의 담당자 의도에서 읽는다. 두 값은 요청에 의도가 있을 때만 불합격 사유이며
    (_intent_kept), 의도가 없으면 판정과 순위(_rank_key)에 쓰지 않고 제안서에만 남는다.
    generate_candidates 가 부른다.
    """
    owner_changed = {c["skill"] for c in state.get("changes") or [] if c.get("skill")}
    current = {skill: source["content"] for skill, source in sources.items()}
    owner = _owner_intent(state) or {}
    expectations = (state.get("intent") or {}).get("expectations") or owner.get("expectations")
    return {
        "is_revert": skill_rules.is_revert(revised, baseline, owner_changed),
        "weakens_contract": skill_rules.weakens_contract(
            revised, current, _scope_keys(expectations or [])
        ),
    }


def _encode_rule_candidates(
    state: AgentState, sources: dict, dep_graph: dict
) -> list[tuple[list[Patch], dict[str, str]]]:
    """의도 expectations 로 결정적 encode_rule 후보의 (patch 목록, 수정 후 원문) 목록을 만든다.

    요청에 담당자 의도가 있고 쓸 expectations 가 있을 때만 만든다. expectations 는 extract_intent 가
    남긴 것(_intent_expectations: 담당자가 준 것 또는 Agent 가 의도 글로 만든 것)이 먼저이고, 없으면
    요청의 담당자 expectations 다. Agent 가 만든 것이어도 후보 검증이 실제 실행으로 판정하므로 쓴다.
    대상 skill 과 규칙은 skillops-agent/skillops_agent/skill_rules.py 의 encode_rule_revisions 가
    정한다. 대상은 최대 두 곳(바뀐 skill, scope 키를 받을 수 있는 가장 이른 단계)이고 같으면 하나다
    (V3-6). 바뀐 skill 은 root_cause 의 skill 이고 흐름 순서는 의존성 그래프의 steps 다. 만들 것이
    없으면 빈 목록이다. LLM 출력과 상관없이 결정되므로 fixed 모드 주문 Agent 와 함께 LLM 없이도 같은
    후보가 나온다(docs/plans/2026-10-26-behavior-edit-root-fix.md 0절 완료 판정 1).
    generate_candidates 가 첫 회차에만 부른다.
    """
    owner = _owner_intent(state)
    expectations = _intent_expectations(state) or list((owner or {}).get("expectations") or [])
    if not owner or not expectations:
        return []
    changed = (state.get("root_cause") or {}).get("skill")
    revisions = skill_rules.encode_rule_revisions(
        {"text": owner["text"], "expectations": expectations},
        changed,
        sources,
        list(dep_graph.get("steps") or []),
    )
    candidates = []
    for skill, text in revisions:
        patch = unified_patch(skill, sources[skill]["content"], text)
        if patch:
            patches = [Patch(skill=skill, base_version=sources[skill]["version"], patch=patch)]
            candidates.append((patches, {skill: text}))
    return candidates


async def generate_candidates(state: AgentState) -> dict:
    """7번 노드다. LLM 에게 수정 후보를 받는다.

    _repair_inputs 로 의존성 그래프와 현재 원문을 읽는다. _repair_inputs 가 원문을 먼저 검사하고, 그
    뒤 의존성 그래프가 complete 가 아니면 ContractError 로 멈춘다.
    skillops-agent/prompts/generate_candidates.md 에
    root_cause(skill_versions 포함), intent, diff, 의존성 그래프, 현재 원문, 재시도 피드백을 넣어
    _CandidatesOutput 으로 받는다. 후보마다 _patches_from_spec 으로 patch 를 만들고 candidate_no 를
    1부터 매긴다. service 의 save_proposal 은 candidate_no 가 1부터 빠짐없이 이어져야 받는다. 후보
    개수와 종류(same_skill, linked_skills, run_order, encode_rule)는 프롬프트 지시로만 정하고
    코드에는 상한이 없다. 의도 expectations 가 있으면 첫 회차에 결정적 encode_rule 후보
    (_encode_rule_candidates, 최대 두 개)를 앞 번호로 먼저 넣고, 그때는 LLM 호출이 실패해도 결정적
    후보만으로 진행한다. 후보마다 is_revert 와 weakens_contract 를 코드로 정한다(_candidate_flags,
    기준 원문은 _baseline_sources). 첫 회차에 후보가 하나도 없으면 ContractError 로 멈춘다. 재시도
    회차에 LLM 이 실패하거나 후보를 내지 않으면 ERROR 로 멈추지 않고 앞 회차의 검증한 후보를 그대로
    둔다 (V3-8). verify_candidates 는 이미 판정한 후보를 다시 돌리지 않고, 재시도 한도에 닿으면
    no_valid_fix 제안서가 된다. 재시도 회차는 candidate_retry 이며 check_valid 가 올린다.
    skillops-agent/skillops_agent/graph.py 의 FLOW 가 이 함수를 generate_candidates 노드로 등록하고
    guarded 가 감싸 부른다. extract_intent 와 check_valid(retry) 다음에 오고, 다음 노드는
    verify_candidates 다.
    """
    root_cause = state.get("root_cause") or {}
    intent = state.get("intent") or {}
    diff = state.get("diff") or {}
    retry = state.get("candidate_retry", 0)
    previous_failures = _retry_feedback(retry, state.get("candidates"), _failed_candidate_lines)

    async with get_tools() as t:
        dep_graph, sources = await _repair_inputs(t, state)
        baseline = await _baseline_sources(t, state)

    if dep_graph.get("complete") is not True:
        raise ContractError("dependency graph is incomplete")

    deterministic = _encode_rule_candidates(state, sources, dep_graph) if retry == 0 else []
    references, reference_ids = rag.references(state)
    llm_error = None
    try:
        result = await _ask_llm(
            "generate_candidates",
            _CandidatesOutput,
            references=references,
            root_cause={**root_cause, "skill_versions": state["skill_versions"]},
            intent=intent,
            diff=diff,
            dependency_graph=dep_graph,
            current_files=sources,
            previous_failures=previous_failures,
        )
    except Exception as exc:
        # 결정적 후보가 있거나 재시도 회차면 LLM 실패로 진단을 멈추지 않는다(V3-8).
        if not deterministic and retry == 0:
            raise
        result, llm_error = None, type(exc).__name__

    candidates: list[Candidate] = []
    for patches, revised in deterministic:
        candidates.append(
            _new_candidate(
                len(candidates) + 1, "encode_rule", patches, None,
                _candidate_flags(revised, sources, baseline, state),
            )
        )
    for spec in result.candidates if result else []:
        patches, validation_error = _patches_from_spec(spec, sources)
        patched = {p["skill"] for p in patches}
        revised = {r.skill: r.full_text for r in spec.patches if r.skill in patched}
        candidates.append(
            _new_candidate(
                len(candidates) + 1, spec.kind, patches, validation_error,
                _candidate_flags(revised, sources, baseline, state),
            )
        )

    if not candidates and retry > 0:
        # 재시도에서 새 후보가 없으면 앞 회차의 검증한(무효) 후보를 그대로 두고 no_valid_fix 쪽으로
        # 간다.
        reason = f"LLM 오류 {llm_error}" if llm_error else "LLM 이 새 후보를 내지 않음"
        message = f"새 후보 없음 (retry={retry}), {reason}, 앞 회차 후보 유지"
        return _progress(
            state, "generate_candidates", message,
            candidates=list(state.get("candidates") or []),
            **_rag_progress(state, "generate_candidates", reference_ids),
        )
    if not candidates:
        raise ContractError("LLM returned no candidates")

    message = f"후보 {len(candidates)}개 생성 (retry={retry})"
    if llm_error:
        message += f", LLM 오류 {llm_error} 로 결정적 후보만 사용"
    return _progress(
        state, "generate_candidates", message, candidates=candidates,
        **_rag_progress(state, "generate_candidates", reference_ids),
    )


# ── Node 8: verify_candidates ────────────────────────────────────────────────


def _check_applied(applied: dict, workspace_id: str, patches: list[Patch]) -> set[str]:
    """apply_patch 응답이 이 workspace 에 후보 patch 를 모두 적용했는지 보고 대상 skill 을 준다.

    응답이 이 workspace의 것이고 연결·필수 정보·구조 오류 목록이 있어야 한다.
    changed_skills는 후보가 고친 skill과 정확히 같아야 한다. 어긋나면 ContractError로 진단을 멈춘다.
    반환한 이름 집합의 크기가 changed_skill_count가 된다. _verify_candidate가 부른다.
    """
    if applied.get("workspace_id") != workspace_id:
        raise ContractError("invalid apply_patch response")
    patch_contract_ok(applied)
    patch_metadata_ok(applied)
    patch_flow_ok(applied)
    expected_skills = {p["skill"] for p in patches}
    if (
        not expected_skills
        or {p["skill"] for p in applied.get("changed_skills", [])} != expected_skills
    ):
        raise ContractError("candidate patches were not all applied")
    return expected_skills


def _expectation_matched(expectation: dict, result: dict, scoped_count: int) -> bool:
    """의도 범위(intent_scoped) 결과 하나가 scope 있는 expectation 하나에 맞았는지 본다.

    run_scenarios 결과는 어느 expectation 이 맞았는지 알려 주지 않으므로 기준 주문의 처음 값
    (config.GOLDEN_ORDERS)으로 scope 를 다시 평가한다. 처음 값을 모르는 시험 주문(재현 주문 f-<실행>
    등)은 scope 있는 expectation 이 하나뿐일 때만 그것에 맞은 것으로 본다. intent_scoped 는 scope 에
    맞은 expectation 이 있다는 뜻이기 때문이다. _intent_tested 가 부른다.
    """
    if not result.get("intent_scoped"):
        return False
    values = GOLDEN_ORDERS.get(result["scenario_id"], {}).get("values")
    if values is None:
        return scoped_count == 1
    return _scope_holds(expectation["scope"], values)


def _intent_tested(scenario_results: list[dict], expectations: list[dict]) -> bool:
    """의도가 실제로 시험됐는지 본다(V3-4, service 재판정과 같은 뜻).

    scope 있는 expectation 마다 그 scope 에 맞아 의도로 채점한 실행이 하나 이상 있어야 한다. scope
    가 모두 null 이면 그 checks 가 모든 실행에 더해지므로 의도와 함께 돌린 실행이 하나 이상이면
    된다. _intent_kept 가 부른다.
    """
    scoped = [e for e in expectations if e.get("scope")]
    if not scoped:
        return bool(scenario_results)
    return all(
        any(_expectation_matched(e, r, len(scoped)) for r in scenario_results) for e in scoped
    )


def _intent_kept(
    candidate: Candidate,
    scenario_results: list[dict],
    intent_results: list[dict],
    expectations: list[dict],
    intent_required: bool,
) -> bool:
    """keeps_intent 를 정한다. 요청에 담당자 의도가 있는지에 따라 식이 다르다.

    의도가 없으면 예전과 같이 확인 조건을 붙인 재현 실행(intent_results)이 모두 통과했는지이며,
    조건이 없으면 참이다. 의도가 있으면 docs/plans/2026-10-26-behavior-edit-root-fix.md 2.7·V3-4 의
    service 재판정과 같은 식이다. expectations 가 하나 이상이고, 의도가 실제로 시험됐고
    (_intent_tested), 의도 expectations 로 채점한 실행(intent_scoped 결과와 intent_results)이 모두
    통과하고, 되돌리기(is_revert)도 계약 약화(weakens_contract)도 아니어야 참이다. _verify_candidate
    가 부른다.
    """
    if not intent_required:
        return all_passed(intent_results)
    scoped = [r for r in scenario_results if r.get("intent_scoped")]
    return (
        bool(expectations)
        and _intent_tested(scenario_results, expectations)
        and all_passed(scoped)
        and all_passed(intent_results)
        and not candidate.get("is_revert")
        and not candidate.get("weakens_contract")
    )


def _judge(
    regression: list[dict],
    repro: list[dict],
    intent_results: list[dict],
    applied: dict,
    expected_skills: set[str],
    called: list[str],
    intent_ok: bool | None = None,
) -> tuple[CandidateResults, bool]:
    """후보 결과와 valid 를 정한다. valid 식은 service ProposalRules.passes 와 같아야 한다.

    fixes_failure 는 재현 시나리오가 모두 통과했는지, keeps_old_orders 는 회귀 세트의 통과 수와 전체
    수({passed, total}), keeps_intent 는 intent_ok(_intent_kept 의 판정)이고 주지 않으면 의도 조건
    실행(intent_results)이 모두 통과했는지다. no_broken_links 는
    apply_patch의 broken_links와 dependency_violations가 모두 비었는지다.
    metadata_valid와 flow_valid는 해당 오류 목록이 비었는지이며 연결 검사와 독립적이다.
    valid는 재현·회귀·의도가 통과하고 연결·필수 정보·구조 오류가 없을 때만 참이다.
    요청에 의도가 있으면 회귀 세트도 의도 expectations 로 채점한 결과다. service 는 저장할 때
    service/src/main/java/com/capstone4/skillops/proposal/ProposalRules.java 의 passes 와 장부로
    valid 를 다시 계산하고, 값이 다르면 서버 값으로 덮고 server_overrides 에 남긴다. 합격 기준을
    바꾸려면 두 곳을 함께 고친다. _verify_candidate 가 부른다.
    """
    functional = all_passed(repro)
    regression_ok = all_passed(regression)
    intent_ok = all_passed(intent_results) if intent_ok is None else intent_ok
    contract_ok = patch_contract_ok(applied)
    metadata_ok = patch_metadata_ok(applied)
    flow_ok = patch_flow_ok(applied)
    results = CandidateResults(
        fixes_failure=functional,
        keeps_old_orders={
            "passed": sum(r["passed"] for r in regression),
            "total": len(regression),
        },
        keeps_intent=intent_ok,
        no_broken_links=contract_ok,
        metadata_valid=metadata_ok,
        flow_valid=flow_ok,
        changed_skill_count=len(expected_skills),
        tools_called=called,
    )
    return (
        results,
        functional and regression_ok and intent_ok and contract_ok and metadata_ok and flow_ok,
    )


async def _verify_candidate(
    t,
    candidate: Candidate,
    snapshot_id: str,
    repro_ids: list[str],
    checks: list[str],
    expectations: list[dict] | None = None,
    intent_required: bool = False,
) -> dict:
    """후보 하나를 새 workspace 에 적용하고 회귀, 재현, 의도 순으로 실행해 판정한다.

    형식 오류 후보는 workspace 없이 무효로 돌려준다. 새 workspace 의 skill 버전이 patch 의
    base_version 과 다르면 ContractError 로 멈춘다. apply_patch 가 INVALID_INPUT 으로 거절하면 그
    후보만 무효로 두고 이유를 validation_error 에 남기며, 다른 도구 오류는 그대로 올려 진단을
    멈춘다. 후보마다 새 workspace 에 시험 전 한 번만 적용하므로 service 의 WORKSPACE_FROZEN 에
    닿지 않는다. 적용되면 회귀 세트, 재현 시나리오, 의도 조건을 붙인 재현 시나리오 순서로 돌린다.
    요청에 의도가 있으면 회귀 세트와 재현 시나리오에 expectations 를 붙이고, 의도 조건(checks)은
    따로 돌리지 않는다. 재현 시나리오가 기준 주문(결과 불일치·의도 미실현)이면 회귀 실행의 그 주문
    결과를 재현 근거로 쓰고 다시 돌리지 않는다. 회귀 세트와 반복 횟수는
    skillops-agent/skillops_agent/config.py 의 GOLDEN_IDS 와 VERIFY_REPEAT 이다. 첫 재현 시나리오의
    실행 기록에서 불린 도구 이름을 모은 뒤 _intent_kept 와 _judge 로 판정한다. evidence_run_ids 는
    돌린 실행의 실행 ID 를 모두 담고, service 가 이 workspace 의 실행 기록과 대조한다.
    verify_candidates 가 후보마다 부른다.
    """
    if candidate.get("validation_error"):
        return {**candidate, "valid": False}
    ws = await t.create_workspace(snapshot_id=snapshot_id)
    for patch in candidate["patches"]:
        if patch["base_version"] != ws["skill_versions"].get(patch["skill"]):
            raise ContractError("candidate patch base_version differs from workspace")
    try:
        applied = await t.apply_patch(ws["workspace_id"], candidate["patches"])
    except MCPToolError as exc:
        if exc.code != "INVALID_INPUT":
            raise
        return {
            **candidate,
            "workspace": ws["workspace_id"],
            "valid": False,
            "validation_error": exc.payload.get("message", str(exc)),
        }
    expected_skills = _check_applied(applied, ws["workspace_id"], candidate["patches"])
    workspace_id = ws["workspace_id"]
    regression = await _run_scenarios(
        t, workspace_id, GOLDEN_IDS, VERIFY_REPEAT, expectations=expectations
    )
    replay_ids = [i for i in repro_ids if i not in GOLDEN_IDS]
    replayed = (
        await _run_scenarios(t, workspace_id, replay_ids, VERIFY_REPEAT, expectations=expectations)
        if replay_ids
        else []
    )
    by_id = {r["scenario_id"]: r for r in regression + replayed}
    repro = [by_id[i] for i in repro_ids]
    # C 는 extra_checks 를 요청한 모든 시나리오에 적용한다. 그래서 의도 조건은 재현 시나리오에만
    # 붙여 따로 돌린다.
    intent_results = (
        await _run_scenarios(t, workspace_id, repro_ids, VERIFY_REPEAT, checks=checks)
        if checks
        else []
    )
    evidence = run_ids_of(regression + replayed + intent_results)
    called = []
    for run_id in repro[0]["run_ids"]:
        called.extend(tools_called(await t.get_trace(run_id)))
    intent_ok = _intent_kept(
        candidate, regression + replayed, intent_results, expectations or [], intent_required
    )
    results, valid = _judge(
        regression, repro, intent_results, applied, expected_skills, called, intent_ok
    )
    return {
        **candidate,
        "workspace": ws["workspace_id"],
        "results": results,
        "metadata_errors": applied["metadata_errors"],
        "flow_errors": applied["flow_errors"],
        "valid": valid,
        "evidence_run_ids": evidence,
    }


async def verify_candidates(state: AgentState) -> dict:
    """8번 노드다. 모든 후보를 차례로 격리 검증하고 candidates 를 결과로 바꾼다.

    후보마다 _verify_candidate 를 부른다. 재현 시나리오는 상태의 repro_scenario_ids 가 먼저이고,
    없으면 f-<execution_id> 다. 요청에 담당자 의도가 있으면 intent.expectations 를 회귀·재현 실행에
    붙이고(_intent_expectations) 되돌리기·계약 약화·빈 의도를 불합격으로 본다. 의도가 없으면
    예전처럼 intent.checks 를 재현 시나리오의 확인 조건으로 따로 돌린다. 판정은 C 실행 결과로만 하며
    LLM 은 부르지 않는다. 이미 판정한 후보(valid 가 None 이 아님)는 다시 돌리지 않는다. 재시도에서
    새 후보가 없어 generate_candidates 가 앞 회차 후보를 그대로 넘긴 경우다.
    skillops-agent/skillops_agent/graph.py 의 FLOW 가 이 함수를 verify_candidates 노드로 등록하고
    guarded 가 감싸 부른다. 다음 노드는 check_valid 다.
    """
    repro_ids = list(state.get("repro_scenario_ids") or [])
    if not repro_ids:
        if not state.get("execution_id"):
            raise ContractError("candidate verification requires a reproduction scenario")
        repro_ids = [replay_scenario_id(state["execution_id"])]
    intent_required = _owner_intent(state) is not None
    expectations = _intent_expectations(state)
    checks = [] if intent_required else (state.get("intent") or {}).get("checks", [])
    updated = []
    async with get_tools() as t:
        for candidate in state["candidates"]:
            if candidate.get("valid") is not None:
                updated.append(candidate)
                continue
            updated.append(
                await _verify_candidate(
                    t, candidate, state["snapshot_id"], repro_ids, checks,
                    expectations, intent_required,
                )
            )
    message = f"후보 {len(updated)}개 실제 C 계약 검증"
    return _progress(state, "verify_candidates", message, candidates=updated)


# ── Node 9: check_valid (라우터) ──────────────────────────────────────────────


def check_valid(state: AgentState) -> dict:
    """9번 노드다. 유효 후보가 있는지 진행 기록에 남기고, 없으면 candidate_retry 를 1 올린다.

    메시지의 분모는 skillops-agent/skillops_agent/config.py 의 MAX_CANDIDATE_RETRIES 다.
    skillops-agent/skillops_agent/graph.py 의 FLOW 가 이 함수를 check_valid 노드로 등록하고 guarded
    가 감싸 부른다. 다음 노드는 같은 파일의 route_valid 가 정한다. 유효 후보가 있으면
    rank_and_explain, 재시도 한도나 단계 한도에 닿으면 save_proposal, 아니면 generate_candidates 로
    간다.
    """
    valid_candidates = [c for c in state["candidates"] if c.get("valid")]
    if valid_candidates:
        return _progress(state, "check_valid", f"유효 후보 {len(valid_candidates)}개")
    retry = state["candidate_retry"] + 1
    message = f"유효 후보 없음, 재시도 {retry}/{MAX_CANDIDATE_RETRIES}"
    return _progress(state, "check_valid", message, candidate_retry=retry)


# ── Node 10: rank_and_explain ─────────────────────────────────────────────────


def _rank_key(candidate: Candidate, intent_required: bool = False) -> tuple[int, int, int, int]:
    """순위 정렬 기준이다. 되돌리기·계약 약화 여부, keeps_intent, no_broken_links(계약 일관성),
    changed_skill_count(최소성) 순서로 본다.

    요청에 의도가 있으면(intent_required) 되돌리기나 계약 약화가 아닌 후보를 앞에 두고, 그다음
    의도를 지킨 후보, 끊긴 연결이 없는 후보, 고친 skill 수가 적은 후보 순으로 앞에 둔다. 값이 같으면
    sorted 가 원래 순서(candidate_no 순)를 지킨다. rank_and_explain 은 유효 후보만 정렬하고, 요청에
    의도가 있으면 되돌리기·계약 약화 후보는 이미 무효라 순위에 들지 않는다. 의도가 없는
    요청(되돌리기가 정답일 수 있는 기존 흐름)은 되돌리기·계약 약화를 보지 않아 순위가 예전과
    같다(V3-8). 유효 후보는 keeps_intent 와 no_broken_links 가 늘 참이다. 순위 기준을 바꾸려면 이
    함수를 고친다. rank_and_explain 이 부른다.
    """
    r = candidate.get("results") or {}
    weak = intent_required and (candidate.get("is_revert") or candidate.get("weakens_contract"))
    return (
        1 if weak else 0,
        0 if r.get("keeps_intent") else 1,
        0 if r.get("no_broken_links") else 1,
        r.get("changed_skill_count", 99),
    )


def _fallback_explanation(candidate: Candidate, rank: int) -> str:
    """LLM 설명이 없을 때 근거 실행 몇 개를 인용한 설명을 만든다.

    'rank N: ' 뒤에 근거 실행 ID 를 skillops-agent/skillops_agent/config.py 의
    RANK_FALLBACK_EVIDENCE_COUNT 개까지 잇는다. skillops-agent/prompts/rank_and_explain.md 가 근거
    실행만 인용하게 하는 것과 같은 원칙이다. _apply_ranks 가 부른다.
    """
    cited = candidate.get("evidence_run_ids", [])[:RANK_FALLBACK_EVIDENCE_COUNT]
    return f"rank {rank}: {', '.join(cited) or '없음'}"


def _apply_ranks(
    candidates: list[Candidate], rank_map: dict[int, int], explanations: dict[int, str]
) -> tuple[list[Candidate], int | None]:
    """순위와 설명을 후보에 반영하고 rank 1 후보의 목록 인덱스를 돌려준다.

    돌려주는 값은 candidate_no 가 아니라 candidates 목록의 인덱스다. 순위가 없는 무효 후보는 그대로
    둔다. LLM 설명이 없거나 빈 글이면 _fallback_explanation 을 쓴다.
    돌려주는 인덱스는 상태의 recommended 가 되고 proposal_payload 가 이 인덱스로
    recommended_candidate_no 를 찾는다. rank_and_explain 이 부른다.
    """
    updated = list(candidates)
    recommended: int | None = None
    for i, c in enumerate(updated):
        cno = c["candidate_no"]
        if cno in rank_map:
            explanation = explanations.get(cno) or _fallback_explanation(c, rank_map[cno])
            updated[i] = {**c, "rank": rank_map[cno], "explanation": explanation}
            if rank_map[cno] == 1:
                recommended = i
    return updated, recommended


async def rank_and_explain(state: AgentState) -> dict:
    """10번 노드다. 유효 후보에 순위를 매기고 설명을 붙인다.

    _rank_key 로 정렬해 rank 를 1부터 매기고, skillops-agent/prompts/rank_and_explain.md 에
    root_cause, intent, 순위를 붙인 유효 후보, 문체 규칙을 넣어 _RankExplanationsOutput 으로 설명을
    받는다. LLM 은 순위를 바꾸지 못하고 설명만 쓴다. LLM 설명이 없거나 비면 근거 실행을 인용한
    설명으로 바꾼다(_apply_ranks). LLM 호출이 실패해도 설명이 없는 것으로 보고 같은 대체 설명을
    쓴다. 순위와 합격은 코드가 정하므로 LLM 없이도 제안서를 저장할 수 있다.
    skillops-agent/skillops_agent/graph.py 의 FLOW 가 이 함수를 rank_and_explain 노드로 등록하고
    guarded 가 감싸 부른다. 다음 노드는 save_proposal 이다.
    """
    valid = [c for c in state["candidates"] if c.get("valid")]

    # (의도가 있으면) 되돌리기·계약 약화가 아닌 후보, 의도 통과, 계약 일관성, 최소성 순서로
    # 정렬한다.
    intent_required = _owner_intent(state) is not None
    ranked = sorted(valid, key=lambda c: _rank_key(c, intent_required))
    rank_map = {c["candidate_no"]: i + 1 for i, c in enumerate(ranked)}

    # 순위는 코드가 정했고, LLM 에게는 explanation 만 받는다.
    llm_error = None
    try:
        result = await _ask_llm(
            "rank_and_explain",
            _RankExplanationsOutput,
            root_cause=state.get("root_cause") or {},
            intent=state.get("intent") or {},
            candidates=[{**c, "rank": rank_map.get(c["candidate_no"])} for c in ranked],
            with_style=True,
        )
    except Exception as exc:
        result, llm_error = None, type(exc).__name__
    explanation_map = {
        e.candidate_no: e.explanation for e in (result.explanations if result else [])
    }

    updated, recommended = _apply_ranks(state["candidates"], rank_map, explanation_map)
    message = f"순위 매기기 완료, recommended={recommended}"
    if llm_error:
        message += f", LLM 오류 {llm_error} 로 근거 실행 설명 사용"
    return _progress(
        state, "rank_and_explain", message, candidates=updated, recommended=recommended
    )


# ── Node 11: save_proposal ────────────────────────────────────────────────────


def _failure_of(trace: dict | None) -> dict | None:
    """실행 기록의 EXECUTION_ERROR 를 제안서의 failure 묶음으로 옮긴다. 오류가 없으면 None 이다.

    첫 EXECUTION_ERROR 이벤트로 execution_id, skill, category, message, missing_inputs, at 을
    채운다. skill 은 이벤트 data 의 skill_id 를 먼저 쓰고, 없으면 이벤트의 skill_id 를 쓴다. trace
    가 없거나 오류 이벤트가 없으면 None 이다. proposal_payload 가 부른다.
    """
    event = error_event(trace or {})
    if not event:
        return None
    data = event_data(event)
    return {
        "execution_id": (trace or {}).get("execution_id"),
        "skill": data.get("skill_id") or event.get("skill_id"),
        "category": data.get("category"),
        "message": data.get("message"),
        "missing_inputs": data.get("missing_inputs", []),
        "at": event.get("at"),
    }


def proposal_payload(state: AgentState) -> dict:
    """save_proposal 로 보낼 제안서를 상태에서 만든다.

    합격 판정은 service 가 다시 하고, 제안서 본문은 service 가 쓴다. 제안서 모양은
    docs/contracts/proposal.schema.json(계약 4)이고, 도구 입출력은
    docs/contracts/mcp-tools-input-output.md 의 save_proposal 절이다. thread_id 는
    pipeline_thread_id 이며 service 가 같은 진단의 중복 저장을 막는 데 쓴다. root_cause 에는 해당
    change 의 before 와 after 를 덧붙이고, 후보의 workspace 는 workspace_id 로 이름을 바꾼다(원래
    상태는 고치지 않는다). recommended 인덱스는 recommended_candidate_no 로 바꾸고, 유효 후보가
    하나라도 있으면 status 를 proposed, 없으면 no_valid_fix 로 둔다. 상태에 개발 요청서 초안
    (development_request)이 있으면 임시 대안 근거(_interim_proof)를 채워 마지막 칸으로 더한다(2.11).
    없으면 칸 자체를 넣지 않는다. service 쪽 처리는
    service/src/main/java/com/capstone4/skillops/proposal/ProposalService.java 의 save 에서
    시작하고, 거부 규칙은
    service/src/main/java/com/capstone4/skillops/proposal/ProposalValidator.java 에 있다.
    save_proposal 노드가 부르고, skillops-agent/tests/test_flow_characterization.py 가 직접 불러
    경계 값을 고정한다.
    """
    root_cause = state.get("root_cause")
    if root_cause:
        # changes 가 비면 change_no 를 읽지 않는다(리팩토링 전 코드와 같은 지연 평가).
        change = next(
            (c for c in state.get("changes", []) if c["change_no"] == root_cause["change_no"]), {}
        )
        root_cause = {**root_cause, "before": change.get("before"), "after": change.get("after")}
    candidates = []
    for c in state.get("candidates", []):
        candidate = {k: v for k, v in c.items() if k != "workspace"}
        candidates.append({**candidate, "workspace_id": c.get("workspace")})
    recommended = state.get("recommended")
    recommended_no = (
        candidates[recommended]["candidate_no"]
        if recommended is not None and recommended < len(candidates)
        else None
    )
    payload = {
        "thread_id": state.get("pipeline_thread_id"),
        "trigger": state["trigger"],
        "baseline_snapshot_id": state.get("baseline_snapshot_id"),
        "snapshot_id": state.get("snapshot_id"),
        "failure": _failure_of(state.get("trace")),
        "root_cause": root_cause,
        "intent": state.get("intent"),
        "candidates": candidates,
        "recommended_candidate_no": recommended_no,
        "status": "proposed" if any(c.get("valid") for c in candidates) else "no_valid_fix",
    }
    request = state.get("development_request")
    if request:
        payload["development_request"] = capability.with_proof(request, _interim_proof(state))
    return payload


def _interim_proof(state: AgentState) -> list[str]:
    """개발 요청서 임시 대안의 근거 실행이다. 원인 실험으로 확인하지 못했으면 빈 목록이다.

    확정한 원인(root_cause, kind 가 incomplete_edit 이 아님)이 빈틈 키를 들여온 변경
    (capability.offending_changes) 가운데 하나일 때만 그 원인의 proof_run_ids(대조 실행 뒤에 그
    변경을 되돌려 통과한 실행)를 쓴다. 임시 대안 patch 가 되돌리는 변경과 실험이 되돌린 변경이
    같아야 근거가 되기 때문이다. proposal_payload 가 부른다.
    """
    root_cause = state.get("root_cause") or {}
    if not root_cause or root_cause.get("kind") == "incomplete_edit":
        return []
    offending = capability.offending_changes(
        state.get("changes") or [], state.get("capability_gaps") or []
    )
    if root_cause.get("change_no") not in {c.get("change_no") for c in offending}:
        return []
    return list(root_cause.get("proof_run_ids") or [])


async def save_proposal(state: AgentState) -> dict:
    """11번 노드다. 제안서를 service 에 저장한다.

    MCP save_proposal 도구(service/src/main/java/com/capstone4/skillops/mcp/ProposalTools.java 의
    saveProposal)가 근거 실행과 합격 조건을 다시 확인한다. 저장되면 보낸 payload 에 id,
    storage_status=SAVED, service 가 다시 계산한 valid_candidate_nos 를 붙여 proposal 로 남기고
    diagnosis_status 를 EVALUATED 로 둔다. 원인을 못 찾았거나 유효 후보가 없을 때도 이 노드로 와서
    no_valid_fix 제안서를 남긴다. 도구가 필요한 수정(finding=NEEDS_TOOL)은 후보 없이 개발 요청서를
    담은 no_valid_fix 제안서로 여기에 온다. skillops-agent/skillops_agent/graph.py 의 FLOW 가 이
    함수를 save_proposal 노드로 등록하고 guarded 가 감싸 부른다. FLOW 의 마지막 노드이며 다음은
    END 다.
    """
    payload = proposal_payload(state)
    # 근거 실행과 합격 조건은 service 가 확인한다.
    # 거부되면 MCPToolError 가 그대로 올라가 ERROR 로 남는다.
    async with get_tools() as t:
        saved = await t.save_proposal(payload)
    # service 는 장부로 판정을 다시 계산해 다르면 서버 값으로 덮는다. 상태와 합격 후보 번호는 서버
    # 값을 따르고, 덮은 칸은 server_overrides 로 함께 남긴다(계획 2.7).
    proposal = {
        **payload,
        "status": saved.get("status") or payload["status"],
        "id": saved["proposal_id"],
        "storage_status": "SAVED",
        "valid_candidate_nos": saved.get("valid_candidate_nos", []),
    }
    if saved.get("server_overrides"):
        proposal["server_overrides"] = saved["server_overrides"]
    # 진단 기록의 개발 요청서(초안)도 저장한 것(임시 대안 근거를 채운 것)으로 바꾼다. 화면은 진단
    # 기록의 맨 위 칸을 먼저 읽는다(frontend/src/utils/developmentRequest.js).
    request = {"development_request": payload["development_request"]} if (
        "development_request" in payload
    ) else {}
    return _progress(
        state,
        "save_proposal",
        f"제안서 저장: {saved['proposal_id']}",
        proposal=proposal,
        diagnosis_status=DiagnosisStatus.EVALUATED,
        **request,
    )
