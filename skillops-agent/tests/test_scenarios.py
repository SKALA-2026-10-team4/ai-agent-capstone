"""C contract boundary regression tests: no LLM or live service calls."""

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
    class Client:
        async def call_tool(self, *args):
            return result

    tools = MCPTools()
    tools._client = Client()
    with pytest.raises(ContractError):
        await tools._call("run_scenarios", {})


@pytest.mark.asyncio
async def test_http_alias_converts_to_uuid_and_failure_has_no_since():
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
