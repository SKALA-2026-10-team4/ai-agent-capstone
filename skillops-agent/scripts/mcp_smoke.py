"""Python 진단 Agent 와 Java service 사이의 MCP 배선을 확인하는 개발용 스크립트다.

service 가 떠 있어야 한다. 접속 주소는 skillops-agent/skillops_agent/config.py 의 MCP_URL 이며, 환경
변수 SKILLOPS_MCP_URL 로 바꾼다(기본 http://localhost:8080/mcp). skillops-agent 폴더에서 다음처럼
실행한다.

    uv run scripts/mcp_smoke.py

도구 목록을 찍고 ping 을 부른다. 상대편은
service/src/main/java/com/capstone4/skillops/mcp/PingTools.java 의 ping() 이다. ping 이 목록에
없거나 응답이 pong 으로 시작하지 않으면 종료 코드 1 로, 맞으면 0 으로 끝난다.
skillops-agent/README.md 와 루트 README.md 가 배선 확인 방법으로 이 스크립트를 안내한다.
"""

import asyncio
import sys
from pathlib import Path

# 스크립트를 직접 실행해도 skillops_agent 패키지를 import 하도록 skillops-agent 폴더를 경로에
# 넣는다.
sys.path.insert(0, str(Path(__file__).resolve().parents[1]))

from skillops_agent.config import MCP_URL  # noqa: E402
from skillops_agent.mcp_client import first_text, mcp_client  # noqa: E402


async def main() -> int:
    """MCP 서버에 붙어 도구 목록을 찍고 ping 을 불러 종료 코드를 돌려준다.

    list_tools 결과가 tools 속성을 가진 객체이든 목록이든 읽는다. 응답 글은
    skillops-agent/skillops_agent/mcp_client.py 의 first_text 로 읽는다.
    """
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
        text = first_text(result)
        print("ping ->", text)
        return 0 if text and text.startswith("pong") else 1


if __name__ == "__main__":
    sys.exit(asyncio.run(main()))
