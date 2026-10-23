"""C 계약 경계의 회귀 테스트다. LLM 이나 실제 service 를 부르지 않는다.

skillops-agent/skillops_agent/contracts.py 에서는 execution_uuid(UUID 와 r_ 별칭만 받는다),
reverse_hunk(단독 hunk 하나만 위치와 문맥을 지키며 뒤집는다), validate_report(빠지거나 겹치거나
어긋난 run_scenarios 응답을 거절한다)를 보호한다. skillops-agent/skillops_agent/tools.py 의
MCPTools 에서는 MCP 오류와 JSON 이 아닌 응답을 정상 결과로 읽지 않는지, 주문 Agent HTTP 경로에서
r_ 별칭을 UUID 로 바꾸고 실패 목록(list_failed_runs)을 조회 조건 없이 부르는지, create_workspace 와
apply_patch 인자 이름을 그대로 보내는지를 보호한다.
"""

from types import SimpleNamespace
from uuid import uuid4

import httpx
import pytest

from skillops_agent.contracts import ContractError, execution_uuid, reverse_hunk, validate_report
from skillops_agent.tools import MCPTools


@pytest.mark.parametrize("value", ["r_bad", "snap_prev", "", None])
def test_invalid_execution_is_rejected(value):
    with pytest.raises(ContractError):
        execution_uuid(value)


def test_reverse_multiline_hunk_preserves_positions_and_context():
    value = (
        "--- a/skills/x/SKILL.md\n+++ b/skills/x/SKILL.md\n"
        "@@ -7,3 +9,4 @@\n context\n-old\n+new\n+extra\n tail\n"
    )
    assert reverse_hunk(value) == "@@ -9,4 +7,3 @@\n context\n+old\n-new\n-extra\n tail\n"


@pytest.mark.parametrize("diff", ["garbage", "", "@@ -1 +1 @@\n-a\n+b\n@@ -4 +4 @@\n-c\n+d\n"])
def test_invalid_ablation_hunks_are_rejected(diff):
    with pytest.raises(ContractError):
        reverse_hunk(diff)


@pytest.mark.asyncio
@pytest.mark.parametrize(
    "result",
    [
        SimpleNamespace(is_error=True, content=[SimpleNamespace(text='{"code":"INVALID_INPUT"}')]),
        SimpleNamespace(is_error=False, content=[SimpleNamespace(text="not JSON")]),
        SimpleNamespace(is_error=False, content=[]),
    ],
)
async def test_mcp_errors_are_not_normal_results(result):
    """is_error 결과, JSON 이 아닌 글, 빈 content 는 모두 ContractError 여야 한다."""

    # call_tool 이 정해 둔 결과를 그대로 돌려주는 MCP client 대역이다.
    class Client:
        async def call_tool(self, *args):
            return result

    tools = MCPTools()
    tools._client = Client()
    with pytest.raises(ContractError):
        await tools._call("run_scenarios", {})


@pytest.mark.asyncio
async def test_http_alias_converts_to_uuid_and_failure_has_no_since():
    """get_trace 는 r_ 별칭을 하이픈 UUID 경로로 바꾸고 실패 목록은 조회 조건 없이 부른다."""
    execution = str(uuid4())
    paths = []

    def respond(req):
        paths.append(str(req.url))
        return httpx.Response(200, json={"events": []})

    tools = MCPTools()
    async with httpx.AsyncClient(
        base_url="http://order", transport=httpx.MockTransport(respond)
    ) as client:
        tools._http = client
        await tools.get_trace("r_" + execution.replace("-", ""))
        await tools.list_failed_runs()
    assert paths == [
        f"http://order/api/executions/{execution}/trace",
        "http://order/api/executions/failed",
    ]


@pytest.mark.asyncio
async def test_workspace_forwards_snapshot_and_patch_names():
    captured = []

    # 받은 도구 이름과 인자를 모으고 workspace 응답을 돌려주는 MCP client 대역이다.
    class Capture:
        async def call_tool(self, name, args):
            captured.append((name, args))
            return SimpleNamespace(
                is_error=False, content=[SimpleNamespace(text='{"workspace_id":"w"}')]
            )

    tools = MCPTools()
    tools._client = Capture()
    await tools.create_workspace(snapshot_id="snapshot")
    await tools.apply_patch("w", [{"skill": "payment", "base_version": 1, "patch": "diff"}])
    assert captured[0] == ("create_workspace", {"snapshot_id": "snapshot"})
    assert captured[1][1]["patches"][0]["patch"] == "diff"


@pytest.mark.parametrize(
    "mutation", ["empty", "duplicate", "wrong_id", "missing_failure", "overall"]
)
def test_incomplete_reports_are_rejected(mutation):
    """빈 결과, 실행 ID 중복, 다른 시나리오, failures 없는 실패, all_passed 불일치를 거절한다."""
    ids = ["r_" + uuid4().hex for _ in range(2)]
    row = {"scenario_id": "g-01", "passed": True, "run_ids": ids, "failures": []}
    report = {"workspace_id": "w", "all_passed": True, "results": [row]}
    if mutation == "empty":
        report["results"] = []
    if mutation == "duplicate":
        row["run_ids"] = [ids[0], ids[0]]
    if mutation == "wrong_id":
        row["scenario_id"] = "g-02"
    if mutation == "missing_failure":
        row["passed"] = False
    if mutation == "overall":
        report["all_passed"] = False
    with pytest.raises(ContractError):
        validate_report(report, "w", ["g-01"], 2)
