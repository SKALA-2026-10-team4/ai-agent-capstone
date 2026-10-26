"""skillops-agent/skillops_agent/skill_rules.py 의 rules 읽기·쓰기와 후보 판정 규칙을 고정한다.

docs/plans/2026-10-26-behavior-edit-root-fix.md 2.2·2.6 과 2.10 V3-6·V3-7 을 보호한다. 결정적
encode_rule 후보는 바뀐 skill 과 scope 를 input 으로 받을 수 있는 가장 이른 단계 두 곳(같으면
하나)에 when=scope 규칙을 넣고, 기대 상태가 HOLD 면 hold, REJECTED 만 있으면 reject 이며, scope 키가
input 이 아니면 input 에 더한다. 같은 when 의 규칙이 이미 있으면 input 만 더한다. reason 은 YAML 1.1
불리언 낱말이 아니다. 형식이 틀린 기존 rules 와 블록 목록 inputs 도 예외 없이, 주석을 지키며 고친다.
is_revert 와 weakens_contract 는 코드가 정한다. 원문은 order-agent/skills 의 모양을 본떠 이 파일에
둔다(다른 작업자가 고치는 파일을 읽지 않는다). reason 의 불리언 낱말 사례는
docs/contracts/examples/skill-rules-cases.json 에서 읽는다.
"""

import json
import re
from pathlib import Path

import pytest

from skillops_agent import skill_rules as rules

PAYMENT = (
    "---\nname: payment\ndescription: 예약된 주문 금액을 결제한다\n"
    "inputs: [order.id, order.total, order.payment_method, inventory.reserved]\n"
    "outputs: [payment.id, payment.status]\ndepends_on: [inventory-check]\n"
    "owner: 주문운영팀\nversion: 1\n---\n# 결제\n\n결제합니다.\n선입금 주문만 결제합니다.\n"
)
INTAKE = (
    "---\nname: order-intake\ndescription: 접수\n"
    "inputs: [order.id, order.customer, order.items, order.payment_method, order.shipping]\n"
    "outputs: [order.id, order.total]\ndepends_on: []\nowner: 주문운영팀\nversion: 1\n---\n본문\n"
)
SHIPPING = (
    "---\nname: shipping-reserve\ndescription: 배송\n"
    "inputs: [order.id, order.shipping, payment.id]\noutputs: [shipping.id]\n"
    "depends_on: [payment]\nowner: 주문운영팀\nversion: 1\n---\n본문\n"
)
FLOW = (
    "---\nname: order-flow\ndescription: 순서\n"
    "steps: [order-intake, payment, shipping-reserve]\nowner: 주문운영팀\nversion: 1\n---\n본문\n"
)
SOURCES = {
    name: {"content": text, "version": 1}
    for name, text in (
        ("order-flow", FLOW),
        ("order-intake", INTAKE),
        ("payment", PAYMENT),
        ("shipping-reserve", SHIPPING),
    )
}
STEPS = ["order-intake", "payment", "shipping-reserve"]
CARD = "order.payment_method == 'card'"


def expectation(scope, *checks):
    """담당자 expectation 하나를 만든다."""
    return {"scope": scope, "checks": list(checks)}


@pytest.mark.parametrize(
    ("checks", "action"),
    [
        (["order.final_status in ['HOLD', 'REJECTED']", "payment.status == null"], "hold"),
        (["order.final_status == 'REJECTED'"], "reject"),
        (["order.final_status == 'HOLD'"], "hold"),
        (["order.final_status == 'SHIPPED'"], None),
        (["order.final_status != 'HOLD'"], None),
        (["payment.status == 'HOLD'"], None),
        ([], None),
    ],
)
def test_stop_action_reads_expected_final_status(checks, action):
    assert rules.stop_action(checks) == action


@pytest.mark.parametrize(
    ("text", "code"),
    [
        ("선입금 주문만 결제한다", "OWNER_POLICY"),
        ("prepaid only", "PREPAID_ONLY"),
        ("domestic 배송만", "DOMESTIC"),
        ("", "OWNER_POLICY"),
        (None, "OWNER_POLICY"),
        ("a " * 40, "A_A_A_A_A_A_A_A_A_A_A_A_A_A_A_A_A_A_A_A"),
    ],
)
def test_reason_code_matches_rule_reason_format(text, code):
    assert rules.reason_code(text) == code


def test_owner_rules_keep_only_scoped_stop_expectations():
    expectations = [
        expectation(CARD, "order.final_status in ['HOLD', 'REJECTED']"),
        expectation("order.payment_method == 'prepaid'", "order.final_status == 'SHIPPED'"),
        expectation(None, "order.final_status == 'HOLD'"),
        expectation("order.total >= 'x'", "order.final_status == 'HOLD'"),
    ]
    assert rules.owner_rules(expectations, "R") == [{"when": CARD, "then": "hold", "reason": "R"}]


def test_encode_rule_goes_to_changed_skill_and_keeps_other_lines():
    expectations = [expectation(CARD, "order.final_status == 'HOLD'")]
    intent = {"text": "카드 보류", "expectations": expectations}
    (skill, text), (earliest, _) = rules.encode_rule_revisions(intent, "payment", SOURCES, STEPS)
    assert (skill, earliest) == ("payment", "order-intake")
    assert text == PAYMENT.replace(
        "version: 1\n---",
        f'version: 1\nrules:\n  - when: "{CARD}"\n    then: hold\n    reason: OWNER_POLICY\n---',
    )
    meta = rules.frontmatter(text)
    assert rules.rules_of(meta) == [{"when": CARD, "then": "hold", "reason": "OWNER_POLICY"}]


def test_missing_condition_key_is_added_to_inputs():
    intent = {
        "text": "x",
        "expectations": [
            expectation("order.customer == 'c_declined'", "order.final_status == 'HOLD'")
        ],
    }
    [(_, text), (earliest, _)] = rules.encode_rule_revisions(intent, "payment", SOURCES, STEPS)
    assert earliest == "order-intake"
    meta = rules.frontmatter(text)
    assert rules.inputs_of(meta)[-1] == "order.customer"
    assert "outputs: [payment.id, payment.status]\n" in text


def test_flow_change_falls_back_to_earliest_step_that_can_read_the_key():
    """바뀐 skill 이 flow 면 scope 키를 받을 수 있는 가장 이른 단계에 넣는다.

    B-02 의 정답 위치(order-intake)와 같다.
    """
    intent = {
        "text": "domestic only",
        "expectations": [
            expectation("order.shipping.country != 'KR'", "order.final_status == 'REJECTED'")
        ],
    }
    [(skill, text)] = rules.encode_rule_revisions(intent, "order-flow", SOURCES, STEPS)
    assert skill == "order-intake"
    assert rules.rules_of(rules.frontmatter(text)) == [
        {"when": "order.shipping.country != 'KR'", "then": "reject", "reason": "DOMESTIC_ONLY"}
    ]


def test_order_total_rule_skips_steps_before_its_producer():
    """order.total 은 order-intake 가 내놓으므로 그 뒤 단계(payment)가 가장 이르다."""
    metas = {name: rules.frontmatter(src["content"]) for name, src in SOURCES.items()}
    without_total = {**metas, "payment": {**metas["payment"], "inputs": ["order.id"]}}
    assert rules.rule_target(None, STEPS, without_total, ["order.total"]) == "payment"
    assert rules.rule_target(None, STEPS, metas, ["order.customer"]) == "order-intake"
    assert rules.rule_target(None, STEPS, metas, ["shipping.eta"]) is None


def test_existing_rule_block_is_extended_with_same_indent():
    current = PAYMENT.replace(
        "owner:", 'rules:\n- when: "order.total >= 100000"\n  then: hold\n  reason: HIGH\nowner:'
    )
    revised = rules.add_rules(current, [{"when": CARD, "then": "reject", "reason": "NO_CARD"}])
    assert '- when: "order.total >= 100000"\n  then: hold\n  reason: HIGH\n- when: "' in revised
    assert [r["when"] for r in rules.rules_of(rules.frontmatter(revised))] == [
        "order.total >= 100000",
        CARD,
    ]


@pytest.mark.parametrize("empty", ["rules: []", "rules:", "rules: null"])
def test_empty_rule_block_is_rewritten(empty):
    current = PAYMENT.replace("owner:", f"{empty}\nowner:")
    revised = rules.add_rules(current, [{"when": CARD, "then": "hold", "reason": "R"}])
    expected = [{"when": CARD, "then": "hold", "reason": "R"}]
    assert rules.rules_of(rules.frontmatter(revised)) == expected


def test_add_rules_refuses_flow_skill_and_duplicate_rule():
    rule = {"when": CARD, "then": "hold", "reason": "R"}
    assert rules.add_rules(FLOW, [rule]) is None
    once = rules.add_rules(PAYMENT, [rule])
    assert rules.add_rules(once, [rule]) is None
    assert rules.add_rules("no frontmatter", [rule]) is None


def test_is_revert_only_looks_at_owner_changed_files():
    baseline = {"payment": PAYMENT.replace("선입금 주문만 결제합니다.\n", "")}
    reverted = {"payment": baseline["payment"].rstrip("\n")}
    assert rules.is_revert(reverted, baseline, {"payment"}) is True
    assert rules.is_revert({"payment": PAYMENT}, baseline, {"payment"}) is False
    assert rules.is_revert(reverted, baseline, {"order-intake"}) is False


def test_weakens_contract_on_rule_or_used_input_removal():
    rule = {"when": "order.total >= 1", "then": "hold", "reason": "R"}
    with_rule = rules.add_rules(PAYMENT, [rule])
    current = {"payment": with_rule, "order-intake": INTAKE}
    # 규칙을 지우면 약화다.
    assert rules.weakens_contract({"payment": PAYMENT}, current, []) is True
    # then·reason 만 바꾸면 약화가 아니다.
    retuned = with_rule.replace("then: hold", "then: reject")
    assert rules.weakens_contract({"payment": retuned}, current, []) is False
    # 규칙이 쓰는 input 을 지우면 약화다.
    no_total = with_rule.replace("order.total, ", "")
    assert rules.weakens_contract({"payment": no_total}, current, []) is True
    # 의도 scope 가 쓰는 input 을 지우면 약화다. 쓰지 않는 input 은 지워도 된다.
    no_method = PAYMENT.replace("order.payment_method, ", "")
    plain = {"payment": PAYMENT}
    assert rules.weakens_contract({"payment": no_method}, plain, ["order.payment_method"]) is True
    assert rules.weakens_contract({"payment": no_method}, plain, []) is False
    # 하위 경로를 덮는 다른 input 이 남으면 약화가 아니다.
    shipping = {"order-intake": INTAKE.replace("order.shipping]", "order.shipping, order.ship]")}
    kept = {"order-intake": INTAKE}
    assert rules.weakens_contract(kept, shipping, ["order.shipping.country"]) is False


@pytest.mark.parametrize(
    ("name", "key", "covered"),
    [
        ("order.shipping", "order.shipping.country", True),
        ("order.shipping", "order.shipping", True),
        ("order.ship", "order.shipping.country", False),
        ("order.total", "order.total.x", True),
    ],
)
def test_covers_follows_the_rule_key_contract(name, key, covered):
    assert rules.covers(name, key) is covered


# ── 계약 보정 v3 (2.10) ────────────────────────────────────────────────────────

DECLINED = "order.customer == 'c_declined'"
B05_PAYMENT = PAYMENT.replace(
    "owner:", f'rules:\n  - when: "{DECLINED}"\n    then: hold\n    reason: DECLINED\nowner:'
)


def test_same_when_rule_gets_only_the_missing_input():
    """B-05: 담당자가 넣은 규칙의 키가 inputs 에 없으면 규칙은 그대로 두고 input 만 더한다."""
    spaced = {"when": f"  {DECLINED} ", "then": "hold", "reason": "X"}
    revised = rules.add_rules(B05_PAYMENT, [spaced])
    assert revised == B05_PAYMENT.replace(
        "inventory.reserved]", "inventory.reserved, order.customer]"
    )
    assert rules.rules_of(rules.frontmatter(revised)) == [
        {"when": DECLINED, "then": "hold", "reason": "DECLINED"}
    ]
    # 이미 input 이 있으면 바꿀 것이 없다.
    assert rules.add_rules(revised, [{"when": DECLINED, "then": "hold", "reason": "X"}]) is None


def test_b05_revisions_fix_inputs_first_then_earliest_step():
    sources = {**SOURCES, "payment": {"content": B05_PAYMENT, "version": 1}}
    intent = {"text": "x", "expectations": [expectation(DECLINED, "order.final_status == 'HOLD'")]}
    (first, inputs_only), (second, _) = rules.encode_rule_revisions(
        intent, "payment", sources, STEPS
    )
    assert (first, second) == ("payment", "order-intake")
    assert "order.customer]" in inputs_only and inputs_only.count("when:") == 1


def test_b02_late_changed_step_also_gets_earliest_step_candidate():
    """B-02: 바뀐 skill 이 결제 뒤 단계(shipping-reserve)여도 가장 이른 단계 후보를 더 만든다."""
    intent = {
        "text": "domestic only",
        "expectations": [
            expectation("order.shipping.country != 'KR'", "order.final_status == 'REJECTED'")
        ],
    }
    revisions = rules.encode_rule_revisions(intent, "shipping-reserve", SOURCES, STEPS)
    assert [skill for skill, _ in revisions] == ["shipping-reserve", "order-intake"]
    metas = {name: rules.frontmatter(src["content"]) for name, src in SOURCES.items()}
    keys = ["order.shipping.country"]
    assert rules.rule_targets("shipping-reserve", STEPS, metas, keys) == [
        "shipping-reserve",
        "order-intake",
    ]
    assert rules.rule_targets("order-intake", STEPS, metas, keys) == ["order-intake"]
    assert rules.rule_target("order-flow", STEPS, metas, keys) == "order-intake"


SKILL_RULES_CASES = json.loads(
    (Path(__file__).resolve().parents[2] / "docs/contracts/examples/skill-rules-cases.json")
    .read_text(encoding="utf-8")
)
BOOL_REASONS = [
    re.search(r"reason: (\S+)", case["skills"]["payment"]).group(1)
    for case in SKILL_RULES_CASES["cases"]
    if case["id"].startswith("invalid-reason-yaml-bool")
]


@pytest.mark.parametrize(
    "text", ["no", "No", "ON", "off", "yes", "Y", "n", "true", "False", "null", *BOOL_REASONS]
)
def test_reason_code_never_is_a_yaml_boolean_word(text):
    assert len(BOOL_REASONS) == 2
    assert rules.reason_code(text) == rules.DEFAULT_REASON
    assert rules.reason_code(f"{text} card") == f"{text.upper()}_CARD"


MALFORMED = [
    'rules:\n  - when: "order.total >= 1"\n    reason: R\n',
    "rules:\n  - when: [order.total]\n    then: hold\n    reason: R\n",
    "rules: [{when: \"order.total >= 1\"}, {then: hold}, {}]\n",
    "rules:\n  - when: \"order.total >= 1\"\n    then: hold\n    reason: NO\n    else: ship\n",
]


@pytest.mark.parametrize("block", MALFORMED)
def test_malformed_existing_rules_never_crash(block):
    """then 이 없거나 when 이 목록인 기존 규칙이 있어도 후보 생성과 판정이 예외를 내지 않는다."""
    current = PAYMENT.replace("owner:", block + "owner:")
    revised = rules.add_rules(current, [{"when": CARD, "then": "hold", "reason": "R"}])
    assert revised is not None
    whens = [rules.when_of(r) for r in rules.rules_of(rules.frontmatter(revised))]
    assert CARD in whens
    sources = {**SOURCES, "payment": {"content": current, "version": 1}}
    intent = {"text": "x", "expectations": [expectation(CARD, "order.final_status == 'HOLD'")]}
    assert rules.encode_rule_revisions(intent, "payment", sources, STEPS)
    assert rules.weakens_contract({"payment": revised}, {"payment": current}, [CARD]) is False


def test_block_style_inputs_are_extended_in_place_with_comments_kept():
    current = PAYMENT.replace(
        "inputs: [order.id, order.total, order.payment_method, inventory.reserved]\n",
        "inputs:\n  # 주문 번호\n  - order.id\n  - order.total  # 결제 금액\n",
    )
    revised = rules.add_rules(current, [{"when": DECLINED, "then": "hold", "reason": "R"}])
    assert (
        "inputs:\n  # 주문 번호\n  - order.id\n  - order.total  # 결제 금액\n"
        "  - order.customer\noutputs:"
    ) in revised
    assert rules.inputs_of(rules.frontmatter(revised)) == [
        "order.id",
        "order.total",
        "order.customer",
    ]


def test_flow_inputs_keep_line_comment_and_quote_special_names():
    current = PAYMENT.replace(
        "inventory.reserved]\n", "inventory.reserved]  # 결제 입력\n"
    )
    revised = rules.add_rules(current, [{"when": DECLINED, "then": "hold", "reason": "R"}])
    assert "inventory.reserved, order.customer]  # 결제 입력\n" in revised
    lines = ["inputs: []"]
    added = rules._with_inputs(lines, {"inputs": []}, ["a:b.c", "yes"])
    assert added == ['inputs: ["a:b.c", "yes"]']
    assert rules.frontmatter("---\n" + added[0] + "\n---\n")["inputs"] == ["a:b.c", "yes"]


def test_weakens_contract_follows_the_shared_v3_definition():
    """지운 input 이 쓰는 키를 덮었으면 다른 input 이 여전히 덮어도 약화다.

    when 을 바꿔도 약화고, when 앞뒤 공백만 바꾸면 약화가 아니다(V3-7).
    """
    wider = INTAKE.replace("order.shipping]", "order.shipping, order.shipping.country]")
    both = {"order-intake": wider}
    narrower = {"order-intake": INTAKE}
    assert rules.weakens_contract(narrower, both, ["order.shipping.country"]) is True
    assert rules.weakens_contract(narrower, both, ["order.customer"]) is False
    rule = {"when": "order.total >= 1", "then": "hold", "reason": "R"}
    with_rule = rules.add_rules(PAYMENT, [rule])
    current = {"payment": with_rule}
    changed_when = with_rule.replace("order.total >= 1", "order.total >= 2")
    assert rules.weakens_contract({"payment": changed_when}, current, []) is True
    spaced = with_rule.replace('"order.total >= 1"', '" order.total >= 1 "')
    assert rules.weakens_contract({"payment": spaced}, current, []) is False
