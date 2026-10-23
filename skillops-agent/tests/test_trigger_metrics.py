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
    assert "재현율과 정밀도가 오르지 않았습니다" in reasons[0]


def test_judge_passes_precision_only_fix():
    # 재현율은 이미 1 이고, 불리면 안 되는 질의에서 불리던 문제만 고친 경우다.
    broad = m.measure(CASES, [[T], [T], ["meeting-summary"], [T]], T)
    narrow = m.measure(CASES, [[T], [T], ["meeting-summary"], ["none"]], T)
    assert m.judge(broad, narrow) == (True, [])
    passed, reasons = m.judge(narrow, broad)
    assert not passed
    assert any("호출 정밀도가 내려갔습니다" in r for r in reasons)


def test_no_issue():
    assert m.no_issue(m.measure(CASES, [[T], [T], ["meeting-summary"], ["none"]], T))
    assert not m.no_issue(m.measure(CASES, [[T], [T], ["meeting-summary"], [T]], T))
