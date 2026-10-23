"""진입 skill 선택이 무엇을 바꾸면 깨지는지 검사한다(주문 Agent 용).

주문 Agent 는 어떤 주문이 와도 정답이 진입 skill(order-flow) 하나라, 질의를 늘리거나 나눠도 같은
문제를 다시 푸는 셈이다. 그래서 "얼마나 맞히나" 대신 기획안 P1("고쳤더니 다른 게 깨짐")처럼
"무엇이 바뀌면 깨지나"를 본다.

변형은 코드가 정해진 규칙으로 만들고(LLM 없음), 선택만 선택 Agent(LLM)가 한다.
- order: 후보 목록 순서를 바꾼다(대상 맨 앞, 맨 뒤, 거꾸로).
- rival: 단계 skill 하나의 description 에 진입 skill 처럼 보이는 문구를 붙인다.
변형마다 진입 skill 을 고른 비율이 목표(TRIGGER_TARGET_RECALL) 이상이면 통과다.
"""

from . import trigger_nodes
from .config import ROBUSTNESS_CASES, ROBUSTNESS_RIVAL_PHRASE, TRIGGER_TARGET_RECALL


def variations(skills: list[dict], target: str, sub_steps: dict) -> list[dict]:
    """검사할 변형 목록이다. 원본을 고치지 않고 새 목록을 만든다."""
    others = [s for s in skills if s["name"] != target]
    me = [s for s in skills if s["name"] == target]
    out = [
        {"id": "order.original", "kind": "order", "detail": "원래 순서", "skills": list(skills)},
        {"id": "order.target_first", "kind": "order", "detail": "대상을 맨 앞에",
         "skills": me + others},
        {"id": "order.target_last", "kind": "order", "detail": "대상을 맨 뒤에",
         "skills": others + me},
        {"id": "order.reversed", "kind": "order", "detail": "순서를 거꾸로",
         "skills": list(reversed(skills))},
    ]
    # 진입/단계 구조가 있을 때만 단계 skill 을 진입처럼 보이게 바꿔 본다.
    for step in sub_steps:
        changed = [
            {**s, "description": f"{s['description']} {ROBUSTNESS_RIVAL_PHRASE}".strip()}
            if s["name"] == step else s
            for s in skills
        ]
        out.append({"id": f"rival.{step}", "kind": "rival",
                    "detail": f"{step} 설명에 '{ROBUSTNESS_RIVAL_PHRASE}' 를 붙임",
                    "skills": changed})
    return out


async def check(skills, cases, target, structure, selector="chat", description=None) -> dict:
    """변형마다 진입 skill 을 고른 비율을 재고, 통과 여부를 코드로 정한다.

    description 을 주면 대상 skill 의 description 을 그것으로 바꿔 검사한다(개선안 검사).
    """
    if description is not None:
        skills = [{**s, "description": description} if s["name"] == target else s
                  for s in skills]
    sample = [c for c in cases if c["should_trigger"] and c["expected_skill"] == target]
    sample = sample[:ROBUSTNESS_CASES]
    results = []
    for v in variations(skills, target, (structure or {}).get("sub_steps") or {}):
        picks = await trigger_nodes._select_all(sample, v["skills"], 1, selector)
        chosen = [p[0] for p in picks]
        hits = sum(p == target for p in chosen)
        rate = hits / len(chosen) if chosen else None
        results.append({
            "id": v["id"], "kind": v["kind"], "detail": v["detail"],
            "hits": f"{hits}/{len(chosen)}",
            "passed": rate is not None and rate >= TRIGGER_TARGET_RECALL,
            "chosen_instead": sorted({p for p in chosen if p != target}),
        })
    broken = [r["id"] for r in results if not r["passed"]]
    return {
        "robust": bool(results) and not broken,
        "broken_by": broken,
        "cases": len(sample),
        "results": results,
    }
