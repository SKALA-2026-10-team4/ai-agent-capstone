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


def context_keys(trace: dict) -> list[str]:
    """실행 기록에서 단계가 받은 값과 내놓은 값의 이름.

    C 가 확인 조건을 볼 때 쓰는 문맥과 같은 범위다.
    """
    keys = set()
    for event in trace.get("events", []):
        data = event_data(event)
        if event.get("kind") == "SKILL_STARTED":
            keys.update((data.get("input") or {}).keys())
        elif event.get("kind") == "SKILL_FINISHED":
            keys.update((data.get("output") or {}).keys())
    return sorted(keys)


# C service/.../scenario/Check.java 와 같은 문법: <값 이름> (==|!=|in) <값>.
# 값은 '글자', true/false, null, 숫자이고, in 뒤에만 [..] 목록이 온다.
_CHECK_LINE = re.compile(r"^\s*(\S+)\s+(==|!=|in)\s+(.+?)\s*$")
_NUMBER = re.compile(r"-?\d+(\.\d+)?")


def _is_literal(raw: str) -> bool:
    value = raw.strip()
    if len(value) >= 2 and value.startswith("'") and value.endswith("'"):
        return True
    return value in ("true", "false", "null") or bool(_NUMBER.fullmatch(value))


def _split_outside_quotes(inner: str) -> list[str]:
    if not inner.strip():
        return []
    parts, current, quoted = [], "", False
    for char in inner:
        if char == "'":
            quoted = not quoted
        if char == "," and not quoted:
            parts.append(current)
            current = ""
        else:
            current += char
    parts.append(current)
    return parts


def check_problem(check: str, known_keys: set[str]) -> str | None:
    """C 가 읽을 수 있고 실행 기록에 있는 값을 보는 조건이면 None, 아니면 버리는 이유."""
    match = _CHECK_LINE.match(check) if isinstance(check, str) else None
    if not match:
        return "형식이 '<값 이름> (==|!=|in) <값>' 이 아닙니다"
    key, op, raw = match.groups()
    if op == "in":
        if not (raw.startswith("[") and raw.endswith("]")):
            return "in 뒤에는 [..] 목록이 와야 합니다"
        if not all(_is_literal(part) for part in _split_outside_quotes(raw[1:-1])):
            return "목록 안의 값을 읽을 수 없습니다"
    elif not _is_literal(raw):
        return "값을 읽을 수 없습니다('글자', true, false, null, 숫자만 됩니다)"
    if key not in known_keys:
        return "실행 기록에 없는 값 이름입니다"
    return None


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
