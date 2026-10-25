"""skillops-agent/skillops_agent/order_agent.py 의 현재 동작을 고정하는 특성 테스트다.

httpx.MockTransport 로 주문 Agent 를 흉내 내므로 실제 주문 Agent 를 부르지 않는다. 보호하는 동작은
다음과 같다.
- 보내기 전에 UUID 와 skill_id(형식과 64자 한도)를 검사해 INVALID_INPUT 으로 거절한다.
- 조회 네 개가 계약 경로를 부르고 응답 원문을 가공 없이 돌려주며, client 를 주지 않으면
  ORDER_AGENT_BASE_URL 과 TIMEOUT_SECONDS 로 만든 client 를 쓰고 닫는다.
- 5xx, 429, 연결 오류만 MAX_ATTEMPTS 번까지 선형으로 늘어나는 대기 뒤에 다시 시도한다.
- 실패 응답은 본문의 code 와 message 를 먼저 쓰고, 없으면 상태 기반 기본값으로 OrderAgentError 를
  만든다.

재시도 대기는 sleeps fixture 가 실제로 기다리지 않게 바꾼다.
"""

from types import SimpleNamespace

import httpx
import pytest

from skillops_agent import order_agent
from skillops_agent.order_agent import OrderAgentError

# 테스트에 쓰는 고정 실행 번호와 snapshot 번호다.
EXECUTION = "33333333-3333-4333-8333-333333333333"
SNAPSHOT = "22222222-2222-4222-8222-222222222222"


async def test_snapshot_file_uses_encoded_query_and_injected_client(sleeps):
    """특수 문자가 있는 파일 경로를 params로 보내고 503만 같은 요청으로 재시도한다."""
    requests = []
    path = "skills/payment/resources/a & b#한글.md"

    def handle(request):
        """원래 경로가 query에서 복원되는지 확인하고 첫 요청만 일시 실패시킨다."""
        requests.append(request)
        assert request.url.params["path"] == path
        return httpx.Response(503 if len(requests) == 1 else 200, json={"content": "original"})

    async with httpx.AsyncClient(
        base_url="http://injected", transport=httpx.MockTransport(handle)
    ) as client:
        assert await order_agent.get_snapshot_file(SNAPSHOT, path, client=client) == {
            "content": "original"
        }
    assert len(requests) == 2
    assert all(r.url.host == "injected" and r.url.path == f"/api/snapshots/{SNAPSHOT}/files"
               for r in requests)
    assert sleeps == [order_agent.RETRY_BACKOFF_SECONDS]


@pytest.mark.parametrize("path", [None, "", "../SKILL.md", "skills/../SKILL.md",
                                  "skills//SKILL.md", "skills/a\\b", "skills/a\x00b"])
async def test_snapshot_file_rejects_invalid_path_before_request(path):
    """manifest로 사용할 수 없는 파일 경로는 요청 전에 거부한다."""
    client, paths = order_client(httpx.Response(200, json={}))
    async with client:
        with pytest.raises(OrderAgentError) as caught:
            await order_agent.get_snapshot_file(SNAPSHOT, path, client=client)
    assert caught.value.code == "INVALID_INPUT"
    assert paths == []


async def test_snapshot_file_rejects_invalid_snapshot_before_request():
    """원문 조회에서도 snapshot UUID를 검사한다."""
    client, paths = order_client(httpx.Response(200, json={}))
    async with client:
        with pytest.raises(OrderAgentError):
            await order_agent.get_snapshot_file("bad", "skills/payment/SKILL.md", client=client)
    assert paths == []


async def test_snapshot_file_invalid_json_is_a_read_error():
    """깨진 JSON을 조회 오류로 바꿔 이미 수집한 다른 파일 증거를 잃지 않게 한다."""
    client, paths = order_client(httpx.Response(200, content=b"not JSON"))
    async with client:
        with pytest.raises(OrderAgentError) as caught:
            await order_agent.get_snapshot_file(SNAPSHOT, "skills/payment/SKILL.md", client=client)
    assert caught.value.code == "ORDER_AGENT_INVALID_RESPONSE"
    assert caught.value.retryable is False
    assert len(paths) == 1


def order_client(*actions):
    """요청마다 actions 를 차례로 쓰는 MockTransport client 와 요청 경로 기록 목록을 돌려준다.

    actions 가 다 떨어지면 마지막 것을 반복한다. 항목이 예외면 그 예외를 던지고, 응답이면 그대로
    돌려준다.
    """
    paths = []

    def handle(request):
        """요청 경로를 기록하고 차례에 맞는 응답을 주거나 예외를 던진다."""
        paths.append(request.url.path)
        action = actions[min(len(paths), len(actions)) - 1]
        if isinstance(action, Exception):
            raise action
        return action

    return httpx.AsyncClient(base_url="http://order", transport=httpx.MockTransport(handle)), paths


def fields(error):
    """OrderAgentError 의 칸을 비교하기 쉬운 dict 로 바꾼다."""
    return {
        "code": error.code,
        "message": str(error),
        "retryable": error.retryable,
        "upstream_status": error.upstream_status,
        "execution_id": error.execution_id,
        "snapshot_id": error.snapshot_id,
    }


@pytest.fixture
def sleeps(monkeypatch):
    """재시도 대기를 실제로 기다리지 않고 대기 시간만 기록한다."""
    recorded = []

    async def fake_sleep(seconds):
        """기다리지 않고 대기 시간만 남긴다."""
        recorded.append(seconds)

    monkeypatch.setattr(order_agent, "asyncio", SimpleNamespace(sleep=fake_sleep))
    return recorded


def backoff_schedule(attempts):
    """attempts 번 시도할 때까지 기다리는 시간 목록을 돌려준다.

    대기 시간은 RETRY_BACKOFF_SECONDS 의 1배, 2배처럼 선형으로 늘어난다.
    """
    return [order_agent.RETRY_BACKOFF_SECONDS * n for n in range(1, attempts)]


# ── 보내기 전 입력 검사 ─────────────────────────────────────────────────────────


@pytest.mark.parametrize("value", ["not-a-uuid", "", None, 123])
async def test_get_trace_rejects_non_uuid_without_request(value):
    """execution_id 가 UUID 가 아니면 요청 없이 INVALID_INPUT 이다."""
    client, paths = order_client(httpx.Response(200, json={}))
    async with client:
        with pytest.raises(OrderAgentError) as caught:
            await order_agent.get_trace(value, client=client)
    assert fields(caught.value) == {
        "code": "INVALID_INPUT",
        "message": f"execution_id 는 UUID 여야 합니다: {value}",
        "retryable": False,
        "upstream_status": None,
        "execution_id": value,
        "snapshot_id": None,
    }
    assert paths == []


@pytest.mark.parametrize("call", ["list_skills", "get_skill"])
async def test_snapshot_reads_reject_non_uuid_snapshot_first(call):
    """snapshot_id 가 UUID 가 아니면 skill_id 보다 먼저 거절한다."""
    client, paths = order_client(httpx.Response(200, json={}))
    args = ("bad",) if call == "list_skills" else ("bad", "Bad_ID")
    async with client:
        with pytest.raises(OrderAgentError) as caught:
            await getattr(order_agent, call)(*args, client=client)
    assert fields(caught.value) == {
        "code": "INVALID_INPUT",
        "message": "snapshot_id 는 UUID 여야 합니다: bad",
        "retryable": False,
        "upstream_status": None,
        "execution_id": None,
        "snapshot_id": "bad",
    }
    assert paths == []


@pytest.mark.parametrize(
    "skill_id", ["Bad_ID", "a" * 65, 123, None, "", "-payment", "payment-", "pay--ment", "1abc"]
)
async def test_get_skill_rejects_malformed_skill_id_without_request(skill_id):
    """skill_id 가 SKILL_ID 형식이 아니거나 64자를 넘으면 요청 없이 INVALID_INPUT 이다."""
    client, paths = order_client(httpx.Response(200, json={}))
    async with client:
        with pytest.raises(OrderAgentError) as caught:
            await order_agent.get_skill(SNAPSHOT, skill_id, client=client)
    assert fields(caught.value) == {
        "code": "INVALID_INPUT",
        "message": f"skill_id 형식이 잘못됐습니다: {skill_id}",
        "retryable": False,
        "upstream_status": None,
        "execution_id": None,
        "snapshot_id": SNAPSHOT,
    }
    assert paths == []


@pytest.mark.parametrize("skill_id", ["a" * 64, "payment", "inventory-check", "a1-b2-c3"])
async def test_get_skill_sends_well_formed_skill_id(skill_id):
    """64자 이하이고 형식이 맞으면 그대로 요청한다."""
    client, paths = order_client(httpx.Response(200, json={"skill_id": skill_id}))
    async with client:
        assert await order_agent.get_skill(SNAPSHOT, skill_id, client=client) == {
            "skill_id": skill_id
        }
    assert paths == [f"/api/snapshots/{SNAPSHOT}/skills/{skill_id}"]


# ── 경로와 응답 원문 ────────────────────────────────────────────────────────────


async def test_reads_call_contract_paths_and_return_body_as_is():
    """조회 네 개는 정해진 경로를 부르고 JSON 본문을 가공 없이 돌려준다."""
    body = {"items": [{"kind": "SKILL_FINISHED", "data": {"output": {"한글": "값"}}}], "x": None}
    client, paths = order_client(httpx.Response(200, json=body))
    async with client:
        assert await order_agent.get_trace(EXECUTION, client=client) == body
        assert await order_agent.list_failed_runs(client=client) == body
        assert await order_agent.list_skills(SNAPSHOT, client=client) == body
        assert await order_agent.get_skill(SNAPSHOT, "payment", client=client) == body
    assert paths == [
        f"/api/executions/{EXECUTION}/trace",
        "/api/executions/failed",
        f"/api/snapshots/{SNAPSHOT}/skills",
        f"/api/snapshots/{SNAPSHOT}/skills/payment",
    ]


async def test_without_client_uses_own_client_with_base_url_and_timeout(monkeypatch):
    """client 가 없으면 ORDER_AGENT_BASE_URL 과 TIMEOUT_SECONDS 로 만든 client 를 쓰고 닫는다."""
    created = []
    urls = []
    real_client = httpx.AsyncClient

    def respond(request):
        """요청 URL 을 남기고 빈 목록을 돌려준다."""
        urls.append(str(request.url))
        return httpx.Response(200, json={"items": []})

    def make_client(**kwargs):
        """httpx.AsyncClient 대신 불려 받은 인자를 남기고 MockTransport client 를 만든다."""
        created.append((kwargs, real_client(transport=httpx.MockTransport(respond), **kwargs)))
        return created[-1][1]

    monkeypatch.setattr(httpx, "AsyncClient", make_client)
    assert await order_agent.list_failed_runs() == {"items": []}
    assert [kwargs for kwargs, _ in created] == [
        {"base_url": order_agent.ORDER_AGENT_BASE_URL, "timeout": order_agent.TIMEOUT_SECONDS}
    ]
    assert created[0][1].is_closed
    assert urls == [order_agent.ORDER_AGENT_BASE_URL.rstrip("/") + "/api/executions/failed"]


# ── HTTP 오류와 재시도 ──────────────────────────────────────────────────────────


async def test_error_body_code_and_message_are_kept_without_retry(sleeps):
    """4xx 본문의 code 와 message 를 그대로 쓰고 한 번만 요청한다."""
    client, paths = order_client(
        httpx.Response(404, json={"code": "EXECUTION_NOT_FOUND", "message": "없음"})
    )
    async with client:
        with pytest.raises(OrderAgentError) as caught:
            await order_agent.get_trace(EXECUTION, client=client)
    assert fields(caught.value) == {
        "code": "EXECUTION_NOT_FOUND",
        "message": "없음",
        "retryable": False,
        "upstream_status": 404,
        "execution_id": EXECUTION,
        "snapshot_id": None,
    }
    assert paths == [f"/api/executions/{EXECUTION}/trace"]
    assert sleeps == []


async def test_non_json_5xx_is_retried_up_to_max_attempts(sleeps):
    """JSON 이 아닌 503 은 MAX_ATTEMPTS 번 시도한 뒤 기본 code 와 message 로 실패한다."""
    client, paths = order_client(httpx.Response(503, text="down"))
    async with client:
        with pytest.raises(OrderAgentError) as caught:
            await order_agent.list_skills(SNAPSHOT, client=client)
    assert fields(caught.value) == {
        "code": "ORDER_AGENT_HTTP_503",
        "message": "주문 Agent 가 503 를 돌려주었습니다.",
        "retryable": True,
        "upstream_status": 503,
        "execution_id": None,
        "snapshot_id": SNAPSHOT,
    }
    assert len(paths) == order_agent.MAX_ATTEMPTS
    assert sleeps == backoff_schedule(order_agent.MAX_ATTEMPTS)


@pytest.mark.parametrize("status", [429, 500, 502, 503, 504, 599])
async def test_5xx_and_429_are_retryable(sleeps, status):
    """5xx 와 429 는 다시 시도하고 마지막 응답의 본문 code 를 쓴다."""
    body = {"code": "BUSY", "message": "잠시 뒤"}
    client, paths = order_client(httpx.Response(status, json=body))
    async with client:
        with pytest.raises(OrderAgentError) as caught:
            await order_agent.list_failed_runs(client=client)
    assert fields(caught.value) == {
        "code": "BUSY",
        "message": "잠시 뒤",
        "retryable": True,
        "upstream_status": status,
        "execution_id": None,
        "snapshot_id": None,
    }
    assert len(paths) == order_agent.MAX_ATTEMPTS
    assert sleeps == backoff_schedule(order_agent.MAX_ATTEMPTS)


@pytest.mark.parametrize("status", [302, 400, 401, 404, 409, 422, 428, 430, 499])
async def test_other_statuses_are_not_retried(sleeps, status):
    """5xx 나 429 가 아닌 실패 상태는 다시 시도하지 않는다."""
    client, paths = order_client(httpx.Response(status, text="no"))
    async with client:
        with pytest.raises(OrderAgentError) as caught:
            await order_agent.list_failed_runs(client=client)
    assert fields(caught.value) == {
        "code": f"ORDER_AGENT_HTTP_{status}",
        "message": f"주문 Agent 가 {status} 를 돌려주었습니다.",
        "retryable": False,
        "upstream_status": status,
        "execution_id": None,
        "snapshot_id": None,
    }
    assert len(paths) == 1
    assert sleeps == []


async def test_retry_succeeds_when_a_later_attempt_succeeds(sleeps):
    """500 다음 200 이면 두 번째 응답의 JSON 을 돌려준다."""
    client, paths = order_client(httpx.Response(500), httpx.Response(200, json={"ok": True}))
    async with client:
        assert await order_agent.get_trace(EXECUTION, client=client) == {"ok": True}
    assert len(paths) == 2
    assert sleeps == backoff_schedule(2)


@pytest.mark.parametrize(
    "error", [httpx.ConnectError("boom"), httpx.ReadTimeout("boom")], ids=["connect", "timeout"]
)
async def test_transport_errors_are_retried_then_unavailable(sleeps, error):
    """연결 실패와 timeout 은 다시 시도한 뒤 ORDER_AGENT_UNAVAILABLE 이다."""
    client, paths = order_client(error)
    async with client:
        with pytest.raises(OrderAgentError) as caught:
            await order_agent.get_skill(SNAPSHOT, "payment", client=client)
    assert fields(caught.value) == {
        "code": "ORDER_AGENT_UNAVAILABLE",
        "message": "주문 Agent 에 연결하지 못했습니다: boom",
        "retryable": True,
        "upstream_status": None,
        "execution_id": None,
        "snapshot_id": SNAPSHOT,
    }
    assert len(paths) == order_agent.MAX_ATTEMPTS
    assert sleeps == backoff_schedule(order_agent.MAX_ATTEMPTS)


async def test_transport_error_then_success(sleeps):
    """연결 실패 뒤 성공하면 결과를 돌려준다."""
    client, paths = order_client(httpx.ConnectError("boom"), httpx.Response(200, json=[1]))
    async with client:
        assert await order_agent.list_failed_runs(client=client) == [1]
    assert len(paths) == 2


async def test_other_exceptions_are_not_retried(sleeps):
    """TransportError 가 아닌 예외는 바로 올린다."""
    client, paths = order_client(RuntimeError("bug"))
    async with client:
        with pytest.raises(RuntimeError, match="bug"):
            await order_agent.list_failed_runs(client=client)
    assert len(paths) == 1
    assert sleeps == []


# 상태 400 에 본문 message 가 없을 때 쓰는 기본 오류 문구다.
DEFAULT_400 = "주문 Agent 가 400 를 돌려주었습니다."


@pytest.mark.parametrize(
    ("body", "code", "message"),
    [
        ({"code": "", "message": ""}, "ORDER_AGENT_HTTP_400", DEFAULT_400),
        ({"code": None}, "ORDER_AGENT_HTTP_400", DEFAULT_400),
        ({"message": "본문 문구"}, "ORDER_AGENT_HTTP_400", "본문 문구"),
        ({"code": "BAD"}, "BAD", DEFAULT_400),
        (["not", "object"], "ORDER_AGENT_HTTP_400", DEFAULT_400),
    ],
    ids=["empty", "null-code", "message-only", "code-only", "list-body"],
)
async def test_missing_body_fields_fall_back_to_status_defaults(sleeps, body, code, message):
    """본문의 code 나 message 가 비었거나 없으면 상태 기반 기본값을 쓴다."""
    client, _ = order_client(httpx.Response(400, json=body))
    async with client:
        with pytest.raises(OrderAgentError) as caught:
            await order_agent.list_failed_runs(client=client)
    assert (caught.value.code, str(caught.value)) == (code, message)


def test_error_defaults_for_optional_fields():
    """OrderAgentError 의 선택 칸 기본값은 None 이다."""
    error = OrderAgentError("X", "m", retryable=True)
    assert fields(error) == {
        "code": "X",
        "message": "m",
        "retryable": True,
        "upstream_status": None,
        "execution_id": None,
        "snapshot_id": None,
    }
