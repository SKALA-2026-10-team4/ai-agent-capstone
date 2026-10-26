"""평가셋 실행기의 판정 로직을 보호한다.

qa/qa_logic.py 는 서버를 부르지 않는 순수 함수만 모은다. qa/run_qa.py 의 check 와 eval 이 이 함수로
케이스 파일을 읽고, 수정본을 만들고, 주문 Agent 반응과 SkillOps 제안서를 정답표와 비교한다.
여기서 판정 규칙(qa/README.md 의 R1~R5)과 지표 계산이 어긋나면 평가 결과 전체를 믿을 수 없으므로,
서버 없이 돌 수 있는 이 테스트로 먼저 고정한다.
"""

import pytest

from qa_logic import (
    GOLDEN_IDS,
    CaseError,
    PatchError,
    apply_changes,
    apply_unified_patch,
    check_fix,
    execution_uuid,
    fix_verdict,
    grade_eval,
    intent_failures,
    intent_preflight_status,
    load_case,
    match_expectation,
    observe_golden,
    preflight_status,
    rag_exclusions,
    reverted_files,
    rule_changes,
    rule_whens,
    scope_keys,
    skill_rules,
    snapshot_metadata,
    summarize,
    unified_patch,
    validate_case_ids,
    weakened,
)

INV = "skills/inventory-check/SKILL.md"
NOTI = "skills/notify/SKILL.md"
BASE = {
    INV: (
        "---\nname: inventory-check\noutputs: [inventory.available, inventory.reserved]\n"
        "---\n본문\n"
    ),
    NOTI: "---\nname: notify\ninputs: [order.id, shipping.eta]\n---\n본문\n",
}
# C-01 과 같은 모양의 담당자 수정이다.
OWNER = [
    {
        "file": INV,
        "from": "outputs: [inventory.available, inventory.reserved]",
        "to": "outputs: [inventory.reservable_quantity]",
    }
]


def minimal_case(**overrides):
    data = {
        "id": "C-01",
        "group": "C",
        "title": "재고 확인 outputs 변경",
        "set": "dev",
        "run": "auto",
        "change": OWNER,
        "answer": {"status": ["EVALUATED"], "proposal": ["proposed"], "fixable": True},
    }
    data.update(overrides)
    return data


# ── 케이스 읽기 ──────────────────────────────────────────────────────────────


def test_load_case_fills_defaults():
    case = load_case(minimal_case(), "C-01.yaml")
    assert case["base"] == "baseline"
    assert case["trigger"] == "skill_saved"
    assert case["answer"]["revert_allowed"] is False
    assert case["change"][0]["cause"] is True
    assert case["fixes"] == []


@pytest.mark.parametrize("missing", ["id", "group", "title", "set", "run"])
def test_load_case_rejects_missing_required_field(missing):
    data = minimal_case()
    del data[missing]
    with pytest.raises(CaseError, match=missing):
        load_case(data, "bad.yaml")


def test_load_case_rejects_unknown_run_kind():
    with pytest.raises(CaseError, match="run"):
        load_case(minimal_case(run="sometimes"), "bad.yaml")


def test_auto_case_needs_answer_status():
    data = minimal_case()
    data["answer"] = {"fixable": True}
    with pytest.raises(CaseError, match="answer.status"):
        load_case(data, "bad.yaml")


def test_manual_case_needs_steps_and_expected():
    data = {"id": "E-02", "group": "E", "title": "중복 전달", "set": "dev", "run": "manual"}
    with pytest.raises(CaseError, match="steps"):
        load_case(data, "bad.yaml")


def test_duplicate_case_ids_are_rejected():
    cases = [load_case(minimal_case(), "a.yaml"), load_case(minimal_case(), "b.yaml")]
    with pytest.raises(CaseError, match="C-01"):
        validate_case_ids(cases)


# ── 수정본 만들기 ────────────────────────────────────────────────────────────


def test_apply_changes_replaces_exactly_once_and_keeps_other_files():
    texts = apply_changes(BASE, OWNER)
    assert "outputs: [inventory.reservable_quantity]" in texts[INV]
    assert texts[NOTI] == BASE[NOTI]
    assert BASE[INV].count("reservable") == 0  # 원본은 바꾸지 않는다.


def test_apply_changes_rejects_missing_text():
    with pytest.raises(CaseError, match="찾지 못했습니다"):
        apply_changes(BASE, [{"file": INV, "from": "없는 줄", "to": "x"}])


def test_apply_changes_rejects_ambiguous_text():
    texts = {INV: "a\na\n"}
    with pytest.raises(CaseError, match="두 번 이상"):
        apply_changes(texts, [{"file": INV, "from": "a", "to": "b"}])


# ── patch 만들기와 적용 ──────────────────────────────────────────────────────


def test_unified_patch_round_trip():
    after = apply_changes(BASE, OWNER)[INV]
    patch = unified_patch(INV, BASE[INV], after)
    assert patch.startswith(f"--- a/{INV}\n+++ b/{INV}\n@@")
    assert apply_unified_patch(BASE[INV], patch) == after


def test_unified_patch_is_empty_when_nothing_changed():
    assert unified_patch(INV, BASE[INV], BASE[INV]) == ""


def test_apply_unified_patch_rejects_context_mismatch():
    after = apply_changes(BASE, OWNER)[INV]
    patch = unified_patch(INV, BASE[INV], after)
    with pytest.raises(PatchError):
        apply_unified_patch(after, patch)


# ── 되돌리기 판정(R1) ────────────────────────────────────────────────────────


def test_reverted_files_lists_owner_files_back_to_base():
    candidate = apply_changes(BASE, OWNER)
    assert reverted_files(BASE, dict(BASE), [INV]) == [INV]
    assert reverted_files(BASE, candidate, [INV]) == []


def test_fix_on_another_file_is_not_a_revert():
    candidate = apply_changes(BASE, OWNER)
    fixed = apply_changes(candidate, [{"file": NOTI, "from": ", shipping.eta", "to": ""}])
    assert reverted_files(BASE, fixed, [INV]) == []


def test_trailing_newline_does_not_hide_a_revert():
    result = {INV: BASE[INV].rstrip("\n"), NOTI: BASE[NOTI]}
    assert reverted_files(BASE, result, [INV]) == [INV]


@pytest.mark.parametrize(
    ("tests_pass", "reverted", "revert_allowed", "policy", "valid"),
    [
        (True, False, False, None, True),
        (False, False, False, None, False),
        (True, True, False, None, False),  # 되돌리기는 개선안이 아니다(R1).
        (True, True, True, None, True),  # 오타·중복·형식 오류는 되돌리기를 허용한다.
        (True, False, False, "입력 삭제(R3)", False),
    ],
)
def test_fix_verdict(tests_pass, reverted, revert_allowed, policy, valid):
    assert fix_verdict(tests_pass, reverted, revert_allowed, policy) is valid


# ── 주문 Agent 반응 ──────────────────────────────────────────────────────────


def test_execution_uuid_accepts_run_id_and_uuid():
    run_id = "r_7be18d9f197b4abc9aad843d6b22ed8a"
    assert execution_uuid(run_id) == "7be18d9f-197b-4abc-9aad-843d6b22ed8a"
    assert execution_uuid("7be18d9f-197b-4abc-9aad-843d6b22ed8a") == execution_uuid(run_id)


def failed_trace(category, skill=None, missing=None, deps=None, completion="FAILED"):
    data = {"category": category}
    if skill:
        data["skill_id"] = skill
    if missing is not None:
        data["missing_inputs"] = missing
    if deps is not None:
        data["missing_dependencies"] = deps
    return {
        "completion": completion,
        "final_status": "FAILED",
        "events": [
            {"kind": "SKILL_STARTED", "data": {}},
            {"kind": "EXECUTION_ERROR", "data": data},
        ],
    }


def test_observe_golden_classifies_three_results():
    assert observe_golden(True, None, [])["result"] == "pass"
    error = observe_golden(
        False, failed_trace("CONTRACT_VIOLATION", "payment", ["inventory.reserved"]), []
    )
    assert error == {
        "result": "error",
        "category": "CONTRACT_VIOLATION",
        "skill": "payment",
        "missing": ["inventory.reserved"],
        "deps": [],
        "reasons": [],
    }
    completed = {"completion": "COMPLETED", "final_status": "SHIPPED", "events": []}
    check_failed = observe_golden(
        False, completed, ["확인 조건 실패: notify.sent == true (실제 null)"]
    )
    assert check_failed["result"] == "check_failed"


def test_match_expectation_compares_only_written_fields():
    observed = observe_golden(False, failed_trace("INCOMPLETE_FLOW"), [])
    assert (
        match_expectation(
            {"result": "error", "category": "INCOMPLETE_FLOW", "skill": None}, observed
        )
        == []
    )
    problems = match_expectation({"result": "error", "category": "UNKNOWN_SKILL"}, observed)
    assert problems and "category" in problems[0]


def test_match_expectation_checks_missing_values_as_a_set():
    observed = observe_golden(
        False,
        failed_trace(
            "CONTRACT_VIOLATION", "shipping-reserve", ["payment.status", "payment.id"], ["payment"]
        ),
        [],
    )
    expected = {"result": "error", "missing": ["payment.id", "payment.status"], "deps": ["payment"]}
    assert match_expectation(expected, observed) == []
    assert match_expectation({"result": "error", "missing": ["payment.id"]}, observed)


def test_match_expectation_reason_is_a_substring():
    observed = observe_golden(
        False, {"completion": "COMPLETED", "events": []}, ["확인 조건 실패: notify.sent == true"]
    )
    assert (
        match_expectation({"result": "check_failed", "reason": "notify.sent == true"}, observed)
        == []
    )
    assert match_expectation({"result": "check_failed", "reason": "payment.status"}, observed)


def test_preflight_status_follows_the_first_failed_order():
    passed = observe_golden(True, None, [])
    error = observe_golden(False, failed_trace("CONTRACT_VIOLATION", "payment"), [])
    result_only = observe_golden(
        False, {"completion": "COMPLETED", "events": []}, ["확인 조건 실패"]
    )
    assert preflight_status([passed, passed, passed]) == "VALIDATED"
    assert preflight_status([error, passed, passed]) == "DIAGNOSE"
    assert preflight_status([result_only, error, passed]) == "VALIDATION_FAILED"


def test_check_fix_reports_disagreement_with_answer_key():
    fix = {"name": "되돌리기", "tests_pass": True, "valid": False, "failure": None, "policy": None}
    observed = {"tests_pass": True, "reverted": True, "failing": [], "revert_allowed": False}
    assert check_fix(fix, observed) == []
    observed_pass_but_key_says_valid = dict(fix, valid=True)
    problems = check_fix(observed_pass_but_key_says_valid, observed)
    assert problems and "valid" in problems[0]


def test_check_fix_requires_the_expected_failure_reason():
    fix = {
        "name": "이름 유지",
        "tests_pass": False,
        "valid": False,
        "failure": "MISSING_SKILL_OUTPUT",
        "policy": None,
    }
    observed = {
        "tests_pass": False,
        "reverted": False,
        "failing": ["payment 단계 오류: TOOL_ERROR"],
        "revert_allowed": False,
    }
    problems = check_fix(fix, observed)
    assert problems and "MISSING_SKILL_OUTPUT" in problems[0]


# ── eval 채점 ────────────────────────────────────────────────────────────────


def proposal_with(candidates, status="proposed", root_skill="inventory-check"):
    return {
        "status": status,
        "root_cause": {"skill": root_skill} if root_skill else None,
        "candidates": candidates,
        "recommended_candidate_no": candidates[0]["candidate_no"] if candidates else None,
    }


def candidate(no, before, after, skill="inventory-check", path=INV, valid=True):
    return {
        "candidate_no": no,
        "valid": valid,
        "patches": [
            {"skill": skill, "base_version": 1, "patch": unified_patch(path, before, after)}
        ],
    }


GOOD_FIX = {
    "name": "reserved 도 함께 넘김",
    "change": [
        {
            "file": INV,
            "from": "outputs: [inventory.reservable_quantity]",
            "to": "outputs: [inventory.reservable_quantity, inventory.reserved]",
        }
    ],
    "tests_pass": True,
    "valid": True,
}


def c01_case(**answer):
    data = minimal_case(fixes=[GOOD_FIX])
    data["answer"].update({"root_cause": ["inventory-check"], **answer})
    return load_case(data, "C-01.yaml")


def test_grade_eval_passes_a_known_good_fix():
    case = c01_case()
    cand = apply_changes(BASE, case["change"])
    good = apply_changes(cand, GOOD_FIX["change"])[INV]
    proposal = proposal_with([candidate(1, cand[INV], good)])
    graded = grade_eval(case, "EVALUATED", proposal, [1], BASE, cand)
    assert graded["verdict"] == "PASS"
    assert graded["presented_fix"] is True
    assert graded["root_cause_ok"] is True


def test_grade_eval_fails_when_a_revert_is_judged_valid():
    case = c01_case()
    cand = apply_changes(BASE, case["change"])
    proposal = proposal_with([candidate(1, cand[INV], BASE[INV])])
    graded = grade_eval(case, "EVALUATED", proposal, [1], BASE, cand)
    assert graded["verdict"] == "FAIL"
    assert graded["revert_proposals"] == 1
    assert any("되돌리기" in r for r in graded["reasons"])


def test_grade_eval_ignores_invalid_candidates():
    case = c01_case()
    cand = apply_changes(BASE, case["change"])
    good = apply_changes(cand, GOOD_FIX["change"])[INV]
    proposal = proposal_with(
        [candidate(1, cand[INV], BASE[INV], valid=False), candidate(2, cand[INV], good)]
    )
    graded = grade_eval(case, "EVALUATED", proposal, [2], BASE, cand)
    assert graded["verdict"] == "PASS"
    assert graded["revert_proposals"] == 0


def test_grade_eval_fails_on_a_fix_the_answer_key_rejects():
    bad_fix = dict(GOOD_FIX, name="입력 삭제", valid=False, policy="입력 삭제(R3)")
    data = minimal_case(fixes=[bad_fix])
    data["answer"]["root_cause"] = ["inventory-check"]
    case = load_case(data, "X.yaml")
    cand = apply_changes(BASE, case["change"])
    proposal = proposal_with([candidate(1, cand[INV], apply_changes(cand, bad_fix["change"])[INV])])
    graded = grade_eval(case, "EVALUATED", proposal, [1], BASE, cand)
    assert graded["verdict"] == "FAIL"
    assert any("입력 삭제" in r for r in graded["reasons"])


def test_grade_eval_marks_conflict_when_unfixable_case_gets_a_new_fix():
    case = c01_case(fixable=False, proposal=["no_valid_fix"])
    cand = apply_changes(BASE, case["change"])
    surprise = cand[INV].replace("본문", "다른 본문")
    proposal = proposal_with([candidate(1, cand[INV], surprise)])
    graded = grade_eval(case, "EVALUATED", proposal, [1], BASE, cand)
    assert graded["verdict"] in {"FAIL", "CONFLICT"}
    assert any("재검증" in r for r in graded["reasons"])


def test_grade_eval_checks_status_and_root_cause():
    case = c01_case()
    cand = apply_changes(BASE, case["change"])
    graded = grade_eval(case, "ERROR", None, [], BASE, cand)
    assert graded["verdict"] == "FAIL"
    assert graded["detected"] is False
    wrong_root = proposal_with([], status="no_valid_fix", root_skill="payment")
    graded = grade_eval(case, "EVALUATED", wrong_root, [], BASE, cand)
    assert graded["root_cause_ok"] is False
    assert graded["verdict"] == "FAIL"


def test_grade_eval_needs_every_cause_for_multi_cause_case():
    data = minimal_case()
    data["answer"]["root_cause_all"] = ["inventory-check", "shipping-reserve"]
    case = load_case(data, "G-01.yaml")
    cand = apply_changes(BASE, case["change"])
    graded = grade_eval(case, "EVALUATED", proposal_with([], status="proposed"), [], BASE, cand)
    assert graded["root_cause_ok"] is False


def test_multi_cause_case_can_pass_with_one_cause_and_a_fix_for_all():
    """제안서는 원인을 하나만 담으므로(D3), 원인 하나를 짚고 모든 원인을 고친 합격안을 내면 맞다.

    예전에는 set(root_cause_all) <= {found} 라 원인이 둘인 G-01 은 어떤 답으로도 통과할 수 없었다.
    """
    data = minimal_case(fixes=[GOOD_FIX])
    data["answer"]["root_cause_all"] = ["inventory-check", "shipping-reserve"]
    case = load_case(data, "G-01.yaml")
    cand = apply_changes(BASE, case["change"])
    good = apply_changes(cand, GOOD_FIX["change"])[INV]
    graded = grade_eval(
        case, "EVALUATED", proposal_with([candidate(1, cand[INV], good)]), [1], BASE, cand
    )
    assert graded["root_cause_ok"] is True
    assert graded["verdict"] == "PASS"
    outside = proposal_with([candidate(1, cand[INV], good)], root_skill="payment")
    assert grade_eval(case, "EVALUATED", outside, [1], BASE, cand)["root_cause_ok"] is False


def test_fixable_case_without_any_valid_candidate_fails():
    case = c01_case()
    cand = apply_changes(BASE, case["change"])
    good = apply_changes(cand, GOOD_FIX["change"])[INV]
    # 제안서는 proposed 지만 service 가 다시 계산한 합격 후보가 없다.
    proposal = proposal_with([candidate(1, cand[INV], good)])
    graded = grade_eval(case, "EVALUATED", proposal, [], BASE, cand)
    assert graded["verdict"] == "FAIL"
    assert any("합격안이 하나도 없습니다" in r for r in graded["reasons"])
    # 제안서를 읽지 못한 경우도 안을 내지 못한 것이다.
    assert grade_eval(case, "EVALUATED", None, [], BASE, cand)["verdict"] == "FAIL"


def test_no_valid_fix_is_not_a_failure_when_the_answer_allows_it():
    case = c01_case(proposal=["proposed", "no_valid_fix"])  # U-01 처럼
    cand = apply_changes(BASE, case["change"])
    graded = grade_eval(case, "EVALUATED", proposal_with([], status="no_valid_fix"), [], BASE, cand)
    assert graded["verdict"] == "PASS"
    assert graded["presented_fix"] is False


# ── 담당자 의도(B 묶음) ──────────────────────────────────────────────────────

PAY = "skills/payment/SKILL.md"
INTAKE = "skills/order-intake/SKILL.md"
SHIP = "skills/shipping-reserve/SKILL.md"
B_BASE = {
    INTAKE: (
        "---\nname: order-intake\ninputs: [order.id, order.customer, order.shipping]\n"
        "outputs: [order.id, order.total]\ndepends_on: []\n---\n본문\n"
    ),
    PAY: (
        "---\nname: payment\ninputs: [order.id, order.total, order.payment_method]\n"
        "outputs: [payment.id, payment.status]\ndepends_on: [order-intake]\n---\n결제한다.\n"
    ),
    SHIP: (
        "---\nname: shipping-reserve\ninputs: [order.id, order.shipping, payment.status]\n"
        "outputs: [shipping.id]\ndepends_on: [payment]\n---\n배송한다.\n"
    ),
}
B_OWNER = [{"file": PAY, "from": "결제한다.", "to": "결제한다. 선입금 주문만 결제한다."}]
PREPAID_RULE = (
    "depends_on: [order-intake]\nrules:\n  - when: \"order.payment_method != 'prepaid'\"\n"
    "    then: hold\n    reason: PREPAID_ONLY\n"
)
B_GOOD = {
    "name": "결제에 규칙",
    "change": [{"file": PAY, "from": "depends_on: [order-intake]\n", "to": PREPAID_RULE}],
    "tests_pass": True,
    "valid": True,
}
B_INTENT = {
    "text": "선입금 주문만 결제한다",
    "expectations": [
        {
            "scope": "order.payment_method == 'card'",
            "checks": ["order.final_status in ['HOLD', 'REJECTED']", "payment.status == null"],
        },
        {
            "scope": "order.payment_method == 'prepaid'",
            "checks": ["order.final_status == 'SHIPPED'"],
        },
    ],
    "repro": ["g-01"],
}


def b_case(**answer):
    data = {
        "id": "B-01",
        "group": "B",
        "title": "선입금 규칙",
        "set": "dev",
        "run": "auto",
        "change": B_OWNER,
        "intent": B_INTENT,
        "fixes": [B_GOOD],
        "answer": {
            "status": ["EVALUATED"],
            "proposal": ["proposed"],
            "root_cause": ["payment"],
            "root_cause_kind": "incomplete_edit",
            "fixable": True,
            "rule_skills": ["order-intake", "payment"],
            **answer,
        },
    }
    return load_case(data, "B-01.yaml")


def b_proposal(cand, result, kind="incomplete_edit"):
    patches = [
        {
            "skill": path.split("/")[1],
            "base_version": 1,
            "patch": unified_patch(path, cand[path], result[path]),
        }
        for path in sorted(result)
        if result[path] != cand[path]
    ]
    return {
        "status": "proposed",
        "root_cause": {"skill": "payment", "kind": kind, "repro_scenario_ids": ["g-01"]},
        "candidates": [{"candidate_no": 1, "valid": True, "patches": patches}],
    }


def test_golden_ids_are_the_fixed_six():
    assert GOLDEN_IDS == ("g-01", "g-02", "g-03", "g-04", "g-05", "g-06")


def test_owner_intent_is_sent_only_when_expectations_are_written():
    case = b_case()
    assert case["owner_intent"] == {
        "text": B_INTENT["text"],
        "expectations": B_INTENT["expectations"],
    }  # repro 는 보내지 않는다.
    assert (
        load_case(minimal_case(intent={"text": "x", "checks": ["a == 1"]}), "C.yaml")[
            "owner_intent"
        ]
        is None
    )
    assert scope_keys(case["owner_intent"]) == ["order.payment_method"]


@pytest.mark.parametrize(
    "intent",
    [
        {"text": "", "expectations": []},
        {"text": "x", "expectations": [{"scope": None, "checks": []}]},
        {"text": "x", "expectations": [{"scope": None, "checks": ["a == 1"], "extra": 1}]},
        {"text": "x", "expectations": [{"scope": "", "checks": ["a == 1"]}]},
        {"text": "x", "expectations": "a == 1"},
    ],
)
def test_owner_intent_rejects_bad_shapes(intent):
    with pytest.raises(CaseError, match="intent"):
        load_case(minimal_case(intent=intent), "bad.yaml")


def test_intent_case_cannot_allow_reverts():
    data = minimal_case(intent=B_INTENT)
    data["answer"]["revert_allowed"] = True
    with pytest.raises(CaseError, match="revert_allowed"):
        load_case(data, "bad.yaml")


def test_rag_exclusions_map_qa_cases_to_rag_case_ids():
    rag = [
        {"id": "case-S2", "qa_cases": ["C-01", "U-01"]},
        {"id": "case-S6", "qa_cases": ["U-01"]},
        {"id": "case-S3"},
    ]
    assert rag_exclusions(rag) == {"C-01": ["case-S2"], "U-01": ["case-S2", "case-S6"]}


def test_rules_are_read_from_front_matter_and_rule_changes_name_the_skill():
    cand = apply_changes(B_BASE, B_OWNER)
    fixed = apply_changes(cand, B_GOOD["change"])
    assert skill_rules(fixed[PAY]) == [
        ("order.payment_method != 'prepaid'", "hold", "PREPAID_ONLY")
    ]
    assert skill_rules(cand[PAY]) == []
    assert rule_changes(cand, fixed) == ["payment"]
    assert rule_changes(fixed, cand) == []  # 지운 것은 weakened 가 본다.


def test_weakened_finds_deleted_rules_and_inputs_used_by_conditions():
    cand = apply_changes(apply_changes(B_BASE, B_OWNER), B_GOOD["change"])
    keys = ["order.shipping.country"]
    assert weakened(cand, cand, keys) == []
    no_rules = dict(cand, **{PAY: cand[PAY].replace(PREPAID_RULE, "depends_on: [order-intake]\n")})
    assert any("rules" in p for p in weakened(cand, no_rules, keys))
    # 규칙 when 이 쓰는 input 을 지움
    no_method = dict(cand, **{PAY: cand[PAY].replace(", order.payment_method]", "]")})
    assert any("order.payment_method" in p for p in weakened(cand, no_method, keys))
    # 의도 scope 의 하위 값(order.shipping.country)이 쓰는 input 을 지움
    no_shipping = dict(cand, **{INTAKE: cand[INTAKE].replace(", order.shipping]", "]")})
    assert any("order.shipping" in p for p in weakened(cand, no_shipping, keys))
    # 조건이 쓰지 않는 input 을 지운 것은 약화가 아니다.
    no_total = dict(cand, **{PAY: cand[PAY].replace("order.total, ", "")})
    assert weakened(cand, no_total, keys) == []


def test_weakened_compares_rules_by_when_only():
    """계획 2.10 V3-7: 규칙을 지우거나 when 을 바꾸면 약화이고, then·reason 만 바꾸면 아니다."""
    cand = apply_changes(apply_changes(B_BASE, B_OWNER), B_GOOD["change"])
    assert rule_whens(cand[PAY]) == ["order.payment_method != 'prepaid'"]
    # reason·then 만 바꾼 규칙은 when 이 그대로라 약화가 아니다.
    reworded = dict(cand, **{PAY: cand[PAY].replace("PREPAID_ONLY", "OWNER_POLICY")})
    assert weakened(cand, reworded, []) == []
    rejected = dict(cand, **{PAY: cand[PAY].replace("then: hold", "then: reject")})
    assert weakened(cand, rejected, []) == []
    # when 의 앞뒤 공백 차이와 규칙 순서는 보지 않는다.
    prepaid = "  - when: \"order.payment_method != 'prepaid'\"\n"
    prepaid += "    then: hold\n    reason: PREPAID_ONLY\n"
    high = '  - when: " order.total >= 100000 "\n    then: hold\n    reason: HIGH_AMOUNT\n'
    two = dict(cand, **{PAY: cand[PAY].replace(prepaid, prepaid + high)})
    swapped = dict(cand, **{PAY: cand[PAY].replace(prepaid, high + prepaid)})
    assert rule_whens(swapped[PAY]) == [
        "order.total >= 100000",
        "order.payment_method != 'prepaid'",
    ]
    assert weakened(two, swapped, []) == []
    # when 을 바꾸면 약화다. 규칙 수가 같아도 원래 조건이 사라졌다.
    narrowed = dict(cand, **{PAY: cand[PAY].replace("!= 'prepaid'", "== 'card'")})
    assert weakened(cand, narrowed, []) == [
        "payment 의 rules when [\"order.payment_method != 'prepaid'\"] 를 지우거나 바꿨습니다"
    ]
    # 두 규칙 가운데 하나를 지워도 약화다.
    assert any("order.total >= 100000" in p for p in weakened(two, cand, []))


def test_weakened_counts_a_removed_input_even_if_another_input_still_covers_the_key():
    """계획 2.10 V3-7: 조건이 쓰는 키를 덮던 input 을 지우면 다른 input 이 덮어도 약화다."""
    cand = apply_changes(B_BASE, B_OWNER)
    both_inputs = ", order.shipping, order.shipping.country]"
    both = dict(cand, **{INTAKE: cand[INTAKE].replace(", order.shipping]", both_inputs)})
    keys = ["order.shipping.country"]
    narrower = dict(both, **{INTAKE: both[INTAKE].replace("order.shipping, ", "")})
    assert "order.shipping.country]" in narrower[INTAKE]
    assert weakened(both, narrower, keys) == [
        "order-intake 의 input order.shipping 를 지웠습니다(조건이 쓰는 값)"
    ]
    # 하위 값만 받던 input 을 지워도, 넓은 input 이 남아 있어도 약화다.
    wider = dict(both, **{INTAKE: both[INTAKE].replace(", order.shipping.country]", "]")})
    assert weakened(both, wider, keys) == [
        "order-intake 의 input order.shipping.country 를 지웠습니다(조건이 쓰는 값)"
    ]
    # 의도가 없는 값(keys 가 비고 규칙도 없음)이면 같은 삭제도 약화가 아니다.
    assert weakened(both, narrower, []) == []


def test_intent_case_accepts_a_candidate_that_only_rewords_an_owner_rule():
    """담당자가 넣은 규칙의 reason·then 만 바꾼 후보는 약화가 아니라 합격안으로 센다(V3-7)."""
    owner_rule = [{"file": PAY, "from": "depends_on: [order-intake]\n", "to": PREPAID_RULE}]
    case = load_case(
        {
            "id": "B-01",
            "group": "B",
            "title": "선입금 규칙",
            "set": "dev",
            "run": "auto",
            "change": owner_rule,
            "intent": B_INTENT,
            "answer": {
                "status": ["EVALUATED"],
                "proposal": ["proposed"],
                "root_cause": ["payment"],
                "root_cause_kind": "incomplete_edit",
                "fixable": True,
                "rule_skills": ["order-intake", "payment"],
            },
        },
        "B-01.yaml",
    )
    cand = apply_changes(B_BASE, owner_rule)
    reworded_pay = cand[PAY].replace("then: hold", "then: reject")
    reworded_pay = reworded_pay.replace("PREPAID_ONLY", "NO_CARD")
    reworded = dict(cand, **{PAY: reworded_pay})
    graded = grade_eval(case, "EVALUATED", b_proposal(cand, reworded), [1], B_BASE, cand)
    assert graded["weakening_proposals"] == 0
    assert graded["verdict"] == "PASS"
    assert graded["presented_fix"] is True
    # 같은 후보가 when 을 바꾸면 약화로 불합격이다.
    narrowed = dict(cand, **{PAY: cand[PAY].replace("!= 'prepaid'", "== 'card'")})
    graded = grade_eval(case, "EVALUATED", b_proposal(cand, narrowed), [1], B_BASE, cand)
    assert graded["weakening_proposals"] == 1
    assert graded["verdict"] == "FAIL"


def test_snapshot_metadata_carries_owner_intent_like_a_save():
    """후보 snapshot metadata 에 저장 경로와 같은 이름·모양의 intent 를 담는다(계획 2.10 V3-1)."""
    meta = snapshot_metadata(b_case(), "snap-base")
    assert meta == {
        "baseline_snapshot_id": "snap-base",
        "qa_case": "B-01",
        "intent": {"text": B_INTENT["text"], "expectations": B_INTENT["expectations"]},
    }
    # 의도가 없는 케이스는 칸을 만들지 않는다.
    plain = load_case(minimal_case(intent={"text": "x", "checks": ["a == 1"]}), "C.yaml")
    assert snapshot_metadata(plain, "snap-base") == {
        "baseline_snapshot_id": "snap-base",
        "qa_case": plain["id"],
    }


def test_intent_preflight_separates_intent_gap_from_errors():
    passed = observe_golden(True, None, [])
    error = observe_golden(False, failed_trace("CONTRACT_VIOLATION", "payment"), [])
    scoped = [
        {"scenario_id": "g-01", "passed": False, "intent_scoped": True},
        {"scenario_id": "g-02", "passed": True, "intent_scoped": True},
        {"scenario_id": "g-04", "passed": False, "intent_scoped": False},
    ]
    assert intent_failures(scoped) == ["g-01"]
    assert intent_preflight_status([passed, passed], scoped) == "INTENT_GAP"
    assert intent_preflight_status([error, passed], scoped) == "DIAGNOSE"
    clean = [{"scenario_id": "g-01", "passed": True, "intent_scoped": True}]
    assert intent_preflight_status([passed, passed], clean) == "VALIDATED"


def test_check_fix_counts_weakening_as_invalid():
    fix = {"name": "규칙 옮김", "tests_pass": True, "valid": False, "failure": None, "policy": None}
    observed = {
        "tests_pass": True,
        "reverted": False,
        "failing": [],
        "revert_allowed": False,
        "weakened": ["payment 의 rules 1개를 지웠습니다"],
    }
    assert check_fix(fix, observed) == []
    assert check_fix(dict(fix, valid=True), observed)


def test_intent_case_passes_a_rule_fix_in_an_accepted_skill():
    case = b_case()
    cand = apply_changes(B_BASE, case["change"])
    good = apply_changes(cand, B_GOOD["change"])
    graded = grade_eval(case, "EVALUATED", b_proposal(cand, good), [1], B_BASE, cand)
    assert graded["verdict"] == "PASS"
    assert graded["presented_fix"] is True
    assert graded["root_cause_kind"] == "incomplete_edit"
    assert graded["repro_scenario_ids"] == ["g-01"]


def test_intent_case_rejects_revert_and_weakening():
    case = b_case()
    cand = apply_changes(B_BASE, case["change"])
    revert = dict(cand, **{PAY: B_BASE[PAY]})
    graded = grade_eval(case, "EVALUATED", b_proposal(cand, revert), [1], B_BASE, cand)
    assert graded["verdict"] == "FAIL"
    assert graded["revert_proposals"] == 1
    weak = dict(
        cand,
        **{
            INTAKE: cand[INTAKE].replace(", order.shipping]", "]"),
            PAY: cand[PAY].replace(", order.payment_method]", "]"),
        },
    )
    graded = grade_eval(case, "EVALUATED", b_proposal(cand, weak), [1], B_BASE, cand)
    assert graded["verdict"] == "FAIL"
    assert graded["weakening_proposals"] == 1


def test_intent_case_marks_conflict_for_a_rule_outside_accepted_skills():
    case = b_case()
    cand = apply_changes(B_BASE, case["change"])
    rule = 'depends_on: [payment]\nrules:\n  - when: "order.shipping != null"\n    then: reject\n'
    rule += "    reason: OTHER\n"
    elsewhere = dict(cand, **{SHIP: cand[SHIP].replace("depends_on: [payment]\n", rule)})
    graded = grade_eval(case, "EVALUATED", b_proposal(cand, elsewhere), [1], B_BASE, cand)
    assert graded["verdict"] == "CONFLICT"
    assert any("shipping-reserve" in r for r in graded["reasons"])


def test_intent_case_checks_root_cause_kind():
    case = b_case()
    cand = apply_changes(B_BASE, case["change"])
    good = apply_changes(cand, B_GOOD["change"])
    graded = grade_eval(case, "EVALUATED", b_proposal(cand, good, kind="change"), [1], B_BASE, cand)
    assert graded["verdict"] == "FAIL"
    assert any("원인 종류" in r for r in graded["reasons"])


def test_grade_eval_save_rejection_case():
    data = {
        "id": "N-07",
        "group": "N",
        "title": "바꾸지 않고 저장",
        "set": "dev",
        "run": "auto",
        "change": [],
        "save_file": NOTI,
        "answer": {"save_rejected": "NO_SKILL_CHANGES"},
    }
    case = load_case(data, "N-07.yaml")
    rejected = grade_eval(case, None, None, [], BASE, BASE, save_rejected="NO_SKILL_CHANGES")
    assert rejected["verdict"] == "PASS"
    assert rejected["status"] == "SAVE_REJECTED(NO_SKILL_CHANGES)"
    assert (
        grade_eval(case, "VALIDATED", None, [], BASE, BASE, save_rejected=None)["verdict"] == "FAIL"
    )


# ── 지표 ─────────────────────────────────────────────────────────────────────


def test_summarize_counts_metrics_with_denominators():
    n_case = load_case(
        minimal_case(id="N-01", change=[], answer={"status": ["VALIDATED"]}), "N-01.yaml"
    )
    c_case = c01_case()
    results = [
        {
            "case": n_case,
            "grade": {"verdict": "PASS", "status": "VALIDATED", "detected": False},
            "seconds": 10,
        },
        {
            "case": c_case,
            "grade": {
                "verdict": "FAIL",
                "status": "EVALUATED",
                "detected": True,
                "root_cause_ok": True,
                "presented_fix": True,
                "revert_proposals": 1,
                "valid_candidates": 2,
            },
            "seconds": 30,
        },
    ]
    metrics = summarize(results)
    assert metrics["false_alarm"] == {"value": 0.0, "num": 0, "den": 1}
    assert metrics["detection"] == {"value": 1.0, "num": 1, "den": 1}
    assert metrics["root_cause"] == {"value": 1.0, "num": 1, "den": 1}
    assert metrics["revert_rate"] == {"value": 0.5, "num": 1, "den": 2}
    assert metrics["completion"]["den"] == 2
    assert metrics["pass_rate"] == {"value": 0.5, "num": 1, "den": 2}
    # 의도를 보낸 케이스가 없으면 의도 실현 안 제시율은 대상 없음이다.
    assert metrics["intent_fix_presented"] == {"value": None, "num": 0, "den": 0}


def test_intent_fix_metric_counts_only_intent_cases():
    results = [
        {"case": b_case(), "grade": {"verdict": "PASS", "presented_fix": True}},
        {"case": b_case(), "grade": {"verdict": "FAIL", "presented_fix": False}},
        {"case": c01_case(), "grade": {"verdict": "PASS", "presented_fix": True}},
    ]
    metrics = summarize(results)
    assert metrics["intent_fix_presented"] == {"value": 0.5, "num": 1, "den": 2}
    assert metrics["fix_presented"] == {"value": 2 / 3, "num": 2, "den": 3}


def test_no_fix_metric_counts_only_no_valid_fix_endings():
    """'유효한 안 없음' 정답률은 진단을 끝까지 하고 no_valid_fix 로 끝낸 경우만 정답으로 센다.

    정답 결론이 EVALUATED 하나인 고칠 수 없는 케이스만 분모에 넣는다. 검증 실패에서 멈추는 것이 지금
    정답인 케이스(V 묶음)는 제안서 단계에 가지 않으므로 이 지표에서 뺀다.
    """
    unfixable = dict(
        minimal_case(id="U-02"),
        answer={"status": ["EVALUATED"], "proposal": ["no_valid_fix"], "fixable": False},
    )
    result_only = dict(
        minimal_case(id="V-01"),
        answer={"status": ["VALIDATION_FAILED", "EVALUATED"], "fixable": False},
    )
    u_case = load_case(unfixable, "U-02.yaml")
    v_case = load_case(result_only, "V-01.yaml")
    results = [
        {
            "case": u_case,
            "grade": {"verdict": "PASS", "status": "EVALUATED", "proposal_status": "no_valid_fix"},
        },
        {
            "case": u_case,
            "grade": {"verdict": "FAIL", "status": "EVALUATED", "proposal_status": "proposed"},
        },
        {"case": u_case, "grade": {"verdict": "FAIL", "status": "ERROR", "proposal_status": None}},
        {
            "case": v_case,
            "grade": {"verdict": "PASS", "status": "VALIDATION_FAILED", "proposal_status": None},
        },
    ]
    assert summarize(results)["no_fix_correct"] == {"value": 1 / 3, "num": 1, "den": 3}
