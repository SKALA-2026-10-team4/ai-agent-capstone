"""RAG 참고 자료 선택의 제외 규칙과 사례 항목의 qa_cases 칸을 고정한다.

skillops-agent/skillops_agent/rag.py 의 references 는 환경 변수 SKILLOPS_RAG_EXCLUDE 와 진단 요청의
rag_exclude 를 합쳐 뺀다. QA 실행기가 평가하는 케이스의 정답 사례를 가리는 데 쓴다
(docs/plans/2026-10-26-behavior-edit-root-fix.md 0절 5). skillops-agent/skillops_agent/rag_data.py
의 case_item 은 사례의 qa_cases 칸을 그대로 남긴다.
"""

import json

from skillops_agent import config, rag
from skillops_agent.rag_data import case_item

ITEMS = [
    {"id": "map-1", "kind": "contract_map", "text": "[정상 계약 지도] a"},
    {"id": "map-2", "kind": "contract_map", "text": "[정상 계약 지도] b"},
    {"id": "map-3", "kind": "contract_map", "text": "[정상 계약 지도] c"},
]


def data_file(tmp_path):
    """contract_map 항목 세 개만 든 자료 파일을 만든다. contract_map 은 늘 고르는 자리다."""
    path = tmp_path / "rag_data.json"
    path.write_text(json.dumps({"items": ITEMS}, ensure_ascii=False), encoding="utf-8")
    return path


def test_request_rag_exclude_is_added_to_environment_exclusion(tmp_path, monkeypatch):
    monkeypatch.setattr(config, "RAG_ENABLED", True)
    monkeypatch.setattr(config, "RAG_EXCLUDED_IDS", ["map-3"])
    state = {"trigger": {"rag_exclude": ["map-1"]}}
    assert rag.excluded_ids(state) == frozenset({"map-1", "map-3"})
    text, ids = rag.references(state, data_file(tmp_path))
    assert ids == ["map-2"]
    assert "[정상 계약 지도] a" not in text and "[정상 계약 지도] b" in text


def test_missing_rag_exclude_uses_environment_only(tmp_path, monkeypatch):
    monkeypatch.setattr(config, "RAG_ENABLED", True)
    monkeypatch.setattr(config, "RAG_EXCLUDED_IDS", [])
    assert rag.excluded_ids({"trigger": {}}) == frozenset()
    assert rag.excluded_ids({}) == frozenset()
    _, ids = rag.references({"trigger": {"rag_exclude": None}}, data_file(tmp_path))
    assert ids == ["map-1", "map-2", "map-3"]


def test_case_item_passes_qa_cases_through():
    case = {
        "id": "case-S2",
        "symptom": {"failed_skill": "payment", "error_category": "CONTRACT_VIOLATION"},
        "change": {"skill": "inventory-check", "field": "outputs", "before": "a", "after": "b"},
        "root_skill": "inventory-check",
        "qa_cases": ["C-01", "U-01", "G-01", "G-02"],
    }
    assert case_item(case)["qa_cases"] == ["C-01", "U-01", "G-01", "G-02"]
    assert case_item({k: v for k, v in case.items() if k != "qa_cases"})["qa_cases"] == []
