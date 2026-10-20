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


def generate_candidates(state: AgentState) -> dict:
    """3종 후보를 생성한다. PB-004 에서 LLM 호출로 교체한다."""
    root_cause = state.get("root_cause") or {}
    skill = root_cause.get("skill", "unknown")
    base_v = root_cause.get("from_version", 1)

    stub_diff = f"--- a/{skill}/SKILL.md\n+++ b/{skill}/SKILL.md\n@@ -1 +1 @@\n-stub before\n+stub after\n"

    candidates: list[Candidate] = [
        Candidate(id="c-local-01",    kind="local",    workspace=None, patches=[Patch(skill=skill, base_version=base_v, diff=stub_diff)], results=None, valid=None, evidence=[], rank=None, explanation=None),
        Candidate(id="c-contract-01", kind="contract", workspace=None, patches=[Patch(skill=skill, base_version=base_v, diff=stub_diff), Patch(skill="payment", base_version=4, diff=stub_diff)], results=None, valid=None, evidence=[], rank=None, explanation=None),
        Candidate(id="c-flow-01",     kind="flow",     workspace=None, patches=[Patch(skill=skill, base_version=base_v, diff=stub_diff), Patch(skill="payment", base_version=4, diff=stub_diff), Patch(skill="notify", base_version=2, diff=stub_diff)], results=None, valid=None, evidence=[], rank=None, explanation=None),
    ]

    base = _tick(state, "generate_candidates", f"후보 {len(candidates)}개 생성")
    base["candidates"] = candidates
    return base


# ── Node 8: verify_candidates ────────────────────────────────────────────────


async def verify_candidates(state: AgentState) -> dict:
    """각 후보를 격리 workspace 에서 골든 + 재현 시나리오로 검증한다."""
    golden_ids = ["g-01", "g-02", "g-03", "g-04", "g-05", "g-06"]
    repro_id = "f-01"
    all_scenario_ids = golden_ids + [repro_id]

    updated: list[Candidate] = []
    async with get_tools() as t:
        for cand in state["candidates"]:
            ws = await t.create_workspace()
            ws_id = ws["ws_id"]
            patch_result = await t.apply_patch(ws_id, cand["patches"])
            contract_ok = len(patch_result.get("contract_violations", [])) == 0

            results = await t.run_scenarios(ws_id, all_scenario_ids, k=2)
            passed_count = sum(1 for r in results if r.get("passed"))
            total = len(results)
            evidence = [run_id for r in results for run_id in r.get("runs", [])]

            candidate_results = CandidateResults(
                functional=any(r["scenario_id"] == repro_id and r.get("passed") for r in results),
                regression={"passed": passed_count, "total": total},
                intent=True,  # PB-004 에서 assertion 실행으로 교체
                contract=contract_ok,
                footprint=len(cand["patches"]),
            )
            valid = (
                candidate_results["functional"]
                and candidate_results["regression"]["passed"] == candidate_results["regression"]["total"]
                and candidate_results["intent"]
            )
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


def rank_and_explain(state: AgentState) -> dict:
    """유효 후보를 기준 4·5 로 순위 매기고 설명을 붙인다. PB-005 에서 LLM 으로 교체한다."""
    valid = [c for c in state["candidates"] if c.get("valid")]

    def sort_key(c: Candidate):
        r = c.get("results") or {}
        contract_score = 0 if r.get("contract") else 1
        footprint = r.get("footprint", 99)
        return (contract_score, footprint)

    ranked = sorted(valid, key=sort_key)
    updated = list(state["candidates"])
    rank_map = {c["id"]: i + 1 for i, c in enumerate(ranked)}

    for i, c in enumerate(updated):
        if c["id"] in rank_map:
            evidence_refs = ", ".join(c.get("evidence", [])[:2]) or "없음"
            updated[i] = {
                **c,
                "rank": rank_map[c["id"]],
                "explanation": f"시나리오 통과 근거: {evidence_refs}",
            }

    recommended = next(
        (i for i, c in enumerate(updated) if c.get("rank") == 1), None
    )
    base = _tick(state, "rank_and_explain", f"순위 매기기 완료, recommended={recommended}")
    base["candidates"] = updated
    base["recommended"] = recommended
    return base


# ── Node 11: save_proposal ────────────────────────────────────────────────────


async def save_proposal(state: AgentState) -> dict:
    root_cause = state.get("root_cause") or {}
    intent = state.get("intent") or {}
    recommended = state.get("recommended")
    status = "proposed" if any(c.get("valid") for c in state["candidates"]) else "no_valid_fix"

    proposal = {
        "trigger": state["trigger"],
        "root_cause": root_cause,
        "intent": intent,
        "candidates": state["candidates"],
        "recommended": recommended,
        "status": status,
    }

    async with get_tools() as t:
        # evidence 없는 후보가 있으면 경고만 (stub 은 항상 저장)
        saved = await t.save_proposal(proposal)

    full_proposal = {**proposal, "id": saved.get("id", "p_unknown")}
    base = _tick(state, "save_proposal", f"proposal 저장: {full_proposal['id']}")
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
