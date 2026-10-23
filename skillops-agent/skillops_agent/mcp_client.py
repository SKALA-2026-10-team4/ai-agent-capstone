"""service 의 MCP 서버(Spring AI, Streamable HTTP, POST /mcp)에 붙는 클라이언트를 만든다.

mcp 2.x 의 고수준 mcp.Client 를 쓴다. URL 문자열을 주면 Streamable HTTP 로 연결한다. 상대편 도구는
service/src/main/java/com/capstone4/skillops/mcp/ 의 @McpTool 메서드다. 주소 기본값은
skillops-agent/skillops_agent/config.py 의 MCP_URL 이다. 환경 변수 SKILLOPS_MCP_URL 이 있으면 그
값을, 없으면 SKILLOPS_SERVICE_URL(기본 http://localhost:8080) 뒤에 /mcp 를 붙인 값을 쓴다.

skillops-agent/skillops_agent/tools.py 의 MCPTools 가 이 모듈로 세션을 열고 응답 글을 읽는다.
skillops-agent/scripts/ 의 c2_real_judge.py, c2_smoke.py, mcp_call.py, mcp_smoke.py 는 MCPTools 의
해석을 거치지 않고 원래 결과를 보려고 이 모듈을 직접 쓴다.

사용 예는 다음과 같다.

    async with mcp_client() as client:
        tools = await client.list_tools()
        result = await client.call_tool("ping", {})
"""

from mcp import Client

from .config import MCP_URL


def mcp_client(url: str = MCP_URL) -> Client:
    """url 의 MCP 서버에 붙는 mcp.Client 를 만든다.

    연결은 async with 로 들어갈 때 열리고 나올 때 닫힌다. 기본값 MCP_URL 은 이 모듈을 import 할 때
    정해지므로, 테스트는 이 함수를 쓰는 쪽(skillops-agent/skillops_agent/tools.py 가 import 한
    mcp_client)을 바꿔 끼운다.
    """
    return Client(url)


def first_text(result) -> str | None:
    """CallToolResult 의 content 블록 중 처음으로 비어 있지 않은 text 를 돌려준다.

    text 속성이 없는 블록과 빈 text 는 건너뛰고, 읽을 글이 없으면 None 을 돌려준다. content 가
    없거나 None 이어도 None 이다. service 의 MCP 도구는 결과와 오류를 JSON 글 한 덩어리로 담아
    보내므로(service/src/main/java/com/capstone4/skillops/mcp/ToolResults.java) 첫 글만 읽으면
    된다. skillops-agent/skillops_agent/tools.py 의 _error_payload 와 _success_payload, 그리고
    skillops-agent/scripts/ 의 스크립트 네 개가 이 함수를 쓴다.
    """
    for block in getattr(result, "content", []) or []:
        text = getattr(block, "text", None)
        if text:
            return text
    return None
