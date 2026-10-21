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

import difflib
from datetime import UTC, datetime

from langgraph.types import interrupt
from pydantic import BaseModel as _PydanticModel

from .config import GOLDEN_IDS
from .contracts import (
    ContractError,
    check_problem,
    context_keys,
    event_data,
    execution_uuid,
    reverse_hunk,
    tools_called,
    validate_report,
)
from .llm import make_structured_llm
from .prompts import load as load_prompt
from .state import (
    AblationRun,
    AgentState,
    Candidate,
    CandidateResults,
    Hypothesis,
    Intent,
    Patch,
    ProgressEvent,
)
from .tools import MCPToolError, get_tools

# ── LLM 출력 스키마 ────────────────────────────────────────────────────────────


class _HypothesisItem(_PydanticModel):
    change_no: int  # v1: hunk_index → change_no
    summary: str
    confidence: float


class _HypothesesOutput(_PydanticModel):
    hypotheses: list[_HypothesisItem]


class _IntentOutput(_PydanticModel):
    text: str
    checks: list[str]  # v1: assertions → checks


class _PatchSpec(_PydanticModel):
    skill: str
    full_text: str  # Revised entire file; B builds the patch against the fixed source.


class _CandidateSpec(_PydanticModel):
    kind: str  # same_skill | linked_skills | run_order

    patches: list[_PatchSpec]


class _CandidatesOutput(_PydanticModel):
    candidates: list[_CandidateSpec]


class _ExplanationItem(_PydanticModel):
    candidate_no: int  # v1: id(str) → candidate_no(int)
    explanation: str


class _RankExplanationsOutput(_PydanticModel):
    explanations: list[_ExplanationItem]


# ── check 평가 헬퍼 ────────────────────────────────────────────────────────────


def _error_event(trace: dict) -> dict:
    return next((e for e in trace.get("events", []) if e.get("kind") == "EXECUTION_ERROR"), {})


async def _run(tools, workspace_id, ids, repeat=2, checks=None):
    if not ids:
        raise ContractError("at least one configured scenario is required")
    report = await tools.run_scenarios(workspace_id, ids, repeat=repeat, extra_checks=checks)
    return validate_report(report, workspace_id, ids, repeat)


# ── 공통 헬퍼 ──────────────────────────────────────────────────────────────────


def _now() -> str:
    return datetime.now(UTC).isoformat()


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
        "baseline_snapshot_id": None,
        "execution_id": None,
        "changes": [],
        "diagnosis_status": "RUNNING",
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


# ── Node 2: fetch_evidence ────────────────────────────────────────────────────


async def fetch_evidence(state: AgentState) -> dict:
    trigger = state["trigger"]
    base = _tick(state, "fetch_evidence", "실제 snapshot과 실행 증거 수집")
    candidate = trigger.get("candidate_snapshot_id")
    baseline = trigger.get("baseline_snapshot_id")
    execution = trigger.get("execution_id") or trigger.get("run_id")
    trace = None
    async with get_tools() as t:
        if execution:
            execution = execution_uuid(execution)
            trace = await t.get_trace(execution)
            if candidate and candidate != trace.get("snapshot_id"):
                raise ContractError("trigger snapshot and execution snapshot differ")
            candidate = trace.get("snapshot_id")
        if not candidate:
            raise ContractError("candidate_snapshot_id or an execution with snapshot is required")
        manifest = await t.get_snapshot(candidate)
        if manifest.get("snapshot_id") != candidate or not manifest.get("hash"):
            raise ContractError("invalid candidate snapshot manifest")
        metadata = manifest.get("metadata", {})
        baseline = baseline or metadata.get("baseline_snapshot_id")
        if not baseline:
            raise ContractError("baseline_snapshot_id is required; version cannot identify it")
        before = await t.get_snapshot(baseline)
        if before.get("snapshot_id") != baseline or not before.get("hash"):
            raise ContractError("invalid baseline snapshot manifest")
        for side in (baseline, candidate):
            execution_uuid(side)
        if trace and trace.get("snapshot_hash") != manifest["hash"]:
            raise ContractError("trace and candidate snapshot hash differ")
        listing = await t.list_skills(candidate)
        if (
            listing.get("snapshot_id") != candidate
            or listing.get("snapshot_hash") != manifest["hash"]
        ):
            raise ContractError("skill list and snapshot differ")
        if listing.get("partial") or listing.get("parse_errors"):
            raise ContractError("candidate skill metadata is incomplete")
        versions = {item["skill_id"]: item["version"] for item in listing["items"]}
        # Actual manifest differences, never failure location or version arithmetic.
        old = {f["path"]: f["hash"] for f in before["files"]}
        new = {f["path"]: f["hash"] for f in manifest["files"]}
        paths = sorted(path for path in old.keys() | new.keys() if old.get(path) != new.get(path))
        announced = trigger.get("changed_paths")
        if announced is not None and set(announced) != set(paths):
            raise ContractError("changed_paths does not match the two snapshot manifests")
        changes = []
        diffs = []
        for path in paths:
            parts = path.split("/")
            if len(parts) != 3 or parts[0] != "skills" or parts[2] != "SKILL.md":
                raise ContractError(f"resource/additional path diagnosis is not supported: {path}")
            if path not in old or path not in new:
                raise ContractError("added/removed skills need a separate ablation policy")
            skill = parts[1]
            diff = await t.diff_skill(skill, baseline, candidate)
            for key, expected in (("before", baseline), ("after", candidate)):
                side = diff.get(key, {})
                file_hash = old[path] if key == "before" else new[path]
                snap_hash = before["hash"] if key == "before" else manifest["hash"]
                if (
                    side.get("snapshot_id") != expected
                    or side.get("file_hash") != file_hash
                    or side.get("snapshot_hash") != snap_hash
                ):
                    raise ContractError("diff evidence does not match the fixed snapshots")
            if not isinstance(diff.get("hunks"), list) or not diff["hunks"]:
                raise ContractError("changed file returned no C hunks")
            diffs.append(diff)
            for hunk in diff["hunks"]:
                if not hunk.get("hunk_id") or not hunk.get("diff"):
                    raise ContractError("C hunk identity/patch missing")
                changes.append({**hunk, "change_no": len(changes) + 1, "skill": skill})
        ids = []
        if not execution:
            baseline_ws = await t.create_workspace(snapshot_id=baseline)
            baseline_results = await _run(t, baseline_ws["workspace_id"], GOLDEN_IDS)
            baseline_ids = [i for r in baseline_results for i in r["run_ids"]]
            if not all(r["passed"] for r in baseline_results):
                base.update(
                    diagnosis_status="BASELINE_FAILED",
                    snapshot_id=candidate,
                    baseline_snapshot_id=baseline,
                    preflight_run_ids=baseline_ids,
                    diagnosis={
                        "explanation": (
                            "기준 세트의 주문도 검증을 통과하지 못했습니다. "
                            "재고 등 검증 환경을 확인하세요. 수정본 결함으로 확정하지 않습니다."
                        ),
                        "failures": [f for r in baseline_results for f in r["failures"]],
                    },
                )
                return base
            ws = await t.create_workspace(snapshot_id=candidate)
            results = await _run(t, ws["workspace_id"], GOLDEN_IDS)
            ids = [r for result in results for r in result["run_ids"]]
            if all(result["passed"] for result in results):
                base.update(diagnosis_status="VALIDATED", preflight_run_ids=ids)
            else:
                failed = next(result for result in results if not result["passed"])
                execution = execution_uuid(failed["failures"][0]["run_id"])
                trace = await t.get_trace(execution)
                if trace.get("completion") != "FAILED":
                    base.update(
                        diagnosis_status="VALIDATION_FAILED",
                        snapshot_id=candidate,
                        baseline_snapshot_id=baseline,
                        preflight_run_ids=ids,
                        diagnosis={
                            "explanation": (
                                "기대 결과와 실제 업무 결과가 다릅니다. "
                                "실행 오류 없이 끝나 자동 원인 확정은 진행하지 않습니다."
                            ),
                            "failures": [f for r in results for f in r["failures"]],
                        },
                    )
                    return base
                # C publishes a derived snapshot for the workspace. Confirm every file hash.
                replay_manifest = await t.get_snapshot(trace["snapshot_id"])
                if {f["path"]: f["hash"] for f in replay_manifest["files"]} != new:
                    raise ContractError("preflight executed different candidate files")
        base.update(
            trace=trace,
            diff={"files": diffs, "hunks": changes},
            changes=changes,
            skill_versions=versions,
            snapshot_id=candidate,
            baseline_snapshot_id=baseline,
            execution_id=execution,
            preflight_run_ids=ids,
        )
        if (
            not changes
            and not trigger.get("diagnosis_only")
            and base.get("diagnosis_status") != "VALIDATED"
        ):
            base["diagnosis_status"] = "NO_SKILL_CHANGES"
        if trigger.get("diagnosis_only"):
            checks = trigger.get("intent_checks", [])
            if not execution or not checks:
                raise ContractError("diagnosis_only requires an execution and intent_checks")
            ws = await t.create_workspace(snapshot_id=candidate)
            results = await _run(t, ws["workspace_id"], [f"f-{execution}"], checks=checks)
            failed_checks = [f for r in results for f in r["failures"]]
            # Only assertion mismatches support an intent-violation diagnosis.
            violated = any("확인 조건 실패:" in f["reason"] for f in failed_checks)
            base.update(
                diagnosis_status="INTENT_VIOLATION" if violated else "EVALUATED",
                diagnosis={
                    "intent_checks": checks,
                    "failures": failed_checks,
                    "evidence_run_ids": [i for r in results for i in r["run_ids"]],
                    "explanation": "변경 정책의 확인 조건과 실제 실행 결과가 일치하지 않습니다."
                    if violated
                    else "실행 결과를 확인했습니다. 의도 위반 확정 여부는 failures를 확인하세요.",
                },
            )
    return base


# ── Node 3: hypothesize ───────────────────────────────────────────────────────


async def hypothesize(state: AgentState) -> dict:
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
        diff=state.get("changes", []),
        previous_failures=previous_failures,
    )

    llm = make_structured_llm(_HypothesesOutput)
    result = await llm.ainvoke(prompt_text)

    hypotheses: list[Hypothesis] = [
        Hypothesis(change_no=h.change_no, summary=h.summary, confidence=h.confidence)
        for h in (result.hypotheses if result else [])
    ]
    if not hypotheses:
        raise ContractError("LLM returned no hypotheses")

    base = _tick(state, "hypothesize", f"가설 {len(hypotheses)}개 생성 (retry={retry})")
    base["hypotheses"] = hypotheses
    return base


# ── Node 4: ablate ────────────────────────────────────────────────────────────


async def ablate(state: AgentState) -> dict:
    execution = state.get("execution_id")
    if not execution:
        raise ContractError("ablation requires an actual execution")
    runs = []
    async with get_tools() as t:
        control = await t.create_workspace(snapshot_id=state["snapshot_id"])
        control_results = await _run(t, control["workspace_id"], [f"f-{execution}"], repeat=1)
        category = event_data(_error_event(state["trace"])).get("category")
        if control_results[0]["passed"]:
            raise ContractError("unmodified candidate no longer reproduces the failure")
        if category and not any(category in f["reason"] for f in control_results[0]["failures"]):
            raise ContractError("replay reproduced a different error category")
        control_ids = control_results[0]["run_ids"]
        for hypothesis in state["hypotheses"]:
            change = next(
                (c for c in state["changes"] if c["change_no"] == hypothesis["change_no"]), None
            )
            if change is None:
                raise ContractError("hypothesis references an unknown change_no")
            ws = await t.create_workspace(snapshot_id=state["snapshot_id"])
            skill = change["skill"]
            patch = Patch(
                skill=skill,
                base_version=ws["skill_versions"][skill],
                patch=reverse_hunk(change["diff"]),
            )
            applied = await t.apply_patch(ws["workspace_id"], [patch])
            if applied.get("workspace_id") != ws["workspace_id"] or not applied.get(
                "changed_skills"
            ):
                raise ContractError("ablation patch was not applied")
            results = await _run(t, ws["workspace_id"], [f"f-{execution}"], repeat=1)
            runs.append(
                AblationRun(
                    change_no=change["change_no"],
                    workspace_id=ws["workspace_id"],
                    run_ids=[i for r in results for i in r["run_ids"]],
                    control_run_ids=control_ids,
                    confirmed=all(r["passed"] for r in results),
                )
            )
    return {**_tick(state, "ablate", f"실제 hunk 복원 실험 {len(runs)}개"), "ablation_runs": runs}


# ── Node 5: check_ablation (라우터) ──────────────────────────────────────────


def check_ablation(state: AgentState) -> dict:
    for run in state["ablation_runs"]:
        if run["confirmed"] and run["run_ids"]:
            change = next(c for c in state["changes"] if c["change_no"] == run["change_no"])
            return {
                **_tick(state, "check_ablation", f"원인 실험 통과: {change['skill']}"),
                "root_cause": {
                    "skill": change["skill"],
                    "change_no": change["change_no"],
                    "hunk_id": change["hunk_id"],
                    "from_version": state["skill_versions"][change["skill"]],
                    "to_version": state["skill_versions"][change["skill"]],
                    "summary": change["before"] + " → " + change["after"],
                    "proof_run_ids": run["control_run_ids"] + run["run_ids"],
                },
            }
    return {
        **_tick(state, "check_ablation", "가설 기각"),
        "hypothesis_retry": state["hypothesis_retry"] + 1,
    }


def route_ablation(state: AgentState) -> str:
    if state.get("root_cause"):
        return "confirmed"
    if state["hypothesis_retry"] >= 3:
        return "escalate"
    if _is_limit_exceeded(state):
        return "limit_exceeded"
    return "retry"


# ── Node 6: extract_intent ────────────────────────────────────────────────────


async def _known_values(state: AgentState) -> list[str]:
    """원인을 되돌려 통과한 실행에서 값 이름을 모은다.

    정상 흐름이 실제로 만드는 값만 확인 조건에 쓸 수 있다.
    """
    passing = next(
        (r for r in state.get("ablation_runs", []) if r["confirmed"] and r["run_ids"]), None
    )
    if passing is None:
        return context_keys(state.get("trace") or {})
    async with get_tools() as t:
        return context_keys(await t.get_trace(passing["run_ids"][0]))


async def extract_intent(state: AgentState) -> dict:
    diff = state.get("diff") or {}
    root_cause = state.get("root_cause") or {}
    known = await _known_values(state)

    prompt_text = load_prompt("extract_intent").format(
        root_cause=root_cause,
        diff=diff,
        context_keys=", ".join(known) or "(없음)",
        style=load_prompt("style"),
    )

    llm = make_structured_llm(_IntentOutput)
    result = await llm.ainvoke(prompt_text)

    # C 가 읽지 못하는 조건이 하나라도 있으면 run_scenarios 전체가 거절된다.
    # 미리 걸러서 이유와 함께 남긴다.
    kept, dropped = [], []
    for check in result.checks if result else []:
        problem = check_problem(check, set(known))
        if problem:
            dropped.append({"check": check, "reason": problem})
        else:
            kept.append(check)

    intent = Intent(
        text=result.text if result else "의도 추출 실패",
        checks=kept,
        dropped_checks=dropped,
        confirmed=None,
    )

    message = f"의도 추출 완료 (조건 {len(kept)}개, 버린 조건 {len(dropped)}개)"
    base = _tick(state, "extract_intent", message)
    base["intent"] = intent
    return base


# ── Node 7: generate_candidates ──────────────────────────────────────────────


async def generate_candidates(state: AgentState) -> dict:
    root_cause = state.get("root_cause") or {}
    intent = state.get("intent") or {}
    diff = state.get("diff") or {}
    retry = state.get("candidate_retry", 0)
    snapshot_id = state["snapshot_id"]

    previous_failures = "없음"
    if retry > 0 and state.get("candidates"):
        lines = []
        for c in state["candidates"]:
            r = c.get("results") or {}
            if not c.get("valid"):
                lines.append(
                    f"{c['candidate_no']}({c['kind']}): fixes_failure={r.get('fixes_failure')}, "
                    f"keeps_old_orders={r.get('keeps_old_orders')}, "
                    f"keeps_intent={r.get('keeps_intent')}, error={c.get('validation_error')}"
                )
        if lines:
            previous_failures = "\n".join(lines)

    async with get_tools() as t:
        dep_graph = await t.get_dependency_graph(
            snapshot_id=snapshot_id,
            entry_skill_id=state["trigger"].get("entry_skill_id", "order-flow"),
        )

        sources = {}
        for skill in state["skill_versions"]:
            source = await t.get_skill(snapshot_id, skill)
            if source.get("snapshot_id") != snapshot_id or not isinstance(
                source.get("content"), str
            ):
                raise ContractError("candidate source does not match the fixed snapshot")
            sources[skill] = {"content": source["content"], "version": source["version"]}

    if dep_graph.get("complete") is not True:
        raise ContractError("dependency graph is incomplete")

    prompt_text = load_prompt("generate_candidates").format(
        root_cause={**root_cause, "skill_versions": state["skill_versions"]},
        intent=intent,
        diff=diff,
        dependency_graph=dep_graph,
        current_files=sources,
        previous_failures=previous_failures,
    )

    llm = make_structured_llm(_CandidatesOutput)
    result = await llm.ainvoke(prompt_text)

    candidates: list[Candidate] = []
    for i, spec in enumerate(result.candidates if result else []):
        patches = []
        validation_error = None
        seen = set()
        for revision in spec.patches:
            if revision.skill not in sources or revision.skill in seen:
                validation_error = "수정 대상이 없거나 같은 파일을 여러 번 제안했습니다."
                break
            seen.add(revision.skill)
            source = sources[revision.skill]
            if not revision.full_text.strip() or not revision.full_text.startswith("---\n"):
                validation_error = "수정 후 전체 SKILL.md 원문이 필요합니다."
                break
            path = f"skills/{revision.skill}/SKILL.md"
            patch = "\n".join(
                difflib.unified_diff(
                    source["content"].splitlines(),
                    revision.full_text.splitlines(),
                    fromfile=f"a/{path}",
                    tofile=f"b/{path}",
                    lineterm="",
                )
            )
            if patch:
                patches.append(
                    Patch(skill=revision.skill, base_version=source["version"], patch=patch + "\n")
                )
        if not patches and not validation_error:
            validation_error = "현재 수정본과 달라진 내용이 없는 후보입니다."
        candidates.append(
            Candidate(
                candidate_no=i + 1,
                kind=spec.kind,
                workspace=None,
                patches=patches,
                results=None,
                valid=None,
                evidence_run_ids=[],
                rank=None,
                explanation=None,
                validation_error=validation_error,
            )
        )

    if not candidates:
        raise ContractError("LLM returned no candidates")

    base = _tick(state, "generate_candidates", f"후보 {len(candidates)}개 생성 (retry={retry})")
    base["candidates"] = candidates
    return base


# ── Node 8: verify_candidates ────────────────────────────────────────────────


async def verify_candidates(state: AgentState) -> dict:
    execution = state["execution_id"]
    repro_id = f"f-{execution}"
    checks = (state.get("intent") or {}).get("checks", [])
    updated = []
    async with get_tools() as t:
        for candidate in state["candidates"]:
            if candidate.get("validation_error"):
                updated.append({**candidate, "valid": False})
                continue
            ws = await t.create_workspace(snapshot_id=state["snapshot_id"])
            for patch in candidate["patches"]:
                if patch["base_version"] != ws["skill_versions"].get(patch["skill"]):
                    raise ContractError("candidate patch base_version differs from workspace")
            try:
                applied = await t.apply_patch(ws["workspace_id"], candidate["patches"])
            except MCPToolError as exc:
                if exc.code != "INVALID_INPUT":
                    raise
                updated.append(
                    {
                        **candidate,
                        "workspace": ws["workspace_id"],
                        "valid": False,
                        "validation_error": exc.payload.get("message", str(exc)),
                    }
                )
                continue
            if applied.get("workspace_id") != ws["workspace_id"] or not isinstance(
                applied.get("broken_links"), list
            ):
                raise ContractError("invalid apply_patch response")
            expected_skills = {p["skill"] for p in candidate["patches"]}
            if (
                not expected_skills
                or {p["skill"] for p in applied.get("changed_skills", [])} != expected_skills
            ):
                raise ContractError("candidate patches were not all applied")
            regression = await _run(t, ws["workspace_id"], GOLDEN_IDS)
            repro = await _run(t, ws["workspace_id"], [repro_id])
            # C extra_checks applies to every requested scenario: scope intent to repro only.
            intent_results = (
                await _run(t, ws["workspace_id"], [repro_id], checks=checks) if checks else []
            )
            evidence = [i for r in regression + repro + intent_results for i in r["run_ids"]]
            functional = all(r["passed"] for r in repro)
            regression_ok = all(r["passed"] for r in regression)
            intent_ok = all(r["passed"] for r in intent_results)
            contract_ok = not applied["broken_links"]
            called = []
            for run_id in repro[0]["run_ids"]:
                called.extend(tools_called(await t.get_trace(run_id)))
            results = CandidateResults(
                fixes_failure=functional,
                keeps_old_orders={
                    "passed": sum(r["passed"] for r in regression),
                    "total": len(regression),
                },
                keeps_intent=intent_ok,
                no_broken_links=contract_ok,
                changed_skill_count=len(expected_skills),
                tools_called=called,
            )
            updated.append(
                {
                    **candidate,
                    "workspace": ws["workspace_id"],
                    "results": results,
                    "valid": functional and regression_ok and intent_ok and contract_ok,
                    "evidence_run_ids": evidence,
                }
            )
    return {
        **_tick(state, "verify_candidates", f"후보 {len(updated)}개 실제 C 계약 검증"),
        "candidates": updated,
    }


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
        style=load_prompt("style"),
    )
    llm = make_structured_llm(_RankExplanationsOutput)
    result = await llm.ainvoke(prompt_text)
    explanation_map = {
        e.candidate_no: e.explanation for e in (result.explanations if result else [])
    }

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


def _failure_of(trace: dict | None) -> dict | None:
    """실행 기록의 EXECUTION_ERROR 를 제안서의 failure 묶음으로 옮긴다. 오류가 없으면 None."""
    event = _error_event(trace or {})
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
    """save_proposal 로 보낼 제안서(계약 5 §10). 합격 판정과 본문 작성은 service 가 다시 한다."""
    root_cause = state.get("root_cause")
    if root_cause:
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
    return {
        **_tick(state, "save_proposal", f"제안서 저장: {saved['proposal_id']}"),
        "proposal": proposal,
        "diagnosis_status": "EVALUATED",
    }


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
