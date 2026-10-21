"""AgentState 및 보조 TypedDict 정의. 모든 Node가 같은 State를 읽고 쓴다."""

from typing import Literal, TypedDict


class Hypothesis(TypedDict):
    change_no: int  # diff_skill 의 change 번호 (v1: hunk_index → change_no)
    summary: str
    confidence: float  # 0.0–1.0


class AblationRun(TypedDict):
    control_run_ids: list[str]
    change_no: int  # v1: hunk_index → change_no
    workspace_id: str  # v1: ws_id → workspace_id
    run_ids: list[str]
    confirmed: bool  # 이 change 를 되돌리면 시나리오가 통과? → True 면 원인 확정


class RootCause(TypedDict):
    skill: str
    from_version: int
    to_version: int
    change_no: int  # v1: hunk → change_no
    summary: str
    proof_run_ids: list[str]  # v1: confirmed_by → proof_run_ids (minItems: 1)


class Intent(TypedDict, total=False):
    text: str
    # v1: assertions → checks ("키 op 리터럴" 형식). C 가 읽을 수 있는 것만 남긴다
    checks: list[str]
    dropped_checks: list[dict]  # 버린 조건과 이유 [{check, reason}]
    confirmed: bool | None  # 승인 화면에서 담당자가 확인하면 True


class Patch(TypedDict):
    skill: str
    base_version: int
    patch: str  # v1: diff → patch (unified diff)


class CandidateResults(TypedDict):
    fixes_failure: bool  # v1: functional → fixes_failure
    keeps_old_orders: dict  # v1: regression → keeps_old_orders {passed, total}
    keeps_intent: bool  # v1: intent → keeps_intent
    no_broken_links: bool  # v1: contract → no_broken_links
    changed_skill_count: int  # v1: footprint → changed_skill_count
    tools_called: list[str]  # PB-007: repro 트레이스 TOOL_REQUEST 이벤트에서 추출


class Candidate(TypedDict):
    validation_error: str | None
    candidate_no: int  # v1: id(str) → candidate_no(int, 1-based)
    kind: Literal["same_skill", "linked_skills", "run_order"]  # v1: local/contract/flow 변경
    workspace: str | None
    patches: list[Patch]
    results: CandidateResults | None  # verify_candidates 후 채워짐
    valid: bool | None  # fixes_failure & keeps_old_orders 전부 & keeps_intent
    evidence_run_ids: list[str]  # v1: evidence → evidence_run_ids (비면 save_proposal 400)
    rank: int | None
    explanation: str | None


class ProgressEvent(TypedDict):
    at: str  # ISO datetime
    node: str
    msg: str
    run_id: str | None


class AgentState(TypedDict):
    pipeline_thread_id: str
    # ── 진입 ──────────────────────────────────────────────────────
    trigger: dict  # DiagnoseRequest dict
    baseline_snapshot_id: str | None
    execution_id: str | None
    changes: list[dict]
    diagnosis_status: str
    diagnosis: dict | None
    preflight_run_ids: list[str]

    # ── 증거 수집 (fetch_evidence) ────────────────────────────────
    trace: dict | None  # get_trace 결과
    diff: dict | None  # diff_skill 결과 → changes[]
    skill_versions: dict  # SKILL_SELECTED 이벤트에서 추출한 {skill: version}
    snapshot_id: str | None  # v1: 실행 당시 스냅샷 ID

    # ── 가설 · ablation (PB-003) ──────────────────────────────────
    hypotheses: list[Hypothesis]
    ablation_runs: list[AblationRun]
    root_cause: RootCause | None
    hypothesis_retry: int  # 최대 3

    # ── 의도 추출 ─────────────────────────────────────────────────
    intent: Intent | None

    # ── 후보 생성 · 검증 (PB-004) ─────────────────────────────────
    candidates: list[Candidate]
    candidate_retry: int  # 최대 3
    recommended: int | None  # rank=1 후보의 candidates 인덱스

    # ── 저장 · 승인 (PB-005) ──────────────────────────────────────
    proposal: dict | None
    approval_status: Literal["pending", "approved", "rejected"] | None
    approval_token: str | None
    smoke_passed: bool | None  # 보존 (서비스가 실제 처리하지만 응답에 포함 가능)

    # ── 제어 (PB-006) ─────────────────────────────────────────────
    step_count: int
    max_steps: int
    events: list[ProgressEvent]
    error: str | None
