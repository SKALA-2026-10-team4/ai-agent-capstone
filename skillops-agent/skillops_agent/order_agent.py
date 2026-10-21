"""주문 Agent(order-agent/) 진단용 조회 API 호출.

API 응답은 가공하지 않고 그대로 돌려준다(order-agent/docs/diagnosis-read-api.md: 이벤트 원문 보존).
조회가 실패하면 빈 결과로 바꾸지 않고 OrderAgentError 를 던진다. 칸은 C-1 MCP 도구 오류와 같다.

사용 예:
    trace = await get_trace(event["execution_id"])
"""

import asyncio
import uuid
from typing import Any

import httpx

from .config import ORDER_AGENT_BASE_URL

TIMEOUT_SECONDS = 5.0
MAX_ATTEMPTS = 3
RETRY_BACKOFF_SECONDS = 0.3


class OrderAgentError(Exception):
    """주문 Agent 조회 실패.

    code 는 주문 Agent 오류 코드(EXECUTION_NOT_FOUND 등)를 그대로 쓰고, 닿지 못했으면
    ORDER_AGENT_UNAVAILABLE, 보내기 전에 거절했으면 INVALID_INPUT 이다.
    retryable 이 True 면 잠시 뒤 다시 불러도 된다(연결 실패·timeout·5xx·429).
    upstream_status 는 주문 Agent 의 HTTP 상태이고, 닿지 못했거나 보내기 전이면 None 이다.
    """

    def __init__(
        self,
        code: str,
        message: str,
        *,
        retryable: bool,
        upstream_status: int | None = None,
        execution_id: str | None = None,
    ) -> None:
        super().__init__(message)
        self.code = code
        self.retryable = retryable
        self.upstream_status = upstream_status
        self.execution_id = execution_id


async def get_trace(execution_id: str, *, client: httpx.AsyncClient | None = None) -> dict:
    """실행 한 건의 기록. GET /api/executions/{execution_id}/trace 응답을 그대로 돌려준다.

    실패한 실행을 조회한 것도 정상 결과다. 실패 skill 은 events 의 EXECUTION_ERROR,
    skill 별 받은 값·내놓은 값·걸린 시간은 SKILL_STARTED·SKILL_FINISHED 에 있다.
    """
    _require_uuid(execution_id, "execution_id", execution_id=execution_id)
    return await _get(f"/api/executions/{execution_id}/trace", client, execution_id=execution_id)


async def _get(path: str, client: httpx.AsyncClient | None, **ref: str) -> Any:
    if client is None:
        async with httpx.AsyncClient(base_url=ORDER_AGENT_BASE_URL, timeout=TIMEOUT_SECONDS) as own:
            return await _get_with_retry(own, path, ref)
    return await _get_with_retry(client, path, ref)


async def _get_with_retry(client: httpx.AsyncClient, path: str, ref: dict) -> Any:
    # 명세 8장: GET 의 연결 오류·429·일시 5xx 만 제한해서 다시 시도한다.
    for attempt in range(1, MAX_ATTEMPTS + 1):
        try:
            response = await client.get(path)
        except httpx.TransportError as e:
            error = OrderAgentError(
                "ORDER_AGENT_UNAVAILABLE", f"주문 Agent 에 연결하지 못했습니다: {e}",
                retryable=True, **ref,
            )
        else:
            if response.is_success:
                return response.json()
            error = _from_response(response, ref)
        if not error.retryable or attempt == MAX_ATTEMPTS:
            raise error
        await asyncio.sleep(RETRY_BACKOFF_SECONDS * attempt)
    raise AssertionError("unreachable")


def _from_response(response: httpx.Response, ref: dict) -> OrderAgentError:
    status = response.status_code
    code = f"ORDER_AGENT_HTTP_{status}"
    message = f"주문 Agent 가 {status} 를 돌려주었습니다."
    try:
        body = response.json()
    except ValueError:
        body = None
    if isinstance(body, dict):
        code = body.get("code") or code
        message = body.get("message") or message
    return OrderAgentError(
        code, message, retryable=status >= 500 or status == 429, upstream_status=status, **ref,
    )


def _require_uuid(value: str, name: str, **ref: str) -> None:
    try:
        uuid.UUID(value)
    except (ValueError, TypeError, AttributeError) as e:
        raise OrderAgentError(
            "INVALID_INPUT", f"{name} 는 UUID 여야 합니다: {value}", retryable=False, **ref,
        ) from e
