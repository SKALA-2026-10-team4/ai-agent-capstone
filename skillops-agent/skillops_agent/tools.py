"""진단 노드가 쓰는 도구 계층이다. 실제 도구(MCPTools)와 stub 선택 팩토리(get_tools)를 정의한다.

MCPTools 는 두 곳을 부른다.
- 주문 Agent HTTP API는 list_failed_runs, get_snapshot, get_trace, list_skills, get_skill,
  get_snapshot_file이 부른다. get_snapshot을 뺀 다섯 개는 order_agent.py를 거쳐 재시도와
  OrderAgentError 규칙을 따른다. 상대편은
  order-agent/backend/src/main/java/com/capstone4/orderagent/controller/EvidenceController.java 다.
- service MCP 서버(/mcp)의 도구는 ping, diff_skill, get_dependency_graph, create_workspace,
  apply_patch, run_scenarios, save_proposal 이 부른다. 도구 구현은
  service/src/main/java/com/capstone4/skillops/mcp/ 의 PingTools, SkillDiffTools,
  DependencyGraphTools, WorkspaceTools, ProposalTools 에 있다. 보내는 인자 이름은 그 메서드의
  @McpToolParam 매개변수 이름과 같아야 한다.

v1 계약에서 promote_workspace 와 rollback 은 service 가 부르기로 해서 Agent 에서 뺐다.

skillops-agent/skillops_agent/nodes.py 의 노드가 get_tools() 로 도구를 얻어 async with 로 연다.
skillops-agent/skillops_agent/stub_tools.py 의 StubMCPTools 와 공개 async 메서드 이름, 매개변수
이름, 기본값이 같아야 한다. 한쪽만 바꾸면 노드와 테스트 대역이 함께 깨지므로
skillops-agent/tests/test_stub_tools.py 가 이 대칭을 검사한다. USE_STUB_MCP 가 참이면 get_tools 가
StubMCPTools 를 돌려줘 service 없이 개발할 수 있다.
"""

import json
from typing import Any

import httpx

from . import order_agent
from .config import MCP_URL, ORDER_AGENT_URL, USE_STUB_MCP
from .contracts import ContractError, execution_uuid
from .mcp_client import first_text, mcp_client
from .stub_tools import StubMCPTools  # get_tools 가 고르는 오프라인 stub 도구다.

# MCPTools 가 주문 Agent HTTP client 에 주는 timeout(초)이다. 조회 네 개도 이 client 를 넘기므로
# 운영 경로에서는 이 값이 쓰인다. skillops-agent/skillops_agent/order_agent.py 의
# TIMEOUT_SECONDS 는 client 를 주지 않을 때만 쓰이므로 두 값을 따로 둔다.
ORDER_AGENT_TIMEOUT_SECONDS = 30.0

# ── 실제 도구 ──────────────────────────────────────────────────────────────────


class MCPToolError(ContractError):
    """service MCP 도구가 오류 결과(isError)로 돌려준 오류를 담는 예외다.

    tool 은 도구 이름이고, payload 는 오류 JSON 객체({code, message, retryable, upstream_status,
    snapshot_id})이며, code 는 payload 의 code 다. 예외 메시지는 'MCP <도구> failed: ' 뒤에 payload
    JSON 을 한글 이스케이프 없이 붙인 글이다. _call 이 던진다.
    skillops-agent/skillops_agent/nodes.py 의 _verify_candidate 는 apply_patch 의 INVALID_INPUT 만
    후보 무효로 바꾸고, 다른 오류는 그대로 올려 노드 ERROR 로 남긴다.
    """

    def __init__(self, tool: str, payload: dict):
        """도구 이름과 payload 를 남기고, payload 의 code 를 따로 꺼내 둔다."""
        self.tool = tool
        self.payload = payload
        self.code = payload.get("code")
        super().__init__(f"MCP {tool} failed: {json.dumps(payload, ensure_ascii=False)}")


def _is_error_result(result: Any) -> bool:
    """MCP 결과가 오류인지 본다. SDK 속성 이름 is_error 와 camelCase 이름 isError 를 모두 본다."""
    return bool(getattr(result, "is_error", False) or getattr(result, "isError", False))


def _error_payload(name: str, result: Any) -> dict:
    """오류 결과의 첫 text 를 JSON 객체로 읽는다.

    service 는 오류를 JSON 객체 하나로 보내고, 그 객체는
    service/src/main/java/com/capstone4/skillops/mcp/ToolResults.java 가 만든다. 글이 없거나 JSON 이
    아니면 'unreadable error', 객체가 아니면 'invalid error object' ContractError 를 던진다. 이 둘은
    MCPToolError 가 아니므로 skillops-agent/skillops_agent/nodes.py 의 _verify_candidate 가 하는
    INVALID_INPUT 처리에 걸리지 않는다.
    """
    try:
        payload = json.loads(first_text(result) or "")
    except ValueError as exc:
        raise ContractError(f"MCP {name} returned an unreadable error") from exc
    if not isinstance(payload, dict):
        raise ContractError(f"MCP {name} returned an invalid error object")
    return payload


def _success_payload(name: str, result: Any) -> Any:
    """정상 결과의 첫 text 를 JSON 으로 읽어 돌려준다.

    글이 없으면 'no JSON content', JSON 이 아니면 'invalid JSON' ContractError 를 던진다. ping 만
    예외다. service/src/main/java/com/capstone4/skillops/mcp/PingTools.java 의 ping() 이 JSON 이
    아닌 'pong <서버 시각>' 글을 돌려주므로 그 글을 그대로 돌려준다.
    """
    text = first_text(result)
    if text is None:
        raise ContractError(f"MCP {name} returned no JSON content")
    try:
        return json.loads(text)
    except json.JSONDecodeError as exc:
        if name == "ping":
            return text
        raise ContractError(f"MCP {name} returned invalid JSON") from exc


def _optional_args(**values: Any) -> dict:
    """참인 선택 인자만 넣은 순서대로 담은 dict 를 만든다.

    None 뿐 아니라 빈 dict, 빈 목록, 빈 글도 빠진다. create_workspace 와 run_scenarios 가 선택
    인자를 보낼 때 쓴다.
    """
    return {key: value for key, value in values.items() if value}


class MCPTools:
    """주문 Agent HTTP API 와 service MCP 서버(/mcp) 도구를 함께 부르는 클라이언트다.

    async with 로 열어야 쓸 수 있다. 노드마다 get_tools() 로 새 인스턴스를 만들어 열기 때문에 MCP
    세션과 httpx client 도 노드마다 새로 생긴다. 공개 메서드는 결과를 가공하지 않고 돌려주며, 계약
    검사는 부르는 노드가 skillops-agent/skillops_agent/contracts.py 의 함수로 한다.
    """

    async def __aenter__(self) -> "MCPTools":
        """MCP 세션을 열고 주문 Agent HTTP client 를 만든다.

        MCP 주소는 config.MCP_URL 이다. 주문 Agent 주소는 config.ORDER_AGENT_URL 이며 환경 변수
        SKILLOPS_ORDER_AGENT_URL, 없으면 ORDER_AGENT_BASE_URL 을 쓴다
        (skillops-agent/skillops_agent/config.py). mcp.Client 인스턴스는 한 번만 들어갈 수 있으므로
        들어갈 때마다 mcp_client 로 새 인스턴스를 만든다.
        """
        self._client = mcp_client(MCP_URL)
        await self._client.__aenter__()
        self._http = httpx.AsyncClient(
            base_url=ORDER_AGENT_URL, timeout=ORDER_AGENT_TIMEOUT_SECONDS
        )
        return self

    async def __aexit__(self, *args: Any) -> None:
        """MCP 세션을 닫고, 그 종료가 실패해도 finally 로 httpx client 를 닫는다."""
        try:
            # 앱에서 난 예외 정보를 SDK 의 task group 에 넣지 않으려고 (None, None, None) 으로
            # 닫는다. mcp.Client.__aexit__ 는 받은 예외 정보를 내부 exit stack 에 그대로 넘긴다.
            await self._client.__aexit__(None, None, None)
        finally:
            await self._http.aclose()

    async def _call(self, name: str, args: dict) -> Any:
        """MCP 도구 name 을 args 로 부르고 결과 글을 해석해 돌려준다.

        오류 결과면 _error_payload 로 읽은 JSON 객체를 담아 MCPToolError 를 던지고, 정상이면
        _success_payload 로 읽은 JSON 을 돌려준다. MCP 도구 메서드 일곱 개가 모두 이 함수를 거친다.
        응답 해석 규칙을 바꾸려면 _is_error_result, _error_payload, _success_payload 를 고친다.
        """
        result = await self._client.call_tool(name, args)
        if _is_error_result(result):
            raise MCPToolError(name, _error_payload(name, result))
        return _success_payload(name, result)

    # ── Order Agent HTTP API (v1) ────────────────────────────────────────────────

    # 조회 다섯 개(실패 목록·trace·Skill 목록·Skill·원문 파일)는 order_agent 의 함수를 불러
    # 재시도와 OrderAgentError 규칙을 따른다. 주소와 timeout 은 self._http 를 따른다.
    # get_snapshot 만 이 규칙 밖에 있다.
    async def list_failed_runs(self) -> dict:
        """주문 Agent 의 실패 실행 목록을 order_agent.list_failed_runs 로 읽는다.

        경로는 GET /api/executions/failed 다. 지금 노드는 이 메서드를 쓰지 않고 테스트만 부른다.
        """
        return await order_agent.list_failed_runs(client=self._http)

    async def get_snapshot(self, snapshot_id: str) -> dict:
        """snapshot manifest 를 GET /api/snapshots/{snapshot_id} 로 읽는다.

        order-agent/backend/src/main/java/com/capstone4/orderagent/controller/ 의
        EvidenceController.snapshot() 이 받는다. 이 메서드만 order_agent 를 거치지 않아서 UUID
        검사와 재시도가 없고, 실패 상태는 httpx.HTTPStatusError 로 올라간다.
        skillops-agent/skillops_agent/nodes.py 의 _fix_snapshots 와 _preflight 가 부른다.
        """
        resp = await self._http.get(f"/api/snapshots/{snapshot_id}")
        resp.raise_for_status()
        return resp.json()

    async def get_trace(self, execution_id: str) -> dict:
        """실행 기록을 order_agent.get_trace 로 읽는다.

        run_scenarios 결과의 run_id 는 r_ 별칭으로 오므로,
        skillops-agent/skillops_agent/contracts.py 의 execution_uuid 로 하이픈 UUID 로 바꾼 뒤
        부른다. 그래서 잘못된 별칭은 요청 전에 ContractError 가 된다.
        skillops-agent/skillops_agent/nodes.py 의 _fix_snapshots, _preflight, _known_values,
        _verify_candidate 가 부른다.
        """
        return await order_agent.get_trace(execution_uuid(execution_id), client=self._http)

    async def list_skills(self, snapshot_id: str) -> dict:
        """snapshot 의 skill 목록을 order_agent.list_skills 로 읽는다.

        skillops-agent/skillops_agent/nodes.py 의 fetch_evidence 가 부른다.
        """
        return await order_agent.list_skills(snapshot_id, client=self._http)

    async def get_skill(self, snapshot_id: str, skill_id: str) -> dict:
        """snapshot 에 고정된 SKILL.md 원문 하나를 order_agent.get_skill 로 읽는다.

        skillops-agent/skillops_agent/nodes.py 의 _repair_inputs 가 부른다.
        """
        return await order_agent.get_skill(snapshot_id, skill_id, client=self._http)

    async def get_snapshot_file(self, snapshot_id: str, path: str) -> dict:
        """partial 증거 수집에 사용할 원본 파일을 공유 HTTP client로 읽는다."""
        return await order_agent.get_snapshot_file(snapshot_id, path, client=self._http)

    # ── MCP 도구 ─────────────────────────────────────────────────────────────────

    async def ping(self) -> str:
        """MCP 도구 ping 을 부른다.

        service/src/main/java/com/capstone4/skillops/mcp/PingTools.java 의 ping() 이 받는다. 노드는
        쓰지 않고 테스트가 부른다. 배선 확인은 skillops-agent/scripts/mcp_smoke.py 가 raw client 로
        한다.
        """
        return await self._call("ping", {})

    async def diff_skill(
        self, skill_id: str, before_snapshot_id: str, after_snapshot_id: str
    ) -> dict:
        """MCP 도구 diff_skill 을 불러 두 snapshot 사이 skill 하나의 SKILL.md 차이를 읽는다.

        service/src/main/java/com/capstone4/skillops/mcp/SkillDiffTools.java 의 diffSkill() 이
        받는다. 인자와 결과 칸은 docs/contracts/mcp-tools-input-output.md 의 diff_skill 절과
        같다. skillops-agent/skillops_agent/nodes.py 의
        _collect_changes 가 결과의 hunks 를 변경 단위로 쓰고,
        skillops-agent/scripts/b_c_contract_smoke.py 도 부른다.
        """
        return await self._call(
            "diff_skill",
            {
                "skill_id": skill_id,
                "before_snapshot_id": before_snapshot_id,
                "after_snapshot_id": after_snapshot_id,
            },
        )

    async def get_dependency_graph(self, snapshot_id: str, entry_skill_id: str) -> dict:
        """MCP 도구 get_dependency_graph 로 snapshot 안 흐름의 skill 연결 관계를 읽는다.

        service/src/main/java/com/capstone4/skillops/mcp/DependencyGraphTools.java 의
        getDependencyGraph() 가 받는다. skillops-agent/skillops_agent/nodes.py 의 _repair_inputs 가
        요청의 entry_skill_id 로 부른다.
        """
        return await self._call(
            "get_dependency_graph",
            {
                "snapshot_id": snapshot_id,
                "entry_skill_id": entry_skill_id,
            },
        )

    async def create_workspace(
        self, start_versions: dict | None = None, snapshot_id: str | None = None
    ) -> dict:
        """MCP 도구 create_workspace 로 운영 skill 을 건드리지 않는 연습 공간을 만든다.

        service/src/main/java/com/capstone4/skillops/mcp/WorkspaceTools.java 의
        createWorkspace() 가 받는다. 두 인자는 참일 때만 보내므로, 둘 다 없으면 빈 인자로 운영 버전
        공간을 만든다. 두 값을 함께 쓸 수 없다는 검사는 service 가 한다. 결과의 skill_versions 가
        apply_patch 의 base_version 기준이다. 노드(skillops-agent/skillops_agent/nodes.py 의
        _preflight, _evaluate_intent_checks, _reproduce_failure, _ablate_change,
        _verify_candidate)는 snapshot_id 만 넘긴다.
        """
        return await self._call(
            "create_workspace",
            _optional_args(start_versions=start_versions, snapshot_id=snapshot_id),
        )

    async def apply_patch(self, workspace_id: str, patches: list[dict]) -> dict:
        """MCP 도구 apply_patch 로 연습 공간의 SKILL.md 에 수정 내용을 넣는다.

        service/src/main/java/com/capstone4/skillops/mcp/WorkspaceTools.java 의 applyPatch() 가
        받는다. patches 항목은 {skill, base_version, patch} 다. 원문과 맞지 않는 수정은
        INVALID_INPUT MCPToolError 가 된다. skillops-agent/skillops_agent/nodes.py 의
        _ablate_change 와 _verify_candidate 가 부르고, _verify_candidate 는 그 오류를 받은 후보만
        무효로 바꾼다.
        """
        return await self._call(
            "apply_patch",
            {
                "workspace_id": workspace_id,
                "patches": patches,
            },
        )

    async def run_scenarios(
        self,
        workspace_id: str,
        scenario_ids: list[str],
        repeat: int = 2,
        replay_run_ids: list[str] | None = None,
        extra_checks: list | None = None,
    ) -> dict:
        """MCP 도구 run_scenarios 로 연습 공간에서 시험 주문을 돌리고 채점 결과를 받는다.

        service/src/main/java/com/capstone4/skillops/mcp/WorkspaceTools.java 의 runScenarios() 가
        받는다. workspace_id, scenario_ids, repeat 는 늘 보내고, replay_run_ids 와 extra_checks 는
        값이 있을 때만 그 순서로 덧붙인다. 노드는 skillops-agent/skillops_agent/nodes.py 의
        _run_scenarios 를 거쳐 repeat 와 extra_checks 를 넘기고, 재현 주문은 scenario_ids 의
        f-<execution_id> 로 넣어 replay_run_ids 는 쓰지 않는다. repeat 기본값 2 는 service 기본값과
        같지만, 노드가 단계별 반복 횟수(skillops-agent/skillops_agent/config.py 의 *_REPEAT)를 늘
        넘기므로 실제 튜닝 지점은 config 다.
        """
        args: dict = {
            "workspace_id": workspace_id,
            "scenario_ids": scenario_ids,
            "repeat": repeat,
            **_optional_args(replay_run_ids=replay_run_ids, extra_checks=extra_checks),
        }
        return await self._call("run_scenarios", args)

    async def save_proposal(self, proposal: dict) -> dict:
        """MCP 도구 save_proposal 로 제안서를 service 에 저장한다.

        service/src/main/java/com/capstone4/skillops/mcp/ProposalTools.java 의 saveProposal() 이
        받는다. 돌려받는 값은 {proposal_id, status, valid_candidate_nos} 다. service 가 근거 실행과
        합격 조건을 다시 확인해, 근거 실행이 맞지 않으면 INVALID_INPUT 오류를 돌려준다.
        skillops-agent/skillops_agent/nodes.py 의 save_proposal 노드가 부르고, 거부되면
        MCPToolError 가 그대로 올라가 진단이 ERROR 로 남는다.
        """
        return await self._call("save_proposal", {"proposal": proposal})


# ── 팩토리 ──────────────────────────────────────────────────────────────────────


def get_tools() -> MCPTools | StubMCPTools:
    """USE_STUB_MCP 설정에 따라 연결하지 않은 실제 도구나 stub 도구를 새로 만들어 돌려준다.

    부를 때마다 이 모듈 전역 USE_STUB_MCP 를 읽으므로 테스트가 monkeypatch 로 바꿀 수 있다. 연결은
    부르는 쪽이 async with 로 연다. skillops-agent/skillops_agent/nodes.py 의 fetch_evidence,
    ablate, _known_values, generate_candidates, verify_candidates, save_proposal 이 부른다. 값은
    환경 변수 SKILLOPS_USE_STUB_MCP 에서 온다(skillops-agent/skillops_agent/config.py).
    """
    if USE_STUB_MCP:
        return StubMCPTools()
    return MCPTools()
