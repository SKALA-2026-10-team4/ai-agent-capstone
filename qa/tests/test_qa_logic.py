"""평가셋 실행기의 판정 로직을 보호한다.

qa/qa_logic.py 는 서버를 부르지 않는 순수 함수만 모은다. qa/run_qa.py 의 check 와 eval 이 이 함수로
케이스 파일을 읽고, 수정본을 만들고, 주문 Agent 반응과 SkillOps 제안서를 정답표와 비교한다.
여기서 판정 규칙(qa/README.md 의 R1~R5)과 지표 계산이 어긋나면 평가 결과 전체를 믿을 수 없으므로,
서버 없이 돌 수 있는 이 테스트로 먼저 고정한다.
"""

import pytest

from qa_logic import (
    CaseError,
    PatchError,
    apply_changes,
    apply_unified_patch,
    check_fix,
    execution_uuid,
    fix_verdict,
    grade_eval,
    load_case,
    match_expectation,
    observe_golden,
    preflight_status,
    reverted_files,
    summarize,
    unified_patch,
    validate_case_ids,
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
