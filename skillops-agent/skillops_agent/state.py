"""AgentState 및 보조 TypedDict 를 정의한다. 모든 Node 가 같은 State 를 읽고 쓴다.

skillops-agent/skillops_agent/graph.py 가 StateGraph(AgentState) 로 그래프를 만들고,
skillops-agent/skillops_agent/nodes.py 의 노드는 이 상태를 읽은 뒤 바꾼 키만 dict 로 돌려준다.
리듀서를 달지 않았으므로 LangGraph 는 노드가 돌려준 키의 값을 그대로 덮어쓴다. 그래서 events 같은
목록에 항목을 더하려면 노드가 기존 목록에 이어 붙인 새 목록을 돌려줘야 한다.
진행 중 상태와 최종 상태는 skillops-agent/skillops_agent/run_store.py 의 RunStore 가 JSON 으로
저장하고, service 의 /api/diagnoses 중계를 거쳐 frontend/src/views/DiagnosisView.vue 가 읽는다.

상태 키를 더하려면 AgentState 에 선언하고, 실행마다 초기화해야 하면 empty_run_fields 에도 넣는다.
init 노드가 돌려주는 empty_run_fields 의 키 순서가 RunStore 기록의 키 순서가 되며,
skillops-agent/tests/test_pipeline_defaults.py 와 skillops-agent/tests/test_flow_characterization.py
가 그 순서를 고정한다. TypedDict 는 타입 표시일 뿐이라 런타임에 키나 값을 검사하지 않는다.
"""

from typing import Literal, NotRequired, TypedDict


class Hypothesis(TypedDict):
    """원인 가설 하나다.

    hypothesize 노드가 LLM 출력(skillops-agent/skillops_agent/nodes.py 의 _HypothesisItem)을 옮겨
    담는다. ablate 노드가 change_no 로 되돌릴 변경을 찾고, 목록 순서대로 실험한다.
    """

    # fetch_evidence 노드가 diff_skill 의 hunk 마다 1부터 붙인 변경 번호다. v1 계약에서
    # hunk_index 가 change_no 로 바뀌었다.
    change_no: int
    summary: str
    # LLM 이 낸 확신도다. skillops-agent/prompts/hypothesize.md 가 0.0 이상 1.0 이하로 내게 하지만
    # 코드는 범위를 검사하지 않고, 판정과 정렬에도 쓰지 않는다.
    confidence: float


class AblationRun(TypedDict):
    """원인 실험(ablation) 하나의 결과다.

    ablate 노드가 change 하나를 되돌린 workspace 에서 재현 시나리오를 돌려 만든다.
    check_ablation 노드가 confirmed 로 원인을 확정하고, root_cause.proof_run_ids 는 control_run_ids
    뒤에 run_ids 를 이어 붙인다. 상태의 ablation_runs 는 ablate 노드가 회차마다 통째로 바꾼다.
    """

    # 수정 없는 후보에서 실패를 재현한 대조 실행 ID 다. 같은 회차의 실험이 모두 같은 값을 쓴다.
    control_run_ids: list[str]
    # 되돌린 변경 번호다. v1 계약에서 hunk_index 가 change_no 로 바뀌었다.
    change_no: int
    # 실험한 workspace 다. v1 계약에서 ws_id 가 workspace_id 로 바뀌었다.
    workspace_id: str
    # change 를 되돌린 뒤 재현 시나리오를 돌린 실행 ID 다.
    run_ids: list[str]
    # 이 change 를 되돌리면 재현 시나리오가 모두 통과하는지 나타낸다. True 이고 run_ids 가 있는
    # 실험 가운데 가설 순서로 첫 실험의 change 가 원인으로 확정된다.
    confirmed: bool


class RootCause(TypedDict):
    """check_ablation 노드가 확정한 원인 변경이다.

    제안서의 root_cause 가 되고 extract_intent, generate_candidates, rank_and_explain 프롬프트에
    들어간다. skillops-agent/skillops_agent/nodes.py 의 _root_cause 가 만드는 실제 dict 에는
    hunk_id 도 있고, 제안서로 보낼 때 같은 파일의 proposal_payload 가 before 와 after 를 덧붙인다.
    """

    skill: str
    # from_version 과 to_version 은 후보 snapshot 의 skill 버전이며 지금은 두 값이 같다.
    from_version: int
    to_version: int
    # v1 계약에서 hunk 가 change_no 로 바뀌었다.
    change_no: int
    summary: str
    # 원인을 증명한 실행 ID(대조 실행 뒤에 되돌린 실행)다. v1 계약에서 confirmed_by 가
    # proof_run_ids 로 바뀌었다. proposed 제안서에서는 비면 안 된다.
    # service/src/main/java/com/capstone4/skillops/proposal/ProposalValidator.java 가 빈 목록이나
    # service 가 돌리지 않은 실행이 든 목록을 거부한다.
    proof_run_ids: list[str]


class Intent(TypedDict, total=False):
    """extract_intent 노드가 남기는 변경 의도다.

    total=False 라 키가 빠져도 된다고 표시하지만 extract_intent 노드는 네 키를 모두 채운다.
    """

    # 운영자가 읽는 의도 문장이다.
    text: str
    # C 가 읽을 수 있는 확인 조건만 남긴 목록이다. 형식은 '키 op 리터럴'이고 v1 계약에서
    # assertions 가 checks 로 바뀌었다. verify_candidates 노드가 재현 시나리오의 extra_checks 로
    # 보낸다.
    checks: list[str]
    # 버린 조건과 이유를 [{check, reason}] 모양으로 담는다.
    dropped_checks: list[dict]
    # Agent 는 늘 None 으로 보낸다. 승인 화면에서 담당자가 확인하고 승인하면
    # service/src/main/java/com/capstone4/skillops/proposal/Proposal.java 의 withIntentConfirmed 가
    # 저장된 제안서에서 True 로 바꾼다.
    confirmed: bool | None


class Patch(TypedDict):
    """skill 파일 하나에 적용할 patch 다.

    ablate 노드(원인 실험)와 generate_candidates 노드(수정 후보)가 만들고, MCP apply_patch 도구
    (service/src/main/java/com/capstone4/skillops/mcp/WorkspaceTools.java 의 applyPatch)로 보낸다.
    base_version 은 patch 를 만든 기준 skill 버전이다.
    """

    skill: str
    base_version: int
    # unified diff 다. v1 계약에서 diff 가 patch 로 바뀌었다.
    patch: str


class CandidateResults(TypedDict):
    """verify_candidates 노드가 후보 하나를 실제로 돌려 얻은 검증 결과다.

    칸 이름은 docs/contracts/proposal.schema.json 의 제안서 계약과
    service/src/main/java/com/capstone4/skillops/proposal/ProposalRules.java 가 읽는 이름과 같다.
    """

    # 재현 시나리오가 모두 통과했는지다. v1 계약에서 functional 이 fixes_failure 로 바뀌었다.
    fixes_failure: bool
    # 회귀 세트 결과 {passed, total} 이다. v1 계약에서 regression 이 keeps_old_orders 로 바뀌었다.
    keeps_old_orders: dict
    # 의도 조건을 붙인 재현 실행이 모두 통과했는지다. 조건이 없으면 True 다.
    # v1 계약에서 intent 가 keeps_intent 로 바뀌었다.
    keeps_intent: bool
    # apply_patch의 broken_links와 dependency_violations가 모두 비었는지다.
    no_broken_links: bool
    # apply_patch의 필수 정보 오류 목록이 비었는지다. 연결 오류와 별개로 판정한다.
    metadata_valid: bool
    # flow_errors가 비어 실행 순서의 구조와 단계 선언 검사가 통과했는지다.
    flow_valid: bool
    # 후보가 고친 skill 수다. v1 계약에서 footprint 가 changed_skill_count 로 바뀌었다.
    changed_skill_count: int
    # 재현 실행 기록의 TOOL_REQUEST 이벤트에서 뽑은 도구 이름이다(PB-007). 재현 실행마다 모은
    # 이름을 이어 붙이므로 같은 이름이 겹칠 수 있다.
    tools_called: list[str]


class Candidate(TypedDict):
    """수정 후보 하나다.

    generate_candidates 노드가 만들고(skillops-agent/skillops_agent/nodes.py 의 _new_candidate),
    verify_candidates 노드가 workspace, results, valid, evidence_run_ids 를 채우고,
    rank_and_explain 노드가 rank 와 explanation 을 채운다. 같은 파일의 proposal_payload 가
    workspace 를 workspace_id 로 이름을 바꿔 제안서에 넣는다.
    """

    # patch 형식 오류나 apply_patch 의 INVALID_INPUT 거절 이유다. 값이 있으면 후보는 무효다.
    validation_error: str | None
    # 저장된 patch에서 발견한 필수 정보 오류다. 적용 실패 사유와 구분해 보존한다.
    metadata_errors: NotRequired[list[dict]]
    # 적용된 patch의 flow 구조 오류로 제안서에 그대로 보존한다.
    flow_errors: NotRequired[list[dict]]
    # 1부터 매기는 후보 번호다. v1 계약에서 문자열 id 가 정수 candidate_no 로 바뀌었다.
    candidate_no: int
    # v1 계약에서 local, contract, flow 가 차례로 same_skill, linked_skills, run_order 로 바뀌었다.
    # LLM 이 낸 값을 그대로 쓰므로 런타임에 이 세 값인지 검사하지 않는다.
    kind: Literal["same_skill", "linked_skills", "run_order"]
    # 검증한 workspace ID 다. 형식 오류로 검증하지 않은 후보는 None 이다.
    workspace: str | None
    patches: list[Patch]
    # verify_candidates 노드가 채운다. 형식 오류나 apply_patch 거절로 시험하지 못하면 None 이다.
    results: CandidateResults | None
    # 재현·회귀·의도·연결·필수 정보·flow 구조 검사가 모두 통과한 경우에만
    # True 다. 형식 오류나 적용 거절 후보는 False 이고, 검증 전에는 None 이다.
    valid: bool | None
    # 검증 실행 ID 전체다. v1 계약에서 evidence 가 evidence_run_ids 로 바뀌었다. 적용된 후보인데
    # 비어 있으면 service 의 save_proposal 이 INVALID_INPUT 으로 거부한다.
    evidence_run_ids: list[str]
    # rank 와 explanation 은 rank_and_explain 노드가 유효 후보에만 채운다.
    rank: int | None
    explanation: str | None


class ProgressEvent(TypedDict):
    """진행 기록 한 줄이다.

    skillops-agent/skillops_agent/nodes.py 의 _event 가 만들고 같은 파일의 _progress 가 events 에
    붙인다. frontend/src/views/DiagnosisView.vue 가 at 과 msg 를 그대로 보여 준다.
    """

    at: str  # ISO 8601 형식의 UTC 시각이다.
    node: str
    msg: str
    run_id: str | None  # 지금은 늘 None 이다.


class AgentState(TypedDict):
    """진단 한 건의 공유 상태다.

    skillops-agent/skillops_agent/main.py 의 _initial_state 가 trigger, max_steps,
    pipeline_thread_id 를 넣어 시작하고, init 노드가 나머지 키를 empty_run_fields 로 채운다.
    그 뒤 각 노드는 바꾼 키만 돌려준다.
    아래 주석의 graph.py 는 skillops-agent/skillops_agent/graph.py 이고,
    config.py 는 skillops-agent/skillops_agent/config.py 이다.
    """

    # RunStore 의 thread_id 다. 값이 있으면 graph.py 의 guarded 가 노드마다 결과를 RunStore 에
    # 저장하고, save_proposal 노드가 제안서의 thread_id 로 보낸다.
    pipeline_thread_id: str
    # ── 진입 ──────────────────────────────────────────────────────
    # skillops-agent/skillops_agent/main.py 의 DiagnoseRequest 를 model_dump 한 dict 다.
    trigger: dict
    # 기준 snapshot 이다. trigger 값이 먼저이고 없으면 후보 manifest 의 metadata 를 쓴다.
    baseline_snapshot_id: str | None
    # 진단할 실패 실행 UUID 다. 저장 직후 사전 검증에서는 실패한 첫 실행이 들어간다.
    execution_id: str | None
    # fetch_evidence 노드가 만든 변경 hunk 목록이다. change_no 는 1부터 매긴다.
    changes: list[dict]
    diagnosis_status: str  # DiagnosisStatus 값이다.
    # 사전 검증 중단이나 diagnosis_only 의 결론이다.
    diagnosis: dict | None
    # 사전 검증 실행 ID 다. 보통 후보 snapshot 실행이고, BASELINE_FAILED 면 기준 snapshot 실행이다.
    preflight_run_ids: list[str]

    # ── 증거 수집 (fetch_evidence) ────────────────────────────────
    # 진단 대상 실행의 get_trace 결과다. 사전 검증이 통과했거나 멈췄으면 None 이다.
    trace: dict | None
    # diff_skill 결과를 {files: 응답 목록, hunks: changes} 로 묶은 값이다.
    diff: dict | None
    # partial 목록에서 수집한 원문·목록 오류·manifest다. 자동 수정에는 사용하지 않는다.
    evidence: dict | None
    # 후보 snapshot 의 list_skills 응답에서 만든 {skill_id: version} 이다.
    skill_versions: dict
    # 진단한 후보 snapshot ID 다. v1 계약에서는 실행이 있으면 실행 당시 snapshot ID 다.
    snapshot_id: str | None

    # ── 가설 · ablation (PB-003) ──────────────────────────────────
    hypotheses: list[Hypothesis]
    ablation_runs: list[AblationRun]
    root_cause: RootCause | None
    # 가설이 기각될 때마다 check_ablation 노드가 1 올린다. graph.py 의 route_ablation 이 이 값이
    # config.py 의 MAX_HYPOTHESIS_RETRIES(지금 3)에 닿으면 save_proposal 로 보낸다.
    hypothesis_retry: int

    # ── 의도 추출 ─────────────────────────────────────────────────
    intent: Intent | None

    # ── 후보 생성 · 검증 (PB-004) ─────────────────────────────────
    candidates: list[Candidate]
    # 유효 후보가 없을 때마다 check_valid 노드가 1 올린다. graph.py 의 route_valid 가 이 값이
    # config.py 의 MAX_CANDIDATE_RETRIES(지금 3)에 닿으면 save_proposal 로 보낸다.
    candidate_retry: int
    recommended: int | None  # rank=1 후보의 candidates 인덱스다(candidate_no 가 아니다).

    # ── RAG 참고 자료 ─────────────────────────────────────────────
    # 노드 이름(hypothesize, extract_intent, generate_candidates)별로 프롬프트에 붙인 참고 자료
    # id 다. skillops-agent/skillops_agent/nodes.py 의 _rag_progress 가 남긴다. init 의
    # empty_run_fields 에는 넣지 않아, 자료를 붙이는 노드까지 가지 않은 진단 기록에는 이 키가 없다.
    rag_references: dict[str, list[str]]

    # ── 저장 · 승인 (PB-005) ──────────────────────────────────────
    # save_proposal 노드가 남긴 제안서다. 보낸 payload 에 id, storage_status, valid_candidate_nos 를
    # 덧붙인 값이다.
    proposal: dict | None
    # 아래 세 칸은 지금 그래프의 어느 노드도 쓰지 않는다. init 이 None 으로 채워 RunStore 기록에
    # 키만 남는다. 승인과 운영 반영은 service 가 실제로 처리하지만 응답에 포함될 수 있어 보존한다.
    approval_status: Literal["pending", "approved", "rejected"] | None
    approval_token: str | None
    smoke_passed: bool | None

    # ── 제어 (PB-006) ─────────────────────────────────────────────
    # init 이 0으로 두고 init 을 뺀 노드마다 _progress 가 1씩 올린다.
    step_count: int
    # 단계 한도다. graph.py 의 _step_limit_reached 가 step_count 와 비교한다.
    max_steps: int
    events: list[ProgressEvent]
    # graph.py 의 guarded 가 노드 예외를 '<타입>: <메시지>' 로 남긴다. 값이 있으면 모든 간선이
    # END 로 간다.
    error: str | None


class DiagnosisStatus:
    """diagnosis_status 값이다.

    화면과 RunStore 가 글자 그대로 읽으므로 Enum 이 아닌 str 상수다. 화면 라벨은
    frontend/src/utils/diagnosisView.js 의 DIAGNOSIS_LABEL 에 있다.
    skillops-agent/skillops_agent/run_store.py 의 ACTIVE_STATUSES 와 같은 frontend 파일의
    IN_PROGRESS_STATUSES 는 QUEUED 와 RUNNING 을 진행 중으로 본다. 값을 더하면 DIAGNOSIS_LABEL 과
    skillops-agent/tests/test_pipeline_defaults.py 의 STATUS_NAMES 를 함께 고치고, 진행 중 상태라면
    ACTIVE_STATUSES 와 IN_PROGRESS_STATUSES 에도 넣는다.
    """

    # skillops-agent/skillops_agent/run_store.py 의 RunStore.accept 가 접수 직후 넣는다.
    QUEUED = "QUEUED"
    # init 노드가 넣는다. 이 값일 때만 skillops-agent/skillops_agent/graph.py 의 route_evidence 가
    # 진단을 잇는다.
    RUNNING = "RUNNING"
    # 저장 직후 사전 검증에서 후보가 회귀 세트를 모두 통과했다.
    VALIDATED = "VALIDATED"
    # 기준 snapshot 도 회귀 세트를 통과하지 못해 수정본을 판정하지 않았다.
    BASELINE_FAILED = "BASELINE_FAILED"
    # 후보가 실패했지만 실행 오류가 없어 원인을 자동으로 찾지 않았다.
    VALIDATION_FAILED = "VALIDATION_FAILED"
    # 두 snapshot 사이에 skill 변경이 없다.
    NO_SKILL_CHANGES = "NO_SKILL_CHANGES"
    # diagnosis_only 진단에서 확인 조건 실패가 나왔다.
    INTENT_VIOLATION = "INTENT_VIOLATION"
    # 제안서를 저장했거나, diagnosis_only 진단에서 의도 위반이 확인되지 않았다.
    EVALUATED = "EVALUATED"
    # 노드 예외나 그래프 실행 오류로 멈췄다. 앱 재시작으로 끊긴 실행도
    # skillops-agent/skillops_agent/run_store.py 의 RunStore.recover 가 이 값으로 바꾼다.
    ERROR = "ERROR"


def empty_run_fields() -> dict:
    """init 이 채우는 실행 상태 기본값이다. 호출마다 새 list·dict 를 만든다.

    실행끼리 같은 목록 객체를 나눠 쓰지 않게 하려고 부를 때마다 새로 만든다. 다음 노드와 분기 판정이
    state['candidates'] 처럼 키를 바로 읽으므로 모든 키를 미리 채워 둔다. 키 순서가 RunStore 기록의
    키 순서가 되므로 skillops-agent/tests/test_pipeline_defaults.py 가 순서와 값을 고정한다.
    production 코드에서는 skillops-agent/skillops_agent/nodes.py 의 init 만 부른다.
    """
    return {
        "trace": None,
        "diff": None,
        "evidence": None,
        "skill_versions": {},
        "snapshot_id": None,
        "baseline_snapshot_id": None,
        "execution_id": None,
        "changes": [],
        "diagnosis_status": DiagnosisStatus.RUNNING,
        "diagnosis": None,
        "preflight_run_ids": [],
        "hypotheses": [],
        "ablation_runs": [],
        "root_cause": None,
        "hypothesis_retry": 0,
        "intent": None,
        "candidates": [],
        "candidate_retry": 0,
        "recommended": None,
        "proposal": None,
        "approval_status": None,
        "approval_token": None,
        "smoke_passed": None,
        "error": None,
    }
