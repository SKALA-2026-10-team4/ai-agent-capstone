"""skillops-agent/skillops_agent/tools.py 의 MCPTools 와 get_tools 를 고정하는 특성 테스트다.

네트워크와 LLM 을 부르지 않고 대역만 쓴다. 보호하는 동작은 다음과 같다.
- _call 이 처음으로 비어 있지 않은 text 를 JSON 으로 읽고, ping 만 JSON 이 아닌 글을 허락한다. 오류
  결과는 JSON 객체일 때만 MCPToolError 가 되고, 읽을 수 없으면 ContractError 가 된다.
- MCP 도구 메서드가 service 도구 이름과 인자 키를 정해진 순서로 보내고, 선택 인자는 참일 때만
  넣는다.
- 주문 Agent 조회가 self._http 로 계약 경로를 부르고, get_snapshot 만 UUID 검사와 재시도 없이
  HTTPStatusError 를 낸다.
- __aenter__ 는 MCP 세션을 연 뒤 httpx client 를 만들고, __aexit__ 는 앱 예외를 SDK 에 넘기지
  않으며 실패해도 httpx client 를 닫는다.
- get_tools 가 부를 때마다 USE_STUB_MCP 를 읽고, tools 가 StubMCPTools 와
  skillops-agent/skillops_agent/mcp_client.py 의 first_text 를 그대로 다시 내보낸다.
"""

import json
from types import SimpleNamespace

import httpx
import pytest

import skillops_agent.tools as tools_module
from skillops_agent.contracts import ContractError
from skillops_agent.order_agent import OrderAgentError
from skillops_agent.tools import MCPToolError, MCPTools, get_tools

# 테스트에 쓰는 고정 snapshot 번호와 실행 번호다.
SNAPSHOT = "22222222-2222-4222-8222-222222222222"
EXECUTION = "33333333-3333-4333-8333-333333333333"


def text_block(text):
    """MCP text content 블록 대역을 만든다."""
    return SimpleNamespace(type="text", text=text)


def ok_result(text="{}"):
    """text 블록 하나를 가진 정상 CallToolResult 대역을 만든다."""
    return SimpleNamespace(is_error=False, content=[text_block(text)])


class RecordingClient:
    """call_tool 인자를 기록하고 정해 둔 결과를 돌려주는 MCP client 대역이다."""

    def __init__(self, result):
        self.calls = []
        self.result = result

    async def call_tool(self, name, args):
        self.calls.append((name, args))
        return self.result


def tools_with(result=None):
    """MCP 세션 대신 RecordingClient 를 끼운 MCPTools 를 만든다. async with 로 열지 않는다."""
    tools = MCPTools()
    tools._client = RecordingClient(result or ok_result())
    return tools


def sent(tools):
    """보낸 호출을 (tool 이름, 인자의 (키, 값) 목록) 으로 돌려준다. 키 순서까지 비교하려고 쓴다."""
    return [(name, list(args.items())) for name, args in tools._client.calls]


# ── _call: 정상 결과 해석 ───────────────────────────────────────────────────────


@pytest.mark.parametrize("empty", ["", None])
async def test_call_reads_first_non_empty_text_block(empty):
    """비어 있는 text 블록은 건너뛰고 처음 나오는 text 의 JSON 을 돌려준다."""
    result = SimpleNamespace(is_error=False, content=[text_block(empty), text_block('{"ok": 1}')])
    assert await tools_with(result)._call("diff_skill", {}) == {"ok": 1}


async def test_call_skips_blocks_without_text_attribute():
    """text 속성이 없는 블록은 건너뛴다."""
    content = [SimpleNamespace(type="image"), text_block("[1]")]
    result = SimpleNamespace(is_error=False, content=content)
    assert await tools_with(result)._call("run_scenarios", {}) == [1]


async def test_call_reads_only_the_first_text_block():
    """처음 나온 text 가 JSON 이 아니면 뒤 블록을 보지 않고 거부한다."""
    result = SimpleNamespace(is_error=False, content=[text_block("oops"), text_block("{}")])
    with pytest.raises(ContractError) as caught:
        await tools_with(result)._call("apply_patch", {})
    assert str(caught.value) == "MCP apply_patch returned invalid JSON"


async def test_call_returns_plain_text_only_for_ping():
    """ping 만 JSON 이 아닌 글을 그대로 돌려주고, 다른 도구는 ContractError 다."""
    result = ok_result("pong 2026-10-22T00:00:00+00:00")
    assert await tools_with(result)._call("ping", {}) == "pong 2026-10-22T00:00:00+00:00"
    with pytest.raises(ContractError) as caught:
        await tools_with(result)._call("diff_skill", {})
    assert type(caught.value) is ContractError
    assert str(caught.value) == "MCP diff_skill returned invalid JSON"


@pytest.mark.parametrize(
    "result",
    [
        SimpleNamespace(is_error=False, content=[]),
        SimpleNamespace(is_error=False, content=None),
        SimpleNamespace(is_error=False, content=[text_block(""), text_block(None)]),
        SimpleNamespace(is_error=False, content=[SimpleNamespace(type="image")]),
        SimpleNamespace(is_error=False),
    ],
    ids=["empty", "none", "blank-texts", "no-text-attr", "no-content-attr"],
)
async def test_call_without_text_is_rejected(result):
    """읽을 text 가 없으면 'no JSON content' ContractError 다."""
    with pytest.raises(ContractError) as caught:
        await tools_with(result)._call("get_dependency_graph", {})
    assert type(caught.value) is ContractError
    assert str(caught.value) == "MCP get_dependency_graph returned no JSON content"


async def test_ping_without_text_is_rejected():
    """ping 도 text 가 없으면 거부한다."""
    with pytest.raises(ContractError) as caught:
        await tools_with(SimpleNamespace(is_error=False, content=[]))._call("ping", {})
    assert str(caught.value) == "MCP ping returned no JSON content"


# ── _call: 오류 결과 해석 ───────────────────────────────────────────────────────


async def test_error_result_raises_mcp_tool_error_with_payload():
    """is_error 결과의 JSON 객체는 MCPToolError 의 code, payload, 메시지가 된다."""
    result = SimpleNamespace(is_error=True, content=[text_block('{"code": "INVALID_INPUT"}')])
    with pytest.raises(MCPToolError) as caught:
        await tools_with(result)._call("run_scenarios", {})
    error = caught.value
    assert isinstance(error, ContractError)
    assert error.tool == "run_scenarios"
    assert error.payload == {"code": "INVALID_INPUT"}
    assert error.code == "INVALID_INPUT"
    assert str(error) == 'MCP run_scenarios failed: {"code": "INVALID_INPUT"}'


async def test_error_message_keeps_korean_text():
    """오류 메시지의 payload JSON 은 한글을 이스케이프하지 않는다."""
    payload = {"code": "INVALID_INPUT", "message": "후보 충돌", "retryable": False}
    result = SimpleNamespace(is_error=True, content=[text_block(json.dumps(payload))])
    with pytest.raises(MCPToolError) as caught:
        await tools_with(result)._call("apply_patch", {})
    assert str(caught.value) == (
        'MCP apply_patch failed: {"code": "INVALID_INPUT", "message": "후보 충돌", '
        '"retryable": false}'
    )


async def test_error_payload_without_code_has_none_code():
    """payload 에 code 가 없으면 code 는 None 이다."""
    result = SimpleNamespace(is_error=True, content=[text_block('{"message": "x"}')])
    with pytest.raises(MCPToolError) as caught:
        await tools_with(result)._call("save_proposal", {})
    assert caught.value.code is None


async def test_camel_case_is_error_is_also_an_error():
    """isError(camelCase) 만 있어도 오류 결과로 본다."""
    result = SimpleNamespace(isError=True, content=[text_block('{"code": "X"}')])
    with pytest.raises(MCPToolError) as caught:
        await tools_with(result)._call("save_proposal", {})
    assert caught.value.code == "X"


async def test_error_result_uses_first_non_empty_text():
    """오류 결과도 비어 있는 text 와 text 없는 블록을 건너뛴다."""
    content = [text_block(""), SimpleNamespace(type="image"), text_block('{"code": "B"}')]
    with pytest.raises(MCPToolError) as caught:
        await tools_with(SimpleNamespace(is_error=True, content=content))._call("ping", {})
    assert caught.value.code == "B"


@pytest.mark.parametrize("text", ["[1, 2]", '"text"', "3", "null"])
async def test_error_result_must_be_a_json_object(text):
    """오류 본문이 JSON 객체가 아니면 MCPToolError 가 아닌 ContractError 다."""
    result = SimpleNamespace(is_error=True, content=[text_block(text)])
    with pytest.raises(ContractError) as caught:
        await tools_with(result)._call("run_scenarios", {})
    assert not isinstance(caught.value, MCPToolError)
    assert str(caught.value) == "MCP run_scenarios returned an invalid error object"


@pytest.mark.parametrize(
    "result",
    [
        SimpleNamespace(is_error=True, content=[]),
        SimpleNamespace(is_error=True, content=[text_block("")]),
        SimpleNamespace(is_error=True, content=[text_block("not JSON")]),
        SimpleNamespace(is_error=True, content=[SimpleNamespace(type="image")]),
        SimpleNamespace(is_error=True),
    ],
    ids=["empty", "blank", "not-json", "no-text-attr", "no-content-attr"],
)
async def test_unreadable_error_result_is_contract_error(result):
    """오류 본문을 읽을 수 없으면 'unreadable error' ContractError 다."""
    with pytest.raises(ContractError) as caught:
        await tools_with(result)._call("run_scenarios", {})
    assert not isinstance(caught.value, MCPToolError)
    assert str(caught.value) == "MCP run_scenarios returned an unreadable error"


# ── MCP 도구 인자 ──────────────────────────────────────────────────────────────


@pytest.mark.parametrize(
    ("method", "args", "expected"),
    [
        ("ping", (), ("ping", [])),
        (
            "diff_skill",
            ("payment", "before", "after"),
            (
                "diff_skill",
                [
                    ("skill_id", "payment"),
                    ("before_snapshot_id", "before"),
                    ("after_snapshot_id", "after"),
                ],
            ),
        ),
        (
            "get_dependency_graph",
            ("snap", "order-flow"),
            ("get_dependency_graph", [("snapshot_id", "snap"), ("entry_skill_id", "order-flow")]),
        ),
        (
            "apply_patch",
            ("w", [{"skill": "payment", "base_version": 1, "patch": "p"}]),
            (
                "apply_patch",
                [
                    ("workspace_id", "w"),
                    ("patches", [{"skill": "payment", "base_version": 1, "patch": "p"}]),
                ],
            ),
        ),
        (
            "save_proposal",
            ({"status": "proposed"},),
            ("save_proposal", [("proposal", {"status": "proposed"})]),
        ),
    ],
    ids=["ping", "diff_skill", "get_dependency_graph", "apply_patch", "save_proposal"],
)
async def test_mcp_tools_send_contract_names_and_argument_keys(method, args, expected):
    """도구 메서드는 C 계약의 tool 이름과 인자 키를 그 순서로 보낸다."""
    tools = tools_with()
    await getattr(tools, method)(*args)
    assert sent(tools) == [expected]


async def test_mcp_tool_methods_return_parsed_result_as_is():
    """도구 메서드는 _call 결과를 가공하지 않고 돌려준다."""
    tools = tools_with(ok_result('{"workspace_id": "w", "skill_versions": {"payment": 1}}'))
    assert await tools.create_workspace(snapshot_id="s") == {
        "workspace_id": "w",
        "skill_versions": {"payment": 1},
    }


async def test_run_scenarios_sends_only_required_arguments_by_default():
    """run_scenarios 는 repeat 기본 2 를 넣고, 비어 있는 선택 인자는 키를 뺀다."""
    tools = tools_with()
    await tools.run_scenarios("w", ["g-01"])
    await tools.run_scenarios("w", ["g-01"], 1, [], [])
    await tools.run_scenarios("w", ["g-01"], 3, None, None)
    assert sent(tools) == [
        ("run_scenarios", [("workspace_id", "w"), ("scenario_ids", ["g-01"]), ("repeat", 2)]),
        ("run_scenarios", [("workspace_id", "w"), ("scenario_ids", ["g-01"]), ("repeat", 1)]),
        ("run_scenarios", [("workspace_id", "w"), ("scenario_ids", ["g-01"]), ("repeat", 3)]),
    ]


async def test_run_scenarios_appends_optional_arguments_in_order():
    """값이 있는 replay_run_ids 와 extra_checks 는 그 순서로 뒤에 붙는다."""
    tools = tools_with()
    await tools.run_scenarios("w", ["g-01"], repeat=1, replay_run_ids=["r_1"], extra_checks=["a"])
    await tools.run_scenarios("w", ["g-01"], extra_checks=["a"])
    await tools.run_scenarios("w", ["g-01"], replay_run_ids=["r_1"])
    base = [("workspace_id", "w"), ("scenario_ids", ["g-01"])]
    both = [("replay_run_ids", ["r_1"]), ("extra_checks", ["a"])]
    assert sent(tools) == [
        ("run_scenarios", [*base, ("repeat", 1), *both]),
        ("run_scenarios", [*base, ("repeat", 2), ("extra_checks", ["a"])]),
        ("run_scenarios", [*base, ("repeat", 2), ("replay_run_ids", ["r_1"])]),
    ]


async def test_create_workspace_sends_only_truthy_optional_arguments():
    """create_workspace 는 참인 선택 인자만 start_versions, snapshot_id 순서로 보낸다."""
    tools = tools_with()
    await tools.create_workspace()
    await tools.create_workspace(start_versions={})
    await tools.create_workspace(start_versions={}, snapshot_id="")
    await tools.create_workspace(snapshot_id="s")
    await tools.create_workspace({"payment": 2}, "s")
    await tools.create_workspace(start_versions={"payment": 2})
    assert sent(tools) == [
        ("create_workspace", []),
        ("create_workspace", []),
        ("create_workspace", []),
        ("create_workspace", [("snapshot_id", "s")]),
        ("create_workspace", [("start_versions", {"payment": 2}), ("snapshot_id", "s")]),
        ("create_workspace", [("start_versions", {"payment": 2})]),
    ]


# ── 주문 Agent HTTP 조회 ────────────────────────────────────────────────────────


def order_http(*responses):
    """요청마다 responses 를 차례로 돌려주는 주문 Agent client 와 요청 경로 기록 목록을 만든다.

    responses 가 다 떨어지면 마지막 응답을 반복한다.
    """
    paths = []

    def respond(request):
        """요청 경로를 남기고 차례에 맞는 응답을 돌려준다."""
        paths.append(request.url.path)
        return responses[min(len(paths), len(responses)) - 1]

    client = httpx.AsyncClient(base_url="http://order", transport=httpx.MockTransport(respond))
    return client, paths


async def test_order_agent_reads_use_the_injected_http_client():
    """조회 다섯 개는 self._http 로 주문 Agent 경로를 부르고 JSON 을 그대로 돌려준다."""
    client, paths = order_http(httpx.Response(200, json={"items": []}))
    tools = MCPTools()
    async with client:
        tools._http = client
        assert await tools.get_snapshot(SNAPSHOT) == {"items": []}
        assert await tools.list_skills(SNAPSHOT) == {"items": []}
        assert await tools.get_skill(SNAPSHOT, "payment") == {"items": []}
        assert await tools.get_trace(EXECUTION) == {"items": []}
        assert await tools.list_failed_runs() == {"items": []}
    assert paths == [
        f"/api/snapshots/{SNAPSHOT}",
        f"/api/snapshots/{SNAPSHOT}/skills",
        f"/api/snapshots/{SNAPSHOT}/skills/payment",
        f"/api/executions/{EXECUTION}/trace",
        "/api/executions/failed",
    ]


@pytest.mark.parametrize("status", [404, 503])
async def test_get_snapshot_does_not_retry_and_raises_http_status_error(status):
    """get_snapshot 은 order_agent 를 거치지 않아 UUID 검사와 재시도 없이 HTTPStatusError 다."""
    client, paths = order_http(httpx.Response(status, json={"code": "SNAPSHOT_NOT_FOUND"}))
    tools = MCPTools()
    async with client:
        tools._http = client
        with pytest.raises(httpx.HTTPStatusError):
            await tools.get_snapshot("not-a-uuid")
    assert paths == ["/api/snapshots/not-a-uuid"]


async def test_get_skill_invalid_id_is_rejected_before_request():
    """get_skill 의 잘못된 skill_id 는 요청 없이 OrderAgentError 다."""
    client, paths = order_http(httpx.Response(200, json={}))
    tools = MCPTools()
    async with client:
        tools._http = client
        with pytest.raises(OrderAgentError) as caught:
            await tools.get_skill(SNAPSHOT, "Bad_ID")
    assert caught.value.code == "INVALID_INPUT"
    assert paths == []


async def test_get_trace_invalid_alias_is_contract_error_before_request():
    """get_trace 는 execution_uuid 로 먼저 바꾸므로 잘못된 별칭은 ContractError 다."""
    client, paths = order_http(httpx.Response(200, json={}))
    tools = MCPTools()
    async with client:
        tools._http = client
        with pytest.raises(ContractError):
            await tools.get_trace("r_bad")
    assert paths == []


# ── 연결 열기·닫기와 팩토리 ──────────────────────────────────────────────────────


async def test_aenter_opens_mcp_session_then_order_agent_client(monkeypatch):
    """__aenter__ 는 MCP_URL 세션을 연 뒤 httpx client 를 만든다.

    httpx client 는 ORDER_AGENT_URL 주소와 30초 timeout 을 쓴다.
    """
    events = []

    class Session:
        """MCP 세션 대역이다. 들어갈 때 기록을 남긴다."""

        async def __aenter__(self):
            events.append("mcp-enter")
            return "ignored"

    def open_session(url):
        """tools.mcp_client 대신 불려 받은 주소를 남기고 세션 대역을 돌려준다."""
        events.append(("mcp_client", url))
        return session

    session = Session()
    real_client = httpx.AsyncClient

    def make_http(**kwargs):
        """httpx.AsyncClient 대신 불려 받은 인자를 남기고 MockTransport client 를 만든다."""
        events.append(("http", kwargs))
        return real_client(transport=httpx.MockTransport(lambda r: httpx.Response(200)), **kwargs)

    monkeypatch.setattr(tools_module, "mcp_client", open_session)
    monkeypatch.setattr(tools_module.httpx, "AsyncClient", make_http)
    tools = MCPTools()
    assert await tools.__aenter__() is tools
    try:
        assert tools._client is session
        assert events == [
            ("mcp_client", tools_module.MCP_URL),
            "mcp-enter",
            ("http", {"base_url": tools_module.ORDER_AGENT_URL, "timeout": 30.0}),
        ]
    finally:
        await tools._http.aclose()


async def test_aexit_closes_mcp_session_without_application_error():
    """__aexit__ 는 앱 예외 정보와 상관없이 MCP 세션에 (None, None, None) 을 넘긴다."""
    events = []

    class Session:
        """MCP 세션 대역이다. 받은 종료 인자를 기록한다."""

        async def __aexit__(self, *args):
            events.append(("mcp", args))

    class Http:
        """httpx client 대역이다. 닫힌 것을 기록한다."""

        async def aclose(self):
            events.append(("http",))

    tools = MCPTools()
    tools._client, tools._http = Session(), Http()
    error = RuntimeError("app")
    assert await tools.__aexit__(RuntimeError, error, error.__traceback__) is None
    assert events == [("mcp", (None, None, None)), ("http",)]


async def test_aexit_closes_http_even_when_mcp_shutdown_fails():
    """MCP 세션 종료가 실패해도 httpx client 는 닫고 그 예외를 올린다."""
    events = []

    class Session:
        """종료하다 실패하는 MCP 세션 대역이다."""

        async def __aexit__(self, *args):
            raise RuntimeError("mcp shutdown")

    class Http:
        """httpx client 대역이다. 닫힌 것을 기록한다."""

        async def aclose(self):
            events.append("http")

    tools = MCPTools()
    tools._client, tools._http = Session(), Http()
    with pytest.raises(RuntimeError, match="mcp shutdown"):
        await tools.__aexit__(None, None, None)
    assert events == ["http"]


def test_get_tools_returns_unconnected_real_tools_when_stub_is_off(monkeypatch):
    """USE_STUB_MCP 가 거짓이면 연결하지 않은 MCPTools 를 돌려준다."""
    monkeypatch.setattr("skillops_agent.tools.USE_STUB_MCP", False)
    tools = get_tools()
    assert type(tools) is MCPTools
    assert not hasattr(tools, "_client")
    assert not hasattr(tools, "_http")


def test_get_tools_reads_the_flag_at_call_time(monkeypatch):
    """get_tools 는 부를 때마다 tools 모듈 전역 USE_STUB_MCP 를 읽는다."""
    monkeypatch.setattr("skillops_agent.tools.USE_STUB_MCP", True)
    first = get_tools()
    monkeypatch.setattr("skillops_agent.tools.USE_STUB_MCP", False)
    second = get_tools()
    assert type(first) is tools_module.StubMCPTools
    assert type(second) is MCPTools
    assert get_tools() is not second


def test_stub_tools_are_reexported_from_tools():
    """tools 모듈은 stub_tools.StubMCPTools 를 다시 내보낸다."""
    from skillops_agent.stub_tools import StubMCPTools

    assert tools_module.StubMCPTools is StubMCPTools


# ── mcp_client.first_text (리팩토링으로 새로 모은 공용 함수) ─────────────────────


@pytest.mark.parametrize(
    ("result", "expected"),
    [
        (ok_result('{"a": 1}'), '{"a": 1}'),
        (SimpleNamespace(content=[text_block(""), text_block(None), text_block("x")]), "x"),
        (SimpleNamespace(content=[SimpleNamespace(type="image"), text_block("raw")]), "raw"),
        (SimpleNamespace(content=[text_block("first"), text_block("second")]), "first"),
        (SimpleNamespace(content=[]), None),
        (SimpleNamespace(content=None), None),
        (SimpleNamespace(content=[text_block("")]), None),
        (SimpleNamespace(), None),
    ],
    ids=["json", "skip-empty", "skip-no-text", "first-wins", "empty", "none", "blank", "no-attr"],
)
def test_first_text_returns_first_non_empty_text_or_none(result, expected):
    """처음으로 비어 있지 않은 text 를 그대로 돌려주고, 없으면 None 이다."""
    from skillops_agent.mcp_client import first_text

    assert first_text(result) == expected


def test_first_text_is_the_helper_tools_use():
    """tools 는 mcp_client 의 first_text 를 그대로 쓴다."""
    from skillops_agent.mcp_client import first_text

    assert tools_module.first_text is first_text
