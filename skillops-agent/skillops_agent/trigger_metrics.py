"""호출 재현율·정밀도를 계산하고 개선안 통과 여부를 판정하는 순수 함수 모음이다.

기획안 2-3 의 지표를 그대로 쓴다.
- 호출 재현율(recall): target 이 불려야 하는 질의 중 실제로 불린 비율
- 호출 정밀도(precision): target 이 불린 질의 중 불려야 했던 비율
질의마다 선택을 여러 번(repeat) 받아 표본 단위로 세고, 과반을 맞힌 질의를 통과로 본다.
LLM 을 부르지 않으므로 skillops-agent/tests/test_trigger_metrics.py 가 값만으로 검사한다.
"""

from collections import Counter

from .trigger_state import NONE_SKILL, TriggerCase


def case_hit(case: TriggerCase, selected: str) -> bool:
    """선택 하나가 이 질의의 기대에 맞는지 본다."""
    if case["should_trigger"]:
        return selected == case["expected_skill"]
    return selected != case["expected_skill"]


def _is_positive(case: TriggerCase, target: str) -> bool:
    """target 이 불려야 하는 질의인지 본다."""
    return case["should_trigger"] and case["expected_skill"] == target


def _wrongly_chose_target(case: TriggerCase, target: str) -> bool:
    """target 을 고르면 틀리는 질의인지 본다. 다른 skill 의 질의이거나 target 금지 질의다."""
    if case["should_trigger"]:
        return case["expected_skill"] != target
    return case["expected_skill"] == target


def _ratio(num: int, den: int) -> float | None:
    """분모가 0 이면 None(잴 수 없음)을 돌려준다."""
    return round(num / den, 3) if den else None


def measure(cases: list[TriggerCase], selections: list[list[str]], target: str) -> dict:
    """질의별 선택 결과로 target 의 재현율·정밀도와 질의별 통과 여부를 낸다.

    selections[i] 는 cases[i] 에 대한 repeat 번의 선택이다.
    """
    rows = []
    for case, picks in zip(cases, selections, strict=True):
        hits = sum(case_hit(case, p) for p in picks)
        rows.append(
            {
                **case,
                "selected": list(picks),
                "hit_rate": _ratio(hits, len(picks)),
                "passed": hits * 2 > len(picks),
            }
        )

    positives = [r for r in rows if _is_positive(r, target)]
    pos_hits = sum(p == target for r in positives for p in r["selected"])
    pos_total = sum(len(r["selected"]) for r in positives)

    # 회차별 재현율의 최소·최대로 편차를 보여 준다(기획안 2-3).
    repeat = len(selections[0]) if selections else 0
    by_round = [
        _ratio(sum(r["selected"][i] == target for r in positives), len(positives))
        for i in range(repeat)
    ]
    by_round = [v for v in by_round if v is not None]

    chosen_right = sum(p == target for r in positives for p in r["selected"])
    chosen_wrong = sum(
        p == target for r in rows if _wrongly_chose_target(r, target) for p in r["selected"]
    )

    all_hits = sum(case_hit(r, p) for r in rows for p in r["selected"])
    all_total = sum(len(r["selected"]) for r in rows)

    # target 대신 불린 skill. 진단에서 경쟁 skill 을 찾는 근거다.
    stolen_by = Counter(p for r in positives for p in r["selected"] if p != target)

    misses = [
        r
        for r in rows
        if (_is_positive(r, target) and r["hit_rate"] < 1)
        or (_wrongly_chose_target(r, target) and target in r["selected"])
    ]

    return {
        "recall": _ratio(pos_hits, pos_total),
        "recall_range": [min(by_round), max(by_round)] if by_round else None,
        "precision": _ratio(chosen_right, chosen_right + chosen_wrong),
        "accuracy": _ratio(all_hits, all_total),
        "stolen_by": dict(stolen_by.most_common()),
        "misses": misses,
        "rows": rows,
    }


def regressions(before: dict, after: dict) -> list[str]:
    """변경 전 통과 → 변경 후 실패로 바뀐 질의 목록이다(기획안 회귀 건수)."""
    return [
        b["query"]
        for b, a in zip(before["rows"], after["rows"], strict=True)
        if b["passed"] and not a["passed"]
    ]


def _change(before: float | None, after: float | None) -> float:
    """지표 변화량이다.

    잴 수 없던 정밀도(None)는 target 을 잘못 고른 적이 없다는 뜻이라 1 로 본다.
    """
    return (1.0 if after is None else after) - (1.0 if before is None else before)


def judge(before: dict, after: dict) -> tuple[bool, list[str]]:
    """개선안이 통과인지와 실패 이유를 돌려준다.

    통과 조건: 재현율이나 정밀도 중 하나가 올랐고, 둘 다 내려가지 않았고, 회귀가 0건이다.
    안 불리는 문제(재현율)와 엉뚱하게 불리는 문제(정밀도)를 모두 개선으로 본다.
    """
    reasons = []
    changes = {
        name: _change(before[key], after[key])
        for name, key in (("재현율", "recall"), ("정밀도", "precision"))
    }
    if not any(c > 0 for c in changes.values()):
        reasons.append(
            f"호출 재현율과 정밀도가 오르지 않았습니다(재현율 {before['recall']} → "
            f"{after['recall']}, 정밀도 {before['precision']} → {after['precision']})."
        )
    subjects = (("재현율", "재현율이", "recall"), ("정밀도", "정밀도가", "precision"))
    for name, subject, key in subjects:
        if changes[name] < 0:
            reasons.append(f"호출 {subject} 내려갔습니다({before[key]} → {after[key]}).")
    lost = regressions(before, after)
    if lost:
        reasons.append(f"회귀 {len(lost)}건: " + ", ".join(lost))
    return not reasons, reasons


def no_issue(metrics: dict) -> bool:
    """원래 description 으로 고칠 것이 없는지 본다. 재현율 1 이고 잘못 불린 적이 없어야 한다."""
    return metrics["recall"] == 1.0 and metrics["precision"] in (None, 1.0)


def summary(metrics: dict) -> dict:
    """보고서와 프롬프트에 넣을 지표만 남긴다(질의별 rows 는 뺀다)."""
    return {k: metrics[k] for k in ("recall", "recall_range", "precision", "accuracy", "stolen_by")}


__all__ = ["NONE_SKILL", "case_hit", "judge", "measure", "no_issue", "regressions", "summary"]
