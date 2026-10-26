"""LLM 결정만 대역으로 바꾸고 B 진단 graph 를 실제 C MCP 와 주문 Agent 로 돌리는 통합 스모크다.

service 가 harness 실행기와 캠핑 기준 주문 g-01~g-06(scenarios/order-agent)으로 떠 있어야 하고, 주문
Agent 는 모델 모드 fixed 로 떠 있어야 한다. 기준 주문이 g-01~g-03 뿐인 환경이면
SKILLOPS_GOLDEN_IDS=g-01,g-02,g-03 을 준다. service 에 SKILLOPS_SERVICE_TOKEN 이 있으면 같은 값을
환경 변수로 준다(MCP 요청에 X-Service-Token 을 붙인다). skillops-agent 폴더에서 다음처럼 실행한다.

    SKILLOPS_USE_STUB_MCP=false uv run scripts/b_c_contract_smoke.py

snapshot 과 모의 실행(dry-run) 기록은 만들지만 유료 모델과 실제 주문은 부르지 않는다. 실행 뒤에
상품 재고와 운영 실패 목록이 그대로인지 확인한다. skillops-agent/docs/c-contract-integration.md 가
이 실험을 설명한다.

graph 를 세 번 돌려 다음을 확인한다.
1. 첫 실행은 broken-contract snapshot 의 모의 실행 실패(F1, type=failure)를 진단해, 모든 후보가
   유효하고 제안서가 SAVED 로 저장되는지 본다. 이 실행에서만
   skillops-agent/skillops_agent/nodes.py 의 make_structured_llm 을 fake_llm 으로 바꾼다.
2. 두 번째 실행은 inventory-check 의 description 만 고친 정상 저장본(type=skill_saved)이
   VALIDATED 로 끝나는지 본다.
3. 세 번째 실행은 정책 위반을 본다. payment 본문에 card 는 결제하지 않는다는 정책을 더한
   저장본으로 card 주문을 모의 실행하고, diagnosis_only 요청의 payment.status != 'PAID' 조건으로
   INTENT_VIOLATION 이 나는지 본다.

2번과 3번 실행은 skillops-agent/skillops_agent/nodes.py 의 fetch_evidence 에서 끝나 LLM 노드에 닿지
않으므로 대역 없이 돈다. 결과는 보고 JSON 으로 찍고, 기대와 다르면 종료 코드 1 로 끝난다. 세 요청
모두 담당자 의도(trigger.intent)가 없어 예전 흐름 그대로다. 대역 LLM 의 의도 출력에는 나중에 더한
expectations 칸이 없어도 된다(노드가 없는 칸을 빈 목록으로 읽는다).
"""

import asyncio
import json
import sys
from pathlib import Path
from types import SimpleNamespace
from uuid import uuid4

import httpx

# 스크립트를 직접 실행해도 skillops_agent 패키지를 import 하도록 skillops-agent 폴더를 경로에
# 넣는다.
sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from skillops_agent import nodes  # noqa: E402
from skillops_agent.config import ORDER_AGENT_URL, USE_STUB_MCP  # noqa: E402
from skillops_agent.graph import graph  # noqa: E402
from skillops_agent.tools import MCPTools  # noqa: E402


async def main():
    """세 번의 graph 실행과 불변량 확인을 차례로 하고, 기대와 다르면 SystemExit(1) 로 끝낸다.

    stub 도구로는 의미가 없으므로 USE_STUB_MCP 가 참이면 바로 RuntimeError 를 던진다. 주문 Agent 쪽
    경로는 order-agent/backend/src/main/java/com/capstone4/orderagent/controller/ 의 컨트롤러가
    받는다. GET /api/products 는 ProductController, POST /api/dry-runs 는 DryRunController, 나머지
    snapshot 과 실행 기록 경로는 EvidenceController 다. MCP 호출은 graph 의 노드와
    MCPTools.diff_skill 이 한다.
    """
    if USE_STUB_MCP:
        raise RuntimeError("SKILLOPS_USE_STUB_MCP must be false")
    async with httpx.AsyncClient(base_url=ORDER_AGENT_URL, timeout=60) as http:

        async def get(path):
            """주문 Agent 에 GET 을 보내고 JSON 을 돌려준다. 실패 상태면 예외를 던진다."""
            response = await http.get(path)
            response.raise_for_status()
            return response.json()

        async def post(path, body):
            """주문 Agent 에 body 를 POST 로 보내고 응답 JSON 을 돌려준다. 실패면 예외를 던진다."""
            response = await http.post(path, json=body)
            response.raise_for_status()
            return response.json()

        # 불변량의 기준값을 먼저 잰다. 끝에서 같은 경로를 다시 읽어 비교한다.
        stocks = await get("/api/products")
        failures = await get("/api/executions/failed")
        snapshots = {
            name: await post("/api/snapshots", {"fixture": name})
            for name in ("baseline", "broken-contract", "repaired")
        }
        broken = snapshots["broken-contract"]["snapshot_id"]
        order = {
            "customer": "c_1001",
            "items": [{"sku": "CAMP-LANTERN-LED", "qty": 1}],
            "payment_method": "card",
            "shipping": {"country": "KR", "express": False},
        }
        # broken-contract snapshot 으로 랜턴 주문을 모의 실행해 F1 실패를 만든다. 모델 모드가
        # fixed 이고 실패 분류가 CONTRACT_VIOLATION 이어야 이 실험이 뜻이 있다.
        started = await post("/api/dry-runs", {"snapshot_id": broken, "order": order})
        trace = await get(f"/api/executions/{started['execution_id']}/trace")
        if (
            trace.get("model_mode") != "fixed"
            or trace.get("error_category") != "CONTRACT_VIOLATION"
        ):
            raise RuntimeError("requires fixed-mode F1 failure")
        # repaired fixture 가 broken 과 실제로 다른지 MCP diff_skill 로 먼저 확인하고, 대역 LLM 이
        # 후보로 낼 repaired 원문 전체를 읽어 둔다.
        async with MCPTools() as tools:
            repair = await tools.diff_skill(
                "inventory-check", broken, snapshots["repaired"]["snapshot_id"]
            )
        if not repair.get("hunks"):
            raise RuntimeError("repair fixture has no changes")
        response = await http.get(
            f"/api/snapshots/{snapshots['repaired']['snapshot_id']}/skills/inventory-check"
        )
        response.raise_for_status()
        repaired_text = response.json()["content"]

        class Chain:
            """LLM 체인 대역이다. ainvoke 가 정해 둔 출력을 그대로 돌려준다."""

            def __init__(self, output):
                """ainvoke 가 돌려줄 출력을 받는다."""
                self.output = output

            async def ainvoke(self, *args):
                """프롬프트를 보지 않고 정해 둔 출력을 돌려준다."""
                return self.output

        def fake_llm(schema):
            """nodes.make_structured_llm 대역이다. 출력 모델에 맞는 고정 출력을 Chain 으로 준다.

            모델 이름은 skillops-agent/skillops_agent/nodes.py 의 _HypothesesOutput,
            _ExpectationsOutput, _IntentOutput, _CandidatesOutput, _RankExplanationsOutput 이다.
            원인은 change_no 1, 의도 조건은 inventory.reservable_quantity != null, 후보는 repaired
            원문 전체를 full_text 로 넣은 same_skill 후보 하나다. _ExpectationsOutput 은 의도 글만
            있는 저장 직후 요청에서만 불리므로 이 실험에서는 닿지 않지만, 닿으면 빈 목록을 돌려줘
            의도 시험 없이 사전 검증하게 한다. 노드가 읽는 속성만 SimpleNamespace 로 흉내 낸다.
            """
            outputs = {
                "_HypothesesOutput": {
                    "hypotheses": [
                        SimpleNamespace(
                            change_no=1, summary="inventory outputs changed", confidence=0.9
                        )
                    ]
                },
                "_ExpectationsOutput": {"expectations": []},
                "_IntentOutput": {
                    "text": "예약 가능 수량을 계속 내놓고 싶다.",
                    "checks": ["inventory.reservable_quantity != null"],
                },
                "_CandidatesOutput": {
                    "candidates": [
                        SimpleNamespace(
                            kind="same_skill",
                            patches=[
                                SimpleNamespace(skill="inventory-check", full_text=repaired_text)
                            ],
                        )
                    ]
                },
                "_RankExplanationsOutput": {
                    "explanations": [
                        SimpleNamespace(
                            candidate_no=1,
                            explanation=(
                                "재고 확인 Skill이 예약 여부(inventory.reserved)를 "
                                "다시 내놓게 했습니다. 실패했던 주문과 "
                                "기준 주문이 모두 통과했습니다."
                            ),
                        )
                    ]
                },
            }
            return Chain(SimpleNamespace(**outputs[schema.__name__]))

        # 첫 번째 실행(F1 실패 진단)에서만 LLM 을 대역으로 바꾸고, 끝나면 반드시 되돌린다.
        original_llm = nodes.make_structured_llm
        nodes.make_structured_llm = fake_llm
        try:
            result = await graph.ainvoke(
                {
                    "trigger": {
                        "type": "failure",
                        "execution_id": started["execution_id"],
                        "candidate_snapshot_id": broken,
                        "baseline_snapshot_id": snapshots["baseline"]["snapshot_id"],
                        "changed_paths": ["skills/inventory-check/SKILL.md"],
                    }
                },
                {"configurable": {"thread_id": str(uuid4())}, "recursion_limit": 100},
            )
        finally:
            nodes.make_structured_llm = original_llm
        baseline = snapshots["baseline"]

        async def edited_snapshot(path, transform):
            """baseline 의 파일 하나를 transform 으로 고쳐 새 저장본 snapshot 을 만든다.

            GET /api/snapshots/{id}/files 로 원문을 읽고 POST /api/snapshots/edits 로 저장한다. 실제
            저장 API 라서 주문 Agent 는 change_id 가 붙은 skill_saved 변경 이벤트도 남긴다.
            order-agent/backend/src/main/java/com/capstone4/orderagent/workspace/ 의
            SkillChangeDispatcher 가 켜져 있으면 그 이벤트가 service 를 거쳐 진단 Agent 의
            POST /diagnose 로도 전달된다.
            """
            response = await http.get(
                f"/api/snapshots/{baseline['snapshot_id']}/files", params={"path": path}
            )
            response.raise_for_status()
            return await post(
                "/api/snapshots/edits",
                {
                    "baseline_snapshot_id": baseline["snapshot_id"],
                    "baseline_hash": baseline["hash"],
                    "changes": {path: transform(response.json()["content"])},
                },
            )

        # 두 번째 실행은 설명 글만 바꾼 정상 저장이다. 회귀 시험을 모두 통과해 VALIDATED 로 끝나야
        # 한다.
        normal = await edited_snapshot(
            "skills/inventory-check/SKILL.md",
            lambda text: text.replace(
                "description: 상품 전체 재고를 확인하고 예약한다",
                "description: 주문 상품 전체의 재고를 확인하고 예약한다",
            ),
        )
        normal_result = await graph.ainvoke(
            {"trigger": {"type": "skill_saved", "candidate_snapshot_id": normal["snapshot_id"]}},
            {"configurable": {"thread_id": str(uuid4())}},
        )
        # 세 번째 실행은 본문 정책 변경이다. 그 저장본으로 card 주문을 모의 실행하고,
        # diagnosis_only 로 의도 조건 위반(INTENT_VIOLATION)만 확인한다.
        policy = await edited_snapshot(
            "skills/payment/SKILL.md",
            lambda text: text + "\nprepaid 주문만 결제합니다. card는 결제하지 않고 보류합니다.\n",
        )
        policy_run = await post(
            "/api/dry-runs", {"snapshot_id": policy["snapshot_id"], "order": order}
        )
        policy_result = await graph.ainvoke(
            {
                "trigger": {
                    "type": "failure",
                    "execution_id": policy_run["execution_id"],
                    "candidate_snapshot_id": policy["snapshot_id"],
                    "diagnosis_only": True,
                    "intent_checks": ["payment.status != 'PAID'"],
                }
            },
            {"configurable": {"thread_id": str(uuid4())}},
        )
        # 세 실행이 모두 끝난 뒤 재고와 운영 실패 목록이 처음과 같아야 한다.
        invariant = stocks == await get("/api/products") and failures == await get(
            "/api/executions/failed"
        )
        report = {
            "method": "real C MCP and order-agent HTTP; deterministic LLM substitute",
            "execution_id": started["execution_id"],
            "snapshots": {k: v["snapshot_id"] for k, v in snapshots.items()},
            "diagnosis_status": result.get("diagnosis_status"),
            "error": result.get("error"),
            "root_cause": result.get("root_cause"),
            "candidates": result.get("candidates"),
            "storage_status": (result.get("proposal") or {}).get("storage_status"),
            "proposal_id": (result.get("proposal") or {}).get("id"),
            "stock_and_live_failures_unchanged": invariant,
            "normal_saved_change": {
                "snapshot_id": normal["snapshot_id"],
                "status": normal_result.get("diagnosis_status"),
                "error": normal_result.get("error"),
            },
            "policy_violation": {
                "snapshot_id": policy["snapshot_id"],
                "status": policy_result.get("diagnosis_status"),
                "error": policy_result.get("error"),
                "diagnosis": policy_result.get("diagnosis"),
            },
        }
        print(json.dumps(report, ensure_ascii=False, indent=2))
        # 기대 결과는 F1 진단의 오류 없음, 모든 후보 유효, 제안서 SAVED, 불변량 유지, 정상 저장
        # VALIDATED, 정책 위반 INTENT_VIOLATION 이다. 하나라도 다르면 종료 코드 1 이다.
        if (
            result.get("error")
            or not result.get("candidates")
            or not all(c["valid"] for c in result["candidates"])
            or (result.get("proposal") or {}).get("storage_status") != "SAVED"
            or not invariant
            or normal_result.get("diagnosis_status") != "VALIDATED"
            or policy_result.get("diagnosis_status") != "INTENT_VIOLATION"
        ):
            raise SystemExit(1)


if __name__ == "__main__":
    asyncio.run(main())
