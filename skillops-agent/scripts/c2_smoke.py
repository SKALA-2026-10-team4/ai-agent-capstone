"""C-2 도구 확인: create_workspace → run_scenarios(g-01) → 잘못된 확인 조건 거부.

service(8080) 가 떠 있어야 한다.

    uv run scripts/c2_smoke.py

정상 경로는 B 의 Agent 가 실제로 쓰는 MCPTools(skillops_agent/tools.py)로 부른다.
계약 5 v0 이름이 맞는지까지 본다.
셋 다 맞으면 `C2 SMOKE OK` 를 찍고 0, 아니면 어긋난 단계를 찍고 1 로 끝난다.
"""

import asyncio
import json
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))

from skillops_agent.config import MCP_URL  # noqa: E402
from skillops_agent.mcp_client import mcp_client  # noqa: E402
from skillops_agent.tools import MCPTools  # noqa: E402


def _text_of(result) -> str | None:
    for block in getattr(result, "content", []) or []:
        text = getattr(block, "text", None)
        if text:
            return text
    return None


def _fail(step: str, detail) -> int:
    print(f"FAIL [{step}]: {detail}")
    return 1


async def main() -> int:
    print(f"connecting {MCP_URL}")
    async with MCPTools() as tools:
        workspace = await tools.create_workspace()
        print("create_workspace ->", workspace)
        ws_id = workspace.get("ws_id", "") if isinstance(workspace, dict) else ""
        if not ws_id.startswith("w_") or len(workspace.get("skill_versions", {})) != 6:
            return _fail("create_workspace", workspace)

        results = await tools.run_scenarios(ws_id, ["g-01"])
        print("run_scenarios ->", results)
        if not isinstance(results, list) or not results:
            return _fail("run_scenarios", results)
        first = results[0]
        if first.get("passed") is not True or len(first.get("runs", [])) != 2:
            return _fail("run_scenarios", first)

    # MCPTools 는 isError 를 감추므로 오류 경로는 MCP 클라이언트로 직접 본다.
    async with mcp_client() as client:
        bad = await client.call_tool(
            "run_scenarios",
            {"ws_id": ws_id, "scenario_ids": ["g-01"], "extra_checks": ["payment.status = PAID"]},
        )
        text = _text_of(bad) or ""
        print("run_scenarios(bad check) ->", text)
        error = json.loads(text) if text.startswith("{") else {}
        structured = error.get("code") == "INVALID_INPUT" and error.get("retryable") is False
        explained = "확인 조건을 읽을 수 없습니다" in error.get("message", "")
        if not (bad.is_error and structured and explained):
            return _fail("bad check", text)

    print("C2 SMOKE OK")
    return 0


if __name__ == "__main__":
    sys.exit(asyncio.run(main()))
