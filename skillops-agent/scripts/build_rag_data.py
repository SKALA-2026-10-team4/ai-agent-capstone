"""SkillOps 수정안 RAG 데이터(skillops-agent/rag/rag_data.json)를 다시 만드는 스크립트다.

주문 Agent(:8081)가 떠 있어야 한다. 접속 주소는 skillops-agent/skillops_agent/config.py 의
ORDER_AGENT_BASE_URL 이다. skillops-agent 폴더에서 다음처럼 실행한다.

    uv run scripts/build_rag_data.py                       # 게시본으로 성공한 최근 실행을 찾아 쓴다
    uv run scripts/build_rag_data.py --execution-id <UUID> # 실행을 직접 지정한다

도구 계약은 게시본(snapshot metadata.source=published-bundle, 또는 정상본 fixture=baseline)으로
끝까지 성공한(completion=COMPLETED) 실행 하나에서 읽는다. 최근 실행 20건에 그런 실행이 없으면
종료 코드 1 로 끝난다. 그때는 주문 화면(:5174)에서 기본 세트로 정상 주문을 한 번 실행한 뒤 다시
돌린다. 허용 도구·인자·보상 도구는 ToolCatalog.java 에서, 계약 지도와 처리 순서 규칙은 그 실행이 쓴
snapshot 의 skill 목록에서, 처음 주문 입력과 필수 마지막 단계는 OrderFlowPolicy.java 에서 읽는다.
ToolCatalog 와 실행 기록이 어긋나면 WARN 을 찍는다. 데이터 만드는 규칙은
skillops-agent/skillops_agent/rag_data.py 에 있다.

QA 평가셋(qa/run_qa.py)이 만든 제안서는 사례로 쌓지 않는다. 평가 케이스의 정답이 사례집에 들어가
다음 평가 때 RAG 로 새기 때문이다. run_qa 는 후보 snapshot metadata 에 qa_case(케이스 ID)를 남기므로
그 표시로 가려낸다. 사람이 쓴 사례(rag/cases.json)는 qa_cases 칸에 정답이 겹치는 QA 케이스를 적고,
run_qa 가 그 케이스를 평가할 때 사례 id 를 rag_exclude 로 보낸다(qa/README.md 의 RAG 정답 누수).
"""

import argparse
import asyncio
import sys
from pathlib import Path

# 스크립트를 직접 실행해도 skillops_agent 패키지를 import 하도록 skillops-agent 폴더를 경로에
# 넣는다.
sys.path.insert(0, str(Path(__file__).resolve().parents[1]))

import os  # noqa: E402

import httpx  # noqa: E402

from skillops_agent import order_agent, rag_data  # noqa: E402
from skillops_agent.config import SERVICE_URL  # noqa: E402

# 승인·반려된 제안서를 읽을 service 계정이다. 비어 있으면 새 제안서 사례를 읽지 않고, 이미 쌓인
# 사례와 시나리오 사례만 쓴다.
SERVICE_USERNAME = os.getenv("SKILLOPS_RAG_SERVICE_USERNAME", "")
SERVICE_PASSWORD = os.getenv("SKILLOPS_RAG_SERVICE_PASSWORD", "")

# 게시본으로 본다고 판단하는 snapshot metadata 다.
PUBLISHED_SOURCES = {"published-bundle"}
BASELINE_FIXTURE = "baseline"


def is_published(snapshot: dict) -> bool:
    """snapshot 이 게시본 그대로인지 metadata 로 판단한다.

    정상본 fixture 도 게시본을 그대로 복사한 것이라 게시본으로 본다.
    """
    meta = snapshot.get("metadata") or {}
    return meta.get("source") in PUBLISHED_SOURCES or meta.get("fixture") == BASELINE_FIXTURE


async def find_published_run() -> tuple[dict, dict] | None:
    """최근 실행 가운데 게시본으로 끝까지 성공한 실행의 trace 와 snapshot 을 찾는다."""
    for run in (await order_agent.list_recent_runs())["items"]:
        if run.get("completion") != "COMPLETED" or not run.get("snapshot_id"):
            continue
        snapshot = await order_agent.get_snapshot(run["snapshot_id"])
        if is_published(snapshot):
            return await order_agent.get_trace(run["execution_id"]), snapshot
    return None


async def decided_proposals() -> list[dict] | None:
    """service 에 로그인해 승인·반려된 제안서 상세를 읽는다. 계정이 없거나 service 가 없으면 None.

    결정 기록(GET /api/approvals, 최근 50건)과 제안서 목록(GET /api/proposals, 최근 100건)에서
    결정된 제안서 id 를 모은 뒤 GET /api/proposals/{id} 로 상세를 읽는다.
    """
    if not (SERVICE_USERNAME and SERVICE_PASSWORD):
        return None
    try:
        async with httpx.AsyncClient(base_url=SERVICE_URL, timeout=10) as http:
            login = await http.post("/api/auth/login", json={"username": SERVICE_USERNAME,
                                                             "password": SERVICE_PASSWORD})
            login.raise_for_status()
            headers = {"Authorization": f"Bearer {login.json()['access_token']}"}
            ids = {a["proposal_id"] for a in
                   (await http.get("/api/approvals", headers=headers)).json().get("items", [])}
            ids |= {p["proposal_id"] for p in
                    (await http.get("/api/proposals", headers=headers)).json().get("items", [])
                    if p.get("status") in ("approved", "rejected")}
            details = []
            for proposal_id in sorted(ids):
                response = await http.get(f"/api/proposals/{proposal_id}", headers=headers)
                response.raise_for_status()
                details.append(response.json())
            return details
    except httpx.HTTPError as exc:
        print(f"WARN: 제안서를 읽지 못해 시나리오 사례만 씁니다 ({exc})")
        return None


async def without_qa_proposals(details: list[dict]) -> tuple[list[dict], int]:
    """QA 평가셋이 만든 제안서를 빼고 (남은 제안서, 뺀 수)를 돌려준다.

    제안서의 후보 snapshot(proposal.snapshot_id) metadata 에 qa_case 가 있으면 qa/run_qa.py 가
    발행한 평가용 수정본이다. snapshot 을 읽지 못하면 QA 것인지 알 수 없으므로 답 누출을 막으려고
    함께 뺀다. snapshot 번호가 없는 제안서는 그대로 둔다.
    """
    kept, skipped = [], 0
    for detail in details:
        snapshot_id = (detail.get("proposal") or {}).get("snapshot_id")
        if snapshot_id:
            try:
                snapshot = await order_agent.get_snapshot(snapshot_id)
            except order_agent.OrderAgentError as exc:
                print(f"WARN: 제안서 {detail.get('proposal_id')} 의 snapshot 을 읽지 못해 "
                      f"사례로 쓰지 않습니다 ({exc})")
                skipped += 1
                continue
            if (snapshot.get("metadata") or {}).get("qa_case"):
                skipped += 1
                continue
        kept.append(detail)
    return kept, skipped


async def error_phases(details: list[dict]) -> dict[str, str]:
    """제안서의 실패 실행마다 EXECUTION_ERROR 의 phase 를 읽는다. 읽지 못한 실행은 넣지 않는다."""
    phases = {}
    for detail in details:
        execution = ((detail.get("proposal") or {}).get("failure") or {}).get("execution_id")
        if not execution or execution in phases:
            continue
        try:
            trace = await order_agent.get_trace(execution)
        except order_agent.OrderAgentError:
            continue
        for event in trace.get("events", []):
            phase = (event.get("data") or {}).get("phase")
            if event.get("kind") == "EXECUTION_ERROR" and phase:
                phases[execution] = phase
    return phases


async def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument(
        "--execution-id", help="도구 계약을 읽을 성공 실행. 없으면 게시본 성공 실행을 찾는다"
    )
    args = parser.parse_args()

    if args.execution_id:
        trace = await order_agent.get_trace(args.execution_id)
        snapshot = await order_agent.get_snapshot(trace["snapshot_id"])
        if trace.get("completion") != "COMPLETED":
            completion = trace.get("completion")
            print(f"FAIL: {args.execution_id} 는 끝까지 성공한 실행이 아닙니다 ({completion})")
            return 1
        if not is_published(snapshot):
            print(f"WARN: {trace['snapshot_id']} 는 게시본이 아닙니다. "
                  "계약 지도가 게시본과 다를 수 있습니다")
    else:
        found = await find_published_run()
        if found is None:
            print("FAIL: 최근 실행 20건에 게시본으로 성공한 실행이 없습니다. "
                  "주문 화면(:5174)에서 기본 세트로 정상 주문을 한 번 실행한 뒤 다시 실행하세요")
            return 1
        trace, snapshot = found

    listing = await order_agent.list_skills(trace["snapshot_id"])
    if listing.get("partial") or listing.get("parse_errors"):
        print(f"FAIL: snapshot {trace['snapshot_id']} 의 skill 목록이 완전하지 않습니다")
        return 1
    policy_source = rag_data.FLOW_POLICY_PATH.read_text(encoding="utf-8")
    constants = rag_data.read_flow_policy_constants(policy_source)
    catalog = rag_data.read_tool_catalog(rag_data.TOOL_CATALOG_PATH.read_text(encoding="utf-8"))
    # 운영자가 결정한 제안서를 사례로 쌓는다. 계정이 없으면 지난번에 쌓은 사례만 쓴다.
    details = await decided_proposals()
    new_cases = []
    if details is None:
        print("INFO: SKILLOPS_RAG_SERVICE_USERNAME/PASSWORD 가 없어 새 제안서 사례는 읽지 않습니다")
    else:
        details, skipped = await without_qa_proposals(details)
        if skipped:
            print(f"INFO: QA 평가셋이 만들었거나 확인할 수 없는 제안서 {skipped}건은 "
                  "사례로 쓰지 않습니다")
        new_cases = rag_data.cases_from_proposals(details, await error_phases(details))
    proposal_cases = rag_data.merge_proposal_cases(new_cases) if (
        new_cases or rag_data.PROPOSAL_CASES_PATH.exists()) else []
    print(f"제안서 사례: 이번에 읽은 {len(new_cases)}건, 쌓인 전체 {len(proposal_cases)}건")
    data, warnings = rag_data.build(
        trace, snapshot, listing["items"], constants, catalog,
        rag_data.load_guides(), rag_data.load_policies(),
        rag_data.load_cases() + proposal_cases,
    )
    path = rag_data.write(data)
    # ToolCatalog 와 실행 기록이 어긋나면 데이터는 저장하되 알린다. 반환 값이 빈 도구 계약이 생길 수
    # 있다.
    for warning in warnings:
        print(f"WARN: {warning}")

    kinds = {}
    for item in data["items"]:
        kinds[item["kind"]] = kinds.get(item["kind"], 0) + 1
    print(f"저장: {path}")
    bundle = data["bundle"]
    print(f"실행 {data['source_execution_id']}, "
          f"snapshot {bundle['snapshot_id']} ({bundle['source']})")
    print("항목:", ", ".join(f"{k} {v}개" for k, v in kinds.items()))
    return 0


if __name__ == "__main__":
    sys.exit(asyncio.run(main()))
