"""주문 Agent(order-agent/)의 진단용 조회 API 를 부른다.

실행 기록, 실패 목록, snapshot 의 skill 목록과 본문을 읽는다. 상대편은 모두
order-agent/backend/src/main/java/com/capstone4/orderagent/controller/EvidenceController.java 의
GET 경로다. 응답은 가공하지 않고 그대로 돌려준다. order-agent/docs/diagnosis-read-api.md 가 이벤트
원문 보존을 요구하기 때문이다. 조회가 실패하면 빈 결과로 바꾸지 않고 OrderAgentError 를 던진다. 이
예외의 code, message, retryable, upstream_status, snapshot_id 칸은
service/src/main/java/com/capstone4/skillops/mcp/ToolResults.java 가 만드는 C-1 MCP 도구 오류와
같고, 어느 실행을 조회했는지 담는 execution_id 칸이 더 있다.

skillops-agent/skillops_agent/tools.py 의 MCPTools 가 조회 네 개를 자기 httpx client 와 함께 부른다.
재시도 규칙은 MAX_ATTEMPTS, RETRY_BACKOFF_SECONDS, _is_retryable_status 에서 바꾼다.

사용 예는 다음과 같다.
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

# client 를 주지 않아 이 모듈이 직접 만드는 client 의 timeout(초)이다. 운영 경로의 MCPTools 는 자기
# client(skillops-agent/skillops_agent/tools.py 의 ORDER_AGENT_TIMEOUT_SECONDS)를 넘기므로 이 값을
# 쓰지 않는다.
TIMEOUT_SECONDS = 5.0
# 한 조회의 최대 시도 횟수다. 첫 시도를 포함한다.
MAX_ATTEMPTS = 3
# 재시도 대기의 단위(초)다. n 번째 실패 뒤에는 RETRY_BACKOFF_SECONDS * n 초를 기다린다.
RETRY_BACKOFF_SECONDS = 0.3
# skill_id 형식과 길이 한도다.
# order-agent/backend/src/main/java/com/capstone4/orderagent/harness/SkillLoader.java 의
# validateName() 과 같은 규칙을 쓴다.
SKILL_ID = re.compile(r"[a-z][a-z0-9]*(?:-[a-z0-9]+)*")
SKILL_ID_MAX_LENGTH = 64


class OrderAgentError(Exception):
    """주문 Agent 조회가 실패했음을 알리는 예외다.

    code 는 주문 Agent 가 응답 본문에 담은 오류 코드(EXECUTION_NOT_FOUND 등)를 그대로 쓰고, 본문에
    없으면 ORDER_AGENT_HTTP_<상태>, 닿지 못했으면 ORDER_AGENT_UNAVAILABLE, 보내기 전에 거절했으면
    INVALID_INPUT 이다. retryable 이 True 면 잠시 뒤 다시 불러도 된다. 연결 실패, timeout, 5xx,
    429 가 여기에 든다. upstream_status 는 주문 Agent 의 HTTP 상태이고, 닿지 못했거나 보내기
    전이면 None 이다. execution_id 와 snapshot_id 는 어느 조회가 실패했는지 알리려고 요청 인자를
    그대로 담는다. skillops-agent/skillops_agent/nodes.py 의 노드는 이 예외를 따로 잡지 않으므로
    skillops-agent/skillops_agent/graph.py 의 guarded 가 노드 ERROR 로 남긴다.
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
        """message 를 예외 문구로 쓰고 나머지 값은 같은 이름의 속성으로 남긴다.

        retryable 은 기본값이 없는 키워드 전용 인자라서 부르는 쪽이 늘 밝혀야 한다.
        """
        super().__init__(message)
        self.code = code
        self.retryable = retryable
        self.upstream_status = upstream_status
        self.execution_id = execution_id
        self.snapshot_id = snapshot_id


async def get_trace(execution_id: str, *, client: httpx.AsyncClient | None = None) -> dict:
    """실행 한 건의 기록을 GET /api/executions/{execution_id}/trace 로 읽어 그대로 돌려준다.

    order-agent/backend/src/main/java/com/capstone4/orderagent/controller/EvidenceController.java 의
    trace() 가 이 경로를 받는다. 실패한 실행을 조회한 것도 정상 결과다. 실패 skill 은 events 의
    EXECUTION_ERROR 에 있고, skill 별 받은 값과 내놓은 값과 걸린 시간은 SKILL_STARTED 와
    SKILL_FINISHED 에 있다. execution_id 는 UUID 여야 하고 r_ 별칭은 받지 않으므로, 부르는 쪽인
    skillops-agent/skillops_agent/tools.py 의 MCPTools.get_trace 가 먼저 하이픈 UUID 로 바꾼다.
    """
    _require_uuid("execution_id", execution_id)
    return await _get(f"/api/executions/{execution_id}/trace", client, execution_id=execution_id)


async def list_failed_runs(*, client: httpx.AsyncClient | None = None) -> dict:
    """실제 운영 실행의 실행 실패 전체 목록을 GET /api/executions/failed 로 읽어 그대로 돌려준다.

    order-agent/backend/src/main/java/com/capstone4/orderagent/controller/EvidenceController.java 의
    failed() 가 이 경로를 받는다. 응답은 {"items": [...]} 이고 최신 실패가 앞에 온다. 실패가 없으면
    items 가 빈 목록이다. 업무 거절(REJECTED, HOLD)과 모의 실행은 들어 있지 않다. 상세 기록이 없는
    과거 실행은 error 대신 error_category 만 있다. skillops-agent/skillops_agent/tools.py 의
    MCPTools.list_failed_runs 가 이 함수를 부르지만, skillops-agent/skillops_agent/nodes.py 는 그
    메서드를 쓰지 않는다.
    """
    return await _get("/api/executions/failed", client)


async def list_skills(snapshot_id: str, *, client: httpx.AsyncClient | None = None) -> dict:
    """snapshot 안의 skill 목록을 GET /api/snapshots/{snapshot_id}/skills 로 읽어 그대로 돌려준다.

    order-agent/backend/src/main/java/com/capstone4/orderagent/controller/EvidenceController.java 의
    skills() 가 이 경로를 받는다. items 는 skill_id 순이다. 형식이 잘못된 skill 은 items 에서 빠지고
    parse_errors 에 남으며, 그때 partial 이 true 다. partial 이면 완전한 목록으로 보지 않는다.
    skillops-agent/skillops_agent/nodes.py 의 fetch_evidence 가 MCPTools.list_skills 를 거쳐 부른다.
    """
    _require_uuid("snapshot_id", snapshot_id)
    return await _get(f"/api/snapshots/{snapshot_id}/skills", client, snapshot_id=snapshot_id)


async def get_skill(
    snapshot_id: str, skill_id: str, *, client: httpx.AsyncClient | None = None
) -> dict:
    """snapshot 에 고정된 skill 하나를 GET /api/snapshots/{snapshot_id}/skills/{skill_id} 로 읽는다.

    order-agent/backend/src/main/java/com/capstone4/orderagent/controller/EvidenceController.java 의
    skill() 이 이 경로를 받고, 응답은 그대로 돌려준다. content 는 frontmatter 를 포함한 SKILL.md
    원문 전체다. 같은 폴더의 다른 파일(resources/policy.md 등)은 이 함수가 아니라 파일 조회 API 로
    읽는다. skillops-agent/skillops_agent/nodes.py 의 _repair_inputs 가 MCPTools.get_skill 을
    거쳐 부른다.
    """
    _require_uuid("snapshot_id", snapshot_id)
    # skill_id 는 주소에 들어가므로 보내기 전에 형식을 확인한다. 기준은 주문 Agent 와 같은 규칙인
    # SKILL_ID 와 SKILL_ID_MAX_LENGTH 다.
    _require_skill_id(skill_id, snapshot_id)
    return await _get(
        f"/api/snapshots/{snapshot_id}/skills/{skill_id}", client, snapshot_id=snapshot_id,
    )


async def list_recent_runs(*, client: httpx.AsyncClient | None = None) -> dict:
    """최근 실행 요약 목록을 GET /api/executions 로 읽어 그대로 돌려준다.

    order-agent/backend/src/main/java/com/capstone4/orderagent/controller/EvidenceController.java 의
    recent() 가 이 경로를 받는다. 응답은 {"items": [...]} 이고 최신 실행이 앞에 오며 최대 20건이다.
    성공한 실행과 모의 실행도 들어 있다. skillops-agent/scripts/build_rag_data.py 가 도구 계약을
    읽을 성공 실행을 찾을 때 쓴다.
    """
    return await _get("/api/executions", client)


async def get_snapshot(snapshot_id: str, *, client: httpx.AsyncClient | None = None) -> dict:
    """snapshot manifest 를 GET /api/snapshots/{snapshot_id} 로 읽어 그대로 돌려준다.

    order-agent/backend/src/main/java/com/capstone4/orderagent/controller/EvidenceController.java 의
    snapshot() 이 이 경로를 받는다. 응답의 metadata.source 로 게시본(published-bundle)인지 알 수
    있다.
    skillops-agent/scripts/build_rag_data.py 가 게시본으로 돈 실행을 고를 때 쓴다.
    """
    _require_uuid("snapshot_id", snapshot_id)
    return await _get(f"/api/snapshots/{snapshot_id}", client, snapshot_id=snapshot_id)


async def _get(path: str, client: httpx.AsyncClient | None, **ref: str) -> Any:
    """path 를 GET 으로 부르고 JSON 본문을 돌려준다.

    client 를 받으면 그 client 의 주소와 timeout 을 따른다. 없으면
    skillops-agent/skillops_agent/config.py 의 ORDER_AGENT_BASE_URL(환경 변수
    ORDER_AGENT_BASE_URL)과 TIMEOUT_SECONDS 로 client 를 직접 만들고 조회가 끝나면 닫는다. ref 는
    실패할 때 OrderAgentError 에 실을 요청 인자(execution_id 또는 snapshot_id)다. 조회 함수 네 개가
    모두 이 함수를 거친다.
    """
    if client is None:
        async with httpx.AsyncClient(base_url=ORDER_AGENT_BASE_URL, timeout=TIMEOUT_SECONDS) as own:
            return await _get_with_retry(own, path, ref)
    return await _get_with_retry(client, path, ref)


async def _get_with_retry(client: httpx.AsyncClient, path: str, ref: dict) -> Any:
    """GET 을 최대 MAX_ATTEMPTS 번 시도하고, 성공하면 JSON 본문을 가공 없이 돌려준다.

    order-agent/docs/diagnosis-read-api.md 8장은 GET 의 일시 연결 오류, 429, 일시 5xx 만 제한해서
    다시 시도하게 한다. 그래서 httpx.TransportError(연결 실패, timeout)와 retryable 상태만 다시
    시도하고, 그 밖의 상태는 첫 응답에서 바로 OrderAgentError 를 던진다. TransportError 가 아닌
    예외는 잡지 않고 그대로 올린다. 대기 시간은 시도마다 RETRY_BACKOFF_SECONDS 씩 늘어난다.
    """
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
    # 마지막 시도는 위에서 반드시 돌려주거나 던지므로 여기에 닿지 않는다.
    raise AssertionError("unreachable")


def _from_response(response: httpx.Response, ref: dict) -> OrderAgentError:
    """실패 응답 하나를 OrderAgentError 로 바꾼다.

    본문이 JSON 객체이고 code 와 message 가 비어 있지 않으면 그 값을 그대로 쓴다. 본문을 읽을 수
    없거나 값이 비었으면 ORDER_AGENT_HTTP_<상태> 와 기본 문구를 쓴다. retryable 은 상태만 보고
    _is_retryable_status 로 정한다. _get_with_retry 가 성공하지 못한 응답마다 부른다.
    """
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
        code, message, retryable=_is_retryable_status(status), upstream_status=status, **ref,
    )


def _is_retryable_status(status: int) -> bool:
    """잠시 뒤 다시 불러도 되는 HTTP 상태(5xx, 429)인지 본다.

    재시도할 상태를 바꾸려면 이 함수만 고친다. _from_response 가 OrderAgentError.retryable 을 정할
    때 부른다.
    """
    return status >= 500 or status == 429


def _require_uuid(name: str, value: str) -> None:
    """value 가 UUID 가 아니면 요청을 보내기 전에 INVALID_INPUT OrderAgentError 를 던진다.

    name 은 오류 문구와 OrderAgentError 의 같은 이름 칸(execution_id 또는 snapshot_id)에 쓰인다.
    문자열이 아닌 값(None, 숫자)도 같은 오류로 바꾼다. 주소에 들어갈 값을 보내기 전에
    확인하므로, 잘못된 입력은 주문 Agent 응답 오류와 섞이지 않고 upstream_status 가 None 인
    INVALID_INPUT 이 된다.
    """
    try:
        uuid.UUID(value)
    except (ValueError, TypeError, AttributeError) as e:
        raise OrderAgentError(
            "INVALID_INPUT", f"{name} 는 UUID 여야 합니다: {value}", retryable=False,
            **{name: value},
        ) from e


def _require_skill_id(skill_id: str, snapshot_id: str) -> None:
    """skill_id 형식이 틀리면 요청 전에 INVALID_INPUT OrderAgentError 를 던진다.

    문자열이 아니거나 SKILL_ID_MAX_LENGTH 를 넘거나 SKILL_ID 와 통째로 맞지 않으면 거절한다. 기준은
    order-agent/backend/src/main/java/com/capstone4/orderagent/harness/SkillLoader.java 의
    validateName() 과 같다. get_skill 이 부른다.
    """
    if (
        not isinstance(skill_id, str)
        or len(skill_id) > SKILL_ID_MAX_LENGTH
        or not SKILL_ID.fullmatch(skill_id)
    ):
        raise OrderAgentError(
            "INVALID_INPUT", f"skill_id 형식이 잘못됐습니다: {skill_id}",
            retryable=False, snapshot_id=snapshot_id,
        )
