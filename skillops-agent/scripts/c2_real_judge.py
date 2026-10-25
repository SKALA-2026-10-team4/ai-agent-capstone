"""실제 주문 Agent 로 수정 후보를 판정하는 실험용 스크립트다.

가짜 실행기 없이 C-2 도구(create_workspace, apply_patch, run_scenarios)가 주문 Agent 에서 모의
실행한 결과로 후보 세 개를 채점한다. docs/contracts/mcp-tools-input-output.md 의 run_scenarios 절
예시는 이 실험의 결과다.

준비할 것은 다음과 같다.
- 주문 Agent(order-agent/, 기본 http://localhost:8081)가 떠 있어야 한다. 모델 모드가 기본값
  fixed 이면 모델 비용이 들지 않는다.
- service 가 harness 실행기로 떠 있어야 하고, 시험 주문은 주문 Agent 용 기준 주문 폴더
  scenarios/order-agent 에서 읽는다. service 는 다음처럼 띄운다.

      cd service
      SKILLOPS_FLOW_RUNNER=harness SKILLOPS_SCENARIOS_DIR=../scenarios/order-agent ./gradlew bootRun

skillops-agent 폴더에서 다음처럼 실행한다. --reuse-failed 를 주면 실제 주문을 새로 넣지 않고 이미
실패 목록에 있는 실행을 재현한다.

    uv run scripts/c2_real_judge.py
    uv run scripts/c2_real_judge.py --reuse-failed <execution_id>

다른 포트의 service 나 주문 Agent 를 쓰려면 아래 환경 변수로 가리킨다. 기본값은
skillops-agent/skillops_agent/config.py 에 있다.

    SKILLOPS_MCP_URL=http://localhost:8090/mcp       service MCP 주소 (기본 http://localhost:8080/mcp)
    SKILLOPS_ORDER_AGENT_URL=http://localhost:8081   주문 Agent 주소 (기본 http://localhost:8081)

service 의 ORDER_AGENT_BASE_URL 과 이 스크립트의 SKILLOPS_ORDER_AGENT_URL 은 같은 주문 Agent 를
가리켜야 한다. 다르면 재고와 실패 목록(이 스크립트)과 모의 실행(service)이 서로 다른 주문 Agent 를
보게 되어 불변량 비교가 의미 없어진다.

main 은 화면에 찍는 단계 제목과 같은 순서로 다음 일을 한다.
1. baseline, broken-contract, repaired 시연 snapshot 을 고정한다. 부를 때마다 새 id 가 생긴다.
2. broken 으로 실제 주문을 1건 넣어 실패를 만들고, /api/executions/failed 의 오류를 찍는다. 재고를
   잡기 전 정적 검사에서 멈추므로 재고는 줄지 않지만 실패 목록은 1건 늘어난다. --reuse-failed 로
   이미 있는 실패 실행을 넘기면 이 단계를 건너뛰고 그 실행을 재현한다.
3. 판정을 시작하기 전에 랜턴 재고와 실패 목록 개수를 잰다. 2단계의 실패 1건이 들어간 뒤를 시작으로
   잰다.
4. 후보 세 개를 만든다(build_candidates).
   (가) 되돌리기 후보는 broken 을 baseline 으로 되돌리는 수정 내용을 skill 마다 difflib 로 만든다.
   (나) repaired 후보는 broken 과 repaired 사이 C-1 diff_skill 의 hunks[].diff 를 이어 붙인다.
   (다) payment 입력 후보는 payment 가 받는 inventory.reserved 를 inventory.reservable_quantity
   로 바꾼다.
5. 후보마다 새 연습 공간(create_workspace snapshot_id=broken)을 만들어 apply_patch 와
   run_scenarios 를 돌린다(judge). 시험 주문은 g-01~g-03 과 2단계 실행을 재현하는
   f-<execution_id> 다. 모든 주문에 확인 조건 inventory.reservable_quantity != null(담당자의
   의도)을 덧붙이고 repeat 는 1 이다. 이어서 후보별 표(table)와 실행 기록 근거(evidence)를 찍는다.
6. 판정이 끝난 뒤 랜턴 재고와 실패 목록 개수를 다시 재어 3단계 값과 비교한다. 판정이 실제 주문
   상태를 건드리지 않았는지 보는 것이다.

종료 코드는 다음과 같다.
- 0 은 실험을 끝냈고, 모든 후보를 판정했으며, 재고와 실패 목록이 그대로라는 뜻이다.
- 1 은 판정이 재고나 실패 목록을 바꿨거나, 연결 실패 등으로 실험을 이어갈 수 없었다는 뜻이다.
- 3 은 재고와 실패 목록은 그대로지만 판정하지 못한 후보(표의 '판정 못 함')가 있다는 뜻이다.
  argparse 의 잘못된 인자(2)와 겹치지 않게 3 으로 한다. 1 과 3 이 함께면 1 이다.

후보가 유효한지는 종료 코드가 아니라 표로 읽는다. 결과를 기대값에 맞춰 고치지 않는다. 표의 의도
확인 해석은 service/src/main/java/com/capstone4/skillops/scenario/ScenarioGrader.java 가 붙이는 실패
문구(INTENT_PREFIX)에 기댄다.
"""

import argparse
import asyncio
import difflib
import json
import sys
import uuid
from dataclasses import dataclass, field
from pathlib import Path

import httpx

# 스크립트를 직접 실행해도 skillops_agent 패키지를 import 하도록 skillops-agent 폴더를 경로에
# 넣는다.
sys.path.insert(0, str(Path(__file__).resolve().parents[1]))

from skillops_agent.config import MCP_URL, ORDER_AGENT_URL  # noqa: E402
from skillops_agent.contracts import (
    execution_uuid,
    patch_contract_ok,
    patch_flow_ok,
    patch_metadata_ok,
)  # noqa: E402
from skillops_agent.mcp_client import first_text, mcp_client  # noqa: E402

# 실험 값은 여기에 모여 있다.
# 재고 불변량을 재는 상품이다. LIVE_ORDER 와 기준 주문 g-01, g-03 이 이 상품을 주문한다.
LANTERN_SKU = "CAMP-LANTERN-LED"
# 모든 후보에 돌리는 기준 주문(scenarios/order-agent/golden)이다.
GOLDEN_IDS = ["g-01", "g-02", "g-03"]
# 담당자의 의도를 나타내는 확인 조건이다. run_scenarios 의 extra_checks 로 모든 주문에 덧붙인다.
INTENT_CHECK = "inventory.reservable_quantity != null"
# service/src/main/java/com/capstone4/skillops/scenario/ScenarioGrader.java 의
# failedCheckReasons 가 붙이는 '확인 조건 실패: <조건> (실제 <값>)' 문구의 앞부분이다. 그 문구를
# 바꾸면 이 값도 같이 고친다.
INTENT_PREFIX = f"확인 조건 실패: {INTENT_CHECK} "
# 2단계에서 broken snapshot 으로 넣는 실제 주문이다. live_order 가 snapshot_id 를 더해 보낸다.
LIVE_ORDER = {
    "customer": "c_1001",
    "items": [{"sku": LANTERN_SKU, "qty": 1}],
    "payment_method": "card",
    "shipping": {"country": "KR", "express": False},
}
# 스크립트 안 이름에서 주문 Agent 시연 fixture 이름으로의 매핑이다. 1단계가 이 순서로 고정한다.
FIXTURES = {"baseline": "baseline", "broken": "broken-contract", "repaired": "repaired"}


class ToolError(Exception):
    """MCP 도구가 isError 로 돌려준 오류를 담는 예외다.

    payload 는 도구가 돌려준 오류 객체({code, message, retryable, ...})이고, JSON 이 아니면 받은 글
    그대로다. call 이 던지고, judge 가 잡아 그 후보를 '판정 못 함'으로 남긴다.
    """

    def __init__(self, tool: str, payload):
        """도구 이름과 payload 를 남기고 '<도구>: <payload>' 문구로 예외를 만든다."""
        self.tool = tool
        self.payload = payload
        super().__init__(f"{tool}: {payload}")


@dataclass
class Candidate:
    """수정 후보 하나다. label 은 표에 찍는 이름이고 summary 는 한 줄 설명이다.

    build_candidates 가 만들고 judge 가 patches 를 apply_patch 에 넣는다.
    """

    label: str
    summary: str
    patches: dict[str, str] = field(default_factory=dict)  # skill 이름별 unified diff 다.


@dataclass
class Verdict:
    """후보 하나의 판정 결과다. judge 가 채우고 table 과 evidence 가 읽는다.

    error가 있으면 판정하지 못한 것이다. 연결·필수 정보·구조 오류 목록은 apply_patch 결과이며
    all_passed는 run_scenarios 결과다.
    """

    label: str
    error: str | None = None
    broken_links: list[dict] = field(default_factory=list)
    dependency_violations: list[dict] = field(default_factory=list)
    metadata_errors: list[dict] = field(default_factory=list)
    flow_errors: list[dict] = field(default_factory=list)
    all_passed: bool = False
    # scenario_id 별 run_scenarios 결과 항목이다.
    results: dict[str, dict] = field(default_factory=dict)
    workspace_id: str = ""


async def call(client, tool: str, arguments: dict) -> dict:
    """MCP 도구 하나를 raw client 로 부르고 결과를 돌려준다.

    structured_content 가 있으면 그것을 쓰고, 없으면 첫 text 를 JSON 으로 읽으며, JSON 이 아니면
    글 그대로 쓴다. is_error 결과면 ToolError 를 던진다. skillops-agent/skillops_agent/tools.py 의
    MCPTools 를 거치지 않는다.
    """
    result = await client.call_tool(tool, arguments)
    payload = getattr(result, "structured_content", None)
    if payload is None:
        text = first_text(result) or ""
        try:
            payload = json.loads(text)
        except json.JSONDecodeError:
            payload = text
    if result.is_error:
        raise ToolError(tool, payload)
    return payload


# ---- 수정 내용 만들기 --------------------------------------------------------------------


def unified(path: str, before: str, after: str) -> str | None:
    """before 를 after 로 바꾸는 통합 diff 를 만든다. 두 글이 같으면 None 을 돌려준다.

    끝에 줄바꿈이 없으면 difflib 로 올바른 수정 내용을 만들 수 없으므로 ValueError 로 거부한다.
    머리줄은 a/<path> 와 b/<path> 다. build_candidates 가 (가) 와 (다) 후보를 만들 때 쓴다.
    """
    if before == after:
        return None
    if not before.endswith("\n") or not after.endswith("\n"):
        raise ValueError(f"{path} 의 끝에 줄바꿈이 없어 difflib 로 수정 내용을 만들 수 없습니다")
    lines = difflib.unified_diff(
        before.splitlines(keepends=True),
        after.splitlines(keepends=True),
        fromfile=f"a/{path}",
        tofile=f"b/{path}",
    )
    return "".join(lines)


def join_hunks(diffs: list[str]) -> str:
    """덩어리별 diff 들을 이어 붙이되, 덩어리마다 붙어 온 '---' 와 '+++' 머리줄은 첫 것만 남긴다.

    머리줄이 중간에 또 나오면 apply_patch 가 그 줄을 덩어리 본문으로 읽는다. 그러면 '---' 줄은
    지우는 줄('-')이 되어 원문과 맞지 않으므로 INVALID_INPUT 으로 거부된다. 덩어리의 줄 번호는 모두
    바뀌기 전 파일 기준이라 그대로 이어도 된다. '@@' 덩어리가 없는 diff 가 오면 ValueError 를
    던진다. build_candidates 가 (나) 후보를 만들 때 쓴다.
    """
    header: list[str] = []
    body: list[str] = []
    for i, diff in enumerate(diffs):
        lines = diff.splitlines(keepends=True)
        first = next((n for n, line in enumerate(lines) if line.startswith("@@")), None)
        if first is None:
            raise ValueError(f"diff 에 '@@' 덩어리가 없습니다: {diff!r}")
        if i == 0:
            header = lines[:first]
        body.extend(lines[first:])
    return "".join(header + body)


def swap_payment_input(text: str) -> str:
    """payment 의 frontmatter inputs 줄에서 inventory.reserved 만 reservable_quantity 로 바꾼다.

    frontmatter 는 첫 줄의 '---' 부터 다음 '---' 까지로 본다. 바꿀 줄이 없으면 ValueError 를
    던진다. build_candidates 가 (다) 후보를 만들 때 쓴다.
    """
    out, swapped, in_front = [], False, False
    for n, line in enumerate(text.splitlines(keepends=True)):
        if n == 0 and line.strip() == "---":
            in_front = True
        elif in_front and line.strip() == "---":
            in_front = False
        elif in_front and line.startswith("inputs:") and "inventory.reserved" in line:
            line = line.replace("inventory.reserved", "inventory.reservable_quantity")
            swapped = True
        out.append(line)
    if not swapped:
        raise ValueError("payment 의 inputs 줄에서 inventory.reserved 를 찾지 못했습니다")
    return "".join(out)


# ---- 주문 Agent HTTP ---------------------------------------------------------------------


class OrderAgent:
    """이 실험이 쓰는 주문 Agent HTTP 호출을 모은 작은 클라이언트다.

    상대편 컨트롤러는 order-agent/backend/src/main/java/com/capstone4/orderagent/controller/ 의
    EvidenceController, ProductController, OrderController 다. live_order 를 뺀 메서드는 실패 상태면
    httpx.HTTPStatusError 를 던진다.
    """

    def __init__(self, http: httpx.AsyncClient):
        """주문 Agent 주소가 base_url 로 잡힌 httpx client 를 받는다."""
        self.http = http

    async def fixture(self, name: str) -> dict:
        """시연 fixture 를 POST /api/snapshots 로 snapshot 으로 고정하고 manifest 를 돌려준다.

        EvidenceController.publish() 가 받으며, 주문 Agent 의 시연 기능(order-agent.demo-enabled)이
        켜져 있어야 한다.
        """
        r = await self.http.post("/api/snapshots", json={"fixture": name})
        r.raise_for_status()
        return r.json()

    async def file_text(self, snapshot_id: str, path: str) -> str:
        """snapshot 안 파일 하나의 본문을 GET /api/snapshots/{id}/files 로 읽는다.

        EvidenceController.file() 이 받는다.
        """
        r = await self.http.get(f"/api/snapshots/{snapshot_id}/files", params={"path": path})
        r.raise_for_status()
        return r.json()["content"]

    async def lantern_stock(self) -> int:
        """GET /api/products 에서 LANTERN_SKU 의 stock_quantity 를 읽는다.

        ProductController.list() 가 받는다. 3단계와 6단계의 불변량 측정에 쓴다.
        """
        r = await self.http.get("/api/products")
        r.raise_for_status()
        return next(p["stock_quantity"] for p in r.json()["items"] if p["sku"] == LANTERN_SKU)

    async def failed(self) -> list[dict]:
        """실패 목록 GET /api/executions/failed 의 items 를 돌려준다.

        EvidenceController.failed() 가 받는다. 2단계와 3단계, 6단계에서 쓴다.
        """
        r = await self.http.get("/api/executions/failed")
        r.raise_for_status()
        return r.json()["items"]

    async def live_order(self, snapshot_id: str) -> httpx.Response:
        """LIVE_ORDER 를 snapshot_id 로 POST /api/orders 에 실제 주문으로 넣고 응답을 돌려준다.

        OrderController.create() 가 받는다. 매번 새 Idempotency-Key 를 붙이고, 실패 응답도 예외 없이
        그대로 돌려준다. 2단계에서 실패를 만들 때 쓴다.
        """
        return await self.http.post(
            "/api/orders",
            json={**LIVE_ORDER, "snapshot_id": snapshot_id},
            headers={"Idempotency-Key": str(uuid.uuid4())},
        )

    async def trace(self, execution_id: str) -> dict:
        """실행 기록을 GET /api/executions/{id}/trace 로 읽는다.

        EvidenceController.trace() 가 받는다. evidence 가 run 마다 부른다.
        """
        r = await self.http.get(f"/api/executions/{execution_id}/trace")
        r.raise_for_status()
        return r.json()


def trace_line(trace: dict) -> str:
    """실행 기록 하나를 한 줄로 요약한다.

    mode, 최종 상태, 정상으로 끝난 skill 순서, inventory-check 출력, 오류(category, skill,
    missing_inputs, phase)를 이어 붙인다. evidence 가 run 마다 찍는다.
    """
    events = trace.get("events", [])
    ok = [e["skill_id"] for e in events if e["kind"] == "SKILL_FINISHED"]
    parts = [f"mode={trace.get('mode')}", f"final={trace.get('final_status')}"]
    parts.append("skills=" + (">".join(ok) if ok else "(없음)"))
    for e in events:
        if e["kind"] == "SKILL_FINISHED" and e["skill_id"] == "inventory-check":
            parts.append(
                "inventory-check 출력=" + json.dumps(e["data"].get("output"), ensure_ascii=False)
            )
        if e["kind"] == "EXECUTION_ERROR":
            d = e["data"]
            parts.append(
                f"오류={d.get('category')} skill={d.get('skill_id')} "
                f"missing_inputs={d.get('missing_inputs')} phase={d.get('phase')}"
            )
    return " ".join(parts)


# ---- 채점 해석 ---------------------------------------------------------------------------


def split_reason(reason: str) -> list[str]:
    """run_scenarios 실패 이유 글을 '; ' 로 나눈다.

    service/src/main/java/com/capstone4/skillops/scenario/ScenarioRunner.java 의 REASON_SEPARATOR 가
    이유 여러 개를 이 구분자로 잇는다.
    """
    return reason.split("; ")


def intent_state(result: dict) -> str:
    """주문 하나의 의도 확인 결과를 '통과', '실패', '확인 못 함' 가운데 하나로 돌려준다.

    떨어진 이유가 없으면 통과이고, 이유가 모두 INTENT_PREFIX 로 시작하면 실패다.
    service/src/main/java/com/capstone4/skillops/scenario/ScenarioGrader.java 는 오류로 멈춘
    주문에도 확인 조건 실패를 덧붙인다(값이 없으니 null). 그 실패는 의도를 평가한 결과가 아니므로,
    의도 문구 말고 다른 이유가 섞여 있으면 '확인 못 함'이다.
    """
    segs = [seg for f in result["failures"] for seg in split_reason(f["reason"])]
    if not segs:
        return "통과"
    if all(seg.startswith(INTENT_PREFIX) for seg in segs):
        return "실패"
    return "확인 못 함"


def candidate_intent(results: dict[str, dict]) -> str:
    """후보 하나의 의도 확인 결과를 주문별 intent_state 로 정한다.

    한 주문이라도 실패면 실패, 실패 없이 확인 못 함이 있으면 확인 못 함, 모두 통과면 통과다. 끝까지
    돈 주문에서 의도 조건이 떨어진 것을 가장 먼저 본다.
    """
    states = {intent_state(r) for r in results.values()}
    for state in ("실패", "확인 못 함"):
        if state in states:
            return state
    return "통과"


def base_passed(result: dict) -> bool:
    """의도 확인 조건 때문에 떨어진 것만 빼면 주문이 통과인지 본다.

    기대 결과나 오류 때문에 떨어진 이유가 하나라도 있으면 False 다. table 의 시나리오 칸과
    evidence 의 '의도 제외' 표시에 쓴다.
    """
    return all(
        seg.startswith(INTENT_PREFIX)
        for f in result["failures"]
        for seg in split_reason(f["reason"])
    )


def is_valid(v: Verdict, replay_id: str) -> bool:
    """후보가 유효한지 본다.

    시험 주문 네 개(g-01~g-03 과 재현 주문)가 모두 결과에 있고 의도 확인까지 합격이며,
    run_scenarios의 all_passed가 참이고 연결·필수 정보·구조 오류가 없어야 유효하다.
    """
    needed = [*GOLDEN_IDS, replay_id]
    full_set = all(sid in v.results and v.results[sid]["passed"] for sid in needed)
    return bool(
        v.all_passed
        and full_set
        and not v.broken_links
        and not v.dependency_violations
        and not v.metadata_errors
        and not v.flow_errors
    )


def mark(ok: bool) -> str:
    """합격 여부를 표에 찍는 글('합격' 또는 '불합격')로 바꾼다."""
    return "합격" if ok else "불합격"


def table(verdicts: list[Verdict], replay_id: str) -> str:
    """후보별 판정을 Markdown 표 글로 만든다.

    칸은 후보, 연결·필수 정보·구조 오류 수, 기준 주문·재현 주문 합격 여부, 의도 확인, 유효
    여부다. 판정하지 못한 후보는 나머지 칸을 '-' 로 채우고 '판정 못 함' 과 이유를 찍는다.
    """
    head = [
        "후보",
        "끊긴 연결",
        "필수 정보 오류",
        "실행 구조 오류",
        *GOLDEN_IDS,
        "실제 실패 주문 재현",
        "의도 확인",
        "유효",
    ]
    rows = [head, ["---"] * len(head)]
    for v in verdicts:
        if v.error:
            rows.append(
                [v.label, "-", "-", "-", "-", "-", "-", "-", "-", f"판정 못 함 ({v.error})"]
            )
            continue
        cells = [
            v.label,
            str(len(v.broken_links) + len(v.dependency_violations)),
            str(len(v.metadata_errors)),
            str(len(v.flow_errors)),
        ]
        for sid in [*GOLDEN_IDS, replay_id]:
            r = v.results.get(sid)
            cells.append(mark(base_passed(r)) if r else "-")
        cells += [candidate_intent(v.results), "유효" if is_valid(v, replay_id) else "무효"]
        rows.append(cells)
    return "\n".join("| " + " | ".join(r) + " |" for r in rows)


# ---- 실험 --------------------------------------------------------------------------------


def section(title: str) -> None:
    """단계 제목을 빈 줄 뒤에 '=== 제목 ===' 으로 찍는다."""
    print(f"\n=== {title} ===")


async def build_candidates(
    client, agent: OrderAgent, snaps: dict[str, str], skills: list[str]
) -> list[Candidate]:
    """후보 세 개((가) 되돌리기, (나) repaired, (다) payment 입력)를 만든다.

    broken snapshot 의 SKILL.md 원문을 주문 Agent 파일 조회로 읽는다. (가)는 baseline 원문과
    unified 로, (나)는 MCP diff_skill 결과의 hunks[].diff 를 join_hunks 로, (다)는
    swap_payment_input 결과와 unified 로 만든다. (가)와 (나)는 바뀐 것이 없는 skill 을 patches 에
    넣지 않는다. skills 는 4단계에서 연습 공간의 skill_versions 로 얻은 이름 목록이다.
    """
    broken, baseline, repaired = snaps["broken"], snaps["baseline"], snaps["repaired"]
    path = "skills/{}/SKILL.md"
    broken_text = {s: await agent.file_text(broken, path.format(s)) for s in skills}

    revert = Candidate("(가) 되돌리기", "broken -> baseline, difflib 로 skill 마다 만든 수정 내용")
    for s in skills:
        base_text = await agent.file_text(baseline, path.format(s))
        patch = unified(path.format(s), broken_text[s], base_text)
        if patch:
            revert.patches[s] = patch

    fixed = Candidate(
        "(나) repaired",
        "broken -> repaired, C-1 diff_skill 의 hunks[].diff 를 skill 마다 이어 붙임",
    )
    for s in skills:
        diff = await call(
            client,
            "diff_skill",
            {"skill_id": s, "before_snapshot_id": broken, "after_snapshot_id": repaired},
        )
        hunks = diff.get("hunks", [])
        print(f"  diff_skill({s}): status={diff.get('status')} hunks={len(hunks)}")
        if hunks:
            fixed.patches[s] = join_hunks([h["diff"] for h in hunks])

    payment = Candidate(
        "(다) payment 입력",
        "payment 의 inputs 에서 inventory.reserved -> inventory.reservable_quantity",
    )
    new_payment = swap_payment_input(broken_text["payment"])
    payment.patches["payment"] = (
        unified(path.format("payment"), broken_text["payment"], new_payment) or ""
    )
    return [revert, fixed, payment]


async def judge(client, candidate: Candidate, broken: str, replay_id: str) -> Verdict:
    """후보 하나를 새 연습 공간에서 판정하고 Verdict 를 돌려준다.

    create_workspace(snapshot_id=broken) 로 공간을 만들고, 받은 skill_versions 를 base_version 으로
    삼아 apply_patch 로 후보를 넣는다. 이어서 run_scenarios 로 기준 주문 세 개와 재현 주문을
    INTENT_CHECK 를 덧붙여 1회씩 돌린다. ToolError 나 다른 예외가 나면 Verdict.error 에 남기고 다음
    후보로 넘어간다. 상대편은
    service/src/main/java/com/capstone4/skillops/mcp/WorkspaceTools.java 다.
    """
    verdict = Verdict(candidate.label)
    try:
        ws = await call(client, "create_workspace", {"snapshot_id": broken})
        verdict.workspace_id = ws["workspace_id"]
        patches = [
            {"skill": s, "base_version": ws["skill_versions"][s], "patch": p}
            for s, p in candidate.patches.items()
        ]
        applied = await call(
            client, "apply_patch", {"workspace_id": ws["workspace_id"], "patches": patches}
        )
        patch_contract_ok(applied)
        patch_metadata_ok(applied)
        patch_flow_ok(applied)
        verdict.broken_links = applied["broken_links"]
        verdict.dependency_violations = applied["dependency_violations"]
        verdict.metadata_errors = applied["metadata_errors"]
        verdict.flow_errors = applied["flow_errors"]
        print(
            f"  {candidate.label}: apply_patch changed_skills={applied['changed_skills']} "
            f"broken_links={applied['broken_links']} "
            f"dependency_violations={applied['dependency_violations']} "
            f"metadata_errors={applied['metadata_errors']} "
            f"flow_errors={applied['flow_errors']}"
        )
        report = await call(
            client,
            "run_scenarios",
            {
                "workspace_id": ws["workspace_id"],
                "scenario_ids": [*GOLDEN_IDS, replay_id],
                "extra_checks": [INTENT_CHECK],
                "repeat": 1,
            },
        )
        verdict.all_passed = report["all_passed"]
        verdict.results = {r["scenario_id"]: r for r in report["results"]}
    except ToolError as e:
        verdict.error = f"{e.tool} {json.dumps(e.payload, ensure_ascii=False)}"
    except Exception as e:  # noqa: BLE001 - 한 후보가 막혀도 다음 후보는 판정한다
        verdict.error = f"{type(e).__name__}: {e}"
    return verdict


async def evidence(agent: OrderAgent, verdicts: list[Verdict], replay_id: str) -> None:
    """후보마다 시험 주문별 결과, run 별 실행 기록 요약(trace_line), 떨어진 이유를 찍는다.

    run_id 는 r_ 별칭이라 execution_uuid 로 하이픈 UUID 로 바꿔 주문 Agent 에서 trace 를 읽는다.
    """
    for v in verdicts:
        print(f"\n[{v.label}] workspace={v.workspace_id or '-'}")
        if v.error:
            print(f"  판정 못 함: {v.error}")
            continue
        print(
            f"  끊긴 연결: {json.dumps(v.broken_links, ensure_ascii=False)}  "
            f"선행 단계 위반: {json.dumps(v.dependency_violations, ensure_ascii=False)}  "
            f"필수 정보 오류: {json.dumps(v.metadata_errors, ensure_ascii=False)}  "
            f"실행 구조 오류: {json.dumps(v.flow_errors, ensure_ascii=False)}  "
            f"run_scenarios.all_passed={v.all_passed}"
        )
        for sid in [*GOLDEN_IDS, replay_id]:
            r = v.results.get(sid)
            if not r:
                continue
            print(
                f"  - {sid}: passed={r['passed']} (의도 제외 {mark(base_passed(r))}, "
                f"의도: {intent_state(r)}) replay_of_run_id={r['replay_of_run_id']}"
            )
            for run_id in r["run_ids"]:
                execution_id = execution_uuid(run_id)
                print(f"      run_id={run_id} execution_id={execution_id}")
                print(f"      {trace_line(await agent.trace(execution_id))}")
            for f in r["failures"]:
                print(f"      떨어진 이유 (skill={f['skill']}): {f['reason']}")


async def main(reuse_failed: str | None) -> int:
    """실험 여섯 단계를 모듈 docstring 순서대로 돌리고 종료 코드(0, 1, 3)를 돌려준다.

    주문 Agent HTTP client 와 raw MCP client 를 함께 연다. reuse_failed 가 있으면 2단계에서 실제
    주문을 넣지 않고 그 실행을 재현한다.
    """
    print(f"service MCP    : {MCP_URL}")
    print(f"주문 Agent     : {ORDER_AGENT_URL}")
    async with (
        httpx.AsyncClient(base_url=ORDER_AGENT_URL, timeout=60) as http,
        mcp_client() as client,
    ):
        agent = OrderAgent(http)

        section("1. 시연 snapshot 고정")
        snaps: dict[str, str] = {}
        manifests: dict[str, dict] = {}
        for key, fixture in FIXTURES.items():
            manifest = await agent.fixture(fixture)
            snaps[key] = manifest["snapshot_id"]
            manifests[key] = manifest
            print(
                f"  {key:9s} {manifest['snapshot_id']}  hash={manifest['hash'][:12]}  "
                f"files={len(manifest['files'])}"
            )
        others = {
            k: {f["path"]: f["hash"] for f in m["files"] if not f["path"].endswith("/SKILL.md")}
            for k, m in manifests.items()
        }
        if len({json.dumps(o, sort_keys=True) for o in others.values()}) > 1:
            print("  주의: resources 파일이 snapshot 마다 달라 apply_patch 로는 옮기지 못합니다")

        section("2. 실제 실패 만들기")
        if reuse_failed:
            replay_execution = reuse_failed
            print(f"  --reuse-failed: 실제 주문 없이 execution {replay_execution} 을 재현합니다")
        else:
            seen = {i["execution_id"] for i in await agent.failed()}
            resp = await agent.live_order(snaps["broken"])
            try:
                body = resp.json()
            except ValueError:
                body = {}
            print(
                f"  POST /api/orders (snapshot=broken) -> HTTP {resp.status_code} "
                f"status={body.get('status')} code={body.get('code')} "
                f"order_id={body.get('order_id')} execution_id={body.get('execution_id')}"
            )
            replay_execution = body.get("execution_id")
            if not replay_execution:
                # 응답에 execution_id 가 없으면 실패 목록에 새로 생긴 한 건을 쓴다.
                new = [i for i in await agent.failed() if i["execution_id"] not in seen]
                if len(new) != 1:
                    print(f"  실패 주문의 execution_id 를 찾지 못했습니다: {resp.text[:400]}")
                    return 1
                replay_execution = new[0]["execution_id"]
        failed = await agent.failed()
        mine = next((i for i in failed if i["execution_id"] == replay_execution), None)
        print(f"  GET /api/executions/failed -> {len(failed)}건")
        if mine is None:
            print(
                f"  execution {replay_execution} 이 실패 목록에 없습니다. 실험을 이어갈 수 없습니다"
            )
            return 1
        print(
            f"  실패: execution_id={mine['execution_id']} order_id={mine['order_id']} "
            f"final_status={mine['final_status']}"
        )
        print(f"        error={json.dumps(mine.get('error'), ensure_ascii=False)}")
        replay_id = f"f-{replay_execution}"

        section("3. 판정 시작 전 측정 (2단계의 실패 1건이 들어간 뒤)")
        stock_before = await agent.lantern_stock()
        failed_before = len(await agent.failed())
        print(f"  랜턴 재고 {stock_before}, 실패 목록 {failed_before}건")

        section("4. 후보 만들기")
        probe = await call(client, "create_workspace", {"snapshot_id": snaps["broken"]})
        skills = sorted(probe["skill_versions"])
        print(f"  skill {len(skills)}개: {', '.join(skills)}")
        candidates = await build_candidates(client, agent, snaps, skills)
        for c in candidates:
            print(f"  {c.label}: {c.summary}; 고칠 skill {sorted(c.patches)}")
            for skill, patch in c.patches.items():
                print(f"    --- apply_patch 에 넣는 수정 내용 ({skill}) ---")
                print("".join(f"    | {line}" for line in patch.splitlines(keepends=True)).rstrip())

        section("5. 후보 판정 (후보마다 새 연습 공간)")
        verdicts = [await judge(client, c, snaps["broken"], replay_id) for c in candidates]

        section("결과 표")
        print(table(verdicts, replay_id))
        print()
        print(f"시험 주문: {', '.join(GOLDEN_IDS)} + 실제 실패 주문 재현 {replay_id}")
        print(
            f"의도 확인: 모든 주문에 덧붙인 `{INTENT_CHECK}`. 시나리오 칸은 이 조건을 뺀 합격 여부"
        )
        print("          확인 못 함 = 오류 등 다른 이유로 떨어져 이 조건을 따로 볼 수 없음")
        print("유효: 위 시험 주문 네 개가 모두 합격(의도 확인 포함)이고 끊긴 연결이 0")

        section("실행 기록 근거")
        await evidence(agent, verdicts, replay_id)

        section("6. 판정 후 측정")
        stock_after = await agent.lantern_stock()
        failed_after = len(await agent.failed())
        print(f"  랜턴 재고 {stock_before} -> {stock_after}")
        print(f"  실패 목록 {failed_before}건 -> {failed_after}건")
        unjudged = [v.label for v in verdicts if v.error]
        if unjudged:
            print(f"  주의: {len(unjudged)}개 후보를 판정하지 못했습니다: {', '.join(unjudged)}")
        if (stock_before, failed_before) != (stock_after, failed_after):
            print("  FAIL: 판정이 실제 주문 상태(재고 또는 실패 목록)를 바꿨습니다")
            return 1
        if unjudged:
            print("  재고와 실패 목록은 그대로지만 판정하지 못한 후보가 있어 끝나지 않았습니다")
            return 3
        print("  OK: 재고와 실패 목록이 그대로입니다")
    return 0


if __name__ == "__main__":
    parser = argparse.ArgumentParser(
        description="실제 주문 Agent 로 수정 후보 판정 (docstring 참고)"
    )
    parser.add_argument(
        "--reuse-failed",
        metavar="EXECUTION_ID",
        help="실제 주문을 새로 넣지 않고, 이미 실패 목록에 있는 execution 을 재현한다",
    )
    # httpx 오류(연결 실패와 raise_for_status 의 실패 상태), OSError, MCP SDK 의 ExceptionGroup 은
    # 실험을 이어갈 수 없다는 뜻이므로, traceback 대신 안내 문구를 찍고 종료 코드 1 로 끝낸다.
    try:
        code = asyncio.run(main(parser.parse_args().reuse_failed))
    except (httpx.HTTPError, OSError, ExceptionGroup) as e:
        print(
            f"연결하지 못했습니다. service({MCP_URL})와 주문 Agent({ORDER_AGENT_URL}) 확인: {e!r}"
        )
        code = 1
    sys.exit(code)
