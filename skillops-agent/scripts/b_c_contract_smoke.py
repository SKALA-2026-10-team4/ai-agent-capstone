"""Run B graph through real C/Order HTTP, substituting only LLM decisions.

Requires C harness + camping g-01..g-03, and order-agent in fixed mode.
Creates snapshots and dry-run evidence; never calls a paid model or a live order.
Verifies unchanged product stocks and live failure IDs after the run.
Run: SKILLOPS_USE_STUB_MCP=false uv run scripts/b_c_contract_smoke.py
"""

import asyncio
import json
import sys
from pathlib import Path
from types import SimpleNamespace
from uuid import uuid4

import httpx

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from skillops_agent import nodes  # noqa: E402
from skillops_agent.config import ORDER_AGENT_URL, USE_STUB_MCP  # noqa: E402
from skillops_agent.graph import graph  # noqa: E402
from skillops_agent.tools import MCPTools  # noqa: E402


async def main():
    if USE_STUB_MCP:
        raise RuntimeError("SKILLOPS_USE_STUB_MCP must be false")
    async with httpx.AsyncClient(base_url=ORDER_AGENT_URL, timeout=60) as http:

        async def get(path):
            response = await http.get(path)
            response.raise_for_status()
            return response.json()

        async def post(path, body):
            response = await http.post(path, json=body)
            response.raise_for_status()
            return response.json()

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
        started = await post("/api/dry-runs", {"snapshot_id": broken, "order": order})
        trace = await get(f"/api/executions/{started['execution_id']}/trace")
        if (
            trace.get("model_mode") != "fixed"
            or trace.get("error_category") != "CONTRACT_VIOLATION"
        ):
            raise RuntimeError("requires fixed-mode F1 failure")
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
            def __init__(self, output):
                self.output = output

            async def ainvoke(self, *args):
                return self.output

        def fake_llm(schema):
            outputs = {
                "_HypothesesOutput": {
                    "hypotheses": [
                        SimpleNamespace(
                            change_no=1, summary="inventory outputs changed", confidence=0.9
                        )
                    ]
                },
                "_IntentOutput": {
                    "text": "keep reservable quantity",
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
                        SimpleNamespace(candidate_no=1, explanation="C trial references retained")
                    ]
                },
            }
            return Chain(SimpleNamespace(**outputs[schema.__name__]))

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
        if (
            result.get("error")
            or not result.get("candidates")
            or not all(c["valid"] for c in result["candidates"])
            or not invariant
            or normal_result.get("diagnosis_status") != "VALIDATED"
            or policy_result.get("diagnosis_status") != "INTENT_VIOLATION"
        ):
            raise SystemExit(1)


if __name__ == "__main__":
    asyncio.run(main())
