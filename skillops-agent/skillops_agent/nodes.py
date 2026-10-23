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
순위 기준은 _rank_key 에, 후보 합격 식은 _judge 에 있다.
"""

from dataclasses import dataclass, field
from datetime import UTC, datetime

from pydantic import BaseModel as _PydanticModel

from .config import (
    ABLATION_REPEAT,
    DEFAULT_ENTRY_SKILL_ID,
    GOLDEN_IDS,
    INTENT_CHECK_REPEAT,
    MAX_CANDIDATE_RETRIES,
    MAX_STEPS_DEFAULT,
    PREFLIGHT_REPEAT,
    RANK_FALLBACK_EVIDENCE_COUNT,
    VERIFY_REPEAT,
)
from .contracts import (
    CHECK_FAILURE_PREFIX,
    ContractError,
    all_passed,
    check_diff_evidence,
    check_manifest,
    check_problem,
    check_skill_listing,
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
# 아래 8개 Pydantic 클래스는 OpenAI 구조화 출력의 JSON schema 가 된다. 출력 클래스 네 개
# (_HypothesesOutput, _IntentOutput, _CandidatesOutput, _RankExplanationsOutput)는 _ask_llm 이
# make_structured_llm 에 넘기고, 나머지 네 개는 그 안에 들어가는 항목이다. 클래스 이름, 필드 이름,
# docstring, Field 설명이 모두 schema 에 들어가 LLM 출력을 바꾸므로 docstring 을 달지 않고 설명은
# 클래스 위 주석으로만 쓴다. 출력 클래스 이름을 바꾸면 테스트와 skillops-agent/scripts/ 의 가짜
# LLM 도 깨진다. 가짜 LLM 은 schema.__name__ 으로 고정 출력을 고른다.


# 원인 가설 하나다. change_no 는 fetch_evidence 가 붙인 변경 번호이고, ablate 가 이 번호로 되돌릴
# hunk 를 찾는다.
class _HypothesisItem(_PydanticModel):
    change_no: int  # v1 계약에서 hunk_index 가 change_no 로 바뀌었다.
    summary: str
    confidence: float


# hypothesize 노드의 LLM 출력이다. 가설 목록 순서가 원인 실험 순서가 된다.
class _HypothesesOutput(_PydanticModel):
    hypotheses: list[_HypothesisItem]


# extract_intent 노드의 LLM 출력이다. checks 는 C 조건 문법 한 줄씩이고 _split_checks 가 거른다.
class _IntentOutput(_PydanticModel):
    text: str
    checks: list[str]  # v1 계약에서 assertions 가 checks 로 바뀌었다.


# 후보가 고칠 파일 하나다. LLM 은 수정 후 전체 원문만 내고 patch 는 _patches_from_spec 이 만든다.
class _PatchSpec(_PydanticModel):
    skill: str
    full_text: str  # 수정 후 전체 파일이다. B 가 고정 원문 기준으로 patch 를 만든다.


# 수정 후보 하나다. kind 를 코드가 검사하지 않으므로 LLM 이 낸 값이 그대로 후보에 들어간다.
class _CandidateSpec(_PydanticModel):
    kind: str  # same_skill, linked_skills, run_order 가운데 하나를 기대한다.

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


async def _run_scenarios(tools, workspace_id, ids, repeat, checks=None):
    """C run_scenarios 를 부르고 결과를 계약대로 검증한다. 반복 횟수는 단계가 정한다.

    MCP run_scenarios 도구(service/src/main/java/com/capstone4/skillops/mcp/WorkspaceTools.java 의
    runScenarios)를 부르고, skillops-agent/skillops_agent/contracts.py 의 validate_report 로 응답을
    검사한 뒤 시나리오별 결과 목록을 돌려준다. ids 가 비면 도구를 부르지 않고 ContractError 를
    던진다. 빈 목록은 all_passed 에서 통과로 읽히므로 이 검사가 시나리오 없는 합격을 막는다.
    repeat 는 부르는 단계가 skillops-agent/skillops_agent/config.py 의 PREFLIGHT_REPEAT,
    INTENT_CHECK_REPEAT, ABLATION_REPEAT, VERIFY_REPEAT 가운데 하나로 정하고, checks 는 extra_checks
    로 보낸다. 사전 검증(_preflight), 의도 조건 검사(_evaluate_intent_checks), 원인 실험
    (_reproduce_failure, _ablate_change), 후보 검증(_verify_candidate)이 모두 이 함수를 거친다.
    """
    if not ids:
        raise ContractError("at least one configured scenario is required")
    report = await tools.run_scenarios(workspace_id, ids, repeat=repeat, extra_checks=checks)
    return validate_report(report, workspace_id, ids, repeat)


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


async def _ask_llm(prompt_name: str, schema, *, with_style: bool = False, **fields):
    """프롬프트를 채워 LLM 에 묻는다. make_structured_llm 은 교체되므로 호출 때 찾는다.

    skillops-agent/prompts/<prompt_name>.md 를 읽어 fields 로 {trace} 같은 자리를 str.format 으로
    채운 뒤, schema(이 모듈의 LLM 출력 Pydantic 클래스)로 구조화 출력을 받는다. with_style 이 참이면
    노드 프롬프트를 읽은 다음 skillops-agent/prompts/style.md 를 읽어 {style} 자리에 넣는다. 이 읽는
    순서는 리팩토링 전 코드와 같고, skillops-agent/tests/test_refactor_equivalence.py 가 고정한다.
    테스트와 scripts 가 이 모듈의 make_structured_llm 이름을 바꿔 끼우므로 import 시점이 아니라
    호출할 때 찾는다. 프롬프트의 자리와 fields 이름이 어긋나면 str.format 이 KeyError 를 던지고
    skillops-agent/skillops_agent/graph.py 의 guarded 가 ERROR 로 남긴다. hypothesize,
    extract_intent, generate_candidates, rank_and_explain 이 부른다.
    """
    template = load_prompt(prompt_name)
    if with_style:
        fields["style"] = load_prompt("style")
    text = template.format(**fields)
    return await make_structured_llm(schema).ainvoke(text)


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
    (BASELINE_FAILED 나 VALIDATION_FAILED)로 노드를 끝내고, validated 가 참이면 diagnosis_status 를
    VALIDATED 로 둔다. 둘 다 아니면 execution 과 trace 로 실패 진단을 이어 간다.
    """

    stop: dict | None = None  # 조기 종료 때 남길 상태(_preflight_stop 의 결과)다.
    execution: str | None = None  # 진단을 이어 갈 실패 실행 UUID 다.
    trace: dict | None = None  # 그 실행의 get_trace 응답이다.
    run_ids: list[str] = field(default_factory=list)  # 후보 snapshot 사전 검증 실행 ID 다.
    validated: bool = False  # 후보 snapshot 이 회귀 세트를 모두 통과했는지다.


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

    BASELINE_FAILED 와 VALIDATION_FAILED 가 같은 모양을 쓴다. 증거 키를 넣지 않으므로 init 이 채운
    기본값이 그대로 남는다. frontend/src/views/DiagnosisView.vue 가
    diagnosis.explanation 과 failures, preflight_run_ids 를 보여 준다. _preflight 가 부른다.
    """
    return {
        "diagnosis_status": status,
        "snapshot_id": snaps.candidate,
        "baseline_snapshot_id": snaps.baseline,
        "preflight_run_ids": run_ids,
        "diagnosis": {"explanation": explanation, "failures": failures},
    }


async def _preflight(t, snaps: _Snapshots) -> _PreflightOutcome:
    """저장 직후 사전 검증이다. 기준 세트를 먼저 보고, 후보가 실패하면 그 실행으로 진단을 잇는다.

    실행 없이 들어온 요청(type=skill_saved)에 쓴다. 회귀 세트와 반복 횟수는
    skillops-agent/skillops_agent/config.py 의 GOLDEN_IDS 와 PREFLIGHT_REPEAT 이다.
    단계는 세 가지다.
    1. 기준 snapshot workspace 에서 회귀 세트를 돌린다. 하나라도 실패하면 재고 같은 검증 환경
       문제일 수 있으므로 수정본 결함으로 판정하지 않고 BASELINE_FAILED 로 멈춘다.
    2. 후보 snapshot workspace 에서 같은 세트를 돌려 모두 통과하면 validated 로 끝낸다. 이때는
       LLM 을 부르지 않는다.
    3. 실패하면 첫 실패 실행의 trace 를 읽는다. completion 이 FAILED 가 아니면 실행 오류 없이 기대
       결과만 다른 것이라 원인을 자동으로 확정할 수 없어 VALIDATION_FAILED 로 멈춘다. FAILED 면 그
       실행이 후보 파일로 돌았는지 확인하고 그 실행을 진단 대상으로 넘긴다.
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
    results = await _run_scenarios(t, ws["workspace_id"], GOLDEN_IDS, PREFLIGHT_REPEAT)
    ids = run_ids_of(results)
    if all_passed(results):
        # trigger 에서 온 실행 번호("" 또는 None)와 trace(None)를 그대로 남긴다.
        # 리팩토링 전 코드와 같다.
        return _PreflightOutcome(
            execution=snaps.execution, trace=snaps.trace, run_ids=ids, validated=True
        )
    failed = next(result for result in results if not result["passed"])
    execution = execution_uuid(failed["failures"][0]["run_id"])
    trace = await t.get_trace(execution)
    if trace.get("completion") != "FAILED":
        return _PreflightOutcome(
            stop=_preflight_stop(
                DiagnosisStatus.VALIDATION_FAILED,
                snaps,
                ids,
                (
                    "기대 결과와 실제 업무 결과가 다릅니다. "
                    "실행 오류 없이 끝나 자동 원인 확정은 진행하지 않습니다."
                ),
                failures_of(results),
            )
        )
    # C 는 workspace 실행에 파생 snapshot 을 따로 발행한다. 그래서 snapshot ID 가 아니라 파일 해시를
    # 모두 후보 manifest 와 대조한다.
    replay_manifest = await t.get_snapshot(trace["snapshot_id"])
    if file_hashes(replay_manifest) != file_hashes(snaps.after):
        raise ContractError("preflight executed different candidate files")
    return _PreflightOutcome(execution=execution, trace=trace, run_ids=ids)


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


async def fetch_evidence(state: AgentState) -> dict:
    """2번 노드다. 진단의 증거를 모은다.

    실행과 두 snapshot 을 고정하고(_fix_snapshots), 후보 snapshot 의 skill 목록과 버전을 list_skills
    로 읽는다. 목록이 partial이면 metadata 증거를 반환하고 전용 수집 경로로 보낸다.
    완전한 목록은 check_skill_listing으로 검사하고 실제 변경을 hunk로 모은다(_collect_changes).
    실행이 없으면(type=skill_saved)
    사전 검증(_preflight)으로 BASELINE_FAILED, VALIDATION_FAILED, VALIDATED 가운데 하나로 끝내거나
    실패한 실행으로 진단을 잇는다. 변경이 없으면 NO_SKILL_CHANGES 를 남기고, diagnosis_only 요청이면
    의도 조건 검사(_evaluate_intent_checks)로 결론을 낸다.
    skillops-agent/skillops_agent/graph.py 의 FLOW 가 이 함수를 fetch_evidence 노드로 등록하고
    guarded 가 감싸 부른다. 다음 노드는 같은 파일의 route_evidence 가 정하며, diagnosis_status 가
    RUNNING 이고 changes 가 있을 때만 hypothesize 로 간다.
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
        update = {}
        execution, trace, ids = snaps.execution, snaps.trace, []
        # 실행 없이 들어온 저장 직후 요청은 사전 검증 결과로 진단을 이을지부터 정한다.
        if not execution:
            outcome = await _preflight(t, snaps)
            if outcome.stop:
                return {**progress, **outcome.stop}
            if outcome.validated:
                update.update(
                    diagnosis_status=DiagnosisStatus.VALIDATED, preflight_run_ids=outcome.run_ids
                )
            execution, trace, ids = outcome.execution, outcome.trace, outcome.run_ids
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
    ablate 다.
    """
    trace = state.get("trace") or {}
    retry = state.get("hypothesis_retry", 0)
    previous_failures = _retry_feedback(
        retry, state.get("ablation_runs"), _rejected_hypothesis_lines
    )
    result = await _ask_llm(
        "hypothesize",
        _HypothesesOutput,
        trace=trace,
        diff=state.get("changes", []),
        previous_failures=previous_failures,
    )

    hypotheses: list[Hypothesis] = [
        Hypothesis(change_no=h.change_no, summary=h.summary, confidence=h.confidence)
        for h in (result.hypotheses if result else [])
    ]
    if not hypotheses:
        raise ContractError("LLM returned no hypotheses")

    message = f"가설 {len(hypotheses)}개 생성 (retry={retry})"
    return _progress(state, "hypothesize", message, hypotheses=hypotheses)


# ── Node 4: ablate ────────────────────────────────────────────────────────────


async def _reproduce_failure(t, snapshot_id: str, repro_id: str, trace: dict) -> list[str]:
    """수정 없는 후보에서 같은 오류 category 가 다시 나는지 보고 control 실행 ID 를 돌려준다.

    대조 실험(control)이다. 수정 없는 후보 snapshot workspace 에서 재현 시나리오를 돌려 통과하면
    원인 실험의 기준이 사라지므로 ContractError 로 멈춘다. 원래 실행의 EXECUTION_ERROR category 가
    실패 사유에 없으면 다른 오류가 재현된 것으로 보고 멈춘다. category 는 통과 여부보다 먼저
    읽으므로, 실행 기록에 EXECUTION_ERROR 가 없으면 skillops-agent/skillops_agent/contracts.py 의
    event_data 가 'trace event.data must be an object' ContractError 를 먼저 던진다. 돌려주는
    control 실행 ID 는 root_cause.proof_run_ids 의 앞부분이 된다. 반복 횟수는
    skillops-agent/skillops_agent/config.py 의 ABLATION_REPEAT 이다. ablate 가 부른다.
    """
    control = await t.create_workspace(snapshot_id=snapshot_id)
    control_results = await _run_scenarios(t, control["workspace_id"], [repro_id], ABLATION_REPEAT)
    category = event_data(error_event(trace)).get("category")
    if control_results[0]["passed"]:
        raise ContractError("unmodified candidate no longer reproduces the failure")
    if category and not any(category in f["reason"] for f in control_results[0]["failures"]):
        raise ContractError("replay reproduced a different error category")
    return control_results[0]["run_ids"]


async def _ablate_change(
    t, snapshot_id: str, repro_id: str, change: dict, control_ids: list[str]
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
    results = await _run_scenarios(t, ws["workspace_id"], [repro_id], ABLATION_REPEAT)
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
    만 되돌려 재현 시나리오를 다시 돌린다(_ablate_change). 재현 시나리오 ID 는
    skillops-agent/skillops_agent/contracts.py 의 replay_scenario_id 가 만드는 f-<execution> 이다.
    실행이 없거나 가설이 모르는 change_no 를 가리키면 ContractError 로 멈춘다. 결과는 ablation_runs
    를 통째로 바꾸므로 이전 회차 실험은 남지 않는다. skillops-agent/skillops_agent/graph.py 의 FLOW
    가 이 함수를 ablate 노드로 등록하고 guarded 가 감싸 부른다. 다음 노드는 check_ablation 이다.
    """
    execution = state.get("execution_id")
    if not execution:
        raise ContractError("ablation requires an actual execution")
    repro_id = replay_scenario_id(execution)
    runs = []
    async with get_tools() as t:
        control_ids = await _reproduce_failure(t, state["snapshot_id"], repro_id, state["trace"])
        for hypothesis in state["hypotheses"]:
            change = _change_by_no(state["changes"], hypothesis["change_no"])
            if change is None:
                raise ContractError("hypothesis references an unknown change_no")
            runs.append(
                await _ablate_change(t, state["snapshot_id"], repro_id, change, control_ids)
            )
    return _progress(state, "ablate", f"실제 hunk 복원 실험 {len(runs)}개", ablation_runs=runs)


# ── Node 5: check_ablation (라우터) ──────────────────────────────────────────


def _root_cause(change: dict, run: AblationRun, skill_versions: dict) -> dict:
    """확정된 변경과 그 실험으로 root_cause 를 만든다. 키 순서는 프롬프트·제안서에 드러난다.

    proof_run_ids 는 대조 실행(control) 뒤에 원인을 되돌린 실행을 이어 붙인다. from_version 과
    to_version 은 둘 다 후보 snapshot 의 skill 버전(skill_versions)이며 기준 snapshot 버전을 따로
    읽지 않는다. summary 는 hunk 의 before 와 after 를 잇는다. check_ablation 이 부른다.
    """
    return {
        "skill": change["skill"],
        "change_no": change["change_no"],
        "hunk_id": change["hunk_id"],
        "from_version": skill_versions[change["skill"]],
        "to_version": skill_versions[change["skill"]],
        "summary": change["before"] + " → " + change["after"],
        "proof_run_ids": run["control_run_ids"] + run["run_ids"],
    }


def check_ablation(state: AgentState) -> dict:
    """5번 노드다. 원인 실험 결과를 분기 판정에 쓸 값으로 바꾼다.

    통과한 첫 실험(_confirmed_run)이 있으면 root_cause 를 남기고, 없으면 hypothesis_retry 를 1
    올린다. skillops-agent/skillops_agent/graph.py 의 FLOW 가 이 함수를 check_ablation 노드로
    등록하고 guarded 가 감싸 부른다. 다음 노드는 같은 파일의 route_ablation 이 정한다.
    root_cause 가 있으면 extract_intent, 재시도 한도나 단계 한도에 닿으면 save_proposal, 아니면
    hypothesize 로 간다. 재시도 한도는 skillops-agent/skillops_agent/config.py 의
    MAX_HYPOTHESIS_RETRIES 다.
    """
    run = _confirmed_run(state["ablation_runs"])
    if run is None:
        return _progress(
            state, "check_ablation", "가설 기각", hypothesis_retry=state["hypothesis_retry"] + 1
        )
    # 없으면 StopIteration 이 난다(리팩토링 전 코드와 같다). ablate 가 change_no 를 먼저 검사하므로
    # 그래프 실행에서는 닿지 않는다.
    change = next(c for c in state["changes"] if c["change_no"] == run["change_no"])
    return _progress(
        state,
        "check_ablation",
        f"원인 실험 통과: {change['skill']}",
        root_cause=_root_cause(change, run, state["skill_versions"]),
    )


# ── Node 6: extract_intent ────────────────────────────────────────────────────


async def _known_values(state: AgentState) -> list[str]:
    """원인을 되돌려 통과한 실행에서 값 이름을 모은다.

    정상 흐름이 실제로 만드는 값만 확인 조건에 쓸 수 있다. 실패한 실행에는 고장 난 단계 뒤의 값이
    없으므로, 통과한 ablation 실험의 첫 실행 기록을 get_trace 로 읽어
    skillops-agent/skillops_agent/contracts.py 의 context_keys 로 이름을 모은다. 그래프에서는
    root_cause 가 있어야 extract_intent 로 오므로 통과 실험이 늘 있다. 없을 때는 원래 실행 기록에서
    모은다. extract_intent 가 프롬프트의 {context_keys} 와 조건 사전 검사에 쓴다.
    """
    passing = _confirmed_run(state.get("ablation_runs", []))
    if passing is None:
        return context_keys(state.get("trace") or {})
    async with get_tools() as t:
        return context_keys(await t.get_trace(passing["run_ids"][0]))


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


async def extract_intent(state: AgentState) -> dict:
    """6번 노드다. 변경 의도를 LLM 에게 묻고 C 가 검사할 수 있는 확인 조건만 남긴다.

    skillops-agent/prompts/extract_intent.md 에 root_cause, diff, 실행 기록의 값 이름
    (_known_values), 문체 규칙(skillops-agent/prompts/style.md)을 넣어 _IntentOutput 으로 받는다.
    조건은 _split_checks 로 걸러 checks 와 dropped_checks 로 남긴다. LLM 이 아무것도 돌려주지 않으면
    text 를 '의도 추출 실패'로 두고 조건 없이 진행한다. confirmed 는 None 으로 두고, 담당자가
    승인하면 service 가 저장된 제안서에서 True 로 바꾼다. checks 는 verify_candidates 가 재현
    시나리오의 extra_checks 로 쓴다. skillops-agent/skillops_agent/graph.py 의 FLOW 가 이 함수를
    extract_intent 노드로 등록하고 guarded 가 감싸 부른다. 다음 노드는 generate_candidates 다.
    """
    diff = state.get("diff") or {}
    root_cause = state.get("root_cause") or {}
    known = await _known_values(state)
    result = await _ask_llm(
        "extract_intent",
        _IntentOutput,
        root_cause=root_cause,
        diff=diff,
        context_keys=", ".join(known) or "(없음)",
        with_style=True,
    )

    # C 가 읽지 못하는 조건이 하나라도 있으면 run_scenarios 전체가 거절된다.
    # 그래서 미리 걸러서 버린 조건을 이유와 함께 남긴다.
    kept, dropped = _split_checks(result.checks if result else [], known)

    intent = Intent(
        text=result.text if result else "의도 추출 실패",
        checks=kept,
        dropped_checks=dropped,
        confirmed=None,
    )

    message = f"의도 추출 완료 (조건 {len(kept)}개, 버린 조건 {len(dropped)}개)"
    return _progress(state, "extract_intent", message, intent=intent)


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
    candidate_no: int, kind: str, patches: list[Patch], validation_error: str | None
) -> Candidate:
    """검증 전 후보를 만든다. 키 순서는 프롬프트·제안서에 그대로 드러난다.

    workspace, results, valid, evidence_run_ids, rank, explanation 은 비워 두고 verify_candidates 와
    rank_and_explain 이 채운다. generate_candidates 가 부른다.
    """
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
        validation_error=validation_error,
    )


async def generate_candidates(state: AgentState) -> dict:
    """7번 노드다. LLM 에게 수정 후보를 받는다.

    _repair_inputs 로 의존성 그래프와 현재 원문을 읽는다. _repair_inputs 가 원문을 먼저 검사하고, 그
    뒤 의존성 그래프가 complete 가 아니면 ContractError 로 멈춘다.
    skillops-agent/prompts/generate_candidates.md 에
    root_cause(skill_versions 포함), intent, diff, 의존성 그래프, 현재 원문, 재시도 피드백을 넣어
    _CandidatesOutput 으로 받는다. 후보마다 _patches_from_spec 으로 patch 를 만들고 candidate_no 를
    1부터 매긴다. service 의 save_proposal 은 candidate_no 가 1부터 빠짐없이 이어져야 받는다. 후보
    개수와 종류(same_skill, linked_skills, run_order)는 프롬프트 지시로만 정하고 코드에는 상한이
    없다. 후보가 하나도 없으면 ContractError 로 멈춘다. 재시도 회차는 candidate_retry 이며
    check_valid 가 올린다. skillops-agent/skillops_agent/graph.py 의 FLOW 가 이 함수를
    generate_candidates 노드로 등록하고 guarded 가 감싸 부른다. extract_intent 와
    check_valid(retry) 다음에 오고, 다음 노드는 verify_candidates 다.
    """
    root_cause = state.get("root_cause") or {}
    intent = state.get("intent") or {}
    diff = state.get("diff") or {}
    retry = state.get("candidate_retry", 0)
    previous_failures = _retry_feedback(retry, state.get("candidates"), _failed_candidate_lines)

    async with get_tools() as t:
        dep_graph, sources = await _repair_inputs(t, state)

    if dep_graph.get("complete") is not True:
        raise ContractError("dependency graph is incomplete")

    result = await _ask_llm(
        "generate_candidates",
        _CandidatesOutput,
        root_cause={**root_cause, "skill_versions": state["skill_versions"]},
        intent=intent,
        diff=diff,
        dependency_graph=dep_graph,
        current_files=sources,
        previous_failures=previous_failures,
    )

    candidates: list[Candidate] = []
    for i, spec in enumerate(result.candidates if result else []):
        patches, validation_error = _patches_from_spec(spec, sources)
        candidates.append(_new_candidate(i + 1, spec.kind, patches, validation_error))

    if not candidates:
        raise ContractError("LLM returned no candidates")

    message = f"후보 {len(candidates)}개 생성 (retry={retry})"
    return _progress(state, "generate_candidates", message, candidates=candidates)


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


def _judge(
    regression: list[dict],
    repro: list[dict],
    intent_results: list[dict],
    applied: dict,
    expected_skills: set[str],
    called: list[str],
) -> tuple[CandidateResults, bool]:
    """후보 결과와 valid 를 정한다. valid 식은 service ProposalRules.passes 와 같아야 한다.

    fixes_failure 는 재현 시나리오가 모두 통과했는지, keeps_old_orders 는 회귀 세트의 통과 수와 전체
    수({passed, total}), keeps_intent 는 의도 조건 실행이 모두 통과했는지, no_broken_links 는
    apply_patch의 broken_links와 dependency_violations가 모두 비었는지다.
    metadata_valid와 flow_valid는 해당 오류 목록이 비었는지이며 연결 검사와 독립적이다.
    valid는 재현·회귀·의도가 통과하고 연결·필수 정보·구조 오류가 없을 때만 참이다.
    의도 조건이 없으면 intent_results 가 빈 목록이라 keeps_intent 가 참이
    된다. service 는 저장할 때
    service/src/main/java/com/capstone4/skillops/proposal/ProposalRules.java 의 passes 로 valid 를
    다시 계산하고, 값이 다르면 INVALID_INPUT 으로 거부한다. 합격 기준을 바꾸려면 두 곳을 함께
    고친다. _verify_candidate 가 부른다.
    """
    functional = all_passed(repro)
    regression_ok = all_passed(regression)
    intent_ok = all_passed(intent_results)
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
    t, candidate: Candidate, snapshot_id: str, repro_id: str, checks: list[str]
) -> dict:
    """후보 하나를 새 workspace 에 적용하고 회귀, 재현, 의도 순으로 실행해 판정한다.

    형식 오류 후보는 workspace 없이 무효로 돌려준다. 새 workspace 의 skill 버전이 patch 의
    base_version 과 다르면 ContractError 로 멈춘다. apply_patch 가 INVALID_INPUT 으로 거절하면 그
    후보만 무효로 두고 이유를 validation_error 에 남기며, 다른 도구 오류는 그대로 올려 진단을
    멈춘다. 적용되면 회귀 세트, 재현 시나리오, 의도 조건을 붙인 재현 시나리오 순서로 돌린다. 회귀
    세트와 반복 횟수는 skillops-agent/skillops_agent/config.py 의 GOLDEN_IDS 와 VERIFY_REPEAT 이다.
    재현 실행 기록에서 불린 도구 이름을 모은 뒤 _judge 로 판정한다. evidence_run_ids 는 세 실행의
    실행 ID 를 모두 담고, service 가 이 workspace 의 실행 기록과 대조한다. verify_candidates 가
    후보마다 부른다.
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
    regression = await _run_scenarios(t, ws["workspace_id"], GOLDEN_IDS, VERIFY_REPEAT)
    repro = await _run_scenarios(t, ws["workspace_id"], [repro_id], VERIFY_REPEAT)
    # C 는 extra_checks 를 요청한 모든 시나리오에 적용한다. 그래서 의도 조건은 재현 시나리오에만
    # 붙여 따로 돌린다.
    intent_results = (
        await _run_scenarios(t, ws["workspace_id"], [repro_id], VERIFY_REPEAT, checks=checks)
        if checks
        else []
    )
    evidence = run_ids_of(regression + repro + intent_results)
    called = []
    for run_id in repro[0]["run_ids"]:
        called.extend(tools_called(await t.get_trace(run_id)))
    results, valid = _judge(regression, repro, intent_results, applied, expected_skills, called)
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

    후보마다 _verify_candidate 를 부른다. 재현 시나리오 ID 는 f-<execution_id> 이고 의도 조건은
    intent.checks 다. 판정은 C 실행 결과로만 하며 LLM 은 부르지 않는다.
    skillops-agent/skillops_agent/graph.py 의 FLOW 가 이 함수를 verify_candidates 노드로 등록하고
    guarded 가 감싸 부른다. 다음 노드는 check_valid 다.
    """
    repro_id = replay_scenario_id(state["execution_id"])
    checks = (state.get("intent") or {}).get("checks", [])
    updated = []
    async with get_tools() as t:
        for candidate in state["candidates"]:
            updated.append(
                await _verify_candidate(t, candidate, state["snapshot_id"], repro_id, checks)
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


def _rank_key(candidate: Candidate) -> tuple[int, int]:
    """순위 정렬 기준이다. no_broken_links(계약 일관성), changed_skill_count(최소성) 순서로 본다.

    끊긴 연결이 없는 후보를 앞에 두고, 그다음 고친 skill 수가 적은 후보를 앞에 둔다. 값이 같으면
    sorted 가 원래 순서(candidate_no 순)를 지킨다. rank_and_explain 은 유효 후보만 정렬하고 유효
    후보는 no_broken_links 가 늘 참이므로, 지금은 changed_skill_count 만 순위를 가른다. 순위 기준을
    바꾸려면 이 함수를 고친다. rank_and_explain 이 부른다.
    """
    r = candidate.get("results") or {}
    return (0 if r.get("no_broken_links") else 1, r.get("changed_skill_count", 99))


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
    설명으로 바꾼다(_apply_ranks). skillops-agent/skillops_agent/graph.py 의 FLOW 가 이 함수를
    rank_and_explain 노드로 등록하고 guarded 가 감싸 부른다. 다음 노드는 save_proposal 이다.
    """
    valid = [c for c in state["candidates"] if c.get("valid")]

    # no_broken_links(계약 일관성)를 먼저, changed_skill_count(최소성)를 다음 기준으로 정렬한다.
    ranked = sorted(valid, key=_rank_key)
    rank_map = {c["candidate_no"]: i + 1 for i, c in enumerate(ranked)}

    # 순위는 코드가 정했고, LLM 에게는 explanation 만 받는다.
    result = await _ask_llm(
        "rank_and_explain",
        _RankExplanationsOutput,
        root_cause=state.get("root_cause") or {},
        intent=state.get("intent") or {},
        candidates=[{**c, "rank": rank_map.get(c["candidate_no"])} for c in ranked],
        with_style=True,
    )
    explanation_map = {
        e.candidate_no: e.explanation for e in (result.explanations if result else [])
    }

    updated, recommended = _apply_ranks(state["candidates"], rank_map, explanation_map)
    message = f"순위 매기기 완료, recommended={recommended}"
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
    """save_proposal 로 보낼 제안서(계약 5 §10)를 상태에서 만든다.

    합격 판정은 service 가 다시 하고, 제안서 본문은 service 가 쓴다. 제안서 모양은
    docs/contracts/proposal.schema.json(계약 4)이고, 도구 입출력은
    docs/contracts/mcp-tools-input-output.md 의 10절(계약 5 §10)이다. thread_id 는
    pipeline_thread_id 이며 service 가 같은 진단의 중복 저장을 막는 데 쓴다. root_cause 에는 해당
    change 의 before 와 after 를 덧붙이고, 후보의 workspace 는 workspace_id 로 이름을 바꾼다(원래
    상태는 고치지 않는다). recommended 인덱스는 recommended_candidate_no 로 바꾸고, 유효 후보가
    하나라도 있으면 status 를 proposed, 없으면 no_valid_fix 로 둔다. service 쪽 처리는
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
    return {
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


async def save_proposal(state: AgentState) -> dict:
    """11번 노드다. 제안서를 service 에 저장한다.

    MCP save_proposal 도구(service/src/main/java/com/capstone4/skillops/mcp/ProposalTools.java 의
    saveProposal)가 근거 실행과 합격 조건을 다시 확인한다. 저장되면 보낸 payload 에 id,
    storage_status=SAVED, service 가 다시 계산한 valid_candidate_nos 를 붙여 proposal 로 남기고
    diagnosis_status 를 EVALUATED 로 둔다. 원인을 못 찾았거나 유효 후보가 없을 때도 이 노드로 와서
    no_valid_fix 제안서를 남긴다. skillops-agent/skillops_agent/graph.py 의 FLOW 가 이 함수를
    save_proposal 노드로 등록하고 guarded 가 감싸 부른다. FLOW 의 마지막 노드이며 다음은 END 다.
    """
    payload = proposal_payload(state)
    # 근거 실행과 합격 조건은 service 가 확인한다.
    # 거부되면 MCPToolError 가 그대로 올라가 ERROR 로 남는다.
    async with get_tools() as t:
        saved = await t.save_proposal(payload)
    proposal = {
        **payload,
        "id": saved["proposal_id"],
        "storage_status": "SAVED",
        "valid_candidate_nos": saved["valid_candidate_nos"],
    }
    return _progress(
        state,
        "save_proposal",
        f"제안서 저장: {saved['proposal_id']}",
        proposal=proposal,
        diagnosis_status=DiagnosisStatus.EVALUATED,
    )
