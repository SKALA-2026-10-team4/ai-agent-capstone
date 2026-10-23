"""의미 검사 Rubric(3-1~3-5)의 항목 정의와, LLM 판단을 Finding 으로 바꾸는 코드다.

LLM 은 항목마다 통과 여부와 이유, 수정 방향만 낸다. 다음은 모두 이 모듈의 코드가 정한다.
- 심각도: RUBRIC 표에 고정한다. LLM 이 바꿀 수 없다.
- UNVERIFIED: 본문이 필요한 항목(3-3, 3-4)인데 SKILL.md 원문이 없거나, LLM 이 답하지 않은 항목.
- 3-4 확인: LLM 이 FAIL 로 본 3-4 는 실행 결과(재현율)가 목표에 못 미칠 때만 '실행으로 확인됨'을
  붙인다.
semantic Finding 은 흐름을 막지 않는다(schemas.blockers 는 rule 만 센다). 등급만 낮춘다.
"""

from .schemas import Finding, finding

# 항목 id → (제목, 심각도, 본문 필요 여부). 기준 문장은 prompts/rubric_<mode>.md 에 있다.
RUBRIC = {
    "3-1": ("description 에 WHAT(기능)과 WHEN(시점)이 모두 있다", "MAJOR", False),
    "3-2": ("고를 때 단서가 되는 표현이 충분하다", "MAJOR", False),
    "3-3": ("description 과 본문·frontmatter 의 의미가 일치한다", "MAJOR", True),
    "3-4": ("호출 조건이 본문에만 있지 않다(body-only trigger 없음)", "BLOCKER", True),
    "3-5": ("호출 범위가 과도하게 넓지 않다", "MAJOR", False),
}


def rubric_findings(
    items, target: str, has_body: bool, recall: float | None = None, target_recall: float = 0.9
) -> list[Finding]:
    """LLM 의 Rubric 판단 목록을 Finding 목록으로 바꾼다. 항목 순서는 RUBRIC 순서다.

    items 는 id, passed, reason, fix 를 가진 객체(Pydantic) 또는 dict 목록이다. recall 은 같은 skill
    description 으로 잰 실제 재현율이고, 3-4 를 실행 결과로 확인할 때 쓴다.
    """
    by_id = {}
    for item in items or []:
        get = item.get if isinstance(item, dict) else lambda k, i=item: getattr(i, k, None)
        if get("id") in RUBRIC and get("id") not in by_id:
            by_id[get("id")] = (bool(get("passed")), get("reason") or "", get("fix") or "")

    results = []
    for rid, (title, severity, needs_body) in RUBRIC.items():
        fid = f"semantic.{rid}"
        if needs_body and not has_body:
            results.append(finding(fid, "UNVERIFIED", severity, f"{title}: SKILL.md 원문이 없어 "
                                   "판단하지 않았습니다.", "", target=target, kind="semantic"))
            continue
        if rid not in by_id:
            results.append(finding(fid, "UNVERIFIED", severity, f"{title}: LLM 이 판단하지 "
                                   "않았습니다.", "", target=target, kind="semantic"))
            continue
        passed, reason, fix = by_id[rid]
        if passed:
            results.append(finding(fid, "PASS", severity, "", "", target=target, kind="semantic"))
            continue
        item = finding(fid, "FAIL", severity, f"{title} 아님: {reason}", fix,
                       target=target, kind="semantic")
        if rid == "3-4":
            # 모델 판단을 실행 결과로 확인한다. 재현율이 목표에 못 미치면 실제로 안 불린 것이다.
            confirmed = recall is not None and recall < target_recall
            item["evidence"] = (
                f"실행으로 확인됨: 재현율 {recall}" if confirmed else "실행으로 확인되지 않음"
            )
        results.append(item)
    return results
