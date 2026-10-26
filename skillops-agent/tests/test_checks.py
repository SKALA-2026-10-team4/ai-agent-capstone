"""의도 확인 조건 사전 검사를 보호한다.

skillops-agent/skillops_agent/contracts.py 의 check_problem 과 context_keys 를 확인한다.
C(service/src/main/java/com/capstone4/skillops/scenario/Check.java)가 읽을 수 있는 조건만 보내야
한다. C 는 읽지 못하는 조건이 하나라도 있으면 run_scenarios 전체를 거절하기 때문이다.
check_problem 은 읽을 수 있고 실행 기록에 있는 값을 보는 조건을 통과시키고, 나머지는 이유와 함께
버려야 한다. context_keys 는 C 가 조건을 평가하는 문맥과 같은 범위(단계의 input 과 output 이름)만
모아야 한다. Check.java 의 문법을 바꾸면 이 표를 함께 고친다. 조건 문법 v2(숫자 비교)의 읽기는
docs/contracts/examples/condition-cases.json 의 공용 사례로 확인한다.
"""

import json
from pathlib import Path

import pytest

from skillops_agent.contracts import (
    RESERVED_KEYS,
    check_problem,
    condition_holds,
    condition_parts,
    condition_problem,
    context_keys,
    literal_values,
)

# 실행 기록에 있다고 가정한 값 이름이다.
KNOWN = {"payment.status", "inventory.available", "notify.sent", "order.items"}


@pytest.mark.parametrize(
    "check",
    [
        "payment.status == 'PAID'",
        "payment.status != 'FAILED'",
        "inventory.available != null",
        "notify.sent == true",
        "inventory.available == 3.5",
        "payment.status in ['PAID', 'HOLD']",
        "  payment.status   ==   'PAID'  ",
    ],
)
def test_readable_checks_on_known_values_pass(check):
    assert check_problem(check, KNOWN) is None


@pytest.mark.parametrize(
    ("check", "reason"),
    [
        ("inventory-check.outputs == ['inventory.available']", "값을 읽을 수 없습니다"),
        ("payment.status = 'PAID'", "형식"),
        ("payment.status == PAID", "값을 읽을 수 없습니다"),
        ("payment.status in 'PAID'", "목록"),
        ("payment.status in ['PAID', PAID]", "목록"),
        ("inventory.reserved != null", "실행 기록에 없는 값 이름"),
        ("", "형식"),
    ],
)
def test_unreadable_or_unknown_checks_are_explained(check, reason):
    assert reason in check_problem(check, KNOWN)


def test_context_keys_come_from_skill_inputs_and_outputs():
    trace = {
        "events": [
            {"kind": "SKILL_STARTED", "data": {"input": {"order.items": []}}},
            {"kind": "SKILL_FINISHED", "data": {"output": {"inventory.available": 3}}},
            {"kind": "TOOL_REQUEST", "data": {"tool": "inventory.reserve"}},
        ]
    }
    assert context_keys(trace) == ["inventory.available", "order.items"]


# ── 조건 문법 v2 공용 사례 ─────────────────────────────────────────────────────
# docs/contracts/examples/condition-cases.json 은 service Check, 주문 Agent RuleCondition 과 함께
# 쓰는 사례다(docs/plans/2026-10-26-behavior-edit-root-fix.md 2.1). Python 은 읽기를 흉내 내고,
# 의도 scope 를 기준 주문의 처음 값으로 볼 때 쓰는 평가(condition_holds)도 같은 사례로 확인한다.
CONDITION_CASES_PATH = (
    Path(__file__).resolve().parents[2] / "docs/contracts/examples/condition-cases.json"
)
CONDITION_CASES = json.loads(CONDITION_CASES_PATH.read_text(encoding="utf-8"))


@pytest.mark.parametrize("text", [case["text"] for case in CONDITION_CASES["evaluate"]])
def test_condition_cases_are_readable(text):
    assert condition_problem(text) is None


@pytest.mark.parametrize("text", CONDITION_CASES["unreadable"])
def test_condition_cases_are_unreadable(text):
    assert condition_problem(text) is not None
    assert condition_holds(text, CONDITION_CASES["context"]) is None


@pytest.mark.parametrize(
    ("text", "holds"),
    [(case["text"], case["holds"]) for case in CONDITION_CASES["evaluate"]],
)
def test_condition_cases_evaluate_like_service(text, holds):
    """공용 사례 문맥에서 service Check.holds 와 같은 결과를 낸다(true 는 1 이 아니다)."""
    assert condition_holds(text, CONDITION_CASES["context"]) is holds


@pytest.mark.parametrize(
    ("check", "reason"),
    [
        ("order.total >= 'abc'", "숫자 비교"),
        ("order.total > null", "숫자 비교"),
        ("order.total>=1", "형식"),
        ("order.total => 1", "형식"),
    ],
)
def test_numeric_comparison_reasons(check, reason):
    assert reason in condition_problem(check)


def test_numeric_checks_on_known_values_pass_and_reserved_keys_need_listing():
    assert check_problem("inventory.available >= 1", KNOWN) is None
    assert check_problem("order.final_status == 'HOLD'", KNOWN) == "실행 기록에 없는 값 이름입니다"
    assert check_problem("order.final_status == 'HOLD'", KNOWN | set(RESERVED_KEYS)) is None


def test_condition_parts_and_literal_values():
    assert condition_parts("order.final_status in ['HOLD', 'REJECTED']") == (
        "order.final_status",
        "in",
        "['HOLD', 'REJECTED']",
    )
    assert condition_parts("order.total>=1") is None
    assert literal_values("['HOLD', 'REJECTED']") == ["HOLD", "REJECTED"]
    assert literal_values("'HOLD'") == ["HOLD"]
    assert literal_values("[1, true]") == []
