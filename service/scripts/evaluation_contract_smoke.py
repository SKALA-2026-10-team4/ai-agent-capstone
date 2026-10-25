"""격리된 service·fixed 주문 Agent에서 #88 REST/MCP 계약을 검증한다. 유료 LLM은 사용하지 않는다."""
import argparse
import json
import time
import uuid
from pathlib import Path
from urllib.error import HTTPError
from urllib.parse import quote, urlparse
from urllib.request import Request, urlopen


def request(base, path, body=None, token=None, extra=None, expected=200):
    """인증 정보는 출력하지 않고 JSON/SSE 응답만 읽는다."""
    headers = {"Accept": "application/json, text/event-stream"}
    if token:
        headers["Authorization"] = f"Bearer {token}"
    headers.update(extra or {})
    data = None if body is None else json.dumps(body).encode()
    if data is not None:
        headers["Content-Type"] = "application/json"
    req = Request(base + path, data=data, headers=headers)
    try:
        response = urlopen(req, timeout=15)
    except HTTPError as error:
        response = error
    raw = response.read().decode()
    assert response.code == expected, (path, response.code, raw[:500])
    if not raw:
        return {}, dict(response.headers)
    if raw.lstrip().startswith(("{", "[")):
        return json.loads(raw), dict(response.headers)
    messages = [json.loads(line[5:].strip()) for line in raw.splitlines() if line.startswith("data:")]
    return messages[-1], dict(response.headers)


def wait_report(service, accepted, token):
    """작업 완료를 기다리고 미완료를 성공으로 취급하지 않는다."""
    for _ in range(120):
        view, _ = request(service, f"/api/evaluations/{accepted['evaluation_id']}", token=token)
        if view["status"] not in ("QUEUED", "RUNNING", "CANCEL_REQUESTED"):
            report, _ = request(service, f"/api/evaluations/{accepted['evaluation_id']}/attempts/{accepted['attempt_id']}/report", token=token)
            return report
        time.sleep(0.25)
    raise AssertionError("평가 완료 시간 초과")


def mcp(service, token, accepted, payload, key, proposal_input, proposal_id):
    """실제 Streamable HTTP에서 새 도구와 기존 도구의 공존을 검사한다."""
    init, headers = request(service, "/mcp", {"jsonrpc": "2.0", "id": 1, "method": "initialize", "params": {
        "protocolVersion": "2025-03-26", "capabilities": {}, "clientInfo": {"name": "evaluation-contract-smoke", "version": "1"}}}, token)
    assert "result" in init, init
    session = next((value for key, value in headers.items() if key.lower() == "mcp-session-id"), None)
    extra = {"Mcp-Session-Id": session, "MCP-Protocol-Version": "2025-03-26"} if session else {}
    request(service, "/mcp", {"jsonrpc": "2.0", "method": "notifications/initialized"}, token, extra, expected=202)
    listed, _ = request(service, "/mcp", {"jsonrpc": "2.0", "id": 2, "method": "tools/list"}, token, extra)
    names = {tool["name"] for tool in listed["result"]["tools"]}
    assert {"run_scenarios", "save_proposal", "apply_patch", "submit_evaluation", "get_evaluation", "get_evaluation_report", "save_proposal_v2"} <= names
    result, _ = request(service, "/mcp", {"jsonrpc": "2.0", "id": 3, "method": "tools/call", "params": {
        "name": "get_evaluation_report", "arguments": {"evaluation_id": accepted["evaluation_id"], "attempt_id": accepted["attempt_id"]}}}, token, extra)
    assert not result.get("error"), result
    assert not result["result"].get("isError"), result
    report = json.loads(result["result"]["content"][0]["text"])
    assert report["verification_status"] == "SERVER_VERIFIED", report
    for number, name, arguments in [(4, "submit_evaluation", {"request": payload, "idempotency_key": key}),
                                     (5, "save_proposal_v2", {"request": proposal_input})]:
        reply, _ = request(service, "/mcp", {"jsonrpc": "2.0", "id": number, "method": "tools/call", "params": {
            "name": name, "arguments": arguments}}, token, extra)
        assert not reply.get("error") and not reply["result"].get("isError"), reply
        content = json.loads(reply["result"]["content"][0]["text"])
        if name == "submit_evaluation":
            assert content["evaluation_id"] == accepted["evaluation_id"]
        else:
            assert content["proposal_id"] == proposal_id
    denied, _ = request(service, "/mcp", {"jsonrpc": "2.0", "id": 6, "method": "tools/call", "params": {
        "name": "get_evaluation", "arguments": {"evaluation_id": accepted["evaluation_id"]}}}, extra=extra)
    assert denied.get("error") or denied.get("result", {}).get("isError"), "인증 없이 새 평가 도구를 사용할 수 있습니다"
    missing_payload = dict(payload, candidate_snapshot_id=str(uuid.uuid4()))
    failed, _ = request(service, "/mcp", {"jsonrpc": "2.0", "id": 7, "method": "tools/call", "params": {
        "name": "submit_evaluation", "arguments": {"request": missing_payload, "idempotency_key": str(uuid.uuid4())}}}, token, extra)
    assert failed["result"].get("isError"), failed
    failure = json.loads(failed["result"]["content"][0]["text"])
    assert failure["code"] == "SNAPSHOT_NOT_FOUND" and failure["upstream_status"] == 404, failure
    return sorted(names)


def main():
    """기존 운영 포트를 거부하고 일회용 서버에서 성공·실패·승인까지 확인한다."""
    parser = argparse.ArgumentParser()
    parser.add_argument("--service", required=True)
    parser.add_argument("--order-agent", required=True)
    parser.add_argument("--environment", required=True)
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()
    for base in (args.service, args.order_agent):
        address = urlparse(base)
        assert address.hostname in ("localhost", "127.0.0.1") and address.port not in (None, 8080, 8081), "격리 포트를 사용해야 합니다"
    login, _ = request(args.service, "/api/auth/login", {"username": "operator", "password": "operator1234"})
    token = login["access_token"]
    inventory_before, _ = request(args.order_agent, "/api/products")
    failed_before, _ = request(args.order_agent, "/api/executions/failed")
    original, _ = request(args.order_agent, "/api/snapshots", {"fixture": "baseline"})
    files = {}
    for file in original["files"]:
        value, _ = request(args.order_agent, f"/api/snapshots/{original['snapshot_id']}/files?path={quote(file['path'], safe='')}")
        assert value["encoding"].lower() == "utf-8"
        files[file["path"]] = value["content"]
    # 알림 단계가 빠진 기준본과 이를 보완한 후보본을 서버에 불변 snapshot으로 발행한다.
    broken = dict(files)
    flow = "skills/order-flow/SKILL.md"
    broken[flow] = broken[flow].replace(", notify]", "]")
    assert broken[flow] != files[flow], "fixture의 flow 형식이 바뀌었습니다"
    baseline, _ = request(args.order_agent, "/api/snapshots/candidates", {"files": broken, "metadata": {"test": "evaluation-88"}})
    candidate, _ = request(args.order_agent, "/api/snapshots/candidates", {"files": files, "metadata": {"test": "evaluation-88"}})
    scenario = json.loads((Path(__file__).resolve().parents[2] / "scenarios/order-agent/golden/g-01.json").read_text())
    cases = []
    for suite in ("REGRESSION", "REPRODUCTION", "INTENT"):
        cases.append({"case_id": suite, "suite": suite, "order": scenario["order"],
                      "baseline_expectation": scenario["expect"], "candidate_expectation": scenario["expect"],
                      "intent_checks": ["notify.sent == true"] if suite == "INTENT" else []})
    registered, _ = request(args.service, "/api/evaluation-sets", {"name": "evaluation-88-smoke", "cases": cases}, token)
    request(args.service, f"/api/evaluation-sets/{registered['evaluation_set_id']}/review", {}, token)
    payload = {"baseline_snapshot_id": baseline["snapshot_id"], "candidate_snapshot_id": candidate["snapshot_id"],
               "evaluation_set_id": registered["evaluation_set_id"], "evaluation_set_version": 1,
               "runner_kind": "ORDER_HARNESS", "model_mode": "FIXED", "repeat": 1,
               "purpose": "CANDIDATE_VERIFICATION", "environment_ref": args.environment,
               "limits": {"wall_time_ms": 120000, "max_total_tokens": 0, "max_cost_usd": "0"}}
    key = str(uuid.uuid4())
    accepted, _ = request(args.service, "/api/evaluations", payload, token, {"Idempotency-Key": key}, 202)
    duplicate, _ = request(args.service, "/api/evaluations", payload, token, {"Idempotency-Key": key}, 202)
    assert duplicate["evaluation_id"] == accepted["evaluation_id"]
    report = wait_report(args.service, accepted, token)
    assert report["status"] == "SUCCEEDED" and report["verification_status"] == "SERVER_VERIFIED", report
    proposal_input = {"evaluation_id": accepted["evaluation_id"], "attempt_id": accepted["attempt_id"], "summary": "알림 단계를 포함한 기능 검증", "intent_text": "배송과 고객 알림을 유지합니다"}
    saved, _ = request(args.service, "/api/verified-proposals", proposal_input, token)
    again, _ = request(args.service, "/api/verified-proposals", proposal_input, token)
    assert saved["proposal_id"] == again["proposal_id"]
    request(args.service, f"/api/proposals/{saved['proposal_id']}/approve", {"candidate_no": 1, "intent_confirmed": True}, token)
    bad_payload = dict(payload, candidate_snapshot_id=baseline["snapshot_id"])
    bad, _ = request(args.service, "/api/evaluations", bad_payload, token, {"Idempotency-Key": str(uuid.uuid4())}, 202)
    bad_report = wait_report(args.service, bad, token)
    assert bad_report["verdict"] == "FAIL" and bad_report["verification_status"] == "NOT_VERIFIED", bad_report
    refused, _ = request(args.service, "/api/verified-proposals", dict(proposal_input, evaluation_id=bad["evaluation_id"], attempt_id=bad["attempt_id"]), token, expected=409)
    assert refused["code"] == "EVIDENCE_MISMATCH"
    names = mcp(args.service, token, accepted, payload, key, proposal_input, saved["proposal_id"])
    inventory_after, _ = request(args.order_agent, "/api/products")
    failed_after, _ = request(args.order_agent, "/api/executions/failed")
    assert inventory_before == inventory_after, "재고가 복원되지 않았습니다"
    assert failed_before == failed_after, "dry-run이 live 실패 목록에 섞였습니다"
    # 토큰·개인정보·전체 trace를 출력 파일에 포함하지 않는다.
    summary = {"success": accepted, "report": report, "failure": bad, "failure_verdict": bad_report["verdict"],
               "proposal_id": saved["proposal_id"], "approval": "approved", "mcp_tools": names,
               "inventory_restored": True, "live_failures_unchanged": True, "llm_api_calls": 0}
    args.output.write_text(json.dumps(summary, ensure_ascii=False, indent=2) + "\n")
    print(json.dumps(summary, ensure_ascii=False))


if __name__ == "__main__":
    main()
