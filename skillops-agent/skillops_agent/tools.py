"""MCP 도구 래퍼 (v1).

- list_failed_runs / get_trace / list_skills / get_skill  → Order Agent HTTP API
- diff_skill / get_dependency_graph / create_workspace / apply_patch
  run_scenarios / save_proposal                           → MCP 도구

v1 변경: promote_workspace / rollback 은 서비스가 호출하므로 Agent 에서 제거.

USE_STUB_MCP=true 이면 StubMCPTools 를 반환해 service 없이 개발할 수 있다.
"""

import json
from typing import Any

import httpx

from . import order_agent
from .config import MCP_URL, ORDER_AGENT_URL, USE_STUB_MCP
from .contracts import ContractError, execution_uuid
from .mcp_client import mcp_client

# ── 실제 도구 ──────────────────────────────────────────────────────────────────


class MCPToolError(ContractError):
    def __init__(self, tool: str, payload: dict):
        self.tool = tool
        self.payload = payload
        self.code = payload.get("code")
        super().__init__(f"MCP {tool} failed: {json.dumps(payload, ensure_ascii=False)}")


class MCPTools:
    """Order Agent HTTP API + service MCP 서버(/mcp) 도구를 호출하는 클라이언트."""

    async def __aenter__(self) -> "MCPTools":
        self._client = mcp_client(MCP_URL)
        await self._client.__aenter__()
        self._http = httpx.AsyncClient(base_url=ORDER_AGENT_URL, timeout=30.0)
        return self

    async def __aexit__(self, *args: Any) -> None:
        try:
            # Do not inject application errors into the SDK task group on shutdown.
            await self._client.__aexit__(None, None, None)
        finally:
            await self._http.aclose()

    async def _call(self, name: str, args: dict) -> Any:
        result = await self._client.call_tool(name, args)
        if getattr(result, "is_error", False) or getattr(result, "isError", False):
            texts = [getattr(b, "text", "") for b in getattr(result, "content", [])]
            try:
                payload = json.loads(next(t for t in texts if t))
            except (ValueError, StopIteration) as exc:
                raise ContractError(f"MCP {name} returned an unreadable error") from exc
            if not isinstance(payload, dict):
                raise ContractError(f"MCP {name} returned an invalid error object")
            raise MCPToolError(name, payload)
        for block in getattr(result, "content", []) or []:
            text = getattr(block, "text", None)
            if text:
                try:
                    return json.loads(text)
                except json.JSONDecodeError as exc:
                    if name == "ping":
                        return text
                    raise ContractError(f"MCP {name} returned invalid JSON") from exc
        raise ContractError(f"MCP {name} returned no JSON content")

    # ── Order Agent HTTP API (v1) ────────────────────────────────────────────────

    # 조회 4개는 order_agent 함수(재시도·OrderAgentError)를 쓴다.
    # 주소·timeout 은 self._http 를 따른다.
    async def list_failed_runs(self) -> dict:
        return await order_agent.list_failed_runs(client=self._http)

    async def get_snapshot(self, snapshot_id: str) -> dict:
        resp = await self._http.get(f"/api/snapshots/{snapshot_id}")
        resp.raise_for_status()
        return resp.json()

    async def get_trace(self, execution_id: str) -> dict:
        return await order_agent.get_trace(execution_uuid(execution_id), client=self._http)

    async def list_skills(self, snapshot_id: str) -> dict:
        return await order_agent.list_skills(snapshot_id, client=self._http)

    async def get_skill(self, snapshot_id: str, skill_id: str) -> dict:
        return await order_agent.get_skill(snapshot_id, skill_id, client=self._http)

    # ── MCP 도구 ─────────────────────────────────────────────────────────────────

    async def ping(self) -> str:
        return await self._call("ping", {})

    async def diff_skill(
        self, skill_id: str, before_snapshot_id: str, after_snapshot_id: str
    ) -> dict:
        return await self._call(
            "diff_skill",
            {
                "skill_id": skill_id,
                "before_snapshot_id": before_snapshot_id,
                "after_snapshot_id": after_snapshot_id,
            },
        )

    async def get_dependency_graph(self, snapshot_id: str, entry_skill_id: str) -> dict:
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
        args = {}
        if start_versions:
            args["start_versions"] = start_versions
        if snapshot_id:
            args["snapshot_id"] = snapshot_id
        return await self._call("create_workspace", args)

    async def apply_patch(self, workspace_id: str, patches: list[dict]) -> dict:
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
        args: dict = {
            "workspace_id": workspace_id,
            "scenario_ids": scenario_ids,
            "repeat": repeat,
        }
        if replay_run_ids:
            args["replay_run_ids"] = replay_run_ids
        if extra_checks:
            args["extra_checks"] = extra_checks
        return await self._call("run_scenarios", args)

    async def save_proposal(self, proposal: dict) -> dict:
        raise ContractError("save_proposal is not provided by the current C server")


# ── Stub 도구 (service 없이 개발용) ───────────────────────────────────────────


from .stub_tools import StubMCPTools  # noqa: E402

# ── 팩토리 ──────────────────────────────────────────────────────────────────────


def get_tools() -> MCPTools | StubMCPTools:
    """USE_STUB_MCP 설정에 따라 실제 또는 stub 도구를 반환한다."""
    if USE_STUB_MCP:
        return StubMCPTools()
    return MCPTools()
