"""MCP 도구 하나를 터미널에서 직접 불러 본다. service(8080) 가 떠 있어야 한다.

    uv run scripts/mcp_call.py --list
    uv run scripts/mcp_call.py create_workspace '{}'
    uv run scripts/mcp_call.py run_scenarios '{"ws_id": "w_...", "scenario_ids": ["g-01"]}'

--list 는 도구 이름과 받는 값을 보여 준다(* 는 꼭 넣어야 하는 값).
결과가 JSON 이면 보기 좋게 펼쳐 찍는다. 도구가 오류를 돌려주면 종료 코드 1 로 끝난다.
"""

import asyncio
import json
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))

from skillops_agent.config import MCP_URL  # noqa: E402
from skillops_agent.mcp_client import mcp_client  # noqa: E402


def _text_of(result) -> str:
    for block in getattr(result, "content", []) or []:
        text = getattr(block, "text", None)
        if text:
            return text
    return ""


def _pretty(text: str) -> str:
    try:
        return json.dumps(json.loads(text), ensure_ascii=False, indent=2)
    except json.JSONDecodeError:
        return text


async def main(argv: list[str]) -> int:
    if not argv or argv[0] in ("-h", "--help"):
        print(__doc__)
        return 0
    try:
        args = json.loads(argv[1]) if len(argv) > 1 else {}
    except json.JSONDecodeError as e:
        print(f'두 번째 인자는 JSON 이어야 합니다 ({e}). 예: \'{{"ws_id": "w_..."}}\'')
        return 2

    print(f"connecting {MCP_URL}")
    async with mcp_client() as client:
        if argv[0] == "--list":
            listed = await client.list_tools()
            for tool in getattr(listed, "tools", listed):
                schema = tool.input_schema or {}
                required = set(schema.get("required", []))
                names = schema.get("properties", {})
                params = [f"{p}{'*' if p in required else ''}" for p in names]
                print(f"- {tool.name}({', '.join(params)})")
            return 0

        result = await client.call_tool(argv[0], args)
        print("is_error:", bool(result.is_error))
        print(_pretty(_text_of(result)))
        return 1 if result.is_error else 0


if __name__ == "__main__":
    sys.exit(asyncio.run(main(sys.argv[1:])))
