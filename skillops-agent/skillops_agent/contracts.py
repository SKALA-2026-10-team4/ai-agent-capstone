"""Adapters and validation for the current C MCP and order-agent HTTP contracts."""

from __future__ import annotations

import re
from uuid import UUID


class ContractError(ValueError):
    pass


def execution_uuid(value: str) -> str:
    try:
        raw = value[2:] if value.startswith("r_") else value
        return str(UUID(raw))
    except (ValueError, AttributeError, TypeError) as exc:
        raise ContractError("execution_id must be a UUID or r_ + UUID hex") from exc


def event_data(event: dict) -> dict:
    value = event.get("data")
    if not isinstance(value, dict):
        raise ContractError("trace event.data must be an object")
    return value


def trace_context(trace: dict) -> dict:
    context = {}
    for event in trace.get("events", []):
        data = event_data(event)
        if event.get("kind") == "SKILL_FINISHED":
            context.update(data.get("output", {}))
    return context


def tools_called(trace: dict) -> list[str]:
    return [
        event_data(e)["tool"]
        for e in trace.get("events", [])
        if e.get("kind") == "TOOL_REQUEST" and "tool" in event_data(e)
    ]


def validate_report(
    report: dict, workspace_id: str, scenario_ids: list[str], repeat: int
) -> list[dict]:
    if not isinstance(report, dict) or report.get("workspace_id") != workspace_id:
        raise ContractError("run_scenarios workspace_id mismatch")
    results = report.get("results")
    if not isinstance(results, list) or len(results) != len(scenario_ids):
        raise ContractError("run_scenarios did not return every planned scenario")
    for expected, result in zip(scenario_ids, results, strict=True):
        if result.get("scenario_id") != expected or type(result.get("passed")) is not bool:
            raise ContractError("run_scenarios scenario_id/passed mismatch")
        ids = result.get("run_ids")
        if not isinstance(ids, list) or len(ids) != repeat or len(set(ids)) != repeat:
            raise ContractError("run_scenarios missing or duplicate trial evidence")
        for run_id in ids:
            execution_uuid(run_id)
        failures = result.get("failures")
        if not isinstance(failures, list) or result["passed"] != (not failures):
            raise ContractError("run_scenarios failures/passed mismatch")
        if any(not isinstance(f, dict) or f.get("run_id") not in ids for f in failures):
            raise ContractError("failure does not reference an executed trial")
    if type(report.get("all_passed")) is not bool or report["all_passed"] != all(
        r["passed"] for r in results
    ):
        raise ContractError("run_scenarios all_passed mismatch")
    return results


def reverse_hunk(diff: str) -> str:
    """Reverse C's standalone unified hunk, preserving context and line coordinates."""
    output = []
    headers = 0
    for line in diff.splitlines():
        if line.startswith(("--- ", "+++ ")):
            continue  # C PatchApplier accepts headerless hunks.
        match = re.fullmatch(r"@@ -(\d+(?:,\d+)?) \+(\d+(?:,\d+)?) @@(.*)", line)
        if match:
            headers += 1
            output.append(f"@@ -{match[2]} +{match[1]} @@{match[3]}")
        elif line.startswith("+"):
            output.append("-" + line[1:])
        elif line.startswith("-"):
            output.append("+" + line[1:])
        elif line.startswith((" ", "\\")):
            output.append(line)
        else:
            raise ContractError("invalid unified hunk line")
    if headers != 1:
        raise ContractError("ablation requires one standalone C hunk")
    return "\n".join(output) + "\n"
