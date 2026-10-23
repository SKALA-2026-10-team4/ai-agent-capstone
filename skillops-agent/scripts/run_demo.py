"""진단 파이프라인을 한 번 돌려 노드 진행 이벤트와 결과 칸을 색을 넣어 찍는 오래된 데모 스크립트다.

LLM 은 이 파일의 _fake_llm 대역으로 바꾸므로 모델을 부르지 않는다. stub 도구를 쓰려면
SKILLOPS_USE_STUB_MCP=true 를 함께 줘야 한다. 스크립트가 이 값을 켜지 않으므로, 주지 않으면 실제
service MCP 에 연결하려 한다. skillops-agent 폴더에서 다음처럼 실행한다.

    SKILLOPS_USE_STUB_MCP=true uv run scripts/run_demo.py

1단계는 graph 를 한 번 돌려 events, root_cause, intent, candidates, proposal 을 찍는다. 2단계는
Command(resume=...) 로 승인을 넘기고 promote_workspace, smoke_check, rollback 이벤트와
approval_status 를 찍는다.

지금 코드와 맞지 않는 점이 있어 파이프라인이 끝까지 돌지 않는다. 스크립트는 예외 없이 끝나지만
원인, 의도, 후보, 제안서 칸은 비어 있다. trigger 의 run_id 'r_stub_F1' 은 UUID 별칭이 아니어서
skillops-agent/skillops_agent/nodes.py 의 fetch_evidence 가 ContractError 로 ERROR 를 남기고 멈춘다.
대역 LLM 출력의 칸 이름(hunk_index, assertions, base_version 과 diff, id)과 찍는 칸
일부(root_cause.hunk, intent.assertions, 후보 id, results.functional)는 v0 계약 이름이다.
graph 에는 승인 대기(interrupt) 노드와 승인 뒤 노드가 없어서 2단계는 이벤트 없이 끝난다. 모듈
끝에서 바로 asyncio.run(main()) 을 부르므로 import 만 해도 데모가 돈다. 저장소 안에서 이 스크립트를
부르는 곳은 없다.
"""

import asyncio
import sys
from pathlib import Path
from types import SimpleNamespace

# 스크립트를 직접 실행해도 skillops_agent 패키지를 import 하도록 skillops-agent 폴더를 경로에
# 넣는다.
sys.path.insert(0, str(Path(__file__).parent.parent))

# ── FakeLLM 주입 (실제 API 호출 없음) ──────────────────────────────────────────

# 대역 후보가 patches 의 diff 칸에 넣는 v0 형식의 unified diff 다.
_STUB_DIFF = (
    "--- a/inventory-check/SKILL.md\n+++ b/inventory-check/SKILL.md\n"
    "@@ -1 +1 @@\n-old\n+new\n"
)

class _FakeChain:
    """구조화 출력 LLM 체인의 대역이다. ainvoke 가 정해 둔 값을 그대로 돌려준다."""
    def __init__(self, val):
        """ainvoke 가 돌려줄 값을 받는다."""
        self._val = val
    async def ainvoke(self, *a, **kw):
        """프롬프트를 보지 않고 정해 둔 값을 돌려준다."""
        return self._val

def _fake_llm(schema):
    """nodes.make_structured_llm 의 대역이다. 출력 모델 이름의 일부로 고정 값을 고른다.

    Hypothes, Intent, Candidates, RankExplan 이 이름에 들어 있는지로 고르고, 그 밖이면 빈
    SimpleNamespace 를 준다. 칸 이름은 v0 계약 이름이라 지금
    skillops-agent/skillops_agent/nodes.py 의 모델(change_no, checks, full_text, candidate_no)과
    맞지 않는다.
    """
    name = schema.__name__
    if "Hypothes" in name:
        val = SimpleNamespace(hypotheses=[
            SimpleNamespace(
                hunk_index=0, summary="inventory-check 출력 필드명 변경", confidence=0.95
            )
        ])
    elif "Intent" in name:
        val = SimpleNamespace(
            text="결제 서비스가 inventory.reserved 필드를 필요로 함",
            assertions=["payment.status == 'PAID'"],
        )
    elif "Candidates" in name:
        val = SimpleNamespace(candidates=[
            SimpleNamespace(kind="local", patches=[
                SimpleNamespace(skill="inventory-check", base_version=4, diff=_STUB_DIFF)
            ]),
            SimpleNamespace(kind="contract", patches=[
                SimpleNamespace(skill="inventory-check", base_version=4, diff=_STUB_DIFF)
            ]),
            SimpleNamespace(kind="flow", patches=[
                SimpleNamespace(skill="inventory-check", base_version=4, diff=_STUB_DIFF)
            ]),
        ])
    elif "RankExplan" in name:
        val = SimpleNamespace(explanations=[
            SimpleNamespace(id="c-local-01", explanation="재현 시나리오 및 골든 시나리오 통과")
        ])
    else:
        val = SimpleNamespace()
    return _FakeChain(val)

import skillops_agent.nodes as _nodes  # noqa: E402

# nodes 의 LLM 팩토리를 대역으로 바꾼다. nodes 는 LLM 을 부를 때마다 모듈 전역
# make_structured_llm 을 찾으므로, 모듈 속성만 바꿔도 graph 의 노드가 대역을 쓴다.
_nodes.make_structured_llm = _fake_llm

# ── 그래프 실행 ────────────────────────────────────────────────────────────────

from langgraph.types import Command  # noqa: E402

from skillops_agent.graph import graph  # noqa: E402

# 터미널 출력에 쓰는 ANSI 색 코드다.
COLORS = {
    "green":  "\033[92m",
    "yellow": "\033[93m",
    "cyan":   "\033[96m",
    "red":    "\033[91m",
    "reset":  "\033[0m",
    "bold":   "\033[1m",
}

def c(color, text):
    """text 를 COLORS 의 color 색으로 감싸고 끝에 색을 되돌린다."""
    return f"{COLORS[color]}{text}{COLORS['reset']}"


async def main():
    """데모 두 단계를 같은 thread 로 돌리고 결과 칸을 찍는다.

    thread_id 는 demo-run-001 로 고정이고 graph 의 MemorySaver 가 두 단계를 잇는다. 1단계 결과는
    events, root_cause, intent, candidates, proposal 이고, 2단계는 승인 뒤 노드의 이벤트와
    approval_status 다.
    """
    print(c("bold", "\n=== SkillOps Agent 파이프라인 데모 ===\n"))

    config = {"configurable": {"thread_id": "demo-run-001"}}
    initial = {
        "trigger": {"type": "failure", "run_id": "r_stub_F1", "skill": None, "version": None},
        "max_steps": 40,
    }

    # ── 1단계: interrupt(승인 대기) 까지 실행 ────────────────────────────────
    # 원래는 승인 대기(interrupt)에서 멈추는 graph 를 전제로 했다. 지금 graph 에는 interrupt 가
    # 없어서 끝까지 돌거나 ERROR 로 끝난다.
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
        print(
            f"  [{valid_str}] {cand['id']:<18} functional={r.get('functional')} "
            f"regression={r.get('regression')} {rank_str}"
        )

    print(f"\n{c('bold', 'Proposal:')}")
    print(f"  id       : {proposal.get('id')}")
    print(f"  status   : {proposal.get('status')}")

    # ── 2단계: 승인 ───────────────────────────────────────────────────────────
    # Command(resume=...) 로 승인 결정을 넘긴다. 지금은 멈춘 지점과 승인 뒤 노드가 없어서
    # promote_workspace, smoke_check, rollback 이벤트가 나오지 않는다.
    print(f"\n{c('cyan', '▶ 승인 처리 (approved)')}")
    final = await graph.ainvoke(
        Command(resume={"status": "approved", "token": "tok_demo_001"}),
        config=config,
    )

    post_events = [
        e
        for e in final.get("events", [])
        if e["node"] in ("promote_workspace", "smoke_check", "rollback")
    ]
    for e in post_events:
        print(f"  {c('green', '✓')} {e['node']:<22} {e['msg']}")

    print(f"\n{c('bold', '승인 상태:')} {c('green', final.get('approval_status', ''))}")
    print(c("bold", "\n=== 완료 ===\n"))


asyncio.run(main())
