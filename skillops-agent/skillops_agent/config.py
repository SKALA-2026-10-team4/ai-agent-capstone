"""환경 변수 기반 설정. 루트의 .env 를 읽는다 (.env.example 참고)."""

import os

from dotenv import find_dotenv, load_dotenv

load_dotenv(find_dotenv(usecwd=True))

MODEL = os.getenv("SKILLOPS_MODEL", "claude-opus-5-5")
SERVICE_URL = os.getenv("SKILLOPS_SERVICE_URL", "http://localhost:8080")
MCP_URL = os.getenv("SKILLOPS_MCP_URL", f"{SERVICE_URL}/mcp")
