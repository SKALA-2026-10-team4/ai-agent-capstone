"""호출(트리거) 진단 그래프의 흐름을 가짜 LLM 으로 검사한다.

가짜 선택 Agent 는 프롬프트에 들어간 weekly-report 의 description 을 보고 고른다. description 에
'주간'이 있어야 보고서 질의에서 weekly-report 를 고르고, 없으면 meeting-summary 가 대신 불린다.
그래서 개선안 description 만 바꿔 재현율 변화와 회귀를 만들 수 있다.
"""

import json
import re
import textwrap
import uuid
from pathlib import Path
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

    def __init__(self, proposals, eval_cases=None, rubric_before=None, rubric_after=None):
        self.proposals = list(proposals)
        self.eval_cases = eval_cases or []
        # 의미 검사 판단이다. 원래 description(진단에 실림)과 통과한 수정안(재판단)에 쓴다.
        self.rubric_before = rubric_before or []
        self.rubric_after = rubric_after or []
        self.calls = {}
        self.prompts = {}
        self.messages = {}

    def __call__(self, schema):
        fake = self

        class Chain:
            async def ainvoke(self, prompt):
                name = schema.__name__
                fake.calls[name] = fake.calls.get(name, 0) + 1
                # system/user 로 나뉜 메시지는 원본을 남기고, 글 비교용으로 이어 붙인다.
                if isinstance(prompt, list):
                    fake.messages.setdefault(name, []).append(prompt)
                    prompt = "\n\n".join(text for _, text in prompt)
                fake.prompts.setdefault(name, []).append(prompt)
                if name == "_SelectionOutput":
                    return SimpleNamespace(skill=_select(prompt), reason="")
                if name == "_TriggerDiagnosisOutput":
                    return SimpleNamespace(
                        cause_type="too_generic",
                        summary="설명이 너무 일반적입니다.",
                        competing_skills=["meeting-summary", "없는-skill"],
                        missing_contexts=["주간 보고"],
                        rubric=fake.rubric_before,
                    )
                if name == "_RubricOutput":
                    return SimpleNamespace(items=fake.rubric_after)
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

    def install(proposals, eval_cases=None, rubric_before=None, rubric_after=None):
        fake = FakeLLM(proposals, eval_cases, rubric_before, rubric_after)
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
        ({"target_skill": "nope"}, "target_skill 'nope'"),
        ({"cases": [], "seed_queries": []}, "seed_queries"),
        ({"cases": [{"query": "x", "expected_skill": "nope", "should_trigger": True}]}, "nope"),
        ({"cases": [{"query": "x", "expected_skill": "none", "should_trigger": False}]}, "none"),
    ],
)
async def test_invalid_input_stops_with_error(llm, overrides, message):
    llm([GOOD])
    result = await run(**overrides)
    assert result["diagnosis_status"] == "ERROR"
    assert message in result["error"]


# ── 규칙 검사(skill_quality) 연결 ──────────────────────────────────────────────


def skill_md(name, description, extra=""):
    return f"---\nname: {name}\ndescription: {description}\n{extra}---\n# 본문\n내용\n"


async def test_skill_md_blocker_stops_before_any_llm_call(llm):
    fake = llm([GOOD])
    skills = [
        {"name": T, "skill_md": skill_md(T, "문서를 정리한다.", "steps:\n")},
        {**SKILLS[1], "skill_md": skill_md("meeting-summary", "회의 내용을 요약해 정리한다.")},
    ]
    result = await run(skills=skills)
    assert result["diagnosis_status"] == "BLOCKED"
    assert nodes_of(result) == ["load_eval_set", "report"]
    assert fake.calls == {}  # LLM 호출 0회
    quality = result["report"]["quality"]
    assert quality["grade"] == "F"
    assert [f["id"] for f in quality["findings"]] == ["rule.steps_not_empty"]
    assert result["report"]["before"]["metrics"] is None


async def test_description_is_read_from_skill_md(llm):
    llm([GOOD])
    skills = [
        {"name": T, "skill_md": skill_md(T, "문서를 정리한다.")},
        SKILLS[1],
    ]
    result = await run(skills=skills)
    assert result["skills"][0]["description"] == "문서를 정리한다."
    assert result["report"]["quality"]["grade"] == "S"
    assert result["diagnosis_status"] == "PROPOSED"


async def test_minor_findings_reach_diagnose_without_blocking(llm):
    fake = llm([GOOD])
    skills = [
        {"name": T, "skill_md": skill_md(T, "문서를 정리한다.") + "<!-- TODO -->\n"},
        SKILLS[1],
    ]
    result = await run(skills=skills)
    assert result["diagnosis_status"] == "PROPOSED"
    assert "rule.no_todo" in fake.prompts["_TriggerDiagnosisOutput"][0]
    assert result["report"]["quality"]["findings"][0]["severity"] == "MINOR"


async def test_without_skill_md_quality_is_unverified(llm):
    llm([GOOD])
    result = await run()
    assert result["report"]["quality"] == {"grade": "UNVERIFIED", "findings": [], "checked": 0}


async def test_proposal_copying_eval_query_is_rejected_before_selection(llm):
    fake = llm(["주간 보고: '이번 주 보고서 정리' 요청에 쓴다."])
    result = await run()
    first = result["report"]["history"][0]
    assert first["passed"] is False
    assert first["metrics"] is None
    assert any("평가 질의를 그대로 옮겼습니다" in r for r in first["reasons"])
    assert first["checks"][0]["id"] == "rule.proposal_no_query_copy"
    # 기준 측정만 선택 테스트를 돌렸다.
    assert fake.calls["_SelectionOutput"] == len(CASES)


async def test_eval_set_without_positive_case_errors_before_llm(llm):
    fake = llm([GOOD])
    cases = [c for c in CASES if c["expected_skill"] != T]
    result = await run(cases=cases)
    assert result["diagnosis_status"] == "ERROR"
    assert "불려야 하는 질의가 없어" in result["error"]
    assert result["quality"]["findings"][0]["id"] == "rule.target_has_positive"
    assert fake.calls == {}


async def test_body_and_plain_expectations_reach_diagnose_and_propose(llm):
    fake = llm([GOOD])
    skills = [{"name": T, "skill_md": skill_md(T, "문서를 정리한다.")}, SKILLS[1]]
    await run(skills=skills)
    for name in ("_TriggerDiagnosisOutput", "_DescriptionProposalOutput"):
        prompt = fake.prompts[name][0]
        assert "# 본문\n내용" in prompt  # SKILL.md 본문
        assert f"기대: {T} 이 불려야 함" in prompt
    # 선택 Agent 는 본문을 보지 않는다(이름과 description 만).
    assert "# 본문" not in fake.prompts["_SelectionOutput"][0]


async def test_generate_eval_set_uses_structure_and_remaps_sub_step_cases(llm):
    from skillops_agent.trigger_nodes import generate_eval_set

    flow = "---\nname: order-flow\ndescription: 주문 처리\nsteps: [payment]\n---\n본문\n"
    pay = "---\nname: payment\ndescription: 결제한다\n---\n본문\n"
    generated = [
        {"query": "카드로 주문해줘", "expected_skill": "order-flow", "should_trigger": True},
        {"query": "결제만 해줘", "expected_skill": "payment", "should_trigger": True},
        {"query": "날씨 알려줘", "expected_skill": "none", "should_trigger": True},
    ]
    fake = llm([GOOD], generated)
    result = await generate_eval_set({
        "target_skill": "order-flow",
        "skills": [{"name": "order-flow", "skill_md": flow}, {"name": "payment", "skill_md": pay}],
        "cases": [], "seed_queries": ["주문할게요"], "repeat": 1,
    })
    # 하위 단계 일만 시키는 질의는 규칙으로 none 이 된다(팀 기준).
    assert [(c["query"], c["expected_skill"]) for c in result["cases"]] == [
        ("카드로 주문해줘", "order-flow"), ("결제만 해줘", "none"), ("날씨 알려줘", "none"),
    ]
    assert result["remapped"] == [
        {"query": "결제만 해줘", "from": "payment", "to": "none", "should_trigger": True}
    ]
    assert result["structure"]["sub_steps"] == {"payment": "order-flow"}
    assert "payment 은 order-flow 의 하위 단계" in fake.prompts["_EvalQueriesOutput"][0]
    assert "_SelectionOutput" not in fake.calls  # 진단 그래프는 돌지 않는다


async def test_generate_eval_set_stops_on_blocker_without_llm(llm):
    from skillops_agent.trigger_nodes import generate_eval_set

    fake = llm([GOOD])
    result = await generate_eval_set({
        "target_skill": T,
        "skills": [{"name": T, "skill_md": skill_md(T, "")}, SKILLS[1]],
        "cases": [], "seed_queries": ["보고서 써줘"],
    })
    assert result["status"] == "BLOCKED" and result["cases"] == []
    assert fake.calls == {}


def test_remap_sub_step_by_structure():
    # 팀 기준: 하위 단계 일만 시키는 요청은 intent 와 관계없이 진입 skill 도 불리면 안 된다.
    from skillops_agent.trigger_nodes import _remap_sub_step

    one_entry = {"entries": ["order-flow"], "sub_steps": {"payment": "order-flow"}}
    two_entries = {"entries": ["order-flow", "faq"], "sub_steps": {"payment": "order-flow"}}
    for intent in ("should_call", "should_not_call", None):
        case = {"intent": intent, "query": "결제 진행해줘", "expected_skill": "payment",
                "should_trigger": True}
        fixed = _remap_sub_step(case, one_entry, "order-flow")
        assert (fixed["expected_skill"], fixed["should_trigger"]) == ("none", True)
        fixed = _remap_sub_step(case, two_entries, "order-flow")
        assert (fixed["expected_skill"], fixed["should_trigger"]) == ("order-flow", False)
    # 하위 단계가 정답이 아니면 그대로 둔다.
    plain = {"intent": "should_call", "query": "주문", "expected_skill": "order-flow",
             "should_trigger": True}
    assert _remap_sub_step(plain, one_entry, "order-flow") is plain


# ── 주문 Agent 선택 모드 ───────────────────────────────────────────────────────

REPO = Path(__file__).resolve().parents[2]
ORDER = {"customer": "c_1", "items": [{"sku": "S", "qty": 1}], "payment_method": "card",
         "shipping": {"country": "KR", "express": False}}


def test_order_agent_prompt_matches_real_system_prompt():
    # 실제 주문 Agent 의 시스템 지시가 바뀌면 이 테스트가 깨져 복사본을 맞추게 한다.
    java = (REPO / "order-agent/backend/src/main/java/com/capstone4/orderagent/harness/"
            "OpenAiDecisionProvider.java").read_text("utf-8")
    real = textwrap.dedent(re.search(r'SYSTEM_PROMPT="""\n(.*?)"""', java, re.S).group(1))
    from skillops_agent.prompts import load

    assert load("select_order_agent") == real


async def test_order_agent_selector_sends_real_question_shape(monkeypatch):
    from skillops_agent import trigger_nodes as n

    sent = []

    class Chain:
        async def ainvoke(self, messages):
            sent.append(messages)
            return SimpleNamespace(skill="order-flow")

    monkeypatch.setattr(n, "make_structured_llm", lambda schema: Chain())
    skills = [{"name": "order-flow", "description": "주문", "version": 3},
              {"name": "payment", "description": "결제"}]
    case = {"query": "g-01", "expected_skill": "order-flow", "should_trigger": True,
            "order": ORDER}
    picks = await n._select_all([case], skills, 2, "order-agent")
    assert picks == [["order-flow", "order-flow"]]
    system, human = sent[0]
    assert system[0] == "system" and "choose one offered entry skill" in system[1]
    question = json.loads(human[1])
    assert question["purpose"] == "select" and question["tool_definition"] == {}
    assert question["candidates"][0] == {"skill_id": "order-flow", "name": "order-flow",
                                         "description": "주문", "version": 3}
    assert question["inputs"]["order.payment_method"] == "card"
    assert set(question["inputs"]) == {"order.id", "order.items", "order.customer",
                                       "order.payment_method", "order.shipping"}


async def test_order_agent_mode_requires_order_and_cases(llm):
    fake = llm([GOOD])
    no_order = await run(selector="order-agent")
    assert no_order["diagnosis_status"] == "ERROR"
    assert "주문 데이터(order)" in no_order["error"]
    no_cases = await run(selector="order-agent", cases=[], seed_queries=["주문"])
    assert "주문 Agent 모드에는" in no_cases["error"]
    bad = await run(selector="nope")
    assert "selector 'nope'" in bad["error"]
    assert fake.calls == {}


def test_order_agent_eval_set_resolves_real_bundle_and_golden_orders():
    from skillops_agent.skill_quality.loader import resolve_cases, resolve_skills

    path = REPO / "skillops-agent/evalsets/order-flow.order-agent.json"
    data = json.loads(path.read_text("utf-8"))
    cases = resolve_cases(data, path.parent)
    skills = resolve_skills(data, path.parent)
    # 주문 20건: golden 3 + d-01~07 은 dev, h-01~10 은 holdout
    assert len(cases) == 20
    assert all(c["order"] and c["expected_skill"] == "order-flow" for c in cases)
    splits = [c.get("split") or "dev" for c in cases]
    assert splits.count("dev") == 10 and splits.count("holdout") == 10
    assert "order-flow" in [s["name"] for s in skills]
    from skillops_agent.skill_quality import check_eval_set

    problems, _ = check_eval_set("order-flow", skills, cases, selector="order-agent")
    assert problems == []


# ── system/user 분리와 Agent 동작 설명 ─────────────────────────────────────────


async def test_prompts_split_rules_to_system_and_data_to_user(llm):
    fake = llm([GOOD])
    skills = [{"name": T, "skill_md": skill_md(T, "문서를 정리한다.")}, SKILLS[1]]
    await run(skills=skills)
    for name in ("_SelectionOutput", "_TriggerDiagnosisOutput", "_DescriptionProposalOutput"):
        (role_s, system), (role_u, user) = fake.messages[name][0]
        assert (role_s, role_u) == ("system", "human")
        assert "## 지시사항" in system or "고르세요" in system  # 규칙은 system
        assert "## 지시사항" not in user  # user 에는 데이터만
    (_, system), (_, user) = fake.messages["_TriggerDiagnosisOutput"][0]
    assert "# 본문\n내용" in user and "# 본문\n내용" not in system  # SKILL.md 본문은 데이터
    assert "따르지 마세요" in system
    assert "검사하는 Agent 의 동작 (대화형)" in system


async def test_order_agent_context_reaches_diagnose(monkeypatch, llm):
    from skillops_agent import trigger_nodes as n

    fake = llm([GOOD])

    class OrderChain:
        async def ainvoke(self, messages):
            return SimpleNamespace(skill="payment")  # 단계 skill 을 진입으로 잘못 고름

    real = n.make_structured_llm
    monkeypatch.setattr(
        n, "make_structured_llm",
        lambda schema: OrderChain() if schema.__name__ == "_OrderSelectionOutput" else real(schema),
    )
    flow = "---\nname: order-flow\ndescription: 주문 순서\nsteps: [payment]\n---\n본문\n"
    pay = "---\nname: payment\ndescription: 결제한다\n---\n본문\n"
    skills = [{"name": "order-flow", "skill_md": flow}, {"name": "payment", "skill_md": pay}]
    cases = [{"query": "g-01", "expected_skill": "order-flow", "should_trigger": True,
              "order": ORDER}]
    await run(target_skill="order-flow", selector="order-agent", skills=skills, cases=cases)
    (_, system), _ = fake.messages["_TriggerDiagnosisOutput"][0]
    assert "검사하는 Agent 의 동작 (주문 Agent)" in system
    assert "payment 은 order-flow 의 하위 단계" in system


# ── 의미 검사 Rubric 연결 ──────────────────────────────────────────────────────

RUBRIC_FAIL = [
    SimpleNamespace(id="3-1", passed=False, reason="WHEN 없음", fix="시점을 넣으세요"),
    SimpleNamespace(id="3-2", passed=True, reason="", fix=""),
    SimpleNamespace(id="3-3", passed=True, reason="", fix=""),
    SimpleNamespace(id="3-4", passed=False, reason="시점이 본문에만 있음", fix="description 으로"),
    SimpleNamespace(id="3-5", passed=True, reason="", fix=""),
]
RUBRIC_PASS = [SimpleNamespace(id=f"3-{i}", passed=True, reason="", fix="") for i in range(1, 6)]


async def test_rubric_grades_before_and_after_without_blocking(llm):
    fake = llm([GOOD], rubric_before=RUBRIC_FAIL, rubric_after=RUBRIC_PASS)
    skills = [{"name": T, "skill_md": skill_md(T, "문서를 정리한다.")}, SKILLS[1]]
    result = await run(skills=skills)
    sem = result["report"]["semantic"]
    assert result["diagnosis_status"] == "PROPOSED"  # 의미 검사 FAIL 은 흐름을 막지 않는다
    # 3-1(MAJOR) + 3-4(의미 검사 BLOCKER 는 MAJOR 로 셈) = 2개 → A
    assert sem["grade_before"] == "A" and sem["grade_after"] == "S"
    by_id = {f["id"]: f for f in sem["before"]}
    assert by_id["semantic.3-4"]["evidence"].startswith("실행으로 확인됨")  # 재현율 0
    assert sem["after"] == []
    # 원래 description 판단은 진단 호출에 실리고, 재판단은 통과한 수정안에만 1회다.
    assert fake.calls["_TriggerDiagnosisOutput"] == 1 and fake.calls["_RubricOutput"] == 1
    assert "의미 검사 Rubric (대화형 Agent 기준)" in fake.prompts["_TriggerDiagnosisOutput"][0]


async def test_rubric_not_reevaluated_for_failed_proposals(llm):
    fake = llm(["보고를 만든다."], rubric_before=RUBRIC_FAIL)
    result = await run()
    assert result["diagnosis_status"] == "NO_VALID_FIX"
    assert "_RubricOutput" not in fake.calls
    assert result["report"]["semantic"]["grade_after"] is None


async def test_order_agent_mode_uses_order_agent_rubric(monkeypatch, llm):
    from skillops_agent import trigger_nodes as n

    fake = llm([GOOD], rubric_before=RUBRIC_FAIL)
    real = n.make_structured_llm

    class OrderChain:
        async def ainvoke(self, messages):
            return SimpleNamespace(skill="payment")

    monkeypatch.setattr(
        n, "make_structured_llm",
        lambda schema: OrderChain() if schema.__name__ == "_OrderSelectionOutput" else real(schema),
    )
    flow = "---\nname: order-flow\ndescription: 주문 순서\nsteps: [payment]\n---\n본문\n"
    pay = "---\nname: payment\ndescription: 결제한다\n---\n본문\n"
    skills = [{"name": "order-flow", "skill_md": flow}, {"name": "payment", "skill_md": pay}]
    cases = [{"query": "g-01", "expected_skill": "order-flow", "should_trigger": True,
              "order": ORDER}]
    await run(target_skill="order-flow", selector="order-agent", skills=skills, cases=cases)
    (_, system), _ = fake.messages["_TriggerDiagnosisOutput"][0]
    assert "의미 검사 Rubric (주문 Agent 기준)" in system
    assert "새 주문이 들어오면 가장 먼저 선택되어" in system


async def test_llm_never_sees_holdout_queries_and_report_says_how_scored(llm):
    fake = llm([GOOD])
    hold = [{**c, "query": "HOLDOUT " + c["query"], "split": "holdout"} for c in CASES]
    result = await run(cases=CASES + hold)
    for name in ("_TriggerDiagnosisOutput", "_DescriptionProposalOutput"):
        assert "HOLDOUT" not in fake.prompts[name][0]  # 채점용 질의는 LLM 에 보이지 않는다
    report = result["report"]
    assert report["evaluation"]["mode"] == "holdout"
    assert (report["evaluation"]["dev_cases"], report["evaluation"]["holdout_cases"]) == (4, 4)
    assert report["after"]["metrics"]["holdout"]["recall"] == 1.0
    assert report["after"]["metrics"]["holdout"]["counts"]["recall_hits"] == "2/2"
    assert any("10개 미만" in n for n in report["evaluation"]["notes"])


async def test_same_set_scoring_is_stated_in_report(llm):
    llm([GOOD])
    report = (await run())["report"]
    assert report["evaluation"]["mode"] == "same_set"
    assert any("처음 보는 질의" in n for n in report["evaluation"]["notes"])
