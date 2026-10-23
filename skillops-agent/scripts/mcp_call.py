"""MCP 도구 하나를 터미널에서 직접 불러 본다. service(8080) 가 떠 있어야 한다.

    uv run scripts/mcp_call.py --list
    uv run scripts/mcp_call.py create_workspace '{}'
    uv run scripts/mcp_call.py run_scenarios '{"ws_id": "w_...", "scenario_ids": ["g-01"]}'

--list 는 도구 이름과 받는 값을 보여 준다(* 는 꼭 넣어야 하는 값).
결과가 JSON 이면 보기 좋게 펼쳐 찍는다. 도구가 오류를 돌려주면 종료 코드 1 로 끝난다.
"""

# 위 모듈 docstring 은 main 이 print(__doc__) 로 찍는 도움말이다. 글을 고치면 도움말 출력이
# 바뀌므로 개발자용 설명은 이 주석에 둔다. MCP 도구 하나를 터미널에서 직접 불러 보는 개발용
# 스크립트다. 접속 주소는 skillops-agent/skillops_agent/config.py 의 MCP_URL 이며 환경 변수
# SKILLOPS_MCP_URL 로 바꾼다. 도구는 service/src/main/java/com/capstone4/skillops/mcp/ 의 @McpTool
# 메서드다. skillops-agent/skillops_agent/tools.py 의 MCPTools 해석을 거치지 않으므로, 도구가
# 돌려준 원래 글과 is_error 를 그대로 볼 수 있다. 두 번째 인자가 JSON 이 아니면 종료 코드 2 로
# 끝난다. 도움말 예시의 ws_id 는 예전 이름이고, 지금 run_scenarios 는 workspace_id 를 받는다.

import asyncio
import json
import sys
from pathlib import Path

# 스크립트를 직접 실행해도 skillops_agent 패키지를 import 하도록 skillops-agent 폴더를 경로에
# 넣는다.
sys.path.insert(0, str(Path(__file__).resolve().parents[1]))

from skillops_agent.config import MCP_URL  # noqa: E402
from skillops_agent.mcp_client import first_text, mcp_client  # noqa: E402


def _pretty(text: str) -> str:
    """text 가 JSON 이면 두 칸 들여쓰기로 펼친 글을, 아니면 받은 글을 그대로 돌려준다.

    한글은 이스케이프하지 않는다. main 이 도구 결과를 찍을 때 쓴다.
    """
    try:
        return json.dumps(json.loads(text), ensure_ascii=False, indent=2)
    except json.JSONDecodeError:
        return text


async def main(argv: list[str]) -> int:
    """명령줄 인자대로 MCP 도구 하나를 부르거나 도구 목록을 찍고 종료 코드를 돌려준다.

    인자가 없거나 -h, --help 면 모듈 docstring 을 찍고 0 이다. 두 번째 인자가 JSON 이 아니면 2,
    도구가 오류를 돌려주면 1, 그 밖에는 0 이다. --list 는 도구마다 input_schema 의 properties 이름을
    찍고, required 에 든 이름에 * 를 붙인다. 결과 글은
    skillops-agent/skillops_agent/mcp_client.py 의 first_text 로 읽는다.
    """
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
        print(_pretty(first_text(result) or ""))
        return 1 if result.is_error else 0


if __name__ == "__main__":
    sys.exit(asyncio.run(main(sys.argv[1:])))
