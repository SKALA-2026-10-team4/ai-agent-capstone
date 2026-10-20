"""MCP 도구 12개 래퍼.

USE_STUB_MCP=true 이면 StubMCPTools 를 반환해 service 없이 개발할 수 있다.

사용 예:
    async with get_tools() as tools:
        pong = await tools.ping()
        trace = await tools.get_trace("r_01")
"""

import json
from datetime import datetime, timezone
from typing import Any

from .config import MCP_URL, USE_STUB_MCP
from .mcp_client import mcp_client


# ── 실제 MCP 도구 ──────────────────────────────────────────────────────────────


class MCPTools:
    """service MCP 서버(/mcp)의 도구 12개 + ping 을 호출하는 클라이언트."""

    async def __aenter__(self) -> "MCPTools":
        self._client = mcp_client(MCP_URL)
        await self._client.__aenter__()
        return self

    async def __aexit__(self, *args: Any) -> None:
        await self._client.__aexit__(*args)

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

    # ── 읽기 도구 6개 (C-1) ─────────────────────────────────────────────────────

    async def ping(self) -> str:
        return await self._call("ping", {})

    async def list_skills(self) -> list[dict]:
        return await self._call("list_skills", {})

    async def get_skill(self, name: str, version: int | None = None) -> dict:
        args = {"name": name}
        if version is not None:
            args["version"] = version
        return await self._call("get_skill", args)

    async def diff_skill(self, name: str, from_version: int, to_version: int) -> dict:
        return await self._call("diff_skill", {
            "name": name, "from_version": from_version, "to_version": to_version,
        })

    async def get_dependency_graph(self) -> dict:
        return await self._call("get_dependency_graph", {})

    async def list_failed_runs(self, since: str | None = None) -> list[dict]:
        args = {}
        if since:
            args["since"] = since
        return await self._call("list_failed_runs", args)

    async def get_trace(self, run_id: str) -> dict:
        return await self._call("get_trace", {"run_id": run_id})

    # ── 쓰기 도구 6개 (C-2) ─────────────────────────────────────────────────────

    async def create_workspace(self, base: dict | None = None) -> dict:
        args = {}
        if base:
            args["base"] = base
        return await self._call("create_workspace", args)

    async def apply_patch(self, ws_id: str, patches: list[dict]) -> dict:
        return await self._call("apply_patch", {"ws_id": ws_id, "patches": patches})

    async def run_scenarios(
        self, ws_id: str, scenario_ids: list[str], k: int = 2
    ) -> list[dict]:
        return await self._call("run_scenarios", {
            "ws_id": ws_id, "scenario_ids": scenario_ids, "k": k,
        })

    async def save_proposal(self, proposal: dict) -> dict:
        return await self._call("save_proposal", {"proposal": proposal})

    async def promote_workspace(self, ws_id: str, approval_token: str) -> dict:
        return await self._call("promote_workspace", {
            "ws_id": ws_id, "approval_token": approval_token,
        })

    async def rollback(self, skill: str, version: int) -> dict:
        return await self._call("rollback", {"skill": skill, "version": version})


# ── Stub 도구 (service 없이 개발용) ───────────────────────────────────────────


class StubMCPTools:
    """F1 시나리오 기준 canned 응답을 반환한다. USE_STUB_MCP=true 일 때 사용."""

    async def __aenter__(self) -> "StubMCPTools":
        return self

    async def __aexit__(self, *args: Any) -> None:
        pass

    async def ping(self) -> str:
        return f"pong {datetime.now(timezone.utc).isoformat()}"

    async def list_skills(self) -> list[dict]:
        return [
            {"name": "order-flow", "version": 1, "owner": "운영팀", "description": "주문 흐름", "inputs": [], "outputs": [], "depends_on": [], "steps": ["order-intake", "inventory-check", "payment", "shipping-reserve", "notify"]},
            {"name": "order-intake", "version": 2, "owner": "운영팀", "description": "주문 접수", "inputs": ["order.id"], "outputs": ["order.id", "order.total"], "depends_on": []},
            {"name": "inventory-check", "version": 5, "owner": "물류팀", "description": "재고 확인", "inputs": ["order.id"], "outputs": ["order.id", "inventory.예약가능수량"], "depends_on": []},
            {"name": "payment", "version": 4, "owner": "재무팀", "description": "결제", "inputs": ["order.id", "order.total", "inventory.reserved"], "outputs": ["payment.id", "payment.status"], "depends_on": ["inventory-check"]},
            {"name": "shipping-reserve", "version": 3, "owner": "물류팀", "description": "배송 예약", "inputs": ["order.id", "payment.id"], "outputs": ["shipping.id"], "depends_on": ["payment"]},
            {"name": "notify", "version": 2, "owner": "운영팀", "description": "알림", "inputs": ["order.id", "payment.status"], "outputs": [], "depends_on": ["payment"]},
        ]

    async def get_skill(self, name: str, version: int | None = None) -> dict:
        return {
            "name": name,
            "version": version or 5,
            "frontmatter": {"inputs": ["order.id"], "outputs": ["inventory.예약가능수량"]},
            "body": f"# {name}\n(stub body)",
        }

    async def diff_skill(self, name: str, from_version: int, to_version: int) -> dict:
        # F1: inventory-check outputs 필드명 변경
        return {
            "hunks": [
                {
                    "index": 0,
                    "header": "@@ -3 +3 @@",
                    "before": "outputs: [order.id, inventory.reserved]",
                    "after":  "outputs: [order.id, inventory.예약가능수량]",
                }
            ]
        }

    async def get_dependency_graph(self) -> dict:
        return {
            "steps": ["order-intake", "inventory-check", "payment", "shipping-reserve", "notify"],
            "edges": [
                {"from": "inventory-check", "to": "payment", "keys": ["inventory.reserved"]},
                {"from": "payment", "to": "shipping-reserve", "keys": ["payment.id"]},
                {"from": "payment", "to": "notify", "keys": ["payment.status"]},
            ],
            "violations": [
                {"skill": "payment", "missing_input": "inventory.reserved"}
            ],
        }

    async def list_failed_runs(self, since: str | None = None) -> list[dict]:
        return [
            {"run_id": "r_stub_F1", "order_id": "ord_001", "failed_skill": "payment", "error": "required input inventory.reserved not found", "at": "2026-10-20T09:00:00Z"},
        ]

    async def get_trace(self, run_id: str) -> dict:
        return {
            "run_id": run_id,
            "order_id": "ord_001",
            "mode": "live",
            "workspace": None,
            "scenario_id": None,
            "started_at": "2026-10-20T09:00:00Z",
            "skill_versions": {"order-flow": 1, "order-intake": 2, "inventory-check": 5, "payment": 4, "shipping-reserve": 3, "notify": 2},
            "steps": [
                {"skill": "order-intake",     "version": 2, "input": {"order.id": "ord_001"}, "output": {"order.id": "ord_001", "order.total": 50000}, "tools_called": [],                 "status": "ok",    "error": None, "ms": 120},
                {"skill": "inventory-check",  "version": 5, "input": {"order.id": "ord_001"}, "output": {"inventory.예약가능수량": True},               "tools_called": ["check_inventory"], "status": "ok",    "error": None, "ms": 200},
                {"skill": "payment",          "version": 4, "input": {"order.id": "ord_001", "order.total": 50000}, "output": {},                       "tools_called": [],                 "status": "error", "error": "required input inventory.reserved not found", "ms": 50},
            ],
            "final_status": "FAILED",
        }

    async def create_workspace(self, base: dict | None = None) -> dict:
        return {"ws_id": "ws_stub_001"}

    async def apply_patch(self, ws_id: str, patches: list[dict]) -> dict:
        return {
            "ws_id": ws_id,
            "applied": [{"skill": p["skill"], "version": p["base_version"] + 1} for p in patches],
            "contract_violations": [],
        }

    async def run_scenarios(
        self, ws_id: str, scenario_ids: list[str], k: int = 2
    ) -> list[dict]:
        return [
            {"scenario_id": sid, "passed": True, "runs": [f"r_stub_{sid}_{i}" for i in range(k)], "failures": []}
            for sid in scenario_ids
        ]

    async def save_proposal(self, proposal: dict) -> dict:
        return {"id": "p_stub_001"}

    async def promote_workspace(self, ws_id: str, approval_token: str) -> dict:
        return {"promoted": [{"skill": "inventory-check", "version": 6}]}

    async def rollback(self, skill: str, version: int) -> dict:
        return {"skill": skill, "version": version}


# ── 팩토리 ──────────────────────────────────────────────────────────────────────


def get_tools() -> MCPTools | StubMCPTools:
    """USE_STUB_MCP 설정에 따라 실제 또는 stub 도구를 반환한다."""
    if USE_STUB_MCP:
        return StubMCPTools()
    return MCPTools()
