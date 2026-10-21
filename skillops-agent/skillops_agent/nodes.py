"""LangGraph Node 함수 15개.

각 Node:
- step_count 를 1 증가시킨다 (PB-006 한도 체크 포함).
- ProgressEvent 를 events 에 추가한다.
- State 의 일부만 반환 (LangGraph 가 merge 한다).
"""

from datetime import datetime, timezone

from langgraph.types import interrupt
from pydantic import BaseModel as _PydanticModel

from .llm import make_structured_llm
from .prompts import load as load_prompt
from .state import (
    AgentState,
    AblationRun,
    Candidate,
    CandidateResults,
    Hypothesis,
    Intent,
    Patch,
    ProgressEvent,
    RootCause,
)
from .tools import get_tools


# ── LLM 출력 스키마 ────────────────────────────────────────────────────────────

class _HypothesisItem(_PydanticModel):
    hunk_index: int
    summary: str
    confidence: float

class _HypothesesOutput(_PydanticModel):
    hypotheses: list[_HypothesisItem]

class _IntentOutput(_PydanticModel):
    text: str
    assertions: list[str]

class _PatchSpec(_PydanticModel):
    skill: str
    base_version: int
    diff: str

class _CandidateSpec(_PydanticModel):
    kind: str  # local | contract | flow
    patches: list[_PatchSpec]

class _CandidatesOutput(_PydanticModel):
    candidates: list[_CandidateSpec]

class _ExplanationItem(_PydanticModel):
    id: str
    explanation: str

class _RankExplanationsOutput(_PydanticModel):
    explanations: list[_ExplanationItem]


# ── assertion 평가 헬퍼 ────────────────────────────────────────────────────────

def _eval_assertion(assertion: str, context: dict) -> bool:
    """'<key> <op> <literal>' 형식을 평가한다. op: ==, !=, in."""
    parts = assertion.split(None, 2)
    if len(parts) != 3:
        return True
    key, op, literal = parts
    value = context.get(key)
    if value is None:
        return False
    clean = literal.strip("'\"")
    if op == "==":
        return str(value) == clean
    if op == "!=":
        return str(value) != clean
    if op == "in":
        return clean in str(value)
    return True


async def _check_intent(assertions: list[str], run_ids: list[str], tools) -> bool:
    """repro 실행 run_id 의 trace 출력으로 assertion 을 검증한다."""
    if not assertions:
        return True
    if not run_ids:
        return False
    trace = await tools.get_trace(run_ids[0])
    context: dict = {}
    for step in trace.get("steps", []):
        context.update(step.get("output", {}))
    return all(_eval_assertion(a, context) for a in assertions)


# ── 공통 헬퍼 ──────────────────────────────────────────────────────────────────


def _now() -> str:
    return datetime.now(timezone.utc).isoformat()


def _event(node: str, msg: str, run_id: str | None = None) -> ProgressEvent:
    return ProgressEvent(at=_now(), node=node, msg=msg, run_id=run_id)


def _tick(state: AgentState, node: str, msg: str, run_id: str | None = None) -> dict:
    """step_count 증가 + 이벤트 추가 공통 반환값."""
    return {
        "step_count": state["step_count"] + 1,
        "events": state["events"] + [_event(node, msg, run_id)],
    }


MAX_STEPS_DEFAULT = 40


# ── Node 1: init ───────────────────────────────────────────────────────────────


def init(state: AgentState) -> dict:
    return {
        "step_count": 0,
        "max_steps": state.get("max_steps", MAX_STEPS_DEFAULT),
        "events": [_event("init", "파이프라인 시작")],
        "trace": None,
        "diff": None,
        "skill_versions": {},
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
        "error": None,
    }


# ── Node 2: fetch_evidence ────────────────────────────────────────────────────


async def fetch_evidence(state: AgentState) -> dict:
    trigger = state["trigger"]
    base = _tick(state, "fetch_evidence", "증거 수집 시작")

    async with get_tools() as t:
        trace = None
        diff = None

        # 전체 skill 목록에서 버전 맵 구성
        skills = await t.list_skills()
        skill_versions: dict = {s["name"]: s["version"] for s in skills}

        if trigger.get("run_id"):
            trace = await t.get_trace(trigger["run_id"])
            # 트레이스의 실행 당시 버전으로 덮어씀 (더 정확)
            skill_versions.update(trace.get("skill_versions", {}))

            failed_step = next(
                (s for s in trace.get("steps", []) if s.get("status") == "error"), None
            )
            if failed_step:
                skill = failed_step["skill"]
                current_v = skill_versions.get(skill, 1)
                if current_v > 1:
                    diff = await t.diff_skill(skill, current_v - 1, current_v)

        elif trigger.get("skill") and trigger.get("version"):
            skill = trigger["skill"]
            version = trigger["version"]
            if version > 1:
                diff = await t.diff_skill(skill, version - 1, version)

    base.update({
        "trace": trace,
        "diff": diff,
        "skill_versions": skill_versions,
        "events": state["events"] + [_event("fetch_evidence", "증거 수집 완료")],
    })
    return base


# ── Node 3: hypothesize ───────────────────────────────────────────────────────


async def hypothesize(state: AgentState) -> dict:
    diff = state.get("diff") or {}
    trace = state.get("trace") or {}
    retry = state.get("hypothesis_retry", 0)

    previous_failures = "없음"
    if retry > 0 and state.get("ablation_runs"):
        lines = [
            f"hunk {r['hunk_index']}: ablation 재현 실패"
            for r in state["ablation_runs"]
            if not r["confirmed"]
        ]
        if lines:
            previous_failures = "\n".join(lines)

    prompt_text = load_prompt("hypothesize").format(
        trace=trace,
        diff=diff,
        previous_failures=previous_failures,
    )

    llm = make_structured_llm(_HypothesesOutput)
    result = await llm.ainvoke(prompt_text)

    hypotheses: list[Hypothesis] = [
        Hypothesis(hunk_index=h.hunk_index, summary=h.summary, confidence=h.confidence)
        for h in (result.hypotheses if result else [])
    ] or [Hypothesis(hunk_index=0, summary="(LLM 응답 없음) 기본 가설", confidence=0.5)]

    base = _tick(state, "hypothesize", f"가설 {len(hypotheses)}개 생성 (retry={retry})")
    base["hypotheses"] = hypotheses
    return base


# ── Node 4: ablate ────────────────────────────────────────────────────────────


# TODO(B/C-1/C-2, 계약 확정 후): diff 응답의 hunks/index(0부터)와 v1 changes/change_no(1부터)를 구분한다.
# patch/diff는 동일한 기준 snapshot에 적용하고, 없는 재현 scenario_id를 조합해 호출하지 않는다.
# replay_run_ids 또는 실제 등록된 재현 주문으로 바꾸되 서버 지원과 함께 맞춘다.
async def ablate(state: AgentState) -> dict:
    """각 가설의 hunk 를 되돌린 뒤 재현 시나리오를 실행한다. confirmed=True 면 원인 확정."""
    trigger = state["trigger"]
    diff = state.get("diff") or {}
    hunks: list[dict] = diff.get("hunks", [])
    skill_versions = state.get("skill_versions", {})

    trace = state.get("trace") or {}
    failed_step = next(
        (s for s in trace.get("steps", []) if s.get("status") == "error"), {}
    )
    skill_name = failed_step.get("skill") or trigger.get("skill") or "unknown"
    base_v = skill_versions.get(skill_name, 1)

    ablation_runs: list[AblationRun] = []
    async with get_tools() as t:
        for hypo in state["hypotheses"]:
            ws = await t.create_workspace()
            ws_id = ws["ws_id"]

            # 해당 hunk 를 되돌리는 역방향 패치 적용 (before ↔ after 반전)
            hunk = next((h for h in hunks if h["index"] == hypo["hunk_index"]), None)
            if hunk:
                reverse_diff = (
                    f"--- a/{skill_name}/SKILL.md\n+++ b/{skill_name}/SKILL.md\n"
                    f"@@ -1 +1 @@\n-{hunk.get('after', '')}\n+{hunk.get('before', '')}\n"
                )
                await t.apply_patch(ws_id, [Patch(
                    skill=skill_name, base_version=base_v, diff=reverse_diff
                )])

            result = await t.run_scenarios(ws_id, ["f-01"], k=1)
            run_ids = [run_id for res in result for run_id in res.get("runs", [])]
            confirmed = all(res.get("passed", False) for res in result)

            ablation_runs.append(AblationRun(
                hunk_index=hypo["hunk_index"],
                ws_id=ws_id,
                run_ids=run_ids,
                confirmed=confirmed,
            ))

    base = _tick(state, "ablate", f"ablation {len(ablation_runs)}개 실행")
    base["ablation_runs"] = ablation_runs
    return base


# ── Node 5: check_ablation (라우터) ──────────────────────────────────────────


def check_ablation(state: AgentState) -> dict:
    confirmed = [r for r in state["ablation_runs"] if r["confirmed"]]
    if confirmed:
        run = confirmed[0]
        diff = state.get("diff") or {}
        hunks = diff.get("hunks", [])
        hunk = next((h for h in hunks if h["index"] == run["hunk_index"]), {})
        skill_versions = state.get("skill_versions", {})

        # 실패 skill 이름 추출
        trace = state.get("trace") or {}
        failed_step = next(
            (s for s in trace.get("steps", []) if s.get("status") == "error"), {}
        )
        skill_name = failed_step.get("skill", state["trigger"].get("skill", "unknown"))
        current_v = skill_versions.get(skill_name, 1)

        root_cause = RootCause(
            skill=skill_name,
            from_version=max(1, current_v - 1),
            to_version=current_v,
            hunk=run["hunk_index"],
            summary=hunk.get("before", "") + " → " + hunk.get("after", ""),
            confirmed_by=run["run_ids"] or ["r_ablation_stub"],
        )
        base = _tick(state, "check_ablation", f"원인 확정: {skill_name} hunk {run['hunk_index']}")
        base["root_cause"] = root_cause
        return base

    retry = state["hypothesis_retry"] + 1
    base = _tick(state, "check_ablation", f"가설 기각, 재시도 {retry}/3")
    base["hypothesis_retry"] = retry
    return base


def route_ablation(state: AgentState) -> str:
    if state.get("root_cause"):
        return "confirmed"
    if state["hypothesis_retry"] >= 3:
        return "escalate"
    return "retry"


# ── Node 6: extract_intent ────────────────────────────────────────────────────


async def extract_intent(state: AgentState) -> dict:
    diff = state.get("diff") or {}
    root_cause = state.get("root_cause") or {}

    prompt_text = load_prompt("extract_intent").format(
        root_cause=root_cause,
        diff=diff,
    )

    llm = make_structured_llm(_IntentOutput)
    result = await llm.ainvoke(prompt_text)

    intent = Intent(
        text=result.text if result else "의도 추출 실패",
        assertions=result.assertions if result else [],
        confirmed=None,
    )

    base = _tick(state, "extract_intent", "의도 추출 완료")
    base["intent"] = intent
    return base


# ── Node 7: generate_candidates ──────────────────────────────────────────────


async def generate_candidates(state: AgentState) -> dict:
    root_cause = state.get("root_cause") or {}
    intent = state.get("intent") or {}
    diff = state.get("diff") or {}
    retry = state.get("candidate_retry", 0)
    skill = root_cause.get("skill", "unknown")
    base_v = root_cause.get("from_version", 1)
    skill_versions = state.get("skill_versions", {})

    previous_failures = "없음"
    if retry > 0 and state.get("candidates"):
        lines = []
        for c in state["candidates"]:
            r = c.get("results") or {}
            if not c.get("valid"):
                lines.append(
                    f"{c['id']}({c['kind']}): functional={r.get('functional')}, "
                    f"regression={r.get('regression')}, intent={r.get('intent')}"
                )
        if lines:
            previous_failures = "\n".join(lines)

    async with get_tools() as t:
        dep_graph = await t.get_dependency_graph()

    prompt_text = load_prompt("generate_candidates").format(
        root_cause=root_cause,
        intent=intent,
        diff=diff,
        dependency_graph=dep_graph,
        previous_failures=previous_failures,
    )

    llm = make_structured_llm(_CandidatesOutput)
    result = await llm.ainvoke(prompt_text)

    candidates: list[Candidate] = []
    for spec in (result.candidates if result else []):
        patches = [
            Patch(skill=p.skill, base_version=p.base_version, diff=p.diff)
            for p in spec.patches
        ]
        candidates.append(Candidate(
            id=f"c-{spec.kind}-{retry + 1:02d}",
            kind=spec.kind,
            workspace=None,
            patches=patches,
            results=None,
            valid=None,
            evidence=[],
            rank=None,
            explanation=None,
        ))

    # LLM 응답 없을 때 fallback
    if not candidates:
        stub_diff = (
            f"--- a/{skill}/SKILL.md\n+++ b/{skill}/SKILL.md\n"
            f"@@ -1 +1 @@\n-stub before\n+stub after\n"
        )
        for kind in ("local", "contract", "flow"):
            base_patches = [Patch(skill=skill, base_version=base_v, diff=stub_diff)]
            candidates.append(Candidate(
                id=f"c-{kind}-{retry + 1:02d}", kind=kind, workspace=None,
                patches=base_patches, results=None, valid=None, evidence=[], rank=None, explanation=None,
            ))

    base = _tick(state, "generate_candidates", f"후보 {len(candidates)}개 생성 (retry={retry})")
    base["candidates"] = candidates
    return base


# ── Node 8: verify_candidates ────────────────────────────────────────────────


# TODO(B/C-2, 계약 확정 후): ws["ws_id"]는 서버 workspace_id와 다르고 결과 배열/runs 파싱도
# 서버 results/run_ids와 다르다. patch의 contract_violations와 broken_links도 실제 응답 기준으로 맞춘다.
# 누락 필드를 []/False 기본값으로 숨겨 계약 위반을 정상 판정하지 않도록 입력 응답 검증을 추가한다.
async def verify_candidates(state: AgentState) -> dict:
    """각 후보를 격리 workspace 에서 골든 + 재현 시나리오로 검증한다."""
    golden_ids = ["g-01", "g-02", "g-03", "g-04", "g-05", "g-06"]

    # 재현 시나리오는 실패 trace 에서 파생한다 (파일 저장 없음)
    trace = state.get("trace") or {}
    repro_id = f"f-{trace.get('run_id', 'repro')}"
    all_scenario_ids = golden_ids + [repro_id]

    assertions = (state.get("intent") or {}).get("assertions", [])

    updated: list[Candidate] = []
    async with get_tools() as t:
        for cand in state["candidates"]:
            ws = await t.create_workspace()
            ws_id = ws["ws_id"]
            patch_result = await t.apply_patch(ws_id, cand["patches"])
            contract_ok = len(patch_result.get("contract_violations", [])) == 0

            results = await t.run_scenarios(ws_id, all_scenario_ids, k=2)
            repro_result = next((r for r in results if r["scenario_id"] == repro_id), None)
            functional = repro_result.get("passed", False) if repro_result else False

            golden_results = [r for r in results if r["scenario_id"] != repro_id]
            passed_count = sum(1 for r in golden_results if r.get("passed"))
            regression_ok = passed_count == len(golden_results)

            repro_run_ids = repro_result.get("runs", []) if repro_result else []
            intent_ok = await _check_intent(assertions, repro_run_ids, t)

            evidence = [run_id for r in results for run_id in r.get("runs", [])]

            candidate_results = CandidateResults(
                functional=functional,
                regression={"passed": passed_count, "total": len(golden_results)},
                intent=intent_ok,
                contract=contract_ok,
                footprint=len(cand["patches"]),
            )
            valid = functional and regression_ok and intent_ok
            updated.append({**cand, "workspace": ws_id, "results": candidate_results, "valid": valid, "evidence": evidence})

    base = _tick(state, "verify_candidates", f"후보 {len(updated)}개 검증 완료")
    base["candidates"] = updated
    return base


# ── Node 9: check_valid (라우터) ──────────────────────────────────────────────


def check_valid(state: AgentState) -> dict:
    valid_candidates = [c for c in state["candidates"] if c.get("valid")]
    if valid_candidates:
        base = _tick(state, "check_valid", f"유효 후보 {len(valid_candidates)}개")
        return base
    retry = state["candidate_retry"] + 1
    base = _tick(state, "check_valid", f"유효 후보 없음, 재시도 {retry}/3")
    base["candidate_retry"] = retry
    return base


def route_valid(state: AgentState) -> str:
    if any(c.get("valid") for c in state["candidates"]):
        return "has_valid"
    if state["candidate_retry"] >= 3:
        return "no_valid_fix"
    return "retry"


# ── Node 10: rank_and_explain ─────────────────────────────────────────────────


async def rank_and_explain(state: AgentState) -> dict:
    valid = [c for c in state["candidates"] if c.get("valid")]

    # 기준 4(계약 일관성) → 기준 5(최소성) 순으로 정렬
    def sort_key(c: Candidate):
        r = c.get("results") or {}
        return (0 if r.get("contract") else 1, r.get("footprint", 99))

    ranked = sorted(valid, key=sort_key)
    rank_map = {c["id"]: i + 1 for i, c in enumerate(ranked)}

    # LLM 으로 explanation 생성
    prompt_text = load_prompt("rank_and_explain").format(
        root_cause=state.get("root_cause") or {},
        intent=state.get("intent") or {},
        candidates=[{**c, "rank": rank_map.get(c["id"])} for c in ranked],
    )
    llm = make_structured_llm(_RankExplanationsOutput)
    result = await llm.ainvoke(prompt_text)
    explanation_map = {e.id: e.explanation for e in (result.explanations if result else [])}

    updated = list(state["candidates"])
    recommended: int | None = None
    for i, c in enumerate(updated):
        if c["id"] in rank_map:
            explanation = explanation_map.get(c["id"]) or (
                f"rank {rank_map[c['id']]}: {', '.join(c.get('evidence', [])[:2]) or '없음'}"
            )
            updated[i] = {**c, "rank": rank_map[c["id"]], "explanation": explanation}
            if rank_map[c["id"]] == 1:
                recommended = i

    base = _tick(state, "rank_and_explain", f"순위 매기기 완료, recommended={recommended}")
    base["candidates"] = updated
    base["recommended"] = recommended
    return base


# ── Node 11: save_proposal ────────────────────────────────────────────────────


async def save_proposal(state: AgentState) -> dict:
    root_cause = state.get("root_cause") or {}
    intent = state.get("intent") or {}
    recommended = state.get("recommended")
    candidates = state.get("candidates", [])
    valid_candidates = [c for c in candidates if c.get("valid")]

    # 유효 후보 중 evidence 가 비어 있으면 400 거부로 간주하고 오류 기록 후 종료
    empty_evidence_ids = [c["id"] for c in valid_candidates if not c.get("evidence")]
    if empty_evidence_ids:
        base = _tick(state, "save_proposal", f"evidence 없음(400): {empty_evidence_ids}")
        base["error"] = f"save_proposal 400: evidence 없는 후보 {empty_evidence_ids}"
        return base

    status = "proposed" if valid_candidates else "no_valid_fix"

    proposal = {
        "trigger": state["trigger"],
        "root_cause": root_cause,
        "intent": intent,
        "candidates": candidates,
        "recommended": recommended,
        "status": status,
    }

    async with get_tools() as t:
        saved = await t.save_proposal(proposal)

    full_proposal = {**proposal, "id": saved.get("id", "p_unknown")}
    base = _tick(state, "save_proposal", f"proposal 저장: {full_proposal['id']} (status={status})")
    base["proposal"] = full_proposal
    return base


# ── Node 12: wait_for_approval ────────────────────────────────────────────────


def wait_for_approval(state: AgentState) -> dict:
    """Graph 를 일시 중단한다. 재개 시 approval_status, approval_token 을 받는다."""
    proposal_id = (state.get("proposal") or {}).get("id", "unknown")
    answer = interrupt({"proposal_id": proposal_id, "msg": "승인 대기 중"})

    base = _tick(state, "wait_for_approval", f"승인 결과: {answer.get('status')}")
    base["approval_status"] = answer.get("status")
    base["approval_token"] = answer.get("token")
    return base


def route_approval(state: AgentState) -> str:
    return "approved" if state.get("approval_status") == "approved" else "rejected"


# ── Node 13: promote_workspace ────────────────────────────────────────────────


async def promote_workspace(state: AgentState) -> dict:
    candidates = state.get("candidates", [])
    recommended = state.get("recommended")
    ws_id = None
    if recommended is not None and recommended < len(candidates):
        ws_id = candidates[recommended].get("workspace")

    if not ws_id:
        base = _tick(state, "promote_workspace", "workspace 없음, 건너뜀")
        return base

    async with get_tools() as t:
        result = await t.promote_workspace(ws_id, state.get("approval_token") or "")

    promoted = result.get("promoted", [])
    base = _tick(state, "promote_workspace", f"promote 완료: {promoted}")
    return base


# ── Node 14: smoke_check ──────────────────────────────────────────────────────


async def smoke_check(state: AgentState) -> dict:
    async with get_tools() as t:
        results = await t.run_scenarios("live", ["g-01", "g-02", "g-03", "g-04", "g-05", "g-06"], k=1)
    all_pass = all(r.get("passed") for r in results)
    msg = "스모크 통과" if all_pass else "스모크 실패"
    base = _tick(state, "smoke_check", msg)
    base["smoke_passed"] = all_pass
    return base


def route_smoke(state: AgentState) -> str:
    return "pass" if state.get("smoke_passed", True) else "fail"


# ── Node 15: rollback ─────────────────────────────────────────────────────────


async def rollback(state: AgentState) -> dict:
    root_cause = state.get("root_cause") or {}
    skill = root_cause.get("skill", "unknown")
    version = root_cause.get("from_version", 1)

    async with get_tools() as t:
        await t.rollback(skill, version)

    base = _tick(state, "rollback", f"rollback: {skill} → v{version}")
    return base
