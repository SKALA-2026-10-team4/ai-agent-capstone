"""의도 확인 조건 사전 검사를 보호한다.

skillops-agent/skillops_agent/contracts.py 의 check_problem 과 context_keys 를 확인한다.
C(service/src/main/java/com/capstone4/skillops/scenario/Check.java)가 읽을 수 있는 조건만 보내야
한다. C 는 읽지 못하는 조건이 하나라도 있으면 run_scenarios 전체를 거절하기 때문이다.
check_problem 은 읽을 수 있고 실행 기록에 있는 값을 보는 조건을 통과시키고, 나머지는 이유와 함께
버려야 한다. context_keys 는 C 가 조건을 평가하는 문맥과 같은 범위(단계의 input 과 output 이름)만
모아야 한다. Check.java 의 문법을 바꾸면 이 표를 함께 고친다.
"""

import pytest

from skillops_agent.contracts import check_problem, context_keys

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
