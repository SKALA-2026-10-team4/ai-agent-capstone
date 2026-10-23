"""진입 skill 견고성 검사(trigger_robustness)를 가짜 선택 Agent 로 검사한다."""

from types import SimpleNamespace

from skillops_agent import config, trigger_nodes, trigger_robustness

SKILLS = [
    {"name": "order-flow", "description": "주문 순서"},
    {"name": "order-intake", "description": "주문을 검수한다"},
    {"name": "payment", "description": "결제한다"},
]
STRUCTURE = {"entries": ["order-flow"], "sub_steps": {"order-intake": "order-flow",
                                                      "payment": "order-flow"}}
ORDER = {"customer": "c", "items": [{"sku": "S", "qty": 1}], "payment_method": "card",
         "shipping": {"country": "KR", "express": False}}
CASES = [{"query": f"g-{i}", "expected_skill": "order-flow", "should_trigger": True,
          "order": ORDER} for i in range(8)]


def install(monkeypatch, choose):
    """주문 Agent 질문(JSON 후보 목록)을 받아 choose(candidates) 로 고르는 가짜 LLM 을 끼운다."""
    import json

    class Chain:
        async def ainvoke(self, messages):
            question = json.loads(messages[1][1])
            return SimpleNamespace(skill=choose(question["candidates"]))

    monkeypatch.setattr(trigger_nodes, "make_structured_llm", lambda schema: Chain())


def test_variations_are_built_by_rule():
    v = trigger_robustness.variations(SKILLS, "order-flow", STRUCTURE["sub_steps"])
    ids = [x["id"] for x in v]
    assert ids == ["order.original", "order.target_first", "order.target_last",
                   "order.reversed", "rival.order-intake", "rival.payment"]
    rival = next(x for x in v if x["id"] == "rival.payment")["skills"]
    assert rival[2]["description"] == f"결제한다 {config.ROBUSTNESS_RIVAL_PHRASE}"
    assert SKILLS[2]["description"] == "결제한다"  # 원본은 그대로
    # 진입/단계 구조가 없으면(대화형) 순서 변형만 한다.
    no_structure = trigger_robustness.variations(SKILLS, "order-flow", {})
    assert all(x["kind"] == "order" for x in no_structure)


async def test_fragile_description_breaks_on_order_and_rival(monkeypatch):
    # 약한 description: 첫 후보를 고르고, '주문을 처리' 라고 쓴 단계 skill 이 있으면 그걸 고른다.
    def choose(candidates):
        for c in candidates:
            if config.ROBUSTNESS_RIVAL_PHRASE in c["description"]:
                return c["name"]
        return candidates[0]["name"]

    install(monkeypatch, choose)
    r = await trigger_robustness.check(SKILLS, CASES, "order-flow", STRUCTURE, "order-agent")
    assert r["robust"] is False
    assert r["cases"] == config.ROBUSTNESS_CASES  # 앞에서 정한 수만큼만 묻는다
    by_id = {x["id"]: x for x in r["results"]}
    assert by_id["order.original"]["passed"] and by_id["order.target_first"]["passed"]
    assert not by_id["order.target_last"]["passed"]
    assert by_id["rival.payment"]["chosen_instead"] == ["payment"]
    assert set(r["broken_by"]) == {"order.target_last", "order.reversed", "rival.order-intake",
                                   "rival.payment"}


async def test_strong_description_survives(monkeypatch):
    # 강한 description: '가장 먼저' 라고 쓴 진입 skill 을 늘 고른다.
    def choose(candidates):
        for c in candidates:
            if "가장 먼저" in c["description"]:
                return c["name"]
        return candidates[0]["name"]

    install(monkeypatch, choose)
    r = await trigger_robustness.check(SKILLS, CASES, "order-flow", STRUCTURE, "order-agent",
                                       description="새 주문이 오면 가장 먼저 선택된다")
    assert r["robust"] is True and r["broken_by"] == []
    assert all(x["hits"] == "5/5" for x in r["results"])
