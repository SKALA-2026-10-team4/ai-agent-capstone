"""파이프라인을 한 바퀴 돌려 이벤트를 출력한다. (stub MCP + fake LLM)"""

import asyncio
import sys
from pathlib import Path
from types import SimpleNamespace

sys.path.insert(0, str(Path(__file__).parent.parent))

# ── FakeLLM 주입 (실제 API 호출 없음) ──────────────────────────────────────────

_STUB_DIFF = "--- a/inventory-check/SKILL.md\n+++ b/inventory-check/SKILL.md\n@@ -1 +1 @@\n-old\n+new\n"

class _FakeChain:
    def __init__(self, val):
        self._val = val
    async def ainvoke(self, *a, **kw):
        return self._val

def _fake_llm(schema):
    name = schema.__name__
    if "Hypothes" in name:
        val = SimpleNamespace(hypotheses=[SimpleNamespace(hunk_index=0, summary="inventory-check 출력 필드명 변경", confidence=0.95)])
    elif "Intent" in name:
        val = SimpleNamespace(text="결제 서비스가 inventory.reserved 필드를 필요로 함", assertions=["payment.status == 'PAID'"])
    elif "Candidates" in name:
        val = SimpleNamespace(candidates=[
            SimpleNamespace(kind="local",    patches=[SimpleNamespace(skill="inventory-check", base_version=4, diff=_STUB_DIFF)]),
            SimpleNamespace(kind="contract", patches=[SimpleNamespace(skill="inventory-check", base_version=4, diff=_STUB_DIFF)]),
            SimpleNamespace(kind="flow",     patches=[SimpleNamespace(skill="inventory-check", base_version=4, diff=_STUB_DIFF)]),
        ])
    elif "RankExplan" in name:
        val = SimpleNamespace(explanations=[SimpleNamespace(id="c-local-01", explanation="재현 시나리오 및 골든 시나리오 통과")])
    else:
        val = SimpleNamespace()
    return _FakeChain(val)

import skillops_agent.nodes as _nodes
_nodes.make_structured_llm = _fake_llm

# ── 그래프 실행 ────────────────────────────────────────────────────────────────

from langgraph.types import Command
from skillops_agent.graph import graph

COLORS = {
    "green":  "\033[92m",
    "yellow": "\033[93m",
    "cyan":   "\033[96m",
    "red":    "\033[91m",
    "reset":  "\033[0m",
    "bold":   "\033[1m",
}

def c(color, text):
    return f"{COLORS[color]}{text}{COLORS['reset']}"


async def main():
    print(c("bold", "\n=== SkillOps Agent 파이프라인 데모 ===\n"))

    config = {"configurable": {"thread_id": "demo-run-001"}}
    initial = {"trigger": {"type": "failure", "run_id": "r_stub_F1", "skill": None, "version": None}, "max_steps": 40}

    # ── 1단계: interrupt(승인 대기) 까지 실행 ────────────────────────────────
    print(c("cyan", "▶ 파이프라인 시작 (init → save_proposal → 승인 대기)"))
    result = await graph.ainvoke(initial, config=config)

    events = result.get("events", [])
    print(f"\n{c('bold', '노드 실행 순서:')}")
    for e in events:
        icon = "✓"
        color = "green"
        print(f"  {c(color, icon)} {e['node']:<22} {e['msg']}")

    root_cause = result.get("root_cause") or {}
    intent = result.get("intent") or {}
    candidates = result.get("candidates", [])
    proposal = result.get("proposal") or {}

    print(f"\n{c('bold', '확정된 원인:')}")
    print(f"  skill    : {root_cause.get('skill')}")
    print(f"  hunk     : {root_cause.get('hunk')}")
    print(f"  summary  : {root_cause.get('summary')}")

    print(f"\n{c('bold', '담당자 의도:')}")
    print(f"  text     : {intent.get('text')}")
    print(f"  assertions: {intent.get('assertions')}")

    print(f"\n{c('bold', '후보 수정안:')}")
    for cand in candidates:
        r = cand.get("results") or {}
        valid_str = c("green", "✓ valid") if cand.get("valid") else c("red", "✗ invalid")
        rank_str = f"rank={cand.get('rank')}" if cand.get("rank") else ""
        print(f"  [{valid_str}] {cand['id']:<18} functional={r.get('functional')} regression={r.get('regression')} {rank_str}")

    print(f"\n{c('bold', 'Proposal:')}")
    print(f"  id       : {proposal.get('id')}")
    print(f"  status   : {proposal.get('status')}")

    # ── 2단계: 승인 ───────────────────────────────────────────────────────────
    print(f"\n{c('cyan', '▶ 승인 처리 (approved)')}")
    final = await graph.ainvoke(
        Command(resume={"status": "approved", "token": "tok_demo_001"}),
        config=config,
    )

    post_events = [e for e in final.get("events", []) if e["node"] in ("promote_workspace", "smoke_check", "rollback")]
    for e in post_events:
        print(f"  {c('green', '✓')} {e['node']:<22} {e['msg']}")

    print(f"\n{c('bold', '승인 상태:')} {c('green', final.get('approval_status', ''))}")
    print(c("bold", "\n=== 완료 ===\n"))


asyncio.run(main())
