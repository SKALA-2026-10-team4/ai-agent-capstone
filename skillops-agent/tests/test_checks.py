"""의도 확인 조건 사전 검사.

C(service/.../scenario/Check.java)가 읽을 수 있는 조건만 보내야 한다.
"""

import pytest

from skillops_agent.contracts import check_problem, context_keys

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
