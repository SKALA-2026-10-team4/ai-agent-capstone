"""service 의 MCP 서버(Spring AI, Streamable HTTP, POST /mcp)에 붙는 클라이언트.

mcp 2.x 의 고수준 `mcp.Client` 를 쓴다. URL 문자열을 주면 Streamable HTTP 로 연결한다.

    async with mcp_client() as client:
        tools = await client.list_tools()
        result = await client.call_tool("ping", {})
"""

from mcp import Client

from .config import MCP_URL


def mcp_client(url: str = MCP_URL) -> Client:
    return Client(url)
