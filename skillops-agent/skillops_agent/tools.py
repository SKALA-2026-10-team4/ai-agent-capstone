"""MCP 도구 래퍼 (v1).

- list_failed_runs / get_trace / list_skills / get_skill  → Order Agent HTTP API
- diff_skill / get_dependency_graph / create_workspace / apply_patch
  run_scenarios / save_proposal                           → MCP 도구

v1 변경: promote_workspace / rollback 은 서비스가 호출하므로 Agent 에서 제거.

USE_STUB_MCP=true 이면 StubMCPTools 를 반환해 service 없이 개발할 수 있다.
"""

import json
from datetime import datetime, timezone
from typing import Any

import httpx

from .config import MCP_URL, ORDER_AGENT_URL, USE_STUB_MCP
from .mcp_client import mcp_client


# ── 실제 도구 ──────────────────────────────────────────────────────────────────


class MCPTools:
    """Order Agent HTTP API + service MCP 서버(/mcp) 도구를 호출하는 클라이언트."""

    async def __aenter__(self) -> "MCPTools":
        self._client = mcp_client(MCP_URL)
        await self._client.__aenter__()
        self._http = httpx.AsyncClient(base_url=ORDER_AGENT_URL, timeout=30.0)
        return self

    async def __aexit__(self, *args: Any) -> None:
        await self._client.__aexit__(*args)
        await self._http.aclose()

    async def _call(self, name: str, args: dict) -> Any:
        result = await self._client.call_tool(name, args)
        for block in getattr(result, "content", []) or []:
            text = getattr(block, "text", None)
            if text:
                try:
                    return json.loads(text)
                except json.JSONDecodeError:
                    return text
        return None

    # ── Order Agent HTTP API (v1) ────────────────────────────────────────────────

    async def list_failed_runs(self, since: str | None = None) -> dict:
        params = {}
        if since:
            params["since"] = since
        resp = await self._http.get("/api/executions/failed", params=params)
        resp.raise_for_status()
        return resp.json()

    async def get_trace(self, execution_id: str) -> dict:
        resp = await self._http.get(f"/api/executions/{execution_id}/trace")
        resp.raise_for_status()
        return resp.json()

    async def list_skills(self, snapshot_id: str) -> dict:
        resp = await self._http.get(f"/api/snapshots/{snapshot_id}/skills")
        resp.raise_for_status()
        return resp.json()

    async def get_skill(self, snapshot_id: str, skill_id: str) -> dict:
        resp = await self._http.get(f"/api/snapshots/{snapshot_id}/skills/{skill_id}")
        resp.raise_for_status()
        return resp.json()

    # ── MCP 도구 ─────────────────────────────────────────────────────────────────

    async def ping(self) -> str:
        return await self._call("ping", {})

    async def diff_skill(
        self, skill_id: str, before_snapshot_id: str, after_snapshot_id: str
    ) -> dict:
        return await self._call("diff_skill", {
            "skill_id": skill_id,
            "before_snapshot_id": before_snapshot_id,
            "after_snapshot_id": after_snapshot_id,
        })

    async def get_dependency_graph(
        self, snapshot_id: str, entry_skill_id: str
    ) -> dict:
        return await self._call("get_dependency_graph", {
            "snapshot_id": snapshot_id,
            "entry_skill_id": entry_skill_id,
        })

    async def create_workspace(self, start_versions: dict | None = None) -> dict:
        args = {}
        if start_versions:
            args["start_versions"] = start_versions
        return await self._call("create_workspace", args)

    async def apply_patch(self, workspace_id: str, patches: list[dict]) -> dict:
        return await self._call("apply_patch", {
            "workspace_id": workspace_id,
            "patches": patches,
        })

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
        return await self._call("save_proposal", {"proposal": proposal})


# ── Stub 도구 (service 없이 개발용) ───────────────────────────────────────────


class StubMCPTools:
    """F1 시나리오 기준 canned 응답을 반환한다. USE_STUB_MCP=true 일 때 사용."""

    async def __aenter__(self) -> "StubMCPTools":
        return self

    async def __aexit__(self, *args: Any) -> None:
        pass

    async def ping(self) -> str:
        return f"pong {datetime.now(timezone.utc).isoformat()}"

    # ── Order Agent HTTP API stubs ───────────────────────────────────────────────

    async def list_failed_runs(self, since: str | None = None) -> dict:
        return {
            "items": [
                {
                    "execution_id": "r_stub_F1",
                    "snapshot_id": "snap_001",
                    "snapshot_hash": "hash001",
                    "order_id": "ord_001",
                    "failed_skill": "payment",
                    "error": {"code": "MISSING_INPUT", "message": "required input inventory.reserved not found"},
                    "started_at": "2026-10-20T09:00:00Z",
                    "finished_at": "2026-10-20T09:00:00Z",
                    "final_status": "FAILED",
                }
            ],
            "cursor": None,
            "has_more": False,
        }

    async def get_trace(self, execution_id: str) -> dict:
        if execution_id == "r_stub_F1":
            return {
                "execution_id": execution_id,
                "snapshot_id": "snap_001",
                "order_id": "ord_001",
                "started_at": "2026-10-20T09:00:00Z",
                "final_status": "FAILED",
                "events": [
                    {"sequence": 1, "kind": "SKILL_SELECTED",    "skill": "order-intake",    "version": 2},
                    {"sequence": 2, "kind": "EXECUTION_FINISHED", "skill": "order-intake",    "output": {"order.id": "ord_001", "order.total": 50000}, "ms": 120},
                    {"sequence": 3, "kind": "SKILL_SELECTED",    "skill": "inventory-check", "version": 5},
                    {"sequence": 4, "kind": "TOOL_REQUEST",       "skill": "inventory-check", "tool": "check_inventory"},
                    {"sequence": 5, "kind": "EXECUTION_FINISHED", "skill": "inventory-check", "output": {"inventory.예약가능수량": True}, "ms": 200},
                    {"sequence": 6, "kind": "SKILL_SELECTED",    "skill": "payment",         "version": 4},
                    {"sequence": 7, "kind": "EXECUTION_ERROR",   "skill": "payment",         "error": {"code": "MISSING_INPUT", "message": "required input inventory.reserved not found"}, "ms": 50},
                ],
            }
        # workspace 시나리오 실행 결과 — 수정 후 통과 트레이스
        return {
            "execution_id": execution_id,
            "snapshot_id": "snap_ws_001",
            "order_id": "ord_001",
            "started_at": "2026-10-20T09:00:00Z",
            "final_status": "SHIPPED",
            "events": [
                {"sequence": 1,  "kind": "SKILL_SELECTED",    "skill": "order-intake",    "version": 2},
                {"sequence": 2,  "kind": "EXECUTION_FINISHED", "skill": "order-intake",    "output": {"order.id": "ord_001", "order.total": 50000}, "ms": 110},
                {"sequence": 3,  "kind": "SKILL_SELECTED",    "skill": "inventory-check", "version": 6},
                {"sequence": 4,  "kind": "TOOL_REQUEST",       "skill": "inventory-check", "tool": "check_inventory"},
                {"sequence": 5,  "kind": "EXECUTION_FINISHED", "skill": "inventory-check", "output": {"inventory.reserved": True}, "ms": 180},
                {"sequence": 6,  "kind": "SKILL_SELECTED",    "skill": "payment",         "version": 4},
                {"sequence": 7,  "kind": "EXECUTION_FINISHED", "skill": "payment",         "output": {"payment.id": "pay_001", "payment.status": "PAID"}, "ms": 90},
                {"sequence": 8,  "kind": "SKILL_SELECTED",    "skill": "shipping-reserve","version": 3},
                {"sequence": 9,  "kind": "EXECUTION_FINISHED", "skill": "shipping-reserve","output": {"shipping.id": "shp_001"}, "ms": 95},
                {"sequence": 10, "kind": "SKILL_SELECTED",    "skill": "notify",          "version": 2},
                {"sequence": 11, "kind": "EXECUTION_FINISHED", "skill": "notify",          "output": {}, "ms": 40},
            ],
        }

    async def list_skills(self, snapshot_id: str) -> dict:
        return {
            "items": [
                {"skill_id": "order-flow",       "path": "order-flow/SKILL.md",       "hash": "h001", "partial": False},
                {"skill_id": "order-intake",      "path": "order-intake/SKILL.md",      "hash": "h002", "partial": False},
                {"skill_id": "inventory-check",   "path": "inventory-check/SKILL.md",   "hash": "h003", "partial": False},
                {"skill_id": "payment",           "path": "payment/SKILL.md",           "hash": "h004", "partial": False},
                {"skill_id": "shipping-reserve",  "path": "shipping-reserve/SKILL.md",  "hash": "h005", "partial": False},
                {"skill_id": "notify",            "path": "notify/SKILL.md",            "hash": "h006", "partial": False},
            ]
        }

    async def get_skill(self, snapshot_id: str, skill_id: str) -> dict:
        return {
            "skill_id": skill_id,
            "path": f"{skill_id}/SKILL.md",
            "hash": "h001",
            "size": 100,
            "content": f"# {skill_id}\n(stub body)",
            "encoding": "utf-8",
        }

    # ── MCP 도구 stubs ───────────────────────────────────────────────────────────

    async def diff_skill(
        self, skill_id: str, before_snapshot_id: str, after_snapshot_id: str
    ) -> dict:
        # F1: inventory-check outputs 필드명 변경 (v1: hunks → changes)
        return {
            "changes": [
                {
                    "change_no": 0,
                    "location": "@@ -3 +3 @@",
                    "before": "outputs: [order.id, inventory.reserved]",
                    "after":  "outputs: [order.id, inventory.예약가능수량]",
                }
            ]
        }

    async def get_dependency_graph(
        self, snapshot_id: str, entry_skill_id: str
    ) -> dict:
        # v1: run_order / links / value_names / broken_links
        return {
            "run_order": ["order-intake", "inventory-check", "payment", "shipping-reserve", "notify"],
            "links": [
                {"from": "inventory-check", "to": "payment",          "value_names": ["inventory.reserved"]},
                {"from": "payment",          "to": "shipping-reserve", "value_names": ["payment.id"]},
                {"from": "payment",          "to": "notify",           "value_names": ["payment.status"]},
            ],
            "broken_links": [
                {"skill": "payment", "missing_input": "inventory.reserved"}
            ],
        }

    async def create_workspace(self, start_versions: dict | None = None) -> dict:
        return {"workspace_id": "ws_stub_001"}

    async def apply_patch(self, workspace_id: str, patches: list[dict]) -> dict:
        return {
            "workspace_id": workspace_id,
            "changed_skills": [{"skill": p["skill"], "version": p["base_version"] + 1} for p in patches],
            "broken_links": [],
        }

    async def run_scenarios(
        self,
        workspace_id: str,
        scenario_ids: list[str],
        repeat: int = 2,
        replay_run_ids: list[str] | None = None,
        extra_checks: list | None = None,
    ) -> dict:
        return {
            "results": [
                {
                    "scenario_id": sid,
                    "passed": True,
                    "all_passed": True,
                    "run_ids": [f"r_stub_{sid}_{i}" for i in range(repeat)],
                    "failures": [],
                }
                for sid in scenario_ids
            ]
        }

    async def save_proposal(self, proposal: dict) -> dict:
        return {"id": "p_stub_001", "status": "proposed", "valid_candidate_nos": [1]}


# ── 팩토리 ──────────────────────────────────────────────────────────────────────


def get_tools() -> MCPTools | StubMCPTools:
    """USE_STUB_MCP 설정에 따라 실제 또는 stub 도구를 반환한다."""
    if USE_STUB_MCP:
        return StubMCPTools()
    return MCPTools()
