"""주문 Agent(order-agent/) 진단용 조회 API 호출.

API 응답은 가공하지 않고 그대로 돌려준다(order-agent/docs/diagnosis-read-api.md: 이벤트 원문 보존).
조회가 실패하면 빈 결과로 바꾸지 않고 OrderAgentError 를 던진다. 칸은 C-1 MCP 도구 오류와 같다.

사용 예:
    trace = await get_trace(event["execution_id"])
    runs = await list_failed_runs()
    skills = await list_skills(trace["snapshot_id"])
    skill = await get_skill(trace["snapshot_id"], "payment")
"""

import asyncio
import re
import uuid
from typing import Any

import httpx

from .config import ORDER_AGENT_BASE_URL

TIMEOUT_SECONDS = 5.0
MAX_ATTEMPTS = 3
RETRY_BACKOFF_SECONDS = 0.3
SKILL_ID = re.compile(r"[a-z][a-z0-9]*(?:-[a-z0-9]+)*")


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
        snapshot_id: str | None = None,
    ) -> None:
        super().__init__(message)
        self.code = code
        self.retryable = retryable
        self.upstream_status = upstream_status
        self.execution_id = execution_id
        self.snapshot_id = snapshot_id


async def get_trace(execution_id: str, *, client: httpx.AsyncClient | None = None) -> dict:
    """실행 한 건의 기록. GET /api/executions/{execution_id}/trace 응답을 그대로 돌려준다.

    실패한 실행을 조회한 것도 정상 결과다. 실패 skill 은 events 의 EXECUTION_ERROR,
    skill 별 받은 값·내놓은 값·걸린 시간은 SKILL_STARTED·SKILL_FINISHED 에 있다.
    """
    _require_uuid(execution_id, "execution_id", execution_id=execution_id)
    return await _get(f"/api/executions/{execution_id}/trace", client, execution_id=execution_id)


async def list_failed_runs(*, client: httpx.AsyncClient | None = None) -> dict:
    """실제 운영 실행의 실행 실패 전체 목록. GET /api/executions/failed 응답을 그대로 돌려준다.

    {"items": [...]} 이고 최신 실패가 앞에 온다. 실패가 없으면 items 가 빈 목록이다.
    업무 거절(REJECTED·HOLD)과 모의 실행은 들어 있지 않다. 상세 기록이 없는 과거 실행은
    error 대신 error_category 만 있다.
    """
    return await _get("/api/executions/failed", client)


async def list_skills(snapshot_id: str, *, client: httpx.AsyncClient | None = None) -> dict:
    """snapshot 안의 skill 목록. GET /api/snapshots/{snapshot_id}/skills 응답을 그대로 돌려준다.

    items 는 skill_id 순이다. 형식이 잘못된 skill 은 items 에서 빠지고 parse_errors 에 남으며,
    그때 partial 이 true 다. partial 이면 완전한 목록으로 보지 않는다.
    """
    _require_uuid(snapshot_id, "snapshot_id", snapshot_id=snapshot_id)
    return await _get(f"/api/snapshots/{snapshot_id}/skills", client, snapshot_id=snapshot_id)


async def get_skill(
    snapshot_id: str, skill_id: str, *, client: httpx.AsyncClient | None = None
) -> dict:
    """snapshot 에 고정된 skill 하나. GET /api/snapshots/{snapshot_id}/skills/{skill_id} 응답을
    그대로 돌려준다. content 는 frontmatter 를 포함한 SKILL.md 원문 전체다.

    같은 폴더의 다른 파일(resources/policy.md 등)은 이 함수가 아니라 파일 조회 API 로 읽는다.
    """
    _require_uuid(snapshot_id, "snapshot_id", snapshot_id=snapshot_id)
    # skill_id 는 주소에 들어가므로 보내기 전에 형식을 확인한다.
    # 기준은 주문 Agent SkillLoader 와 같다.
    if not isinstance(skill_id, str) or len(skill_id) > 64 or not SKILL_ID.fullmatch(skill_id):
        raise OrderAgentError(
            "INVALID_INPUT", f"skill_id 형식이 잘못됐습니다: {skill_id}",
            retryable=False, snapshot_id=snapshot_id,
        )
    return await _get(
        f"/api/snapshots/{snapshot_id}/skills/{skill_id}", client, snapshot_id=snapshot_id,
    )


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
