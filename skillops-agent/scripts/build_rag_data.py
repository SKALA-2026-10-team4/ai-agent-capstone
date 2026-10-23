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
"""

import argparse
import asyncio
import sys
from pathlib import Path

# 스크립트를 직접 실행해도 skillops_agent 패키지를 import 하도록 skillops-agent 폴더를 경로에
# 넣는다.
sys.path.insert(0, str(Path(__file__).resolve().parents[1]))

from skillops_agent import order_agent, rag_data  # noqa: E402

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
    data, warnings = rag_data.build(
        trace, snapshot, listing["items"], constants, catalog,
        rag_data.load_guides(), rag_data.load_policies(), rag_data.load_cases(),
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
