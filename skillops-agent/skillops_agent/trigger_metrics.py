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

    # target 을 고르면 틀리는 질의가 하나도 없으면 정밀도는 늘 1.0 이라 의미가 없다.
    # 그때는 재지 않는다(None).
    negatives = sum(1 for r in rows if _wrongly_chose_target(r, target))
    return {
        "recall": _ratio(pos_hits, pos_total),
        "recall_range": [min(by_round), max(by_round)] if by_round else None,
        "precision": _ratio(chosen_right, chosen_right + chosen_wrong) if negatives else None,
        "accuracy": _ratio(all_hits, all_total),
        "stolen_by": dict(stolen_by.most_common()),
        # 숫자를 읽을 때 같이 봐야 하는 크기다. 1.0 이 몇 건 중에서 나왔는지 보여 준다.
        "counts": {
            "cases": len(rows),
            "positive_cases": len(positives),
            "negative_cases": negatives,
            "recall_hits": f"{pos_hits}/{pos_total}",
        },
        "misses": misses,
        "rows": rows,
    }


def split_of(case: dict) -> str:
    """질의의 묶음이다. split 이 없으면 진단용(dev)이다."""
    return case.get("split") or "dev"


def measure_split(cases: list[TriggerCase], selections: list[list[str]], target: str) -> dict:
    """진단용(dev)과 채점용(holdout)을 따로 잰다.

    돌려주는 값은 dev 의 measure 결과에 "holdout" 키(holdout measure 결과, 없으면 None)를 더한
    것이다.
    LLM 에는 dev 의 틀린 질의만 보여 주고, 통과 판정은 holdout 이 있으면 holdout 으로 한다(judge).
    """
    pairs = list(zip(cases, selections, strict=True))
    dev = [(c, s) for c, s in pairs if split_of(c) == "dev"]
    hold = [(c, s) for c, s in pairs if split_of(c) == "holdout"]
    result = measure([c for c, _ in dev], [s for _, s in dev], target)
    result["holdout"] = (
        measure([c for c, _ in hold], [s for _, s in hold], target) if hold else None
    )
    return result


def judged(m: dict) -> dict:
    """통과 판정에 쓰는 지표다. holdout 이 있으면 holdout, 없으면 dev(같은 질의로 채점)다."""
    return m.get("holdout") or m


def regressions(before: dict, after: dict) -> list[str]:
    """변경 전 통과 → 변경 후 실패로 바뀐 질의 목록이다(기획안 회귀 건수).

    dev 와 holdout 을 모두 본다.
    """
    lost = [
        b["query"]
        for b, a in zip(before["rows"], after["rows"], strict=True)
        if b["passed"] and not a["passed"]
    ]
    if before.get("holdout") and after.get("holdout"):
        lost += regressions(before["holdout"], after["holdout"])
    return lost


def _recall_change(before: float | None, after: float | None) -> float | None:
    """재현율 변화량이다. 불려야 하는 질의가 없어 잴 수 없으면 None(비교 안 함)이다."""
    if before is None or after is None:
        return None
    return after - before


def _precision_change(before: float | None, after: float | None) -> float | None:
    """정밀도 변화량이다. None 은 target 이 한 번도 불리지 않아 잴 수 없다는 뜻이다.

    - None → None: 변화 없음(0).
    - None → 값: 잘못 불린 적이 없던 상태(1.0)와 비교한다. 1.0 미만이면 새 오호출이라 하락이다.
    - 값 → None: 아예 안 불리게 된 것이라 개선으로 세지 않는다(None). 재현율 0 검사가 따로 막는다.
    """
    if before is None and after is None:
        return 0.0
    if after is None:
        return None
    return after - (1.0 if before is None else before)


def judge(before: dict, after: dict) -> tuple[bool, list[str]]:
    """개선안이 통과인지와 실패 이유를 돌려준다.

    통과 조건: 재현율이나 정밀도 중 하나가 올랐고, 둘 다 내려가지 않았고, 회귀가 0건이다.
    안 불리는 문제(재현율)와 엉뚱하게 불리는 문제(정밀도)를 모두 개선으로 본다.
    target 이 불려야 하는 질의가 있는데 개선안에서 한 번도 불리지 않으면(재현율 0) 실패다.
    """
    reasons = []
    # 통과 판정은 LLM 이 보지 못한 holdout 으로 한다. holdout 이 없으면 같은 질의(dev)로 채점한다.
    lost = regressions(before, after)
    before, after = judged(before), judged(after)
    changes = {
        "재현율": _recall_change(before["recall"], after["recall"]),
        "정밀도": _precision_change(before["precision"], after["precision"]),
    }
    if after["recall"] is None:
        # 정상 호출을 확인할 질의가 없으면 개선을 검증할 수 없으므로 통과시키지 않는다.
        reasons.append("대상 skill 이 불려야 하는 질의가 없어 정상 호출을 확인할 수 없습니다.")
    elif after["recall"] == 0.0:
        reasons.append("대상 skill 이 불려야 하는 질의에서 한 번도 불리지 않았습니다(재현율 0).")
    if not any(c is not None and c > 0 for c in changes.values()):
        reasons.append(
            f"호출 재현율과 정밀도가 오르지 않았습니다(재현율 {before['recall']} → "
            f"{after['recall']}, 정밀도 {before['precision']} → {after['precision']})."
        )
    subjects = (("재현율", "재현율이", "recall"), ("정밀도", "정밀도가", "precision"))
    for name, subject, key in subjects:
        if changes[name] is not None and changes[name] < 0:
            reasons.append(f"호출 {subject} 내려갔습니다({before[key]} → {after[key]}).")
    if lost:
        reasons.append(f"회귀 {len(lost)}건: " + ", ".join(lost))
    return not reasons, reasons


def no_issue(metrics: dict) -> bool:
    """원래 description 으로 고칠 것이 없는지 본다.

    dev·holdout 모두 재현율 1 이고 잘못 불린 적이 없어야 한다.
    """
    parts = [metrics] + ([metrics["holdout"]] if metrics.get("holdout") else [])
    return all(m["recall"] == 1.0 and m["precision"] in (None, 1.0) for m in parts)


_SUMMARY_KEYS = ("recall", "recall_range", "precision", "accuracy", "stolen_by", "counts")


def summary(metrics: dict) -> dict:
    """보고서와 프롬프트에 넣을 지표만 남긴다(질의별 rows 는 뺀다).

    holdout 이 있으면 같이 넣는다.
    """
    out = {k: metrics.get(k) for k in _SUMMARY_KEYS}
    if metrics.get("holdout"):
        out["holdout"] = {k: metrics["holdout"].get(k) for k in _SUMMARY_KEYS}
    return out


__all__ = [
    "NONE_SKILL", "case_hit", "judge", "judged", "measure", "measure_split", "no_issue",
    "regressions", "split_of", "summary",
]
