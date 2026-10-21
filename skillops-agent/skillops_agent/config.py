"""환경 변수 기반 설정. 루트의 .env 를 읽는다 (.env.example 참고)."""

import os

from dotenv import find_dotenv, load_dotenv

load_dotenv(find_dotenv(usecwd=True))

MODEL = os.getenv("SKILLOPS_MODEL", "gpt-4.1-mini")
SERVICE_URL = os.getenv("SKILLOPS_SERVICE_URL", "http://localhost:8080")
MCP_URL = os.getenv("SKILLOPS_MCP_URL", f"{SERVICE_URL}/mcp")
ORDER_AGENT_URL = os.getenv("SKILLOPS_ORDER_AGENT_URL", "http://localhost:8081")

# 주문 Agent(order-agent/) 진단용 조회 API. 실행 기록·실패 목록·skill 목록·본문을 직접 부른다.
ORDER_AGENT_BASE_URL = os.getenv("ORDER_AGENT_BASE_URL", "http://localhost:8081")

# /diagnose 엔드포인트 보호용 토큰. 비어 있으면 검증 건너뜀(개발 모드).
SERVICE_TOKEN = os.getenv("SKILLOPS_SERVICE_TOKEN", "")

# true 이면 service(:8080) 없이 stub 응답으로 개발한다.
USE_STUB_MCP = os.getenv("SKILLOPS_USE_STUB_MCP", "true").lower() == "true"
