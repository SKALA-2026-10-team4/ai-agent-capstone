"""호출(트리거) 진단 그래프의 흐름을 가짜 LLM 으로 검사한다.

가짜 선택 Agent 는 프롬프트에 들어간 weekly-report 의 description 을 보고 고른다. description 에
'주간'이 있어야 보고서 질의에서 weekly-report 를 고르고, 없으면 meeting-summary 가 대신 불린다.
그래서 개선안 description 만 바꿔 재현율 변화와 회귀를 만들 수 있다.
"""

import re
import uuid
from types import SimpleNamespace

import pytest

from skillops_agent import config, trigger_nodes
from skillops_agent.trigger_graph import trigger_graph

T = "weekly-report"
SKILLS = [
    {"name": T, "description": "문서를 정리한다."},
    {"name": "meeting-summary", "description": "회의 내용을 요약해 정리한다."},
]
CASES = [
    {"query": "주간 보고 써줘", "expected_skill": T, "should_trigger": True},
    {"query": "이번 주 보고서 정리", "expected_skill": T, "should_trigger": True},
    {"query": "회의 요약해줘", "expected_skill": "meeting-summary", "should_trigger": True},
    {"query": "점심 메뉴 추천", "expected_skill": "none", "should_trigger": True},
]

GOOD = "이번 주 업무를 묶어 주간 보고서를 쓴다. 회의 기록 요약은 meeting-summary 가 맡는다."
GREEDY = "주간 보고서와 회의 정리를 모두 맡는다."


def _select(prompt: str) -> str:
    """선택 프롬프트에서 질의와 weekly-report description 을 읽어 고른다."""
    query = prompt.split("## 사용자 요청\n", 1)[1].split("\n", 1)[0]
    desc = re.search(rf"- {T}: (.*)", prompt).group(1)
    if "보고" in query:
        if "주간" in desc:
            return T
        # '보고서'만 있는 description 은 '보고서' 질의만 맞힌다(부분 개선).
        if "보고서" in desc and "보고서" in query:
            return T
        return "meeting-summary"
    if "회의" in query:
        # 욕심 많은 description 은 회의 질의까지 빼앗는다(회귀).
        return T if "회의 정리" in desc else "meeting-summary"
    return "none"


class FakeLLM:
    """출력 클래스 이름으로 응답을 고르는 가짜 LLM 이다. 개선안은 proposals 를 차례로 낸다."""

    def __init__(self, proposals, eval_cases=None):
        self.proposals = list(proposals)
        self.eval_cases = eval_cases or []
        self.calls = {}

    def __call__(self, schema):
        fake = self

        class Chain:
            async def ainvoke(self, prompt):
                name = schema.__name__
                fake.calls[name] = fake.calls.get(name, 0) + 1
                if name == "_SelectionOutput":
                    return SimpleNamespace(skill=_select(prompt), reason="")
                if name == "_TriggerDiagnosisOutput":
                    return SimpleNamespace(
                        cause_type="too_generic",
                        summary="설명이 너무 일반적입니다.",
                        competing_skills=["meeting-summary", "없는-skill"],
                        missing_contexts=["주간 보고"],
                    )
                if name == "_DescriptionProposalOutput":
                    text = fake.proposals.pop(0) if len(fake.proposals) > 1 else fake.proposals[0]
                    return SimpleNamespace(description=text, rationale="사용 시점을 넣었다.")
                if name == "_EvalQueriesOutput":
                    return SimpleNamespace(
                        cases=[SimpleNamespace(model_dump=lambda c=c: c) for c in fake.eval_cases]
                    )
                raise AssertionError(name)

        return Chain()


@pytest.fixture
def llm(monkeypatch):
    """trigger_nodes 의 make_structured_llm 을 가짜로 바꿔 끼우는 함수를 돌려준다."""

    def install(proposals, eval_cases=None):
        fake = FakeLLM(proposals, eval_cases)
        monkeypatch.setattr(trigger_nodes, "make_structured_llm", fake)
        return fake

    return install


async def run(**overrides):
    state = {"target_skill": T, "skills": SKILLS, "cases": CASES, "repeat": 1, **overrides}
    return await trigger_graph.ainvoke(
        state, config={"configurable": {"thread_id": str(uuid.uuid4())}, "recursion_limit": 100}
    )


def nodes_of(result):
    return [e["node"] for e in result["events"]]


def test_tuning_values():
    assert config.MAX_TRIGGER_ATTEMPTS == 3
    assert config.TRIGGER_SELECT_REPEAT % 2 == 1
    assert config.TRIGGER_TARGET_RECALL == 0.9


async def test_proposes_fix_that_raises_recall(llm):
    llm([GOOD])
    result = await run()
    report = result["report"]
    assert result["diagnosis_status"] == "PROPOSED"
    assert nodes_of(result) == [
        "load_eval_set", "measure_baseline", "diagnose", "propose", "reverify", "report",
    ]
    assert report["before"]["metrics"]["recall"] == 0.0
    assert report["before"]["metrics"]["stolen_by"] == {"meeting-summary": 2}
    assert report["after"] == {"description": GOOD, "metrics": report["after"]["metrics"]}
    assert report["after"]["metrics"]["recall"] == 1.0
    assert report["regressions"] == []
    assert report["attempt_count"] == 1
    # 목록에 없는 경쟁 skill 이름은 버린다.
    assert report["diagnosis"]["competing_skills"] == ["meeting-summary"]
    assert {m["query"] for m in report["evidence"]["misses_before"]} == {
        "주간 보고 써줘", "이번 주 보고서 정리",
    }


async def test_retries_with_previous_attempts_then_passes(llm):
    fake = llm(["보고를 만든다.", GOOD])
    result = await run()
    assert result["diagnosis_status"] == "PROPOSED"
    assert result["report"]["attempt_count"] == 2
    assert nodes_of(result).count("diagnose") == 2
    assert result["report"]["history"][0]["passed"] is False
    assert fake.calls["_DescriptionProposalOutput"] == 2


async def test_keeps_improving_below_target_and_picks_best(llm):
    # 1번째 안은 재현율 0.5 로 통과했지만 목표(0.9)에 못 미쳐 한 번 더 고친다.
    llm(["보고서를 쓴다.", GOOD])
    result = await run()
    history = result["report"]["history"]
    assert [h["passed"] for h in history] == [True, True]
    assert [h["metrics"]["recall"] for h in history] == [0.5, 1.0]
    assert result["report"]["after"]["description"] == GOOD


async def test_proposes_best_partial_fix_when_target_not_reached(llm):
    llm(["보고서를 쓴다.", "보고서를 작성한다.", "보고를 만든다."])
    result = await run()
    assert result["diagnosis_status"] == "PROPOSED"
    assert result["report"]["attempt_count"] == config.MAX_TRIGGER_ATTEMPTS
    # 통과한 두 안의 점수가 같으면 먼저 나온 안을 고른다.
    assert result["report"]["after"]["description"] == "보고서를 쓴다."
    assert result["report"]["after"]["metrics"]["recall"] == 0.5


async def test_gives_up_after_max_attempts(llm):
    llm(["보고를 만든다."])
    result = await run()
    assert result["diagnosis_status"] == "NO_VALID_FIX"
    assert result["report"]["attempt_count"] == config.MAX_TRIGGER_ATTEMPTS
    assert result["report"]["after"] is None
    assert nodes_of(result).count("reverify") == config.MAX_TRIGGER_ATTEMPTS


async def test_rejects_fix_that_steals_other_skill_queries(llm):
    llm([GREEDY])
    result = await run()
    assert result["diagnosis_status"] == "NO_VALID_FIX"
    first = result["report"]["history"][0]
    assert first["metrics"]["recall"] == 1.0
    assert first["regressions"] == ["회의 요약해줘"]


async def test_invalid_proposal_is_not_measured(llm):
    fake = llm(["문서를 정리한다."])  # 원래 description 과 같다
    result = await run()
    assert result["diagnosis_status"] == "NO_VALID_FIX"
    assert result["report"]["history"][0]["reasons"] == ["원래 description 과 같습니다."]
    # 기준 측정 4번만 불렸다.
    assert fake.calls["_SelectionOutput"] == len(CASES)


async def test_no_issue_skips_diagnosis(llm):
    llm([GOOD])
    skills = [{"name": T, "description": GOOD}, SKILLS[1]]
    result = await run(skills=skills)
    assert result["diagnosis_status"] == "NO_ISSUE"
    assert "diagnose" not in nodes_of(result)


async def test_generates_eval_queries_when_cases_missing(llm):
    generated = [
        {"query": "주간 보고 부탁해", "expected_skill": T, "should_trigger": True},
        {"query": "회의 요약 부탁해", "expected_skill": "meeting-summary", "should_trigger": True},
        {"query": "없는 skill 질의", "expected_skill": "nope", "should_trigger": True},
        {"query": "주간 보고 부탁해", "expected_skill": T, "should_trigger": True},
    ]
    llm([GOOD], generated)
    result = await run(cases=[], seed_queries=["이번 주 보고서 정리해줘"])
    assert nodes_of(result)[:2] == ["load_eval_set", "generate_eval_queries"]
    # 없는 skill 과 중복 질의는 버린다.
    assert [c["query"] for c in result["cases"]] == ["주간 보고 부탁해", "회의 요약 부탁해"]
    assert result["diagnosis_status"] == "PROPOSED"


@pytest.mark.parametrize(
    "overrides, message",
    [
        ({"target_skill": "nope"}, "not in skills"),
        ({"cases": [], "seed_queries": []}, "cases or seed_queries"),
        ({"cases": [{"query": "x", "expected_skill": "nope", "should_trigger": True}]}, "nope"),
        ({"cases": [{"query": "x", "expected_skill": "none", "should_trigger": False}]}, "none"),
    ],
)
async def test_invalid_input_stops_with_error(llm, overrides, message):
    llm([GOOD])
    result = await run(**overrides)
    assert result["diagnosis_status"] == "ERROR"
    assert message in result["error"]
