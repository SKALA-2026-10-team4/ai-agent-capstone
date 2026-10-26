"""진단 상태에 맞는 RAG 참고 자료를 골라 LLM 프롬프트에 붙일 글로 만든다.

자료는 skillops-agent/skillops_agent/rag_data.py 가 만든 skillops-agent/rag/rag_data.json 이다.
팀 RAG 실험의 TSG 설계(도구 계약 + skill 이름으로 조회 + 판단 기준)를 따른다. 유사도 검색으로 문서를
고르지 않는다. 실험에서 유사도 검색은 엉뚱한 skill 의 문서를 가져오거나, 사례 문서가 도구 계약을
밀어내 정답률이 떨어졌다. 그래서 자료를 두 자리로 나눠 넣는다.

고정 자리(항상 먼저, 밀려나지 않는다)
- tool_contract: 원인 skill(root_cause.skill)과 바뀐 skill(changes[].skill)과 이름이 같은 것만
- contract_map: 항상
- policy-flow: order-flow 가 원인이거나 바뀌었을 때만
- guideline: 실패 기록의 오류 종류가 applies_to_errors 에 들 때. 오류 종류를 모르면 모두

남는 자리(EXTRA_SLOTS 개까지)
- case: 오류 종류와 원인·바뀐 skill 로 거른 뒤 진단 상태와 글자가 많이 겹치는 순으로
  MAX_CASES 개까지
- 그 밖의 policy(policy-outcomes 등): applies_to_skills 가 대상 skill 과 겹칠 때

skillops-agent/skillops_agent/nodes.py 의 hypothesize, extract_intent, generate_candidates 가
references 를 불러 _ask_llm 에 넘기고, _ask_llm 이 프롬프트 맨 끝에 붙인다. rank_and_explain 은 쓰지
않는다. 자료 파일이 없거나 SKILLOPS_RAG_ENABLED=false 이면 빈 글을 돌려주고 진단은 자료 없이 그대로
진행한다. 빼고 고를 자료 id 는 환경 변수 SKILLOPS_RAG_EXCLUDE 와 진단 요청의 rag_exclude 를 합친
것이다(excluded_ids).
"""

import json
import re
from functools import lru_cache
from pathlib import Path

from . import config
from .rag_data import DATA_PATH, FLOW_SKILL

# 프롬프트에 붙일 때 맨 앞에 두는 머리말이다. 자료가 지시가 아니라 판단 근거임을 밝힌다.
HEADER = "## 참고 자료 (사내 지식 검색 결과, 판단 근거로만 사용)"
# 남는 자리의 크기와 그 안에서 사례의 상한이다. 실험에서 사례가 여러 자리를 차지하자 도구 계약이
# 밀려 정답률이 떨어져 사례는 1~2건으로 묶는다.
EXTRA_SLOTS = 2
MAX_CASES = 2
# 줄 앞에 붙이는 종류 이름이다. contract_map 은 글 자체가 '[정상 계약 지도: 게시본 기준]' 으로
# 시작한다.
_LABELS = {"tool_contract": "[도구 계약] ", "policy": "[업무 규칙] ",
           "guideline": "[사내 수정 판단 기준] ", "case": "[장애 사례] ", "contract_map": ""}
_WORD = re.compile(r"[a-z][a-z0-9_.-]+")


@lru_cache(maxsize=4)
def _load(path: str) -> dict | None:
    """자료 파일을 한 번 읽어 둔다. 파일이 없으면 None 이다.

    파일을 다시 만들면 프로세스를 다시 띄워야 새 자료를 읽는다.
    """
    file = Path(path)
    if not file.exists():
        return None
    return json.loads(file.read_text(encoding="utf-8"))


def target_skills(state: dict) -> set[str]:
    """자료를 고를 skill 이름이다. 원인 skill 과 바뀐 skill 이다.

    hypothesize 때는 원인이 아직 없으므로 바뀐 skill 만 쓴다.
    """
    skills = {change.get("skill") for change in state.get("changes") or []}
    root = (state.get("root_cause") or {}).get("skill")
    if root:
        skills.add(root)
    skills.discard(None)
    return skills


def _error_events(state: dict) -> list[dict]:
    events = (state.get("trace") or {}).get("events", [])
    return [e.get("data") or {} for e in events if e.get("kind") == "EXECUTION_ERROR"]


def error_categories(state: dict) -> set[str]:
    """진단 대상 실행 기록의 EXECUTION_ERROR 오류 종류다. 사전 검증 중이면 비어 있을 수 있다."""
    return {data["category"] for data in _error_events(state) if data.get("category")}


def _state_words(state: dict) -> set[str]:
    """사례와 견줄 진단 상태의 낱말이다. 바뀐 줄, 실패 skill, 누락 입력, 오류 종류를 쓴다."""
    parts = [
        f"{c.get('skill')} {c.get('before')} {c.get('after')}" for c in state.get("changes") or []
    ]
    for data in _error_events(state):
        missing = " ".join(data.get("missing_inputs") or [])
        parts.append(f"{data.get('skill_id')} {data.get('category')} {missing}")
    return set(_WORD.findall(" ".join(parts).lower()))


def _similarity(words: set[str], item: dict) -> float:
    """글자 겹침 점수(Jaccard)다. 거른 뒤 후보가 몇 건뿐이라 임베딩 대신 이 점수로 순서만 정한다."""
    other = set(_WORD.findall(item.get("text", "").lower()))
    return len(words & other) / len(words | other) if words and other else 0.0


def select(state: dict, items: list[dict], excluded: frozenset[str] = frozenset()) -> list[dict]:
    """모듈 설명의 규칙대로 자료를 고른다.

    excluded 의 id 는 고르지 않는다. 평가할 때 같은 케이스의 사례를 빼서 답 누출을 막는 데 쓴다.
    """
    skills, categories = target_skills(state), error_categories(state)
    usable = [i for i in items if i["id"] not in excluded]
    by_kind: dict[str, list[dict]] = {}
    for item in usable:
        by_kind.setdefault(item["kind"], []).append(item)

    fixed = [i for i in by_kind.get("tool_contract", []) if i.get("skill") in skills]
    fixed += by_kind.get("contract_map", [])
    policies = by_kind.get("policy", [])
    if FLOW_SKILL in skills:
        fixed += [p for p in policies if FLOW_SKILL in p.get("applies_to_skills", [])]
    fixed += [g for g in by_kind.get("guideline", [])
              if not categories or categories & set(g.get("applies_to_errors", []))]

    words = _state_words(state)
    # 사례는 실패한 skill 이 아니라 원인 skill·바뀐 skill 이 같아야 고른다. 실패 위치만 같은
    # 사례(예: payment 에서 실패했지만 원인은 inventory-check 인 S2)는 다른 해법을 보여 줘
    # 수정안을 흐렸다.
    cases = [c for c in by_kind.get("case", [])
             if (not categories or categories & set(c.get("error_categories", [])))
             and skills & {c.get("root_skill"), (c.get("change") or {}).get("skill")}]
    cases.sort(key=lambda c: (-_similarity(words, c), c["id"]))
    extra = cases[:MAX_CASES]
    if _needs_outcome_rules(state, skills, categories):
        # order-flow 가 바뀌면 흐름 전체의 업무 결과가 걸리므로 skill 이 겹치지 않아도 넣는다.
        extra += [p for p in policies if p not in fixed and (
            FLOW_SKILL in skills or skills & set(p.get("applies_to_skills", [])))]
    return fixed + extra[:EXTRA_SLOTS]


def _needs_outcome_rules(state: dict, skills: set[str], categories: set[str]) -> bool:
    """정상 거절 규칙(policy-outcomes 등)이 필요한지 정한다.

    order-flow 가 바뀌었거나, 실패 기록에 오류 종류가 없거나, 주문이 REJECTED·HOLD 로 끝났을 때다.
    오류 종류가 분명한 계약 결함에는 넣지 않는다. 그때는 정상 거절과 헷갈릴 일이 없다.
    """
    final = (state.get("trace") or {}).get("final_status")
    return FLOW_SKILL in skills or not categories or final in ("REJECTED", "HOLD")


def render(picked: list[dict]) -> str:
    """고른 자료를 프롬프트 맨 끝에 붙일 글로 만든다. 고른 것이 없으면 빈 글이다."""
    if not picked:
        return ""
    lines = [f"- {_LABELS.get(item['kind'], '')}{item['text']}" for item in picked]
    return "\n\n" + HEADER + "\n" + "\n".join(lines) + "\n"


def excluded_ids(state: dict) -> frozenset[str]:
    """고르지 않을 자료 id 다. 환경 변수(config.RAG_EXCLUDED_IDS)와 요청의 rag_exclude 를 합친다.

    QA 실행기(qa/run_qa.py)는 평가하는 케이스와 대응하는 사례 id 를 /diagnose 의 rag_exclude 로 보내
    정답이 프롬프트에 새지 않게 한다(docs/plans/2026-10-26-behavior-edit-root-fix.md 0절 5).
    """
    requested = (state.get("trigger") or {}).get("rag_exclude") or []
    return frozenset(config.RAG_EXCLUDED_IDS) | frozenset(requested)


def references(state: dict, path: Path = DATA_PATH) -> tuple[str, list[str]]:
    """진단 상태로 참고 자료를 골라 (프롬프트에 붙일 글, 고른 자료 id 목록)을 돌려준다."""
    if not config.RAG_ENABLED:
        return "", []
    data = _load(str(path))
    if not data:
        return "", []
    picked = select(state, data.get("items", []), excluded_ids(state))
    return render(picked), [item["id"] for item in picked]
