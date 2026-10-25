"""도구가 필요한 수정의 판정과 개발 요청서를 고정한다.

계약은 docs/plans/2026-10-26-behavior-edit-root-fix.md 2.11 이다.

네트워크와 유료 호출은 없다. 보호하는 동작은 다음과 같다.
- skillops-agent/skillops_agent/capability.py 의 순수 함수. 담당자가 새로 넣은 inputs·rules 키를
  처음 입력 키와 도구 produces 가 덮지 못하면 missing_value, 새 outputs 키를 어떤 도구도 내지 않으면
  tool_output_missing 이다. order.shipping.country 처럼 덮이는 하위 경로와 기준본에 이미 있던 키는
  빈틈이 아니다. 개발 요청서의 제목, 방법(extend_tool·new_tool), 바꿀 곳, 임시 대안 patch 와 설명.
- skillops-agent/skillops_agent/order_agent.py 의 get_tool_catalog. 읽지 못하거나 모양이 틀리면
  예외 없이 None 이다.
- 그래프 끝까지. 날씨 사례(notify inputs 에 shipping.weather 만 더함)는 사전 검증과 원인 실험 뒤
  의도 추출·후보 생성 없이(LLM 후보 호출 없음) 개발 요청서를 담은 no_valid_fix 제안서로 끝난다.
  원인을 확정하지 못해도 저장하고, 의도가 있어도 같다. 도구 목록을 읽지 못하면 예전 흐름 그대로다.
"""

import difflib
from types import SimpleNamespace
from uuid import uuid4

import httpx
import pytest

from skillops_agent import capability, nodes, order_agent
from skillops_agent.contracts import unified_patch
from skillops_agent.graph import FLOW, graph, route_ablation, route_evidence
from skillops_agent.stub_tools import _TOOL_CATALOG, StubMCPTools, _snapshot_hash

BASELINE = StubMCPTools.BASELINE
CANDIDATE = StubMCPTools.CANDIDATE
EXECUTION = StubMCPTools.EXECUTION
STEPS = ["order-intake", "inventory-check", "payment", "shipping-reserve", "notify"]
CATALOG = _TOOL_CATALOG


def skill(name, inputs, outputs, extra=""):
    """order-agent/skills 와 같은 모양의 단계 skill 원문을 만든다."""
    return (
        f"---\nname: {name}\ndescription: {name} 단계\ninputs: [{', '.join(inputs)}]\n"
        f"outputs: [{', '.join(outputs)}]\ndepends_on: []\n{extra}owner: 주문운영팀\nversion: 1\n"
        f"---\n# {name}\n\n{name} 단계를 처리합니다.\n"
    )


NOTIFY_INPUTS = ["order.id", "order.customer", "payment.status", "shipping.id", "shipping.eta"]
NOTIFY_OUTPUTS = ["notify.sent", "notify.channel"]
NOTIFY_BASE = skill("notify", NOTIFY_INPUTS, NOTIFY_OUTPUTS)
# 실제 사례(사용자 실험 1): 담당자가 notify inputs 에 shipping.weather 만 더했다. 의도는 없다.
NOTIFY_WEATHER = skill("notify", NOTIFY_INPUTS + ["shipping.weather"], NOTIFY_OUTPUTS)
SHIPPING_INPUTS = [
    "order.id", "order.shipping", "payment.id", "payment.status", "inventory.reserved",
]
SHIPPING_BASE = skill("shipping-reserve", SHIPPING_INPUTS, ["shipping.id", "shipping.eta"])
SOURCES = {
    "order-flow": (
        "---\nname: order-flow\ndescription: 처리 순서\n"
        f"steps: [{', '.join(STEPS)}]\nowner: 주문운영팀\nversion: 1\n---\n# 처리 순서\n"
    ),
    "order-intake": skill(
        "order-intake",
        ["order.id", "order.customer", "order.items", "order.payment_method", "order.shipping"],
        ["order.id", "order.total"],
    ),
    "inventory-check": skill(
        "inventory-check",
        ["order.id", "order.items"],
        ["inventory.available", "inventory.reserved"],
    ),
    "payment": skill(
        "payment",
        ["order.id", "order.total", "order.payment_method", "inventory.reserved"],
        ["payment.id", "payment.status"],
    ),
    "shipping-reserve": SHIPPING_BASE,
    "notify": NOTIFY_BASE,
}
# shipping-reserve 와 notify 앞에서 쓸 수 있는 키다(처음 입력 키, 앞 단계 도구 produces 순서).
BEFORE_SHIPPING = [
    "order.id", "order.items", "order.customer", "order.payment_method", "order.shipping",
    "order.total", "inventory.available", "inventory.reservable_quantity", "inventory.reserved",
    "payment.status", "payment.id",
]
BEFORE_NOTIFY = [*BEFORE_SHIPPING, "shipping.id", "shipping.eta"]
WEATHER_GAP = {
    "key": "shipping.weather", "needed_by": "notify", "field": "inputs", "kind": "missing_value",
}


def c_hunk(name, before, after, part="frontmatter"):
    """service 의 diff_skill 이 주는 모양의 단독 hunk 하나를 만든다(머리줄, 문맥 3줄)."""
    path = f"skills/{name}/SKILL.md"
    rows = list(
        difflib.unified_diff(
            before.splitlines(), after.splitlines(), f"a/{path}", f"b/{path}", lineterm=""
        )
    )
    body = rows[3:]
    return {
        "index": 0,
        "hunk_id": f"h_{name}",
        "part": part,
        "header": rows[2],
        "before": "\n".join(r[1:] for r in body if r.startswith("-")),
        "after": "\n".join(r[1:] for r in body if r.startswith("+")),
        "diff": "\n".join(rows) + "\n",
    }


def change(name, before, after, change_no=1, part="frontmatter"):
    """fetch_evidence 가 만드는 change(hunk 에 change_no 와 skill 을 붙인 것)다."""
    return {**c_hunk(name, before, after, part), "change_no": change_no, "skill": name}


# ── 빈틈 판정 ──────────────────────────────────────────────────────────────────


def test_weather_input_is_a_missing_value_gap():
    """어떤 도구도 shipping.weather 를 내지 않으므로 notify inputs 의 빈틈이다."""
    assert capability.find_gaps(CATALOG, {"notify": (NOTIFY_BASE, NOTIFY_WEATHER)}) == [WEATHER_GAP]


def test_new_rule_key_is_a_gap_in_rules_too():
    """rules 의 when 이 보는 새 키도 따로 빈틈이다. inputs 와 rules 를 각각 남긴다."""
    rule = "rules:\n  - when: \"shipping.weather == 'storm'\"\n    then: hold\n    reason: STORM\n"
    edited = skill("notify", NOTIFY_INPUTS + ["shipping.weather"], NOTIFY_OUTPUTS, extra=rule)
    assert capability.find_gaps(CATALOG, {"notify": (NOTIFY_BASE, edited)}) == [
        WEATHER_GAP,
        {**WEATHER_GAP, "field": "rules"},
    ]


def test_new_output_no_tool_produces_is_tool_output_missing():
    """shipping-reserve outputs 에 도구가 내지 않는 shipping.carrier 를 더하면 출력 빈틈이다."""
    edited = skill(
        "shipping-reserve", SHIPPING_INPUTS, ["shipping.id", "shipping.eta", "shipping.carrier"]
    )
    gaps = capability.find_gaps(CATALOG, {"shipping-reserve": (SHIPPING_BASE, edited)})
    assert gaps == [
        {"key": "shipping.carrier", "needed_by": "shipping-reserve", "field": "outputs",
         "kind": "tool_output_missing"}
    ]
    # 확장할 도구는 그 단계 자신의 도구이고, 새 도구는 그 단계 앞에 둔다.
    assert capability.options(CATALOG, gaps, STEPS) == [
        {"type": "extend_tool", "tool": "shipping.reserve", "skill": "shipping-reserve",
         "add_produces": ["shipping.carrier"]},
        {"type": "new_tool", "suggested_name": "carrier.lookup", "place_before": "shipping-reserve",
         "available_inputs": BEFORE_SHIPPING, "produces": ["shipping.carrier"],
         "note": capability.EXAMPLE_NOTE},
    ]


def test_output_produced_by_another_tool_is_not_a_gap():
    """다른 도구가 이미 내는 값은 출력 빈틈이 아니다."""
    outputs = ["shipping.id", "shipping.eta", "order.total"]
    edited = skill("shipping-reserve", SHIPPING_INPUTS, outputs)
    assert capability.find_gaps(CATALOG, {"shipping-reserve": (SHIPPING_BASE, edited)}) == []


def test_nested_key_under_an_initial_input_is_covered():
    """order.shipping.country 는 order.shipping 아래 경로라 inputs·rules 어디든 빈틈이 아니다."""
    intake = SOURCES["order-intake"]
    rule = (
        "rules:\n  - when: \"order.shipping.country != 'KR'\"\n    then: reject\n"
        "    reason: DOMESTIC\n"
    )
    edited = intake.replace("order.shipping]", "order.shipping, order.shipping.country]").replace(
        "owner:", rule + "owner:"
    )
    assert "order.shipping.country]" in edited and "when:" in edited
    assert capability.find_gaps(CATALOG, {"order-intake": (intake, edited)}) == []


def test_keys_already_in_the_baseline_are_ignored():
    """기준본에 이미 있던 키는 도구가 없어도 담당자가 새로 넣은 것이 아니므로 보지 않는다."""
    edited = skill("notify", NOTIFY_INPUTS + ["shipping.weather", "order.total"], NOTIFY_OUTPUTS)
    assert capability.find_gaps(CATALOG, {"notify": (NOTIFY_WEATHER, edited)}) == []


def test_unreadable_frontmatter_has_no_gaps():
    """frontmatter 를 읽을 수 없으면 새 키도 없다고 본다. 모르는 것을 빈틈으로 만들지 않는다."""
    assert capability.find_gaps(CATALOG, {"notify": (NOTIFY_BASE, "본문만")}) == []


def test_may_need_tool_screens_hunks_before_reading_sources():
    """도구가 내지 않는 값 이름이 frontmatter hunk 에 있을 때만 원문을 읽는다.

    본문 hunk 와 diff 머리줄(skills/notify/SKILL.md)은 보지 않는다. StubMCPTools 의 고정 hunk 는
    inventory.reserve 가 내는 값만 있어 거짓이다.
    """
    weather = change("notify", NOTIFY_BASE, NOTIFY_WEATHER)
    assert "--- a/skills/notify/SKILL.md" in weather["diff"]
    assert capability.may_need_tool(CATALOG, [weather]) is True
    assert capability.may_need_tool(CATALOG, [{**weather, "part": "body"}]) is False
    stub_hunk = {"part": "frontmatter",
                 "diff": "@@ -1 +1 @@\n-outputs: [inventory.reserved]\n+outputs: []\n"}
    assert capability.may_need_tool(CATALOG, [stub_hunk]) is False
    covered = change("notify", NOTIFY_BASE, skill("notify", NOTIFY_INPUTS + ["order.total"], []))
    assert capability.may_need_tool(CATALOG, [covered]) is False


# ── 개발 요청서 ────────────────────────────────────────────────────────────────


def test_weather_development_request_shape():
    """제목, 방법, 바꿀 곳, 임시 대안, 근거 실행 칸이 계약대로다."""
    changes = [change("notify", NOTIFY_BASE, NOTIFY_WEATHER)]
    sources = {"notify": (NOTIFY_BASE, NOTIFY_WEATHER)}
    patch = capability.interim_patch(changes, [WEATHER_GAP], sources)
    assert patch == unified_patch("notify", NOTIFY_WEATHER, NOTIFY_BASE)
    assert "-inputs: [order.id, order.customer, payment.status, shipping.id, shipping.eta, " \
        "shipping.weather]\n" in patch
    request = capability.development_request(CATALOG, [WEATHER_GAP], STEPS, patch, ["r_pre"])
    assert request == {
        "kind": "needs_tool",
        "title": "주문 Agent 업무 도구에 'shipping.weather' 값을 만드는 도구 개발 필요",
        "gaps": [WEATHER_GAP],
        "options": [
            {"type": "extend_tool", "tool": "shipping.reserve", "skill": "shipping-reserve",
             "add_produces": ["shipping.weather"]},
            {"type": "new_tool", "suggested_name": "weather.lookup", "place_before": "notify",
             "available_inputs": BEFORE_NOTIFY, "produces": ["shipping.weather"],
             "note": capability.EXAMPLE_NOTE},
        ],
        "change_points": [
            "order-agent/backend/src/main/java/com/capstone4/orderagent/harness/ToolCatalog.java",
            "order-agent/backend/src/main/java/com/capstone4/orderagent/service/OrderToolService.java",
            "order-agent/backend/src/main/java/com/capstone4/orderagent/adapter/",
        ],
        "interim": {
            "summary": "notify 의 inputs 에서 shipping.weather 를 빼는 것이 임시 대안이지만 다시 "
            "처리되는지는 원인 실험으로 확인하지 못했습니다(의도 보류)",
            "patch": patch,
            "proof_run_ids": [],
        },
        "evidence_run_ids": ["r_pre"],
    }
    proven = capability.with_proof(request, ["r_ctl", "r_rev"])
    assert proven["interim"]["proof_run_ids"] == ["r_ctl", "r_rev"]
    assert proven["interim"]["summary"] == (
        "notify 의 inputs 에서 shipping.weather 를 빼면 지금 주문은 다시 처리됩니다(의도 보류)"
    )
    assert request["interim"]["proof_run_ids"] == []


def test_extend_tool_skips_tools_that_run_after_the_needing_step():
    """payment 가 shipping.weather 를 받으려면 결제 뒤의 shipping.reserve 확장은 늦다."""
    gap = {**WEATHER_GAP, "needed_by": "payment"}
    [only] = capability.options(CATALOG, [gap], STEPS)
    assert only["type"] == "new_tool" and only["place_before"] == "payment"
    assert only["available_inputs"] == BEFORE_SHIPPING[:9]


def test_interim_patch_reverts_only_the_offending_hunk():
    """빈틈 키를 들여온 hunk 만 되돌리고 같은 파일의 다른 변경(본문)은 남긴다."""
    edited = NOTIFY_WEATHER.replace("notify 단계를 처리합니다.", "날씨를 보고 알립니다.")
    weather = change("notify", NOTIFY_BASE, NOTIFY_WEATHER)
    body = change(
        "notify", NOTIFY_WEATHER, edited, change_no=2, part="body"
    )
    assert capability.offending_changes([weather, body], [WEATHER_GAP]) == [weather]
    sources = {"notify": (NOTIFY_BASE, edited)}
    patch = capability.interim_patch([weather, body], [WEATHER_GAP], sources)
    reverted = edited.replace(", shipping.weather]", "]")
    assert patch == unified_patch("notify", edited, reverted)


def test_interim_patch_falls_back_to_the_baseline_when_the_hunk_does_not_fit():
    """hunk 가 원문과 맞지 않으면 그 skill 을 기준본으로 되돌리는 patch 다."""
    stale = {**change("notify", NOTIFY_BASE, NOTIFY_WEATHER), "diff": "@@ -40 +40 @@\n-a\n+b\n"}
    sources = {"notify": (NOTIFY_BASE, NOTIFY_WEATHER)}
    patch = capability.interim_patch([stale], [WEATHER_GAP], sources)
    assert patch == unified_patch("notify", NOTIFY_WEATHER, NOTIFY_BASE)


def test_interim_summary_says_restore_when_the_patch_brings_back_the_old_name():
    """출력 이름을 도구에 없는 이름으로 바꾼 수정의 임시 대안은 원래 이름을 되살리는 patch 다.

    실서버 실험(inventory.reserved → inventory.is_reserved)에서 설명이 '빼면' 이라 patch 와 달랐다.
    설명은 patch 가 되살리는 키를 읽어 '원래 값 … 로 되돌리면' 이라고 써야 한다.
    """
    base = (
        "---\nname: inventory-check\noutputs: [inventory.available, inventory.reserved]\n"
        "---\n본문\n"
    )
    edit = base.replace("inventory.reserved]", "inventory.is_reserved]")
    gap = {"key": "inventory.is_reserved", "needed_by": "inventory-check", "field": "outputs",
           "kind": "tool_output_missing"}
    patch = unified_patch("inventory-check", edit, base)
    assert capability.restored_keys(patch) == {
        ("inventory-check", "outputs"): ["inventory.reserved"]
    }
    assert capability.interim_summary([gap], proven=True, patch=patch) == (
        "inventory-check 의 outputs 에서 inventory.is_reserved 를 원래 값 inventory.reserved 로 "
        "되돌리면 지금 주문은 다시 처리됩니다(의도 보류)"
    )
    assert capability.interim_summary([gap], proven=False, patch=patch).startswith(
        "inventory-check 의 outputs 에서 inventory.is_reserved 를 원래 값 inventory.reserved 로 "
        "되돌리는 것이 임시 대안이지만"
    )
    # 새 키만 더한 수정(날씨)은 되살리는 키가 없으므로 그대로 '빼면' 이다.
    weather = capability.interim_patch(
        [change("notify", NOTIFY_BASE, NOTIFY_WEATHER)], [WEATHER_GAP],
        {"notify": (NOTIFY_BASE, NOTIFY_WEATHER)},
    )
    assert capability.restored_keys(weather) == {}


# ── 주문 Agent 도구 목록 조회 ──────────────────────────────────────────────────


def order_client(*responses):
    """요청마다 responses 를 차례로 돌려주는 MockTransport client 와 요청 경로 기록이다."""
    paths = []

    def handle(request):
        paths.append(request.url.path)
        action = responses[min(len(paths), len(responses)) - 1]
        if isinstance(action, Exception):
            raise action
        return action

    return httpx.AsyncClient(base_url="http://order", transport=httpx.MockTransport(handle)), paths


@pytest.fixture
def no_sleep(monkeypatch):
    """재시도 대기를 실제로 기다리지 않는다."""

    async def fake_sleep(_seconds):
        return None

    monkeypatch.setattr(order_agent, "asyncio", SimpleNamespace(sleep=fake_sleep))


async def test_get_tool_catalog_reads_and_normalizes_the_contract_shape():
    """GET /api/tools 응답을 칸을 고정한 사본으로 돌려준다. description 이 없으면 빈 글이다."""
    body = {
        "initial_input_keys": ["order.id"],
        "tools": [{"tool": "order.inspect", "skill": "order-intake", "produces": ["order.id"],
                   "extra": True}],
    }
    client, paths = order_client(httpx.Response(200, json=body))
    async with client:
        assert await order_agent.get_tool_catalog(client=client) == {
            "initial_input_keys": ["order.id"],
            "tools": [{"tool": "order.inspect", "skill": "order-intake", "description": "",
                       "produces": ["order.id"]}],
        }
    assert paths == ["/api/tools"]


async def test_stub_catalog_has_the_contract_shape():
    """StubMCPTools 의 도구 목록은 계약 모양이고 부를 때마다 새 사본이다."""
    stub = StubMCPTools()
    first = await stub.get_tool_catalog()
    assert order_agent._tool_catalog(first) == first == CATALOG
    first["tools"].clear()
    assert (await stub.get_tool_catalog()) == CATALOG


@pytest.mark.parametrize(
    "response",
    [
        httpx.Response(404, json={"code": "NOT_FOUND", "message": "no route"}),
        httpx.Response(503),
        httpx.Response(200, content=b"not JSON"),
        httpx.ConnectError("down"),
        httpx.Response(200, json=[]),
        httpx.Response(200, json={"initial_input_keys": ["order.id"], "tools": []}),
        httpx.Response(200, json={"initial_input_keys": "order.id", "tools": CATALOG["tools"]}),
        httpx.Response(200, json={"initial_input_keys": [], "tools": [
            {"tool": "x", "skill": "y"}]}),
        httpx.Response(200, json={"initial_input_keys": [], "tools": [
            {"tool": "x", "skill": "y", "produces": [1]}]}),
        httpx.Response(200, json={"initial_input_keys": [], "tools": [
            {"tool": "", "skill": "y", "produces": []}]}),
        httpx.Response(200, json={"initial_input_keys": [], "tools": [
            {"tool": "x", "skill": "y", "produces": [], "description": 3}]}),
    ],
)
async def test_get_tool_catalog_returns_none_instead_of_raising(no_sleep, response):
    """연결 실패, 실패 응답, 깨진 JSON, 모양이 틀린 응답은 모두 None 이다."""
    client, _ = order_client(response)
    async with client:
        assert await order_agent.get_tool_catalog(client=client) is None


# ── 그래프 끝까지 ──────────────────────────────────────────────────────────────


class WeatherStub(StubMCPTools):
    """날씨 사례 대역이다. notify inputs 에 shipping.weather 만 더한 수정본이다.

    수정본 workspace 는 시험 주문이 떨어지고(StubMCPTools 규칙), 원인 hunk 를 되돌린 workspace 는
    통과한다. 도구 목록은 StubMCPTools 의 실제 주문 Agent 표다. catalog 를 바꾸면 도구 목록 응답이
    바뀌고, 예외면 그 예외를 던진다.
    """

    changed = "notify"
    edited = NOTIFY_WEATHER
    catalog = "default"

    def __init__(self):
        super().__init__()
        self.skill_reads = []

    async def get_tool_catalog(self):
        if self.catalog == "default":
            return await super().get_tool_catalog()
        if isinstance(self.catalog, Exception):
            raise self.catalog
        return self.catalog

    async def get_snapshot(self, snapshot_id):
        return {
            "snapshot_id": snapshot_id,
            "hash": _snapshot_hash(snapshot_id),
            "metadata": {"baseline_snapshot_id": BASELINE},
            "files": [
                {"path": f"skills/{name}/SKILL.md",
                 "hash": ("old" if snapshot_id == BASELINE else "new")
                 if name == self.changed else "same"}
                for name in SOURCES
            ],
        }

    async def list_skills(self, snapshot_id):
        return {
            "snapshot_id": snapshot_id,
            "snapshot_hash": _snapshot_hash(snapshot_id),
            "items": [{"skill_id": name, "version": 1} for name in SOURCES],
            "partial": False,
            "parse_errors": [],
        }

    async def get_skill(self, snapshot_id, skill_id):
        self.skill_reads.append((snapshot_id, skill_id))
        content = SOURCES[skill_id]
        if skill_id == self.changed and snapshot_id == CANDIDATE:
            content = self.edited
        return {"snapshot_id": snapshot_id, "skill_id": skill_id, "version": 1, "content": content}

    async def diff_skill(self, skill_id, before_snapshot_id, after_snapshot_id):
        return {
            "skill_id": skill_id,
            "before": {"snapshot_id": before_snapshot_id,
                       "snapshot_hash": _snapshot_hash(before_snapshot_id), "file_hash": "old"},
            "after": {"snapshot_id": after_snapshot_id,
                      "snapshot_hash": _snapshot_hash(after_snapshot_id), "file_hash": "new"},
            "status": "modified",
            "hunks": [c_hunk(skill_id, SOURCES[skill_id], self.edited)],
        }

    async def get_dependency_graph(self, snapshot_id, entry_skill_id):
        view = await super().get_dependency_graph(snapshot_id, entry_skill_id)
        return {**view, "steps": STEPS}

    async def create_workspace(self, start_versions=None, snapshot_id=None):
        ws = await super().create_workspace(start_versions, snapshot_id)
        return {**ws, "skill_versions": dict.fromkeys(SOURCES, 1)}


class RecordingLLM:
    """make_structured_llm 대역이다. 부른 출력 클래스 이름을 남기고, 모르는 이름이면 실패시킨다."""

    def __init__(self, outputs):
        self.outputs = outputs
        self.calls = []

    def __call__(self, schema):
        name = schema.__name__
        self.calls.append(name)
        if name not in self.outputs:
            pytest.fail(f"unexpected LLM call: {name}")
        output = self.outputs[name]

        class Chain:
            async def ainvoke(self, *args):
                return output

        return Chain()


HYPOTHESES = SimpleNamespace(
    hypotheses=[SimpleNamespace(
        change_no=1, summary="notify 가 어떤 도구도 만들지 않는 shipping.weather 를 받음",
        confidence=0.98,
    )]
)
NEEDS_TOOL_NODES = ["init", "fetch_evidence", "hypothesize", "ablate", "check_ablation",
                    "save_proposal"]


@pytest.fixture
def weather(monkeypatch):
    """WeatherStub 하나를 nodes.get_tools 자리에 끼운다."""
    stub = WeatherStub()
    monkeypatch.setattr(nodes, "get_tools", lambda: stub)
    return stub


@pytest.fixture
def llm(monkeypatch):
    """가설만 낼 수 있는 가짜 LLM 이다. 의도·후보·순위 출력을 부르면 테스트가 실패한다."""
    fake = RecordingLLM({"_HypothesesOutput": HYPOTHESES})
    monkeypatch.setattr(nodes, "make_structured_llm", fake)
    return fake


async def run(state):
    """새 thread_id 로 그래프를 끝까지 돌린다."""
    return await graph.ainvoke(
        state, {"configurable": {"thread_id": str(uuid4())}, "recursion_limit": 100}
    )


def saved_trigger(intent=None):
    """수정본 저장 직후 요청이다."""
    trigger = {"type": "skill_saved", "candidate_snapshot_id": CANDIDATE}
    if intent is not None:
        trigger["intent"] = intent
    return {"trigger": trigger, "max_steps": 40}


def nodes_of(result):
    return [event["node"] for event in result["events"]]


async def test_weather_case_saves_a_development_request_without_candidate_llm(weather, llm):
    """원인 실험으로 원인을 확정한 뒤 후보 생성 없이 개발 요청서를 담은 제안서를 저장한다."""
    result = await run(saved_trigger())

    assert result["error"] is None
    assert result["diagnosis_status"] == "EVALUATED"
    assert nodes_of(result) == NEEDS_TOOL_NODES
    assert llm.calls == ["_HypothesesOutput"]
    assert result["events"][1]["msg"] == (
        "실제 snapshot과 실행 증거 수집, 도구 개발 필요(shipping.weather)"
    )
    assert result["capability_gaps"] == [WEATHER_GAP]
    assert result["diagnosis"]["finding"] == "NEEDS_TOOL"
    assert "shipping.weather" in result["diagnosis"]["explanation"]
    assert result["candidates"] == [] and result["intent"] is None
    root_cause = result["root_cause"]
    assert root_cause["skill"] == "notify" and root_cause["change_no"] == 1
    assert root_cause["kind"] == "change"

    [proposal] = weather.saved
    assert proposal["status"] == "no_valid_fix"
    assert proposal["candidates"] == []
    assert list(proposal)[-1] == "development_request"
    request = proposal["development_request"]
    expected = capability.development_request(
        CATALOG, [WEATHER_GAP], STEPS, unified_patch("notify", NOTIFY_WEATHER, NOTIFY_BASE),
        result["preflight_run_ids"],
    )
    assert request == capability.with_proof(expected, root_cause["proof_run_ids"])
    assert request["interim"]["proof_run_ids"] == root_cause["proof_run_ids"] != []
    assert request["interim"]["summary"] == (
        "notify 의 inputs 에서 shipping.weather 를 빼면 지금 주문은 다시 처리됩니다(의도 보류)"
    )
    assert request["evidence_run_ids"] == result["preflight_run_ids"] != []
    assert result["proposal"]["development_request"] == request
    assert result["development_request"] == request
    # 판정에 쓴 원문 읽기는 바뀐 skill 의 기준본·수정본 두 번이다.
    assert weather.skill_reads == [(BASELINE, "notify"), (CANDIDATE, "notify")]


async def test_weather_failure_trigger_with_owner_intent_takes_the_same_route(weather, llm):
    """실패 실행 진단이고 담당자 의도가 있어도 의도 추출·후보 생성 없이 같은 길로 끝난다."""
    intent = {
        "text": "날씨가 나쁘면 알림을 보류한다",
        "expectations": [{"scope": "order.payment_method == 'card'",
                          "checks": ["order.final_status == 'HOLD'"]}],
    }
    state = {"trigger": {"type": "failure", "execution_id": EXECUTION, "intent": intent},
             "max_steps": 40}
    result = await run(state)

    assert result["error"] is None
    assert nodes_of(result) == NEEDS_TOOL_NODES
    assert llm.calls == ["_HypothesesOutput"]
    assert result["diagnosis"]["finding"] == "NEEDS_TOOL"
    [proposal] = weather.saved
    assert proposal["status"] == "no_valid_fix" and proposal["candidates"] == []
    assert proposal["intent"]["source"] == "owner"
    assert proposal["intent"]["expectations"] == intent["expectations"]
    request = proposal["development_request"]
    assert request["gaps"] == [WEATHER_GAP]
    # 실패 실행 진단에는 사전 검증 실행이 없다. 임시 대안 근거는 대조 실행과 되돌린 실행이다.
    assert request["evidence_run_ids"] == []
    assert request["interim"]["proof_run_ids"] == result["root_cause"]["proof_run_ids"]
    assert len(request["interim"]["proof_run_ids"]) == 2


async def test_unconfirmed_ablation_still_saves_the_development_request(weather, llm, monkeypatch):
    """원인 실험이 원인을 확정하지 못해도 개발 요청서를 저장한다. 임시 대안 근거는 비어 있다."""
    original = weather.apply_patch

    async def apply(workspace_id, patches):
        result = await original(workspace_id, patches)
        weather._workspaces[workspace_id] = False
        return result

    monkeypatch.setattr(weather, "apply_patch", apply)
    result = await run(saved_trigger())

    assert result["error"] is None
    assert result["diagnosis_status"] == "EVALUATED"
    assert nodes_of(result)[-1] == "save_proposal"
    assert "generate_candidates" not in nodes_of(result)
    assert set(llm.calls) == {"_HypothesesOutput"}
    assert result["root_cause"] is None
    assert result["diagnosis"]["finding"] == "NEEDS_TOOL"
    [proposal] = weather.saved
    request = proposal["development_request"]
    assert proposal["status"] == "no_valid_fix" and proposal["root_cause"] is None
    assert request["interim"]["proof_run_ids"] == []
    assert "확인하지 못했습니다" in request["interim"]["summary"]


def test_needs_tool_routes_go_straight_to_save_proposal():
    """NEEDS_TOOL 이면 원인 확정·진단 종료 뒤 needs_tool 이고, fetch_evidence 가 원인을 정했으면
    fetch_evidence 뒤에서 바로 needs_tool 이다. NEEDS_TOOL 이 아니면 예전 라벨 그대로다."""
    needs = {"finding": "NEEDS_TOOL"}
    base = {"diagnosis_status": "RUNNING", "changes": [{}], "hypothesis_retry": 0,
            "step_count": 1, "max_steps": 40}
    assert route_evidence({**base, "diagnosis": needs}) == "diagnose"
    decided = {**base, "diagnosis": needs, "root_cause": {"kind": "incomplete_edit"}}
    assert route_evidence(decided) == "needs_tool"
    intent_gap = {"finding": "INTENT_NOT_REALIZED"}
    assert route_evidence({**base, "diagnosis": intent_gap, "root_cause": {}}) == "intent_gap"
    confirmed = {**base, "root_cause": {"skill": "x"}}
    assert route_ablation({**confirmed, "diagnosis": needs}) == "needs_tool"
    assert route_ablation(confirmed) == "confirmed"
    failed = {**base, "diagnosis_status": "VALIDATION_FAILED"}
    assert route_ablation({**failed, "diagnosis": needs}) == "needs_tool"
    assert route_ablation(failed) == "stop"
    assert route_ablation({**base, "diagnosis": needs}) == "retry"
    assert FLOW["fetch_evidence"][1]["needs_tool"] == "save_proposal"
    assert FLOW["check_ablation"][1]["needs_tool"] == "save_proposal"


@pytest.mark.parametrize("catalog", [None, RuntimeError("tools api down")])
async def test_unreadable_tool_catalog_keeps_the_old_flow(weather, monkeypatch, catalog):
    """도구 목록을 읽지 못하면 판정을 건너뛰고 예전처럼 의도 추출·후보 생성으로 간다."""
    weather.catalog = catalog
    fake = RecordingLLM({
        "_HypothesesOutput": HYPOTHESES,
        "_IntentOutput": SimpleNamespace(text="notify 알림", checks=[]),
        "_CandidatesOutput": SimpleNamespace(candidates=[
            SimpleNamespace(kind="same_skill",
                            patches=[SimpleNamespace(skill="notify", full_text=NOTIFY_BASE)])
        ]),
        "_RankExplanationsOutput": SimpleNamespace(explanations=[]),
    })
    monkeypatch.setattr(nodes, "make_structured_llm", fake)
    result = await run(saved_trigger())

    assert result["error"] is None
    assert "_CandidatesOutput" in fake.calls
    assert nodes_of(result)[:7] == [
        "init", "fetch_evidence", "hypothesize", "ablate", "check_ablation", "extract_intent",
        "generate_candidates",
    ]
    assert "capability_gaps" not in result and "development_request" not in result
    assert (result["diagnosis"] or {}).get("finding") is None
    [proposal] = weather.saved
    assert "development_request" not in proposal
    assert result["events"][1]["msg"] == "실제 snapshot과 실행 증거 수집"


async def test_covered_edit_reads_no_extra_sources(weather, monkeypatch):
    """도구가 내는 값만 더한 수정은 원문을 더 읽지 않고 예전 흐름이다.

    order.total 은 order.inspect 가 낸다.
    """
    weather.edited = skill("notify", NOTIFY_INPUTS + ["order.total"], NOTIFY_OUTPUTS)
    fake = RecordingLLM({
        "_HypothesesOutput": HYPOTHESES,
        "_IntentOutput": SimpleNamespace(text="notify 알림", checks=[]),
        "_CandidatesOutput": SimpleNamespace(candidates=[
            SimpleNamespace(kind="same_skill",
                            patches=[SimpleNamespace(skill="notify", full_text=NOTIFY_BASE)])
        ]),
        "_RankExplanationsOutput": SimpleNamespace(explanations=[]),
    })
    monkeypatch.setattr(nodes, "make_structured_llm", fake)
    result = await run(saved_trigger())

    assert result["error"] is None
    assert "capability_gaps" not in result
    assert "generate_candidates" in nodes_of(result)
    # 원문은 후보 생성(_repair_inputs, _baseline_sources)에서만 읽는다.
    assert (BASELINE, "notify") in weather.skill_reads
    assert weather.skill_reads[0] == (CANDIDATE, "order-flow")
