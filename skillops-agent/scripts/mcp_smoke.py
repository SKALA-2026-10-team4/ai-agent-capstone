"""Python ↔ Java MCP 배선 확인. service(8080) 가 떠 있어야 한다.

    uv run scripts/mcp_smoke.py

tool 목록을 찍고 `ping` 을 호출한다. ping 이 없거나 실패하면 종료 코드 1.
"""

import asyncio
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))

from skillops_agent.config import MCP_URL  # noqa: E402
from skillops_agent.mcp_client import mcp_client  # noqa: E402


def _text_of(result) -> str | None:
    for block in getattr(result, "content", []) or []:
        text = getattr(block, "text", None)
        if text:
            return text
    return None


async def main() -> int:
    print(f"connecting {MCP_URL}")
    async with mcp_client() as client:
        listed = await client.list_tools()
        tools = getattr(listed, "tools", listed)
        names = [t.name for t in tools]
        print("tools:", names)
        if "ping" not in names:
            print("FAIL: ping tool 이 없다")
            return 1
        result = await client.call_tool("ping", {})
        text = _text_of(result)
        print("ping ->", text)
        return 0 if text and text.startswith("pong") else 1


if __name__ == "__main__":
    sys.exit(asyncio.run(main()))
