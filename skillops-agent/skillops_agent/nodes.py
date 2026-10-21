"""LangGraph Node 함수 12개 (v1).

v1 변경:
- promote_workspace / smoke_check / rollback → 서비스가 처리하므로 제거
- 모든 필드명 v1 스펙으로 갱신 (change_no, workspace_id, fixes_failure 등)
- get_trace 응답 events[] 기반 파싱

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
    change_no: int       # v1: hunk_index → change_no
    summary: str
    confidence: float

class _HypothesesOutput(_PydanticModel):
    hypotheses: list[_HypothesisItem]

class _IntentOutput(_PydanticModel):
    text: str
    checks: list[str]    # v1: assertions → checks

class _PatchSpec(_PydanticModel):
    skill: str
    base_version: int
    patch: str           # v1: diff → patch

class _CandidateSpec(_PydanticModel):
    kind: str            # same_skill | linked_skills | run_order

    patches: list[_PatchSpec]

class _CandidatesOutput(_PydanticModel):
    candidates: list[_CandidateSpec]

class _ExplanationItem(_PydanticModel):
    candidate_no: int    # v1: id(str) → candidate_no(int)
    explanation: str

class _RankExplanationsOutput(_PydanticModel):
    explanations: list[_ExplanationItem]


# ── check 평가 헬퍼 ────────────────────────────────────────────────────────────

def _eval_check(check: str, context: dict) -> bool:
    """'<key> <op> <literal>' 형식을 평가한다. op: ==, !=, in."""
    parts = check.split(None, 2)
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


def _error_event(trace: dict) -> dict:
    """v1 events에서 EXECUTION_ERROR 이벤트를 반환한다. skill_id는 top-level 필드."""
    return next(
        (e for e in trace.get("events", []) if e.get("kind") == "EXECUTION_ERROR"), {}
    )


async def _check_intent(checks: list[str], run_ids: list[str], tools) -> bool:
    """repro 실행 execution_id 의 trace 출력으로 check 을 검증한다.
    실제 API: SKILL_FINISHED 이벤트의 data.output 에서 출력값 추출.
    """
    if not checks:
        return True
    if not run_ids:
        return False
    trace = await tools.get_trace(run_ids[0])
    context: dict = {}
    for event in trace.get("events", []):
        if event.get("kind") == "SKILL_FINISHED":
            context.update(event.get("data", {}).get("output", {}))
    return all(_eval_check(c, context) for c in checks)


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


def _is_limit_exceeded(state: AgentState) -> bool:
    return state.get("step_count", 0) >= state.get("max_steps", MAX_STEPS_DEFAULT)


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
        "snapshot_id": None,
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


# ── Node 2: fetch_evidence ────────────────────────────────────────────────────


async def fetch_evidence(state: AgentState) -> dict:
    trigger = state["trigger"]
    base = _tick(state, "fetch_evidence", "증거 수집 시작")

    async with get_tools() as t:
        trace = None
        diff = None
        skill_versions: dict = {}
        snapshot_id: str | None = None

        if trigger.get("run_id"):
            trace = await t.get_trace(trigger["run_id"])
            snapshot_id = trace.get("snapshot_id")

            # 실제 API: SKILL_LOADED 이벤트의 skill_id + data.version 으로 버전 맵 추출
            for event in trace.get("events", []):
                if event.get("kind") == "SKILL_LOADED" and event.get("skill_id"):
                    v = event.get("data", {}).get("version")
                    if v is not None:
                        skill_versions[event["skill_id"]] = v

            # 실제 API: EXECUTION_ERROR event의 top-level skill_id 로 실패 skill 추출
            failed_ev = _error_event(trace)
            if failed_ev:
                skill = failed_ev.get("skill_id") or failed_ev.get("skill", "unknown")
                current_v = skill_versions.get(skill, 1)
                if current_v > 1:
                    prev_snap = f"{snapshot_id}_prev" if snapshot_id else "snap_prev"
                    diff = await t.diff_skill(skill, prev_snap, snapshot_id or "snap_cur")

        elif trigger.get("skill") and trigger.get("version"):
            skill = trigger["skill"]
            version = trigger["version"]
            if version > 1:
                diff = await t.diff_skill(skill, f"snap_v{version - 1}", f"snap_v{version}")

    base.update({
        "trace": trace,
        "diff": diff,
        "skill_versions": skill_versions,
        "snapshot_id": snapshot_id,
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
            f"change {r['change_no']}: ablation 재현 실패"
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
        Hypothesis(change_no=h.change_no, summary=h.summary, confidence=h.confidence)
        for h in (result.hypotheses if result else [])
    ] or [Hypothesis(change_no=0, summary="(LLM 응답 없음) 기본 가설", confidence=0.5)]

    base = _tick(state, "hypothesize", f"가설 {len(hypotheses)}개 생성 (retry={retry})")
    base["hypotheses"] = hypotheses
    return base


# ── Node 4: ablate ────────────────────────────────────────────────────────────


async def ablate(state: AgentState) -> dict:
    """각 가설의 change 를 되돌린 뒤 재현 시나리오를 실행한다. confirmed=True 면 원인 확정."""
    trigger = state["trigger"]
    diff = state.get("diff") or {}
    changes: list[dict] = diff.get("changes", [])
    skill_versions = state.get("skill_versions", {})

    trace = state.get("trace") or {}
    failed_ev = _error_event(trace)
    skill_name = failed_ev.get("skill_id") or failed_ev.get("skill") or trigger.get("skill") or "unknown"
    base_v = skill_versions.get(skill_name, 1)

    ablation_runs: list[AblationRun] = []
    async with get_tools() as t:
        for hypo in state["hypotheses"]:
            ws = await t.create_workspace()
            workspace_id = ws["workspace_id"]

            # 해당 change 를 되돌리는 역방향 패치 적용 (before ↔ after 반전)
            change = next((c for c in changes if c["change_no"] == hypo["change_no"]), None)
            if change:
                reverse_patch = (
                    f"--- a/{skill_name}/SKILL.md\n+++ b/{skill_name}/SKILL.md\n"
                    f"@@ -1 +1 @@\n-{change.get('after', '')}\n+{change.get('before', '')}\n"
                )
                await t.apply_patch(workspace_id, [Patch(
                    skill=skill_name, base_version=base_v, patch=reverse_patch
                )])

            result = await t.run_scenarios(workspace_id, ["f-01"], repeat=1)
            results = result.get("results", [])
            run_ids = [run_id for res in results for run_id in res.get("run_ids", [])]
            confirmed = all(res.get("passed", False) for res in results)

            ablation_runs.append(AblationRun(
                change_no=hypo["change_no"],
                workspace_id=workspace_id,
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
        changes = diff.get("changes", [])
        change = next((c for c in changes if c["change_no"] == run["change_no"]), {})
        skill_versions = state.get("skill_versions", {})

        # 실패 skill 이름 추출
        trace = state.get("trace") or {}
        failed_ev = _error_event(trace)
        skill_name = failed_ev.get("skill_id") or failed_ev.get("skill") or state["trigger"].get("skill", "unknown")
        current_v = skill_versions.get(skill_name, 1)

        root_cause = RootCause(
            skill=skill_name,
            from_version=max(1, current_v - 1),
            to_version=current_v,
            change_no=run["change_no"],
            summary=change.get("before", "") + " → " + change.get("after", ""),
            proof_run_ids=run["run_ids"] or ["r_ablation_stub"],
        )
        base = _tick(state, "check_ablation", f"원인 확정: {skill_name} change {run['change_no']}")
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
    if _is_limit_exceeded(state):
        return "limit_exceeded"
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
        checks=result.checks if result else [],
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
    snapshot_id = state.get("snapshot_id") or "snap_unknown"

    previous_failures = "없음"
    if retry > 0 and state.get("candidates"):
        lines = []
        for c in state["candidates"]:
            r = c.get("results") or {}
            if not c.get("valid"):
                lines.append(
                    f"{c['candidate_no']}({c['kind']}): fixes_failure={r.get('fixes_failure')}, "
                    f"keeps_old_orders={r.get('keeps_old_orders')}, keeps_intent={r.get('keeps_intent')}"
                )
        if lines:
            previous_failures = "\n".join(lines)

    async with get_tools() as t:
        dep_graph = await t.get_dependency_graph(
            snapshot_id=snapshot_id,
            entry_skill_id=skill,
        )

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
    for i, spec in enumerate(result.candidates if result else []):
        patches = [
            Patch(skill=p.skill, base_version=p.base_version, patch=p.patch)
            for p in spec.patches
        ]
        candidates.append(Candidate(
            candidate_no=i + 1,
            kind=spec.kind,
            workspace=None,
            patches=patches,
            results=None,
            valid=None,
            evidence_run_ids=[],
            rank=None,
            explanation=None,
        ))

    # LLM 응답 없을 때 fallback
    if not candidates:
        stub_patch = (
            f"--- a/{skill}/SKILL.md\n+++ b/{skill}/SKILL.md\n"
            f"@@ -1 +1 @@\n-stub before\n+stub after\n"
        )
        for i, kind in enumerate(("same_skill", "linked_skills", "run_order")):
            base_patches = [Patch(skill=skill, base_version=base_v, patch=stub_patch)]
            candidates.append(Candidate(
                candidate_no=i + 1, kind=kind, workspace=None,
                patches=base_patches, results=None, valid=None,
                evidence_run_ids=[], rank=None, explanation=None,
            ))

    base = _tick(state, "generate_candidates", f"후보 {len(candidates)}개 생성 (retry={retry})")
    base["candidates"] = candidates
    return base


# ── Node 8: verify_candidates ────────────────────────────────────────────────


async def verify_candidates(state: AgentState) -> dict:
    """각 후보를 격리 workspace 에서 골든 + 재현 시나리오로 검증한다."""
    golden_ids = ["g-01", "g-02", "g-03", "g-04", "g-05", "g-06"]

    trace = state.get("trace") or {}
    repro_id = f"f-{trace.get('execution_id', 'repro')}"
    all_scenario_ids = golden_ids + [repro_id]

    checks = (state.get("intent") or {}).get("checks", [])

    updated: list[Candidate] = []
    async with get_tools() as t:
        for cand in state["candidates"]:
            ws = await t.create_workspace()
            workspace_id = ws["workspace_id"]
            patch_result = await t.apply_patch(workspace_id, cand["patches"])
            contract_ok = len(patch_result.get("broken_links", [])) == 0

            run_result = await t.run_scenarios(workspace_id, all_scenario_ids, repeat=2)
            results = run_result.get("results", [])

            repro_result = next((r for r in results if r["scenario_id"] == repro_id), None)
            functional = repro_result.get("passed", False) if repro_result else False

            golden_results = [r for r in results if r["scenario_id"] != repro_id]
            passed_count = sum(1 for r in golden_results if r.get("passed"))
            regression_ok = passed_count == len(golden_results)

            repro_run_ids = repro_result.get("run_ids", []) if repro_result else []
            intent_ok = await _check_intent(checks, repro_run_ids, t)

            evidence_run_ids = [run_id for r in results for run_id in r.get("run_ids", [])]

            # PB-007: repro 트레이스 TOOL_REQUEST 이벤트에서 도구 호출 목록 추출
            # 실제 API: tool 이름은 event.data.tool 에 있음
            tools_called: list[str] = []
            if repro_run_ids:
                repro_trace = await t.get_trace(repro_run_ids[0])
                tools_called = [
                    ev.get("data", {}).get("tool") or ev.get("tool")
                    for ev in repro_trace.get("events", [])
                    if ev.get("kind") == "TOOL_REQUEST"
                    and (ev.get("data", {}).get("tool") or ev.get("tool"))
                ]

            candidate_results = CandidateResults(
                fixes_failure=functional,
                keeps_old_orders={"passed": passed_count, "total": len(golden_results)},
                keeps_intent=intent_ok,
                no_broken_links=contract_ok,
                changed_skill_count=len(cand["patches"]),
                tools_called=tools_called,
            )
            valid = functional and regression_ok and intent_ok
            updated.append({**cand, "workspace": workspace_id, "results": candidate_results, "valid": valid, "evidence_run_ids": evidence_run_ids})

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
    if _is_limit_exceeded(state):
        return "no_valid_fix"
    return "retry"


# ── Node 10: rank_and_explain ─────────────────────────────────────────────────


async def rank_and_explain(state: AgentState) -> dict:
    valid = [c for c in state["candidates"] if c.get("valid")]

    # no_broken_links(계약 일관성) → changed_skill_count(최소성) 순으로 정렬
    def sort_key(c: Candidate):
        r = c.get("results") or {}
        return (0 if r.get("no_broken_links") else 1, r.get("changed_skill_count", 99))

    ranked = sorted(valid, key=sort_key)
    rank_map = {c["candidate_no"]: i + 1 for i, c in enumerate(ranked)}

    # LLM 으로 explanation 생성
    prompt_text = load_prompt("rank_and_explain").format(
        root_cause=state.get("root_cause") or {},
        intent=state.get("intent") or {},
        candidates=[{**c, "rank": rank_map.get(c["candidate_no"])} for c in ranked],
    )
    llm = make_structured_llm(_RankExplanationsOutput)
    result = await llm.ainvoke(prompt_text)
    explanation_map = {e.candidate_no: e.explanation for e in (result.explanations if result else [])}

    updated = list(state["candidates"])
    recommended: int | None = None
    for i, c in enumerate(updated):
        cno = c["candidate_no"]
        if cno in rank_map:
            explanation = explanation_map.get(cno) or (
                f"rank {rank_map[cno]}: {', '.join(c.get('evidence_run_ids', [])[:2]) or '없음'}"
            )
            updated[i] = {**c, "rank": rank_map[cno], "explanation": explanation}
            if rank_map[cno] == 1:
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

    # 유효 후보 중 evidence_run_ids 가 비어 있으면 400 거부로 간주하고 오류 기록
    empty_evidence_nos = [c["candidate_no"] for c in valid_candidates if not c.get("evidence_run_ids")]
    if empty_evidence_nos:
        base = _tick(state, "save_proposal", f"evidence_run_ids 없음(400): {empty_evidence_nos}")
        base["error"] = f"save_proposal 400: evidence_run_ids 없는 후보 {empty_evidence_nos}"
        return base

    status = "proposed" if valid_candidates else "no_valid_fix"

    recommended_candidate_no = None
    if recommended is not None and recommended < len(candidates):
        recommended_candidate_no = candidates[recommended].get("candidate_no")

    proposal = {
        "trigger": state["trigger"],
        "root_cause": root_cause,
        "intent": intent,
        "candidates": candidates,
        "recommended_candidate_no": recommended_candidate_no,
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
    """Graph 를 일시 중단한다. 재개 시 approval_status, approval_token 을 받는다.
    v1: promote_workspace / smoke_check / rollback 은 서비스가 처리한다.
    """
    proposal_id = (state.get("proposal") or {}).get("id", "unknown")
    answer = interrupt({"proposal_id": proposal_id, "msg": "승인 대기 중"})

    base = _tick(state, "wait_for_approval", f"승인 결과: {answer.get('status')}")
    base["approval_status"] = answer.get("status")
    base["approval_token"] = answer.get("token")
    return base


def route_approval(state: AgentState) -> str:
    return "approved" if state.get("approval_status") == "approved" else "rejected"
