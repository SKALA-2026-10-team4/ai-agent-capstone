"""실제 REST·MCP·SQLite로 partial 증거 수집을 검증한다. 운영 Skill과 주문은 수정하지 않는다.

service는 별도 테스트 DB로 실행한다. 이 스크립트는 테스트 snapshot을 게시하고 임시 진단 서버를
띄운다. 도구 응답은 대체하지 않는다. 경계의 감사 기록과 금지 호출 차단만 추가한다.
"""

import argparse
import asyncio
import hashlib
import json
import os
import re
import socket
import sqlite3
import subprocess
import sys
import time
from collections import Counter
from datetime import datetime
from pathlib import Path
from zoneinfo import ZoneInfo

import httpx

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))


def require(condition, message):
    """실서버 검증 실패를 최적화 옵션과 관계없이 명시적으로 중단한다."""
    if not condition:
        raise RuntimeError(message)


def digest(value):
    """불변량의 JSON 또는 원본 바이트 SHA-256을 계산한다."""
    raw = value if isinstance(value, bytes) else json.dumps(value, sort_keys=True).encode()
    return hashlib.sha256(raw).hexdigest()


def serve(args):
    """실제 진단 앱을 감사용 경계와 함께 띄운다. 분석·수집 함수는 원래 구현을 사용한다."""
    import uvicorn

    from skillops_agent import nodes
    from skillops_agent.config import USE_STUB_MCP
    from skillops_agent.main import app
    from skillops_agent.tools import MCPTools

    require(not USE_STUB_MCP, "real MCP is required")

    def audit(kind, **values):
        """인증 정보·본문을 제외한 실제 호출 이름을 로컬 감사 파일에 기록한다."""
        with args.audit.open("a") as output:
            output.write(json.dumps({"kind": kind, **values}) + "\n")

    class ObservedTools(MCPTools):
        """응답을 대체하지 않고 실제 HTTP/MCP 경계의 호출을 기록한다."""

        async def __aenter__(self):
            """기존 연결을 열고 주문 Agent의 HTTP 요청 감사를 추가한다."""
            await super().__aenter__()

            async def request_hook(request):
                """조회 요청만 허용하고 호출 경로를 기록한다."""
                audit("order_http", method=request.method, path=request.url.path)
                require(request.method == "GET", "unexpected mutating order API")

            self._http.event_hooks["request"].append(request_hook)
            return self

        async def _call(self, name, arguments):
            """실제 diff만 호출하고 자동 수정·실행 관련 도구는 시도 시 실패시킨다."""
            audit("mcp_tool", name=name)
            require(name == "diff_skill", f"unexpected MCP tool: {name}")
            return await super()._call(name, arguments)

    def forbidden_llm(*unused, **kwargs):
        """LLM 호출 시도를 기록하고 외부 모델을 호출하기 전에 중단한다."""
        audit("llm_attempt")
        raise RuntimeError("unexpected LLM call")

    nodes.get_tools = ObservedTools
    nodes.make_structured_llm = forbidden_llm
    audit("runtime", stub=False)
    uvicorn.run(app, host="127.0.0.1", port=args.agent_port, log_level="warning")


async def wait_health(client, url, process):
    """자식 진단 서버의 준비를 유한 시간 동안 확인한다."""
    deadline = time.monotonic() + 30
    while time.monotonic() < deadline:
        require(process.poll() is None, "diagnosis server exited; inspect agent.log")
        try:
            if (await client.get(url + "/health")).is_success:
                return
        except httpx.TransportError:
            pass
        await asyncio.sleep(0.2)
    raise RuntimeError("diagnosis server startup timed out")


async def request_json(client, method, url, **kwargs):
    """실제 HTTP 요청의 상태를 검사하고 JSON을 읽는다."""
    response = await client.request(method, url, **kwargs)
    response.raise_for_status()
    return response.json()


def stop(process):
    """이 스크립트가 만든 진단 서버만 종료한다."""
    if process is not None and process.poll() is None:
        process.terminate()
        try:
            process.wait(timeout=10)
        except subprocess.TimeoutExpired:
            process.kill()
            process.wait(timeout=5)


async def smoke(args):
    """실제 snapshot 세 종류를 진단하고 저장·중계·재시작·호출 불변량을 확인한다."""
    require(os.getenv("SKILLOPS_SERVICE_TOKEN"), "set an isolated test service token")
    # 기존 진단 서버에 실수로 요청을 보내지 않도록 자식 서버 전용 포트를 먼저 확인한다.
    with socket.socket() as probe:
        probe.bind(("127.0.0.1", args.agent_port))
    require(not args.runtime.exists(), "runtime directory must be new")
    args.runtime.mkdir(parents=True)
    args.audit = args.runtime / "calls.jsonl"
    db = args.runtime / "runs.sqlite3"
    agent_url = f"http://127.0.0.1:{args.agent_port}"
    headers = {"X-Service-Token": os.environ["SKILLOPS_SERVICE_TOKEN"]}
    child_env = {
        **os.environ,
        "SKILLOPS_USE_STUB_MCP": "false",
        "SKILLOPS_MCP_URL": args.service_url + "/mcp",
        "SKILLOPS_ORDER_AGENT_URL": args.order_url,
        "ORDER_AGENT_BASE_URL": args.order_url,
        "SKILLOPS_RUN_DB": str(db),
        "OPENAI_API_KEY": "not-set",
        "LANGCHAIN_TRACING_V2": "false",
        "LANGSMITH_TRACING": "false",
    }
    child_command = [
        sys.executable,
        str(Path(__file__).resolve()),
        "--serve",
        "--audit",
        str(args.audit),
        "--agent-port",
        str(args.agent_port),
    ]
    process = None
    log = (args.runtime / "agent.log").open("a")
    records = []
    try:
        async with httpx.AsyncClient(timeout=30) as client:
            await request_json(client, "GET", args.service_url + "/api/health")
            stocks_before = await request_json(client, "GET", args.order_url + "/api/products")
            executions_before = await request_json(
                client, "GET", args.order_url + "/api/executions"
            )
            failures_before = await request_json(
                client, "GET", args.order_url + "/api/executions/failed"
            )
            baseline = await request_json(
                client, "POST", args.order_url + "/api/snapshots", json={"fixture": "baseline"}
            )
            baseline_id = baseline["snapshot_id"]
            files = {}
            for entry in baseline["files"]:
                source = await request_json(
                    client,
                    "GET",
                    args.order_url + f"/api/snapshots/{baseline_id}/files",
                    params={"path": entry["path"]},
                )
                require(source["encoding"] == "utf-8", "baseline must be UTF-8")
                require(
                    digest(source["content"].encode()) == entry["hash"], "baseline hash differs"
                )
                files[entry["path"]] = source["content"]
            normal_listing = await request_json(
                client, "GET", args.order_url + f"/api/snapshots/{baseline_id}/skills"
            )
            require(normal_listing["partial"] is False, "baseline listing must be complete")
            login = await request_json(
                client,
                "POST",
                args.service_url + "/api/auth/login",
                json={"username": "operator", "password": "operator1234"},
            )
            user_headers = {"Authorization": "Bearer " + login["access_token"]}
            process = subprocess.Popen(child_command, env=child_env, stdout=log, stderr=log)
            await wait_health(client, agent_url, process)
            require(
                (await client.get(agent_url + "/runs")).status_code == 401, "token not enforced"
            )
            path = "skills/inventory-check/SKILL.md"
            source = files[path]
            variants = {
                "missing-owner": re.sub(r"(?m)^owner:[^\r\n]*\r?\n", "", source, count=1),
                "broken-yaml": re.sub(r"(?m)^outputs:[^\r\n]*", "outputs: [", source, count=1),
                "missing-version": re.sub(r"(?m)^version:[^\r\n]*\r?\n", "", source, count=1),
            }
            for name, text in variants.items():
                require(text != source, f"fixture field missing: {name}")
                candidate = await request_json(
                    client,
                    "POST",
                    args.order_url + "/api/snapshots/candidates",
                    json={
                        "files": {**files, path: text},
                        "metadata": {
                            "baseline_snapshot_id": baseline_id,
                            "source_test": "partial-evidence-72",
                        },
                    },
                )
                candidate_id = candidate["snapshot_id"]
                listing = await request_json(
                    client, "GET", args.order_url + f"/api/snapshots/{candidate_id}/skills"
                )
                require(listing["partial"] is True, f"not partial: {name}")
                require(len(listing["parse_errors"]) == 1, "expected exactly one parse error")
                strict = await client.get(
                    args.order_url + f"/api/snapshots/{candidate_id}/skills/inventory-check"
                )
                require(strict.status_code == 400, "strict Skill read must reject invalid metadata")
                accepted = await request_json(
                    client,
                    "POST",
                    args.service_url + "/internal/skill-changes",
                    headers=headers,
                    json={
                        "type": "skill_saved",
                        "change_id": f"partial-smoke-{candidate_id}",
                        "candidate_snapshot_id": candidate_id,
                        "baseline_snapshot_id": baseline_id,
                        "changed_paths": [path],
                    },
                )
                thread_id = accepted["thread_id"]
                deadline = time.monotonic() + 60
                while True:
                    state = await request_json(
                        client, "GET", agent_url + f"/runs/{thread_id}/events", headers=headers
                    )
                    if state["diagnosis_status"] not in ("QUEUED", "RUNNING"):
                        break
                    require(time.monotonic() < deadline, "diagnosis timed out")
                    await asyncio.sleep(0.2)
                evidence = state.get("evidence") or {}
                require(state["diagnosis_status"] == "ERROR", "partial must finish as ERROR")
                require(
                    evidence.get("collection_status") == "COLLECTED",
                    f"incomplete collection: {state.get('error')}",
                )
                require(evidence["collection_errors"] == [], "unexpected collection error")
                require(evidence["metadata"] == listing, "metadata/error evidence differs")
                require(
                    state["proposal"] is None and state["candidates"] == [],
                    "candidate was generated",
                )
                require(
                    [e["node"] for e in state["events"]]
                    == [
                        "init",
                        "fetch_evidence",
                        "collect_partial_sources",
                        "collect_partial_diffs",
                    ],
                    "unexpected diagnosis route",
                )
                expected_files = {baseline_id: files, candidate_id: {**files, path: text}}
                expected_count = sum(p.endswith("/SKILL.md") for p in files) * 2
                require(len(evidence["sources"]) == expected_count, "source evidence missing")
                for item in evidence["sources"]:
                    expected = expected_files[item["snapshot_id"]][item["path"]]
                    require(item["content"] == expected, "source text changed")
                    require(digest(expected.encode()) == item["hash"], "source hash differs")
                require(len(state["diff"]["files"]) == 1 and bool(state["changes"]), "diff missing")
                diff = state["diff"]["files"][0]
                if name == "broken-yaml":
                    require(bool(diff["frontmatter_parse_error"]), "YAML error evidence missing")
                proxied = await request_json(
                    client,
                    "GET",
                    args.service_url + f"/api/diagnoses/{thread_id}",
                    headers=user_headers,
                )
                require(proxied["evidence"] == evidence, "service detail proxy lost evidence")
                recent = await request_json(
                    client, "GET", args.service_url + "/api/diagnoses", headers=user_headers
                )
                require(
                    all("evidence" not in row for row in recent["items"]), "raw evidence in list"
                )
                with sqlite3.connect(db) as connection:
                    row = connection.execute(
                        "SELECT state_json FROM diagnosis_runs WHERE id=?", (thread_id,)
                    ).fetchone()
                require(json.loads(row[0])["evidence"] == evidence, "SQLite evidence differs")
                records.append(
                    {
                        "case": name,
                        "snapshot_id": candidate_id,
                        "thread_id": thread_id,
                        "source_count": len(evidence["sources"]),
                        "hunk_count": len(state["changes"]),
                        "parse_error_codes": [e["code"] for e in listing["parse_errors"]],
                        "frontmatter_parse_error": bool(diff.get("frontmatter_parse_error")),
                        "evidence_hash": digest(evidence),
                    }
                )
            stop(process)
            process = subprocess.Popen(child_command, env=child_env, stdout=log, stderr=log)
            await wait_health(client, agent_url, process)
            for record in records:
                state = await request_json(
                    client,
                    "GET",
                    agent_url + f"/runs/{record['thread_id']}/events",
                    headers=headers,
                )
                require(
                    digest(state["evidence"]) == record["evidence_hash"], "restart lost evidence"
                )
                record["persisted_after_restart"] = True
            stocks_after = await request_json(client, "GET", args.order_url + "/api/products")
            executions_after = await request_json(client, "GET", args.order_url + "/api/executions")
            failures_after = await request_json(
                client, "GET", args.order_url + "/api/executions/failed"
            )
            require(stocks_before == stocks_after, "product inventory changed")
            require(executions_before == executions_after, "order execution list changed")
            require(failures_before == failures_after, "failed execution list changed")
        calls = [json.loads(line) for line in args.audit.read_text().splitlines()]
        tools = Counter(c["name"] for c in calls if c["kind"] == "mcp_tool")
        require(dict(tools) == {"diff_skill": 3}, "unexpected MCP tool calls")
        require(not any(c["kind"] == "llm_attempt" for c in calls), "LLM was attempted")
        report = {
            "verified_at": datetime.now(ZoneInfo("Asia/Seoul")).isoformat(),
            "baseline_snapshot_id": baseline_id,
            "normal_skill_count": len(normal_listing["items"]),
            "cases": records,
            "mcp_tool_calls": dict(tools),
            "llm_attempts": 0,
            "product_inventory_unchanged": True,
            "execution_list_unchanged": True,
            "failed_execution_list_unchanged": True,
            "service_proxy_verified": True,
            "token_enforced": True,
            "runtime_directory": str(args.runtime),
        }
        args.output.parent.mkdir(parents=True, exist_ok=True)
        args.output.write_text(json.dumps(report, ensure_ascii=False, indent=2) + "\n")
        print(json.dumps(report, ensure_ascii=False, indent=2))
    finally:
        stop(process)
        log.close()


def arguments():
    """로컬 서버 주소와 산출물 위치를 명시적으로 받는다."""
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--serve", action="store_true", help=argparse.SUPPRESS)
    parser.add_argument("--audit", type=Path, help=argparse.SUPPRESS)
    parser.add_argument("--order-url", default="http://127.0.0.1:8081")
    parser.add_argument("--service-url", default="http://127.0.0.1:18072")
    parser.add_argument("--agent-port", type=int, default=18073)
    parser.add_argument("--runtime", type=Path)
    parser.add_argument("--output", type=Path)
    args = parser.parse_args()
    if not args.serve:
        if not args.runtime or not args.output:
            parser.error("--runtime and --output are required")
        args.runtime = args.runtime.resolve()
        args.output = args.output.resolve()
    return args


if __name__ == "__main__":
    options = arguments()
    if options.serve:
        serve(options)
    else:
        asyncio.run(smoke(options))
