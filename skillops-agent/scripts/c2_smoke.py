"""C-2 연습 공간 도구가 계약대로 동작하는지 확인하는 개발용 스크립트다.

service 가 떠 있어야 한다. MCP 주소는 환경 변수 SKILLOPS_MCP_URL 로 바꾼다(기본
http://localhost:8080/mcp). skillops-agent 폴더에서 다음처럼 실행한다.

    uv run scripts/c2_smoke.py

확인하는 것은 세 단계다.
1. create_workspace 를 인자 없이 불러, w_ 로 시작하는 workspace_id 와 skill 여섯 개의
   skill_versions 를 받는지 본다. 인자가 없으면 운영 skills/ 폴더로 연습 공간을 만든다.
2. run_scenarios 로 g-01 을 기본 반복 2회 돌려, 결과가 하나이고 합격이며 run_id 가 두 개이고
   replay_of_run_id 칸이 null 인지 본다.
3. 읽을 수 없는 확인 조건('payment.status = PAID')을 extra_checks 로 넣으면 isError 결과와
   INVALID_INPUT, retryable=false, '확인 조건을 읽을 수 없습니다' 문구가 오는지 본다.

1단계와 2단계는 B 의 Agent 가 실제로 쓰는 skillops-agent/skillops_agent/tools.py 의 MCPTools 로
불러, 계약 5 v1 이름(B 의 #52 이후)이 맞는지까지 본다. 3단계는 오류 결과를 그대로 보려고 raw MCP
client 로 부른다. 상대편은 service/src/main/java/com/capstone4/skillops/mcp/WorkspaceTools.java 다.
셋 다 맞으면 'C2 SMOKE OK' 를 찍고 0 으로, 아니면 어긋난 단계를 찍고 1 로 끝난다.
"""

import asyncio
import json
import sys
from pathlib import Path

# 스크립트를 직접 실행해도 skillops_agent 패키지를 import 하도록 skillops-agent 폴더를 경로에
# 넣는다.
sys.path.insert(0, str(Path(__file__).resolve().parents[1]))

from skillops_agent.config import MCP_URL  # noqa: E402
from skillops_agent.mcp_client import first_text, mcp_client  # noqa: E402
from skillops_agent.tools import MCPTools  # noqa: E402


def _fail(step: str, detail) -> int:
    """어긋난 단계와 받은 값을 'FAIL [단계]: 값' 으로 찍고 종료 코드 1 을 돌려준다."""
    print(f"FAIL [{step}]: {detail}")
    return 1


async def main() -> int:
    """세 단계를 차례로 확인하고 종료 코드를 돌려준다.

    앞 단계가 어긋나면 뒤 단계는 돌리지 않는다. 3단계는 1단계에서 받은 workspace_id 를 다시 쓴다.
    """
    print(f"connecting {MCP_URL}")
    async with MCPTools() as tools:
        workspace = await tools.create_workspace()
        print("create_workspace ->", workspace)
        workspace_id = workspace.get("workspace_id", "") if isinstance(workspace, dict) else ""
        if not workspace_id.startswith("w_") or len(workspace.get("skill_versions", {})) != 6:
            return _fail("create_workspace", workspace)

        report = await tools.run_scenarios(workspace_id, ["g-01"])
        print("run_scenarios ->", report)
        if not isinstance(report, dict) or report.get("workspace_id") != workspace_id:
            return _fail("run_scenarios", report)
        if report.get("all_passed") is not True or len(report.get("results", [])) != 1:
            return _fail("run_scenarios", report)
        first = report["results"][0]
        if first.get("passed") is not True or len(first.get("run_ids", [])) != 2:
            return _fail("run_scenarios", first)
        if "replay_of_run_id" not in first or first["replay_of_run_id"] is not None:
            return _fail("run_scenarios", first)

    # MCPTools 는 isError 결과를 MCPToolError 로 바꿔 던진다. 원래 is_error 와 본문 글을 함께 보려고
    # 오류 경로는 MCP 클라이언트로 직접 부른다.
    async with mcp_client() as client:
        bad = await client.call_tool(
            "run_scenarios",
            {
                "workspace_id": workspace_id,
                "scenario_ids": ["g-01"],
                "extra_checks": ["payment.status = PAID"],
            },
        )
        text = first_text(bad) or ""
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
