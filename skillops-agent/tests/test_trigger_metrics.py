"""호출 재현율·정밀도 계산과 개선안 판정(trigger_metrics)을 값만으로 검사한다."""

from skillops_agent import trigger_metrics as m

T = "weekly-report"
CASES = [
    {"query": "주간 보고 써줘", "expected_skill": T, "should_trigger": True},
    {"query": "이번 주 보고서", "expected_skill": T, "should_trigger": True},
    {"query": "회의 요약해줘", "expected_skill": "meeting-summary", "should_trigger": True},
    {"query": "문서 오타 정리", "expected_skill": T, "should_trigger": False},
]


def test_case_hit_positive_and_negative():
    assert m.case_hit(CASES[0], T)
    assert not m.case_hit(CASES[0], "meeting-summary")
    # 불리면 안 되는 질의는 target 만 아니면 맞다.
    assert m.case_hit(CASES[3], "none")
    assert not m.case_hit(CASES[3], T)


def test_measure_counts_samples_and_majority():
    selections = [
        [T, T, "meeting-summary"],  # 2/3 맞음 → 통과
        ["meeting-summary"] * 3,  # 0/3
        ["meeting-summary"] * 3,  # 3/3
        [T, "none", "none"],  # target 이 한 번 잘못 불림, 2/3 맞음
    ]
    r = m.measure(CASES, selections, T)
    assert r["recall"] == round(2 / 6, 3)
    # 회차별 재현율은 0.5, 0.5, 0.0 이다.
    assert r["recall_range"] == [0.0, 0.5]
    # target 을 고른 3번 중 2번이 맞다.
    assert r["precision"] == round(2 / 3, 3)
    assert r["accuracy"] == round(7 / 12, 3)
    assert r["stolen_by"] == {"meeting-summary": 4}
    assert [row["passed"] for row in r["rows"]] == [True, False, True, True]
    misses = {x["query"] for x in r["misses"]}
    assert misses == {"주간 보고 써줘", "이번 주 보고서", "문서 오타 정리"}


def test_precision_is_none_when_target_never_chosen():
    r = m.measure(CASES, [["none"]] * 4, T)
    assert r["recall"] == 0.0
    assert r["precision"] is None


def test_judge_passes_only_with_higher_recall_and_no_regression():
    before = m.measure(CASES, [["none"], ["none"], ["meeting-summary"], ["none"]], T)
    better = m.measure(CASES, [[T], [T], ["meeting-summary"], ["none"]], T)
    assert m.judge(before, better) == (True, [])

    # 재현율은 올랐지만 회의 요약 질의를 빼앗아 회귀가 났다.
    stolen = m.measure(CASES, [[T], [T], [T], ["none"]], T)
    passed, reasons = m.judge(before, stolen)
    assert not passed
    assert m.regressions(before, stolen) == ["회의 요약해줘"]
    assert any("회귀 1건" in r for r in reasons)

    passed, reasons = m.judge(before, before)
    assert not passed
    assert any("재현율과 정밀도가 오르지 않았습니다" in r for r in reasons)


def test_judge_passes_precision_only_fix():
    # 재현율은 이미 1 이고, 불리면 안 되는 질의에서 불리던 문제만 고친 경우다.
    broad = m.measure(CASES, [[T], [T], ["meeting-summary"], [T]], T)
    narrow = m.measure(CASES, [[T], [T], ["meeting-summary"], ["none"]], T)
    assert m.judge(broad, narrow) == (True, [])
    passed, reasons = m.judge(narrow, broad)
    assert not passed
    assert any("호출 정밀도가 내려갔습니다" in r for r in reasons)


def test_judge_rejects_fix_that_stops_target_entirely():
    # 엉뚱한 질의에서만 불리던 skill(정밀도 0)을 아예 안 불리게(정밀도 None) 만든 안이다.
    # 정밀도가 None 이 된 것은 개선이 아니고, 재현율 0 이라 실패다.
    before = m.measure(CASES, [["none"], ["none"], ["meeting-summary"], [T]], T)
    after = m.measure(CASES, [["none"], ["none"], ["meeting-summary"], ["none"]], T)
    assert before["precision"] == 0.0 and after["precision"] is None
    passed, reasons = m.judge(before, after)
    assert not passed
    assert any("재현율 0" in r for r in reasons)
    assert any("오르지 않았습니다" in r for r in reasons)


def test_judge_counts_new_false_trigger_as_precision_drop():
    # 개선 전에는 target 이 한 번도 안 불렸고(None), 개선 후 엉뚱한 질의에서도 불린다.
    before = m.measure(CASES, [["none"], ["none"], ["meeting-summary"], ["none"]], T)
    after = m.measure(CASES, [[T], [T], ["meeting-summary"], [T]], T)
    passed, reasons = m.judge(before, after)
    assert not passed
    assert any("호출 정밀도가 내려갔습니다" in r for r in reasons)


def test_judge_precision_only_when_no_positive_cases():
    # target 이 불려야 하는 질의가 없으면 재현율은 None 이고 정밀도로만 판정한다.
    negatives = [CASES[2], CASES[3]]
    before = m.measure(negatives, [["meeting-summary"], [T]], T)
    after = m.measure(negatives, [["meeting-summary"], ["none"]], T)
    assert before["recall"] is None and after["recall"] is None
    # 오호출은 없어졌지만 정상 호출을 확인할 질의가 없어 통과시키지 않는다.
    passed, reasons = m.judge(before, after)
    assert not passed
    assert any("정상 호출을 확인할 수 없습니다" in r for r in reasons)


def test_judge_none_precision_unchanged_passes_on_recall():
    before = m.measure(CASES, [["none"], ["none"], ["meeting-summary"], ["none"]], T)
    after = m.measure(CASES, [[T], ["none"], ["meeting-summary"], ["none"]], T)
    # 정밀도 None → 1.0 은 변화 없음, 재현율 0 → 0.5 로 통과한다.
    assert m.judge(before, after) == (True, [])


def test_no_issue():
    assert m.no_issue(m.measure(CASES, [[T], [T], ["meeting-summary"], ["none"]], T))
    assert not m.no_issue(m.measure(CASES, [[T], [T], ["meeting-summary"], [T]], T))


# ── 진단용(dev) / 채점용(holdout) 분리 ────────────────────────────────────────

# 아래 테스트는 지표 값을 m 이라는 지역 이름으로 받으므로 모듈은 m_ 로 부른다.
m_ = m


def _split_cases():
    dev = [{**c, "split": "dev"} for c in CASES]
    hold = [{**c, "query": c["query"] + " (h)", "split": "holdout"} for c in CASES]
    return dev + hold


def test_measure_split_keeps_dev_for_llm_and_holdout_for_scoring():
    cases = _split_cases()
    sel = [[T], [T], ["meeting-summary"], ["none"]] + [["none"], [T], ["meeting-summary"], ["none"]]
    m = m_.measure_split(cases, sel, T)
    assert m["recall"] == 1.0 and m["holdout"]["recall"] == 0.5
    assert m_.judged(m) is m["holdout"]
    assert [r["query"] for r in m["rows"]] == [c["query"] for c in CASES]  # LLM 이 보는 쪽
    assert m["counts"]["recall_hits"] == "2/2" and m["holdout"]["counts"]["recall_hits"] == "1/2"


def test_judge_uses_holdout_not_dev():
    cases = _split_cases()
    before = m_.measure_split(cases, [["none"]] * 4 + [["none"]] * 4, T)
    # dev 는 다 맞혔지만 holdout 은 그대로 → 개선 아님(과적합)
    overfit = m_.measure_split(
        cases, [[T], [T], ["meeting-summary"], ["none"]] + [["none"]] * 4, T
    )
    passed, reasons = m_.judge(before, overfit)
    assert not passed and any("재현율 0" in r for r in reasons)
    # holdout 도 오르면 통과
    real = m_.measure_split(cases, [[T], [T], ["meeting-summary"], ["none"]] * 2, T)
    assert m_.judge(before, real) == (True, [])


def test_regressions_count_holdout_too():
    cases = _split_cases()
    good = [[T], [T], ["meeting-summary"], ["none"]]
    before = m_.measure_split(cases, good + good, T)
    after = m_.measure_split(cases, good + [[T], [T], [T], ["none"]], T)
    assert m_.regressions(before, after) == ["회의 요약해줘 (h)"]


def test_precision_is_not_measured_without_negative_cases():
    only_positive = [CASES[0], CASES[1]]
    m = m_.measure(only_positive, [[T], [T]], T)
    assert m["precision"] is None  # 늘 1.0 이 되는 값은 내지 않는다
    assert m["counts"]["negative_cases"] == 0


def test_no_issue_needs_holdout_perfect_too():
    cases = _split_cases()
    good = [[T], [T], ["meeting-summary"], ["none"]]
    assert m_.no_issue(m_.measure_split(cases, good + good, T))
    assert not m_.no_issue(m_.measure_split(cases, good + [["none"], [T], ["meeting-summary"],
                                                           ["none"]], T))
