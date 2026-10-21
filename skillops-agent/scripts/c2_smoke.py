"""C-2 도구 확인: create_workspace → run_scenarios(g-01) → 잘못된 확인 조건 거부.

service(8080) 가 떠 있어야 한다.

    uv run scripts/c2_smoke.py

셋 다 맞으면 `C2 SMOKE OK` 를 찍고 0, 아니면 어긋난 단계를 찍고 1 로 끝난다.
"""

import asyncio
import json
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


def _fail(step: str, detail) -> int:
    print(f"FAIL [{step}]: {detail}")
    return 1


async def main() -> int:
    print(f"connecting {MCP_URL}")
    async with mcp_client() as client:
        created = await client.call_tool("create_workspace", {})
        text = _text_of(created)
        print("create_workspace ->", text)
        if created.is_error or not text:
            return _fail("create_workspace", text)
        workspace = json.loads(text)
        workspace_id = workspace.get("workspace_id", "")
        if not workspace_id.startswith("w_") or len(workspace.get("skill_versions", {})) != 6:
            return _fail("create_workspace", workspace)

        ran = await client.call_tool(
            "run_scenarios", {"workspace_id": workspace_id, "scenario_ids": ["g-01"]}
        )
        text = _text_of(ran)
        print("run_scenarios ->", text)
        if ran.is_error or not text:
            return _fail("run_scenarios", text)
        report = json.loads(text)
        if report.get("all_passed") is not True or len(report["results"][0]["run_ids"]) != 2:
            return _fail("run_scenarios", report)

        bad = await client.call_tool(
            "run_scenarios",
            {
                "workspace_id": workspace_id,
                "scenario_ids": ["g-01"],
                "extra_checks": ["payment.status = PAID"],
            },
        )
        text = _text_of(bad) or ""
        print("run_scenarios(bad check) ->", text)
        if not bad.is_error or "확인 조건을 읽을 수 없습니다" not in text:
            return _fail("bad check", text)

    print("C2 SMOKE OK")
    return 0


if __name__ == "__main__":
    sys.exit(asyncio.run(main()))
