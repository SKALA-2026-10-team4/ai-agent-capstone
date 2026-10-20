"""AgentState 및 보조 TypedDict 정의. 모든 Node가 같은 State를 읽고 쓴다."""

from typing import Literal, TypedDict


class Hypothesis(TypedDict):
    hunk_index: int
    summary: str
    confidence: float  # 0.0–1.0


class AblationRun(TypedDict):
    hunk_index: int
    ws_id: str
    run_ids: list[str]
    confirmed: bool  # 이 hunk 를 되돌리면 시나리오가 통과? → True 면 원인 확정


class RootCause(TypedDict):
    skill: str
    from_version: int
    to_version: int
    hunk: int          # diff_skill 의 hunk 번호 (ablation 으로 확정)
    summary: str
    confirmed_by: list[str]  # ablation 실행 run_id 목록 (minItems: 1)


class Intent(TypedDict):
    text: str
    assertions: list[str]   # "<키> <op> <리터럴>" 형식, op: ==|!=|in
    confirmed: bool | None  # 승인 화면에서 담당자가 확인하면 True


class Patch(TypedDict):
    skill: str
    base_version: int
    diff: str  # unified diff


class CandidateResults(TypedDict):
    functional: bool          # 기준 1: 실패 주문이 통과
    regression: dict          # 기준 2: {passed: int, total: int}
    intent: bool              # 기준 3: 의도 보존
    contract: bool            # 기준 4: 계약 일관성
    footprint: int            # 기준 5: 수정한 skill 수


class Candidate(TypedDict):
    id: str
    kind: Literal["local", "contract", "flow"]
    workspace: str | None
    patches: list[Patch]
    results: CandidateResults | None  # verify_candidates 후 채워짐
    valid: bool | None                # functional & regression 전부 & intent
    evidence: list[str]               # run_id / scenario_id (비면 save_proposal 400)
    rank: int | None
    explanation: str | None


class ProgressEvent(TypedDict):
    at: str          # ISO datetime
    node: str
    msg: str
    run_id: str | None


class AgentState(TypedDict):
    # ── 진입 ──────────────────────────────────────────────────────
    trigger: dict                        # DiagnoseRequest dict

    # ── 증거 수집 (fetch_evidence) ────────────────────────────────
    trace: dict | None                   # get_trace 결과
    diff: dict | None                    # diff_skill 결과 → hunks[]
    skill_versions: dict                 # trace.skill_versions

    # ── 가설 · ablation (PB-003) ──────────────────────────────────
    hypotheses: list[Hypothesis]
    ablation_runs: list[AblationRun]
    root_cause: RootCause | None
    hypothesis_retry: int                # 최대 3

    # ── 의도 추출 ─────────────────────────────────────────────────
    intent: Intent | None

    # ── 후보 생성 · 검증 (PB-004) ─────────────────────────────────
    candidates: list[Candidate]
    candidate_retry: int                 # 최대 3

    # ── 저장 · 승인 (PB-005) ──────────────────────────────────────
    proposal: dict | None
    approval_status: Literal["pending", "approved", "rejected"] | None
    approval_token: str | None

    # ── 제어 (PB-006) ─────────────────────────────────────────────
    step_count: int
    max_steps: int
    events: list[ProgressEvent]
    error: str | None
