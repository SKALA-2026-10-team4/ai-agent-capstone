"""호출(트리거) 진단 그래프의 노드 함수를 모은다.

skillops-agent/skillops_agent/trigger_graph.py 의 TRIGGER_FLOW 에 노드 이름이 있고, 같은 이름의
함수를 이 모듈에서 찾는다. 노드는 TriggerState 를 받아 바꾼 키만 dict 로 돌려준다.

흐름: load_eval_set → (질의가 없으면 generate_eval_queries) → measure_baseline → diagnose →
propose → reverify → (통과·시도 소진이면 report, 아니면 diagnose 로 다시).

선택 Agent 는 select_skill 프롬프트로 skill 이름과 description 만 보고 하나를 고른다. 이 선택을
여러 번 받아 skillops-agent/skillops_agent/trigger_metrics.py 가 재현율·정밀도를 계산한다. 판정은
지표로만 하고 LLM 출력은 진단과 개선안 제안에만 쓴다.

테스트는 이 모듈의 make_structured_llm 을 바꿔 끼우므로 호출할 때 모듈 전역에서 찾는다.
"""

import asyncio
from datetime import UTC, datetime
from typing import Literal

from pydantic import BaseModel as _PydanticModel

from . import trigger_metrics as metrics
from .config import (
    MAX_TRIGGER_ATTEMPTS,
    TRIGGER_CONCURRENCY,
    TRIGGER_DESCRIPTION_MAX,
    TRIGGER_SELECT_REPEAT,
    TRIGGER_TARGET_RECALL,
)
from .contracts import ContractError
from .llm import make_structured_llm
from .prompts import load as load_prompt
from .trigger_state import NONE_SKILL, SkillCard, TriggerCase, TriggerState, TriggerStatus

# 평가셋이 없을 때 만드는 질의 수다. 불려야 하는 질의와 불리면 안 되는 질의로 나눈다.
GENERATED_POSITIVE_COUNT = 8
GENERATED_NEGATIVE_COUNT = 6

# ── LLM 출력 스키마 ────────────────────────────────────────────────────────────
# 클래스 이름, 필드, docstring 이 OpenAI JSON schema 가 되므로 docstring 을 달지 않는다.
# 테스트의 가짜 LLM 은 schema.__name__ 으로 고정 출력을 고른다.


# 선택 Agent 의 출력이다. skill 은 목록의 이름이거나 none 이다.
class _SelectionOutput(_PydanticModel):
    skill: str
    reason: str


# diagnose 노드의 출력이다. cause_type 값은 trigger_diagnose.md 에 설명이 있다.
class _TriggerDiagnosisOutput(_PydanticModel):
    cause_type: Literal[
        "too_generic",
        "overlaps_competitor",
        "missing_usage_context",
        "missing_keywords",
        "too_broad",
    ]
    summary: str
    competing_skills: list[str]
    missing_contexts: list[str]


# propose 노드의 출력이다. 대상 skill 의 새 description 하나만 낸다.
class _DescriptionProposalOutput(_PydanticModel):
    description: str
    rationale: str


# 생성한 평가 질의 하나다. 형식은 trigger_state.TriggerCase 와 같다.
class _EvalCaseItem(_PydanticModel):
    query: str
    expected_skill: str
    should_trigger: bool


# generate_eval_queries 노드의 출력이다.
class _EvalQueriesOutput(_PydanticModel):
    cases: list[_EvalCaseItem]


# ── 공통 헬퍼 ──────────────────────────────────────────────────────────────────


def _progress(state: TriggerState, node: str, msg: str, **updates) -> dict:
    """step_count 를 올리고 진행 이벤트를 붙인 반환값을 만든다(nodes._progress 와 같은 모양)."""
    event = {"at": datetime.now(UTC).isoformat(), "node": node, "msg": msg, "run_id": None}
    return {
        "step_count": state.get("step_count", 0) + 1,
        "events": state.get("events", []) + [event],
        **updates,
    }


async def _ask_llm(prompt_name: str, schema, *, with_style: bool = False, **fields):
    """프롬프트를 채워 구조화 출력 LLM 에 묻는다. make_structured_llm 은 호출 때 찾는다."""
    template = load_prompt(prompt_name)
    if with_style:
        fields["style"] = load_prompt("style")
    return await make_structured_llm(schema).ainvoke(template.format(**fields))


def _skill_list(skills: list[SkillCard]) -> str:
    """선택 Agent 에 보여 줄 skill 목록 글이다. 이름과 description 만 넣는다."""
    return "\n".join(f"- {s['name']}: {s['description']}" for s in skills)


def _target_card(state: TriggerState) -> SkillCard:
    """skills 에서 진단 대상 skill 을 찾는다."""
    return next(s for s in state["skills"] if s["name"] == state["target_skill"])


def _others(state: TriggerState) -> list[SkillCard]:
    """대상이 아닌 skill 목록이다."""
    return [s for s in state["skills"] if s["name"] != state["target_skill"]]


def _with_description(skills: list[SkillCard], target: str, description: str) -> list[SkillCard]:
    """대상 skill 의 description 만 바꾼 새 목록을 만든다. 원래 목록은 그대로 둔다."""
    return [
        {**s, "description": description} if s["name"] == target else dict(s) for s in skills
    ]


def _miss_lines(misses: list[dict]) -> str:
    """틀린 질의를 프롬프트 줄로 만든다."""
    if not misses:
        return "없음"
    return "\n".join(
        f"- \"{m['query']}\" (expected={m['expected_skill']}, "
        f"should_trigger={m['should_trigger']}, selected={m['selected']})"
        for m in misses
    )


def _attempt_lines(attempts: list[dict]) -> str:
    """이전 시도를 프롬프트 줄로 만든다. 같은 실패를 되풀이하지 않게 하는 근거다."""
    if not attempts:
        return "없음"
    lines = []
    for a in attempts:
        m = a.get("metrics") or {}
        # 그 시도에서도 여전히 틀린 질의를 함께 넘겨 같은 수정을 되풀이하지 않게 한다.
        still = ", ".join(f"\"{x['query']}\"" for x in a.get("misses", [])) or "없음"
        lines.append(
            f"- 시도 {a['attempt']}: description=\"{a['description']}\", "
            f"재현율={m.get('recall')}, 정밀도={m.get('precision')}, "
            f"실패 이유: {' '.join(a['reasons'])} 여전히 틀린 질의: {still}"
        )
    return "\n".join(lines)


async def _select_all(
    cases: list[TriggerCase], skills: list[SkillCard], repeat: int
) -> list[list[str]]:
    """질의마다 선택 Agent 를 repeat 번 불러 고른 skill 이름을 모은다.

    목록에 없는 이름도 그대로 남긴다. 대상과 다르므로 틀린 선택으로 세진다.
    """
    sem = asyncio.Semaphore(TRIGGER_CONCURRENCY)
    skill_list = _skill_list(skills)

    async def one(query: str) -> str:
        async with sem:
            out = await _ask_llm(
                "select_skill", _SelectionOutput, skill_list=skill_list, query=query
            )
        return (out.skill or NONE_SKILL).strip()

    picks = await asyncio.gather(*(one(c["query"]) for c in cases for _ in range(repeat)))
    return [list(picks[i * repeat : (i + 1) * repeat]) for i in range(len(cases))]


async def _measure(state: TriggerState, skills: list[SkillCard]) -> dict:
    """주어진 skill 목록으로 평가셋을 돌려 지표를 낸다."""
    selections = await _select_all(state["cases"], skills, state["repeat"])
    return metrics.measure(state["cases"], selections, state["target_skill"])


# ── 입력 검사 ──────────────────────────────────────────────────────────────────


def _check_skills(skills, target) -> list[SkillCard]:
    """skill 목록 형식과 대상 skill 이 목록에 있는지 본다."""
    if not skills:
        raise ContractError("skills is empty")
    names = [s.get("name") for s in skills]
    if any(not n or not isinstance(n, str) for n in names):
        raise ContractError("every skill needs a name")
    if len(set(names)) != len(names):
        raise ContractError("skill names must be unique")
    if NONE_SKILL in names:
        raise ContractError(f"'{NONE_SKILL}' is reserved for no selection")
    if target not in names:
        raise ContractError(f"target_skill '{target}' is not in skills")
    return [{"name": s["name"], "description": s.get("description") or ""} for s in skills]


def _check_case(case, names: set[str]) -> TriggerCase:
    """평가 질의 하나의 형식을 본다."""
    query = case.get("query")
    expected = case.get("expected_skill")
    should = case.get("should_trigger")
    if not isinstance(query, str) or not query.strip():
        raise ContractError("case.query must be a non-empty string")
    if expected != NONE_SKILL and expected not in names:
        raise ContractError(f"case.expected_skill '{expected}' is not in skills")
    if not isinstance(should, bool):
        raise ContractError("case.should_trigger must be true or false")
    if expected == NONE_SKILL and not should:
        raise ContractError("expected_skill none needs should_trigger true")
    return {"query": query.strip(), "expected_skill": expected, "should_trigger": should}


# ── 노드 ───────────────────────────────────────────────────────────────────────


def load_eval_set(state: TriggerState) -> dict:
    """평가셋과 skill 목록을 검사하고 실행 상태를 처음 값으로 채운다."""
    target = state.get("target_skill")
    skills = _check_skills(state.get("skills"), target)
    names = {s["name"] for s in skills}
    cases = [_check_case(c, names) for c in state.get("cases") or []]
    if not cases and not state.get("seed_queries"):
        raise ContractError("cases or seed_queries is required")
    repeat = state.get("repeat") or TRIGGER_SELECT_REPEAT
    if repeat < 1:
        raise ContractError("repeat must be at least 1")

    msg = (
        f"{target} 의 평가 질의 {len(cases)}개를 읽었습니다."
        if cases
        else f"{target} 의 평가 질의가 없어 요청 문장으로 질의를 만듭니다."
    )
    return _progress(
        state,
        "load_eval_set",
        msg,
        skills=skills,
        cases=cases,
        repeat=repeat,
        baseline=None,
        diagnosis=None,
        proposal=None,
        attempts=[],
        report=None,
        error=None,
        diagnosis_status=TriggerStatus.RUNNING,
    )


async def generate_eval_queries(state: TriggerState) -> dict:
    """평가셋이 없을 때 요청 문장으로 불려야 하는 질의와 불리면 안 되는 질의를 만든다.

    기획안 시나리오 5 의 평가 셋 자동 생성이다.
    """
    target = _target_card(state)
    out = await _ask_llm(
        "generate_eval_queries",
        _EvalQueriesOutput,
        target_skill=target["name"],
        description=target["description"],
        other_skills=_skill_list(_others(state)),
        seed_queries="\n".join(f"- {q}" for q in state.get("seed_queries") or []) or "없음",
        positive_count=GENERATED_POSITIVE_COUNT,
        negative_count=GENERATED_NEGATIVE_COUNT,
    )

    # 형식이 틀린 질의와 중복 질의는 버린다.
    names = {s["name"] for s in state["skills"]}
    cases, seen = [], set()
    for item in out.cases:
        try:
            case = _check_case(item.model_dump(), names)
        except ContractError:
            continue
        if case["query"] in seen:
            continue
        seen.add(case["query"])
        cases.append(case)

    if not any(c["should_trigger"] and c["expected_skill"] == target["name"] for c in cases):
        raise ContractError("generated cases have no query that should trigger the target")
    return _progress(
        state,
        "generate_eval_queries",
        f"평가 질의 {len(cases)}개를 만들었습니다.",
        cases=cases,
    )


async def measure_baseline(state: TriggerState) -> dict:
    """원래 description 으로 평가셋을 돌려 기준 지표를 잰다."""
    baseline = await _measure(state, state["skills"])
    return _progress(
        state,
        "measure_baseline",
        f"원래 설명으로 질의 {len(state['cases'])}개를 {state['repeat']}번씩 물었더니 "
        f"호출 재현율이 {baseline['recall']}, 정밀도가 {baseline['precision']} 입니다.",
        baseline=baseline,
    )


async def diagnose(state: TriggerState) -> dict:
    """틀린 질의와 지표로 대상 skill 이 안 불리는 원인을 진단한다."""
    target = _target_card(state)
    baseline = state["baseline"]
    out = await _ask_llm(
        "trigger_diagnose",
        _TriggerDiagnosisOutput,
        with_style=True,
        target_skill=target["name"],
        description=target["description"],
        other_skills=_skill_list(_others(state)),
        metrics=metrics.summary(baseline),
        misses=_miss_lines(baseline["misses"]),
        previous_attempts=_attempt_lines(state["attempts"]),
    )
    # 목록에 없는 skill 이름은 버린다.
    names = {s["name"] for s in _others(state)}
    diagnosis = {
        "cause_type": out.cause_type,
        "summary": out.summary,
        "competing_skills": [n for n in out.competing_skills if n in names],
        "missing_contexts": list(out.missing_contexts),
    }
    attempt_no = len(state["attempts"]) + 1
    return _progress(
        state,
        "diagnose",
        f"{attempt_no}번째 진단: {out.summary}",
        diagnosis=diagnosis,
    )


async def propose(state: TriggerState) -> dict:
    """진단을 바탕으로 대상 skill 의 새 description 을 만든다."""
    target = _target_card(state)
    out = await _ask_llm(
        "trigger_propose",
        _DescriptionProposalOutput,
        target_skill=target["name"],
        description=target["description"],
        other_skills=_skill_list(_others(state)),
        diagnosis=state["diagnosis"],
        misses=_miss_lines(state["baseline"]["misses"]),
        previous_attempts=_attempt_lines(state["attempts"]),
        max_length=TRIGGER_DESCRIPTION_MAX,
    )
    proposal = {"description": out.description.strip(), "rationale": out.rationale}
    return _progress(state, "propose", "description 수정안을 만들었습니다.", proposal=proposal)


def _proposal_problems(proposal: dict, current: str) -> list[str]:
    """재검증 전에 걸러야 할 개선안 문제를 찾는다."""
    text = proposal["description"]
    if not text:
        return ["description 이 비어 있습니다."]
    if len(text) > TRIGGER_DESCRIPTION_MAX:
        return [f"description 이 {TRIGGER_DESCRIPTION_MAX}자를 넘습니다({len(text)}자)."]
    if text == current.strip():
        return ["원래 description 과 같습니다."]
    return []


def reached_target(attempt: dict) -> bool:
    """통과했고 목표 재현율에도 닿은 안인지 본다. trigger_graph.route_reverify 가 쓴다."""
    return attempt["passed"] and (attempt["metrics"]["recall"] or 0) >= TRIGGER_TARGET_RECALL


def best_attempt(attempts: list[dict]) -> dict | None:
    """통과한 안 중 재현율, 정밀도 순으로 가장 좋은 안이다. 같으면 먼저 나온 안을 고른다."""
    passed = [a for a in attempts if a["passed"]]
    if not passed:
        return None
    return max(
        passed,
        key=lambda a: (
            a["metrics"]["recall"] or 0,
            1.0 if a["metrics"]["precision"] is None else a["metrics"]["precision"],
            -a["attempt"],
        ),
    )


async def reverify(state: TriggerState) -> dict:
    """개선안 description 으로 같은 평가셋을 다시 돌려 통과 여부를 판정한다."""
    target = _target_card(state)
    proposal = state["proposal"]
    attempt_no = len(state["attempts"]) + 1
    record = {
        "attempt": attempt_no,
        "diagnosis": state["diagnosis"],
        "description": proposal["description"],
        "rationale": proposal["rationale"],
        "metrics": None,
        "misses": [],
        "regressions": [],
    }

    problems = _proposal_problems(proposal, target["description"])
    if problems:
        record.update(passed=False, reasons=problems)
        msg = f"{attempt_no}번째 수정안은 형식이 맞지 않아 시험하지 않았습니다."
    else:
        skills = _with_description(state["skills"], target["name"], proposal["description"])
        after = await _measure(state, skills)
        passed, reasons = metrics.judge(state["baseline"], after)
        record.update(
            metrics=metrics.summary(after),
            misses=after["misses"],
            regressions=metrics.regressions(state["baseline"], after),
            passed=passed,
            reasons=reasons,
        )
        if not passed:
            verdict = "통과하지 못했습니다"
        elif reached_target(record):
            verdict = "통과했습니다"
        else:
            verdict = f"통과했지만 목표 재현율 {TRIGGER_TARGET_RECALL} 에 못 미칩니다"
        msg = (
            f"{attempt_no}번째 수정안을 다시 시험했더니 호출 재현율이 "
            f"{state['baseline']['recall']} 에서 {after['recall']} 로 바뀌었고 {verdict}."
        )
    return _progress(state, "reverify", msg, attempts=state["attempts"] + [record])


def report(state: TriggerState) -> dict:
    """진단 결과를 승인 요청에 쓸 보고서로 묶는다. LLM 을 부르지 않는다."""
    target = _target_card(state)
    baseline = state["baseline"]
    attempts = state["attempts"]
    passed = best_attempt(attempts)

    if not attempts and metrics.no_issue(baseline):
        status = TriggerStatus.NO_ISSUE
        msg = "원래 설명으로 모든 질의를 맞혀 고칠 것이 없습니다."
    elif passed:
        status = TriggerStatus.PROPOSED
        msg = f"{passed['attempt']}번째 수정안이 재검증을 통과해 승인 요청을 준비했습니다."
    else:
        status = TriggerStatus.NO_VALID_FIX
        msg = f"{len(attempts)}번 시도했지만 재검증을 통과한 수정안이 없습니다."

    def _brief(rows):
        return [{"query": r["query"], "selected": r["selected"]} for r in rows]

    result = {
        "target_skill": target["name"],
        "status": status,
        "before": {"description": target["description"], "metrics": metrics.summary(baseline)},
        "after": (
            {"description": passed["description"], "metrics": passed["metrics"]}
            if passed
            else None
        ),
        "diagnosis": passed["diagnosis"] if passed else state.get("diagnosis"),
        "rationale": passed["rationale"] if passed else None,
        "regressions": passed["regressions"] if passed else [],
        "attempt_count": len(attempts),
        "max_attempts": MAX_TRIGGER_ATTEMPTS,
        "history": [
            {
                "attempt": a["attempt"],
                "description": a["description"],
                "metrics": a["metrics"],
                "regressions": a["regressions"],
                "passed": a["passed"],
                "reasons": a["reasons"],
            }
            for a in attempts
        ],
        "evidence": {
            "case_count": len(state["cases"]),
            "repeat": state["repeat"],
            "misses_before": _brief(baseline["misses"]),
            "misses_after": _brief(passed["misses"]) if passed else [],
        },
    }
    return _progress(state, "report", msg, report=result, diagnosis_status=status)
