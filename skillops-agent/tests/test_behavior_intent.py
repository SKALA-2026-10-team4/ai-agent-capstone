"""담당자 의도(intent)가 있는 행동 변경 수정본의 진단 흐름을 그래프 끝까지 돌려 고정한다.

docs/plans/2026-10-26-behavior-edit-root-fix.md 의 0절 완료 판정 1·3 과 2.5·2.6 을 보호한다.
네트워크와 유료 호출은 없다. skillops-agent/skillops_agent/nodes.py 의 get_tools 를 아래
BehaviorStub(B-01 모양: payment 본문에 "선입금 주문만 결제" 문장만 더한 수정본)이나 StubMCPTools 로,
make_structured_llm 을 가짜 LLM 으로 바꿔 끼운다. 보호하는 동작은 다음과 같다.
- 의도 범위 기준 주문만 오류 없이 떨어지면 INTENT_NOT_REALIZED 로 원인 실험 없이 후보로 가고, LLM
  후보가 없거나 LLM 이 실패해도 결정적 encode_rule 후보 하나가 합격한다.
- 기준본 사전 검증과 원인 실험에는 intent_expectations 를 붙이지 않고, 수정본 사전 검증과 후보
  검증에는 붙인다. 시험을 돌린 workspace 에 patch 를 다시 넣지 않는다(WORKSPACE_FROZEN).
- 의도가 있으면 되돌리기와 계약 약화 후보를 실행 결과와 상관없이 코드가 불합격시킨다. 의도가 없으면
  되돌리기 후보도 합격하고 사전 검증은 예전과 같다.
- 담당자 글만 있는 의도는 LLM expectations 를 걸러 쓰고(출처 inferred), 값 이름 후보에 선언
  inputs/outputs 와 예약 키가 들어간다(D1).
- 저장 직후 요청에 의도 글만 있으면 fetch_evidence 가 사전 검증 전에 LLM 으로 expectations 를 한 번
  만들어(_ExpectationsOutput) 본문만 고친 수정본도 INTENT_NOT_REALIZED 로 찾고 결정적 encode_rule
  후보가 합격한다. 못 만들면 예전처럼 의도 시험 없이 사전 검증하고 diagnosis.intent_note 를 남긴다.
  담당자 expectations 가 있거나 의도가 없으면 이 LLM 호출은 없다.
- 계약 보정 v3(2.10). scope 가 null 인 의도로만 떨어진 주문은 의도 없이 다시 돌려 의도 미실현으로
  가린다(V3-3). Agent 가 만든 expectations 는 기준본이 이미 통과하거나, scope 밖 기준 주문이 없거나,
  맞는 기준 주문이 없으면 버리고, extract_intent 도 10개·10개 상한을 지킨다(V3-4). 읽을 수 없는
  담당자 항목은 빼고 남긴다(V3-2). 결정적 후보는 바뀐 skill 과 가장 이른 단계 두 곳이고 같은 when 이
  있으면 input 만 더한다(V3-6, B-02·B-05). 결과 불일치 원인 실험이 가설 없음·대조 실행 흔들림으로
  끝나거나 재시도에서 LLM 이 실패해도 ERROR 가 아니다(V3-8).
"""

import json
import re
from pathlib import Path
from types import SimpleNamespace
from uuid import uuid4

import pytest

from skillops_agent import config, nodes, skill_rules
from skillops_agent.contracts import SCOPE_KEYS
from skillops_agent.graph import graph
from skillops_agent.stub_tools import (
    _GOLDEN_FIXTURE_IDS,
    StubMCPTools,
    _intent_scoped,
    _snapshot_hash,
)
from skillops_agent.tools import MCPToolError

BASELINE = StubMCPTools.BASELINE
CANDIDATE = StubMCPTools.CANDIDATE
EXECUTION = StubMCPTools.EXECUTION
REPLAY = f"f-{EXECUTION}"
GOLDEN = ["g-01", "g-02", "g-03", "g-04", "g-05", "g-06"]
STEPS = ["order-intake", "inventory-check", "payment", "shipping-reserve", "notify"]
# 담당자가 저장 요청에 준 B-01 의도다(2.9).
OWNER_EXPECTATIONS = [
    {
        "scope": "order.payment_method == 'card'",
        "checks": ["order.final_status in ['HOLD', 'REJECTED']", "payment.status == null"],
    },
    {"scope": "order.payment_method == 'prepaid'", "checks": ["order.final_status == 'SHIPPED'"]},
]
OWNER_INTENT = {
    "text": "선입금 주문만 결제하고 카드 주문은 결제하지 않는다",
    "expectations": OWNER_EXPECTATIONS,
}


def skill(name, inputs, outputs, body, extra=""):
    """order-agent/skills 와 같은 모양의 단계 skill 원문을 만든다."""
    return (
        f"---\nname: {name}\ndescription: {name} 단계\ninputs: [{', '.join(inputs)}]\n"
        f"outputs: [{', '.join(outputs)}]\ndepends_on: []\n{extra}owner: 주문운영팀\nversion: 1\n"
        f"---\n# {name}\n\n{body}\n"
    )


FLOW = (
    "---\nname: order-flow\ndescription: 처리 순서\n"
    f"steps: [{', '.join(STEPS)}]\nowner: 주문운영팀\nversion: 1\n---\n# 처리 순서\n"
)
PAYMENT_INPUTS = ["order.id", "order.total", "order.payment_method", "inventory.reserved"]
PAYMENT_BASE = skill(
    "payment", PAYMENT_INPUTS, ["payment.id", "payment.status"], "결제 도구로 결제합니다."
)
PAYMENT_EDIT = PAYMENT_BASE.replace(
    "결제 도구로 결제합니다.", "결제 도구로 결제합니다.\n선입금 주문만 결제합니다."
)
INTAKE = skill(
    "order-intake",
    ["order.id", "order.customer", "order.items", "order.payment_method", "order.shipping"],
    ["order.id", "order.total"],
    "주문을 확인합니다.",
)
SOURCES = {
    "order-flow": FLOW,
    "order-intake": INTAKE,
    "inventory-check": skill(
        "inventory-check", ["order.id", "order.items"], ["inventory.reserved"], "재고를 잡습니다."
    ),
    "payment": PAYMENT_BASE,
    "shipping-reserve": skill(
        "shipping-reserve",
        ["order.id", "order.shipping", "payment.id", "payment.status", "inventory.reserved"],
        ["shipping.id", "shipping.eta"],
        "배송을 예약합니다.",
    ),
    "notify": skill("notify", ["order.id", "shipping.id"], ["notify.sent"], "알립니다."),
}
CARD_RULE = "order.payment_method == 'card'"
INTAKE_WITH_RULE = INTAKE.replace(
    "owner:", f'rules:\n  - when: "{CARD_RULE}"\n    then: hold\n    reason: NO_CARD\nowner:'
)


def has_card_rule(patches):
    """patch 가 결제 방식을 보는 rules 항목을 더하는지 본다.

    stub 이 후보의 행동을 흉내 낼 때 쓴다.
    """
    return any(
        line.startswith("+") and "when:" in line and "order.payment_method" in line
        for patch in patches
        for line in patch["patch"].splitlines()
    )


B01_HUNK = {
    "index": 0,
    "hunk_id": "h_policy",
    "part": "body",
    "header": "@@ -12 +12,2 @@",
    "before": "결제 도구로 결제합니다.",
    "after": "결제 도구로 결제합니다.\n선입금 주문만 결제합니다.",
    "diff": "@@ -12 +12,2 @@\n 결제 도구로 결제합니다.\n+선입금 주문만 결제합니다.\n",
}


class BehaviorStub(StubMCPTools):
    """B-01 모양의 수정본을 흉내 내는 C·주문 Agent 대역이다.

    payment 본문에만 정책 문장이 더해져 수정본은 기준본과 같은 결과를 낸다. intent_expectations 가
    오면 scope 에 맞는 기준 주문을 의도로 채점(intent_scoped, service 처럼 처음 값으로 평가)하고,
    카드 주문(g-01, g-03, g-05, g-06)은 결제 방식 rules 가 들어간 workspace 에서만 통과한다(g-02 는
    재고 거절, g-04 는 선입금). 의도가 없으면 rules 가 들어간 workspace 에서 카드 정상 주문이
    떨어진다. 시험을 돌린 workspace 에 apply_patch 가 오면 service 처럼 WORKSPACE_FROZEN 으로
    거절한다. 다른 B 케이스는 changed·edited·hunk 와 passes 를 바꾼 하위 클래스로 흉내 낸다.
    """

    changed = "payment"
    edited = PAYMENT_EDIT
    hunk = B01_HUNK

    def __init__(self, overrides=None):
        super().__init__()
        self.scenario_calls = []
        self.ran = set()
        self.rule = {}
        self.applied = {}
        self.snapshot_of = {}
        self.overrides = overrides

    def passes(self, workspace_id, scenario, intent_expectations):
        """시험 주문 하나의 통과 여부다.

        B-01 규칙(카드 주문은 결제 방식 rules 가 있어야 통과)이다.
        """
        rule = self.rule[workspace_id]
        if intent_expectations:
            return rule or scenario in ("g-02", "g-04")
        return not rule or scenario in ("g-02", "g-03", "g-04")

    async def get_snapshot(self, snapshot_id):
        return {
            "snapshot_id": snapshot_id,
            "hash": _snapshot_hash(snapshot_id),
            "metadata": {"baseline_snapshot_id": BASELINE},
            "files": [
                {
                    "path": f"skills/{name}/SKILL.md",
                    "hash": ("old" if snapshot_id == BASELINE else "new")
                    if name == self.changed
                    else "same",
                }
                for name in SOURCES
            ],
        }

    async def list_skills(self, snapshot_id):
        return {
            "snapshot_id": snapshot_id,
            "snapshot_hash": _snapshot_hash(snapshot_id),
            "items": [
                {
                    "skill_id": name,
                    "version": 1,
                    "inputs": skill_rules.inputs_of(skill_rules.frontmatter(text)),
                    "outputs": skill_rules.frontmatter(text).get("outputs") or [],
                }
                for name, text in SOURCES.items()
            ],
            "partial": False,
            "parse_errors": [],
        }

    async def get_skill(self, snapshot_id, skill_id):
        content = SOURCES[skill_id]
        if skill_id == self.changed and snapshot_id == CANDIDATE:
            content = self.edited
        return {"snapshot_id": snapshot_id, "skill_id": skill_id, "version": 1, "content": content}

    async def diff_skill(self, skill_id, before_snapshot_id, after_snapshot_id):
        return {
            "skill_id": skill_id,
            "before": {
                "snapshot_id": before_snapshot_id,
                "snapshot_hash": _snapshot_hash(before_snapshot_id),
                "file_hash": "old",
            },
            "after": {
                "snapshot_id": after_snapshot_id,
                "snapshot_hash": _snapshot_hash(after_snapshot_id),
                "file_hash": "new",
            },
            "status": "modified",
            "hunks": [self.hunk],
        }

    async def get_dependency_graph(self, snapshot_id, entry_skill_id):
        graph_view = await super().get_dependency_graph(snapshot_id, entry_skill_id)
        return {**graph_view, "steps": STEPS}

    async def create_workspace(self, start_versions=None, snapshot_id=None):
        ws = await super().create_workspace(start_versions, snapshot_id)
        self.rule[ws["workspace_id"]] = False
        self.snapshot_of[ws["workspace_id"]] = snapshot_id
        return {**ws, "skill_versions": dict.fromkeys(SOURCES, 1)}

    async def apply_patch(self, workspace_id, patches):
        if workspace_id in self.ran:
            raise MCPToolError("apply_patch", {"code": "WORKSPACE_FROZEN", "message": "frozen"})
        response = await super().apply_patch(workspace_id, patches)
        self.rule[workspace_id] = has_card_rule(patches)
        self.applied[workspace_id] = list(patches)
        return response

    async def run_scenarios(
        self,
        workspace_id,
        scenario_ids,
        repeat=2,
        replay_run_ids=None,
        extra_checks=None,
        intent_expectations=None,
    ):
        self.ran.add(workspace_id)
        self.scenario_calls.append((list(scenario_ids), intent_expectations))
        results = []
        for scenario in scenario_ids:
            passed = self.passes(workspace_id, scenario, intent_expectations)
            runs = [self._record_trial() for _ in range(repeat)]
            result = {
                "scenario_id": scenario,
                "passed": passed,
                "run_ids": runs,
                "failures": []
                if passed
                else [
                    {
                        "run_id": run,
                        "skill": None,
                        "reason": "확인 조건 실패: order.final_status in ['HOLD', 'REJECTED']"
                        " (실제 'SHIPPED')",
                    }
                    for run in runs
                ],
            }
            if intent_expectations:
                result["intent_scoped"] = _intent_scoped(scenario, intent_expectations)
            results.append(result)
        return {
            "workspace_id": workspace_id,
            "all_passed": all(r["passed"] for r in results),
            "results": results,
        }

    def _record_trial(self):
        execution = str(uuid4())
        self._traces[execution] = {
            "execution_id": execution,
            "snapshot_id": CANDIDATE,
            "completion": "COMPLETED",
            "final_status": "SHIPPED",
            "events": [
                {
                    "kind": "TOOL_REQUEST",
                    "skill_id": "payment",
                    "data": {"tool": "payment.charge"},
                }
            ],
        }
        return "r_" + execution.replace("-", "")

    async def save_proposal(self, proposal):
        saved = await super().save_proposal(proposal)
        if self.overrides is not None:
            saved = {**saved, **self.overrides}
        return saved


class FakeChain:
    """구조화 출력 체인의 대역이다. 정해 둔 출력이나 예외를 돌려준다."""

    def __init__(self, output):
        self.output = output

    async def ainvoke(self, *args):
        if isinstance(self.output, Exception):
            raise self.output
        return self.output


def llm_with(outputs):
    """출력 클래스 이름별 출력으로 make_structured_llm 대역을 만든다.

    outputs 에 없는 이름이면 테스트를 실패시킨다.
    """

    def make(schema):
        if schema.__name__ not in outputs:
            pytest.fail(f"unexpected LLM call: {schema.__name__}")
        return FakeChain(outputs[schema.__name__])

    return make


def revision(name, full_text):
    """LLM 후보 출력의 파일 하나다."""
    return SimpleNamespace(skill=name, full_text=full_text)


def no_candidates_llm():
    """의도 미실현 경로에서 LLM 후보를 하나도 내지 않는 가짜 LLM 이다. 가설·의도는 부르면 실패다."""
    return llm_with(
        {
            "_CandidatesOutput": SimpleNamespace(candidates=[]),
            "_RankExplanationsOutput": SimpleNamespace(explanations=[]),
        }
    )


@pytest.fixture
def behavior(monkeypatch):
    """BehaviorStub 하나를 nodes.get_tools 자리에 끼운다."""
    stub = BehaviorStub()
    monkeypatch.setattr(nodes, "get_tools", lambda: stub)
    return stub


def saved(intent=None):
    """수정본 저장 직후 요청의 초기 상태다. intent 를 주면 trigger.intent 로 넣는다."""
    trigger = {"type": "skill_saved", "candidate_snapshot_id": CANDIDATE}
    if intent is not None:
        trigger["intent"] = intent
    return {"trigger": trigger, "max_steps": 40}


async def run(state):
    """새 thread_id 로 그래프를 끝까지 돌린다."""
    return await graph.ainvoke(
        state, {"configurable": {"thread_id": str(uuid4())}, "recursion_limit": 100}
    )


def nodes_of(result):
    """진행 기록에 남은 노드 이름이다."""
    return [event["node"] for event in result["events"]]


INTENT_GAP_NODES = [
    "init",
    "fetch_evidence",
    "extract_intent",
    "generate_candidates",
    "verify_candidates",
    "check_valid",
    "rank_and_explain",
    "save_proposal",
]


# ── 의도 미실현(B-01) ─────────────────────────────────────────────────────────


@pytest.mark.asyncio
async def test_b01_intent_gap_ends_with_deterministic_encode_rule_without_llm_candidates(
    behavior, monkeypatch
):
    monkeypatch.setattr(nodes, "make_structured_llm", no_candidates_llm())
    result = await run(saved(OWNER_INTENT))

    assert result["error"] is None
    assert result["diagnosis_status"] == "EVALUATED"
    assert nodes_of(result) == INTENT_GAP_NODES
    assert result["diagnosis"]["finding"] == "INTENT_NOT_REALIZED"
    assert result["repro_scenario_ids"] == ["g-01", "g-03", "g-05", "g-06"]
    assert result["hypotheses"] == [] and result["ablation_runs"] == []
    root_cause = result["root_cause"]
    assert root_cause["kind"] == "incomplete_edit"
    assert (root_cause["skill"], root_cause["change_no"], root_cause["hunk_id"]) == (
        "payment",
        1,
        "h_policy",
    )
    assert root_cause["proof_run_ids"] == result["preflight_run_ids"]
    assert root_cause["repro_scenario_ids"] == ["g-01", "g-03", "g-05", "g-06"]

    intent = result["intent"]
    assert intent["source"] == "owner"
    assert intent["text"] == OWNER_INTENT["text"]
    assert intent["expectations"] == OWNER_EXPECTATIONS
    assert intent["checks"] == []

    # 결정적 후보는 바뀐 skill(payment)과 scope 키를 받는 가장 이른 단계(order-intake) 두 곳이다.
    candidate, earliest = result["candidates"]
    assert candidate["kind"] == "encode_rule"
    assert candidate["valid"] is True
    assert candidate["is_revert"] is False and candidate["weakens_contract"] is False
    [patch] = candidate["patches"]
    assert patch["skill"] == "payment" and patch["base_version"] == 1
    assert "+rules:\n" in patch["patch"]
    assert f'+  - when: "{CARD_RULE}"\n' in patch["patch"]
    assert "+    then: hold\n" in patch["patch"]
    assert "+    reason: OWNER_POLICY\n" in patch["patch"]
    assert candidate["results"]["fixes_failure"] is True
    assert candidate["results"]["keeps_old_orders"] == {"passed": 6, "total": 6}
    assert candidate["results"]["keeps_intent"] is True
    # 재현 주문이 기준 주문이라 회귀 실행(6개 × 2회)이 곧 재현 근거다.
    assert len(candidate["evidence_run_ids"]) == 12
    assert candidate["explanation"].startswith("rank 1: ")
    assert earliest["kind"] == "encode_rule" and earliest["valid"] is True
    assert [p["skill"] for p in earliest["patches"]] == ["order-intake"]
    assert earliest["rank"] == 2

    # 기준본에는 의도를 붙이지 않고 수정본 사전 검증과 후보 검증에만 붙인다. 원인 실험은 없다.
    assert behavior.scenario_calls == [
        (GOLDEN, None),
        (GOLDEN, OWNER_EXPECTATIONS),
        (GOLDEN, OWNER_EXPECTATIONS),
        (GOLDEN, OWNER_EXPECTATIONS),
    ]
    proposal = behavior.saved[-1]
    assert proposal["status"] == "proposed"
    assert proposal["recommended_candidate_no"] == 1
    assert proposal["root_cause"]["kind"] == "incomplete_edit"
    assert proposal["intent"]["source"] == "owner"
    assert proposal["trigger"]["intent"] == OWNER_INTENT
    assert "server_overrides" not in result["proposal"]


@pytest.mark.asyncio
async def test_deterministic_candidate_survives_llm_outage(behavior, monkeypatch):
    """LLM 호출이 모두 실패해도 결정적 후보로 검증·순위·저장까지 끝난다."""
    monkeypatch.setattr(
        nodes,
        "make_structured_llm",
        llm_with(
            {
                "_CandidatesOutput": RuntimeError("no model"),
                "_RankExplanationsOutput": RuntimeError("no model"),
            }
        ),
    )
    result = await run(saved(OWNER_INTENT))
    assert result["error"] is None
    assert result["diagnosis_status"] == "EVALUATED"
    assert [(c["kind"], c["valid"]) for c in result["candidates"]] == [("encode_rule", True)] * 2
    messages = {event["node"]: event["msg"] for event in result["events"]}
    assert "LLM 오류 RuntimeError" in messages["generate_candidates"]
    assert "LLM 오류 RuntimeError" in messages["rank_and_explain"]
    assert result["proposal"]["status"] == "proposed"


@pytest.mark.asyncio
async def test_server_overrides_are_kept_without_crashing(monkeypatch):
    """save_proposal 응답의 server_overrides 와 서버 상태를 그대로 남긴다."""
    overrides = [
        {"candidate_no": 1, "field": "keeps_intent", "agent_value": True, "server_value": False}
    ]
    stub = BehaviorStub(
        overrides={
            "status": "no_valid_fix",
            "valid_candidate_nos": [],
            "server_overrides": overrides,
        }
    )
    monkeypatch.setattr(nodes, "get_tools", lambda: stub)
    monkeypatch.setattr(nodes, "make_structured_llm", no_candidates_llm())
    result = await run(saved(OWNER_INTENT))
    assert result["error"] is None
    assert result["proposal"]["server_overrides"] == overrides
    assert result["proposal"]["status"] == "no_valid_fix"
    assert result["proposal"]["valid_candidate_nos"] == []


@pytest.mark.asyncio
async def test_revert_and_weakening_are_rejected_by_code_with_intent(behavior, monkeypatch):
    """실행은 통과해도 의도가 있으면 되돌리기와 입력 삭제 후보는 코드가 불합격시킨다.

    두 LLM 후보 모두 order-intake 에 카드 규칙을 넣어 시험은 통과한다. 하나는 payment 를 기준본으로
    되돌리고(is_revert), 하나는 의도 scope 가 쓰는 order.payment_method 를 payment inputs 에서
    지운다(weakens_contract).
    """
    weakened = PAYMENT_EDIT.replace("order.payment_method, ", "")
    monkeypatch.setattr(
        nodes,
        "make_structured_llm",
        llm_with(
            {
                "_CandidatesOutput": SimpleNamespace(
                    candidates=[
                        SimpleNamespace(
                            kind="linked_skills",
                            patches=[
                                revision("order-intake", INTAKE_WITH_RULE),
                                revision("payment", PAYMENT_BASE),
                            ],
                        ),
                        SimpleNamespace(
                            kind="encode_rule",
                            patches=[
                                revision("order-intake", INTAKE_WITH_RULE),
                                revision("payment", weakened),
                            ],
                        ),
                    ]
                ),
                "_RankExplanationsOutput": SimpleNamespace(explanations=[]),
            }
        ),
    )
    result = await run(saved(OWNER_INTENT))
    assert result["error"] is None
    *deterministic, revert, weaken = result["candidates"]
    assert [(c["kind"], c["valid"]) for c in deterministic] == [("encode_rule", True)] * 2
    assert (revert["is_revert"], revert["weakens_contract"]) == (True, False)
    assert (weaken["is_revert"], weaken["weakens_contract"]) == (False, True)
    for candidate in (revert, weaken):
        assert candidate["valid"] is False
        assert candidate["results"]["fixes_failure"] is True
        assert candidate["results"]["keeps_old_orders"] == {"passed": 6, "total": 6}
        assert candidate["results"]["keeps_intent"] is False
        assert candidate["rank"] is None
    assert result["proposal"]["recommended_candidate_no"] == 1
    assert result["proposal"]["valid_candidate_nos"] == [1, 2]


@pytest.mark.asyncio
async def test_without_intent_body_only_edit_is_validated_as_before(behavior, monkeypatch):
    """의도가 없으면 사전 검증에 intent_expectations 를 붙이지 않고 예전처럼 VALIDATED 로 끝난다."""
    monkeypatch.setattr(nodes, "make_structured_llm", lambda _: pytest.fail("LLM was called"))
    result = await run(saved())
    assert result["diagnosis_status"] == "VALIDATED"
    assert nodes_of(result) == ["init", "fetch_evidence"]
    assert behavior.scenario_calls == [(GOLDEN, None), (GOLDEN, None)]
    assert result["root_cause"] is None and result["repro_scenario_ids"] == []


# ── 실패 실행 진단에서의 의도 ──────────────────────────────────────────────────

INVENTORY_BASELINE = "---\nname: inventory-check\noutputs: [inventory.reserved]\n---\nbody\n"
FIXED_INVENTORY = INVENTORY_BASELINE


def stub_llm(intent_output, candidates):
    """StubMCPTools 실패 진단용 가짜 LLM 이다."""
    return llm_with(
        {
            "_HypothesesOutput": SimpleNamespace(
                hypotheses=[SimpleNamespace(change_no=1, summary="outputs", confidence=0.9)]
            ),
            "_IntentOutput": intent_output,
            "_CandidatesOutput": SimpleNamespace(candidates=candidates),
            "_RankExplanationsOutput": SimpleNamespace(explanations=[]),
        }
    )


def failed_report(report):
    """run_scenarios 응답의 모든 시나리오를 확인 조건 실패로 바꾼다."""
    for result in report["results"]:
        result["passed"] = False
        result["failures"] = [
            {"run_id": run, "skill": None, "reason": "확인 조건 실패: 의도와 다릅니다"}
            for run in result["run_ids"]
        ]
    report["all_passed"] = False
    return report


@pytest.fixture
def stub(monkeypatch):
    """기준 snapshot 의 inventory-check 원문을 실제 기준본(outputs 가 있는 것)으로 돌려주는 stub.

    기준본은 의도를 실현하지 않으므로 기준 snapshot workspace 를 intent_expectations 와 함께 돌리면
    모든 시나리오가 떨어진다(Agent 가 만든 expectations 의 기준본 확인, V3-4 의 c).
    """
    tools = StubMCPTools()
    original = tools.get_skill

    async def get_skill(snapshot_id, skill_id):
        source = await original(snapshot_id, skill_id)
        if snapshot_id == BASELINE and skill_id == "inventory-check":
            source = {**source, "content": INVENTORY_BASELINE}
        return source

    calls = []
    baseline_workspaces = set()
    run_scenarios, create_workspace = tools.run_scenarios, tools.create_workspace

    async def create(start_versions=None, snapshot_id=None):
        ws = await create_workspace(start_versions, snapshot_id)
        if snapshot_id == BASELINE:
            baseline_workspaces.add(ws["workspace_id"])
        return ws

    async def record(workspace_id, scenario_ids, **kwargs):
        calls.append((list(scenario_ids), kwargs))
        report = await run_scenarios(workspace_id, scenario_ids, **kwargs)
        intent = kwargs.get("intent_expectations")
        if workspace_id in baseline_workspaces and intent and not tools.baseline_passes_intent:
            report = failed_report(report)
        return report

    monkeypatch.setattr(tools, "get_skill", get_skill)
    monkeypatch.setattr(tools, "create_workspace", create)
    monkeypatch.setattr(tools, "run_scenarios", record)
    # 참이면 기준본이 의도 expectations 를 이미 모두 통과한다(늘 참인 의도).
    tools.baseline_passes_intent = False
    monkeypatch.setattr(nodes, "get_tools", lambda: tools)
    tools.calls = calls
    return tools


def same_skill_revert():
    """inventory-check 를 기준본으로 되돌리는 LLM 후보다."""
    patches = [revision("inventory-check", FIXED_INVENTORY)]
    return [SimpleNamespace(kind="same_skill", patches=patches)]


@pytest.mark.asyncio
async def test_revert_stays_valid_without_intent(stub, monkeypatch):
    """의도가 없는 기존 흐름(QA 의 revert_allowed)에서는 되돌리기 후보도 합격한다."""
    monkeypatch.setattr(
        nodes,
        "make_structured_llm",
        stub_llm(SimpleNamespace(text="t", checks=[]), same_skill_revert()),
    )
    result = await run({"trigger": {"type": "failure", "execution_id": EXECUTION}, "max_steps": 40})
    assert result["error"] is None
    [candidate] = result["candidates"]
    assert candidate["is_revert"] is True
    assert candidate["valid"] is True and candidate["rank"] == 1
    assert not any("intent_expectations" in kwargs for _, kwargs in stub.calls)


@pytest.mark.asyncio
async def test_owner_intent_on_failure_path_scopes_only_candidate_runs(stub, monkeypatch):
    """실패 진단에 의도가 있으면 원인 실험은 의도 없이, 후보 검증은 의도와 함께 돌린다.

    LLM 의 되돌리기 후보는 불합격이고, 결정적 encode_rule 후보는 원인 skill(inventory-check)에
    규칙과 조건 키 input 을 넣는다. 맨 앞의 결정적 후보는 원인 skill 의 계약 칸(outputs)을
    기준본으로 바로잡은 원문 위에 같은 규칙을 얹는다(_repair_and_encode).
    """
    monkeypatch.setattr(
        nodes,
        "make_structured_llm",
        stub_llm(SimpleNamespace(text="t", checks=[]), same_skill_revert()),
    )
    trigger = {"type": "failure", "execution_id": EXECUTION, "intent": OWNER_INTENT}
    result = await run({"trigger": trigger, "max_steps": 40})
    assert result["error"] is None
    assert result["intent"]["source"] == "owner"
    repaired, deterministic, revert = result["candidates"]
    assert repaired["kind"] == "encode_rule" and repaired["is_revert"] is False
    [fix] = repaired["patches"]
    assert "+outputs: [inventory.reserved]\n" in fix["patch"] and "+rules:\n" in fix["patch"]
    assert deterministic["kind"] == "encode_rule" and deterministic["valid"] is True
    [patch] = deterministic["patches"]
    assert patch["skill"] == "inventory-check"
    assert "+inputs: [order.payment_method]\n" in patch["patch"]
    assert revert["is_revert"] is True and revert["valid"] is False
    assert stub.calls[:2] == [
        ([REPLAY], {"repeat": 1, "extra_checks": None}),
        ([REPLAY], {"repeat": 1, "extra_checks": None}),
    ]
    expected = {"repeat": 2, "extra_checks": None, "intent_expectations": OWNER_EXPECTATIONS}
    assert stub.calls[2:] == [(GOLDEN, expected), ([REPLAY], expected)] * 3


@pytest.mark.asyncio
async def test_text_only_intent_uses_filtered_inferred_expectations(stub, monkeypatch):
    """담당자가 글만 주면 LLM expectations 를 걸러 쓰고 출처를 inferred 로 남긴다."""
    intent_output = SimpleNamespace(
        text="LLM 이 다시 쓴 문장",
        checks=["payment.status == 'PAID'"],
        expectations=[
            SimpleNamespace(
                scope="order.payment_method == 'card'", checks=["order.shipments == 0"]
            ),
            SimpleNamespace(scope="payment.status == 'X'", checks=["order.shipments == 0"]),
            SimpleNamespace(scope=None, checks=["inventory.unknown != null"]),
        ],
    )
    candidates = [
        SimpleNamespace(
            kind="same_skill",
            patches=[revision("inventory-check", FIXED_INVENTORY.replace("body", "body 2"))],
        )
    ]
    monkeypatch.setattr(nodes, "make_structured_llm", stub_llm(intent_output, candidates))
    owner = {"text": "카드 주문은 배송하지 않는다", "expectations": []}
    trigger = {"type": "failure", "execution_id": EXECUTION, "intent": owner}
    result = await run({"trigger": trigger, "max_steps": 40})
    assert result["error"] is None
    intent = result["intent"]
    assert intent["source"] == "inferred"
    assert intent["text"] == owner["text"]
    assert intent["expectations"] == [
        {"scope": "order.payment_method == 'card'", "checks": ["order.shipments == 0"]},
        {"scope": None, "checks": ["payment.status == 'PAID'"]},
    ]
    assert intent["checks"] == ["payment.status == 'PAID'"]
    dropped = [d["check"] for d in intent["dropped_checks"]]
    assert dropped == ["scope: payment.status == 'X'", "inventory.unknown != null"]
    # 기준본 확인(V3-4 의 c) 한 번과 후보 검증 한 번이다.
    sent = [kwargs.get("intent_expectations") for ids, kwargs in stub.calls if ids == GOLDEN]
    assert sent == [intent["expectations"]] * 2
    # 결정적 후보는 Agent 가 만든 expectations 로도 만들지만, 업무 종료(HOLD·REJECTED)를 기대하는
    # scope 있는 expectation 이 없으면 rules 로 옮길 것이 없어 만들지 않는다.
    assert [c["kind"] for c in result["candidates"]] == ["same_skill"]


@pytest.mark.asyncio
async def test_text_only_intent_with_no_usable_expectation_keeps_intent_false(stub, monkeypatch):
    """의도가 있는데 쓸 수 있는 expectation 이 하나도 없으면(빈 의도) keeps_intent 가 거짓이다."""
    intent_output = SimpleNamespace(text="t", checks=[], expectations=[])
    candidates = [
        SimpleNamespace(
            kind="same_skill",
            patches=[revision("inventory-check", FIXED_INVENTORY.replace("body", "body 2"))],
        )
    ]
    monkeypatch.setattr(nodes, "make_structured_llm", stub_llm(intent_output, candidates))
    owner = {"text": "무언가 바꾼다", "expectations": []}
    trigger = {"type": "failure", "execution_id": EXECUTION, "intent": owner}
    result = await run({"trigger": trigger, "max_steps": 40})
    assert result["error"] is None
    assert result["intent"]["expectations"] == []
    assert all(c["results"]["keeps_intent"] is False for c in result["candidates"])
    assert result["proposal"]["status"] == "no_valid_fix"


@pytest.mark.asyncio
async def test_intent_value_names_include_declared_inputs_outputs_and_reserved_keys(
    stub, monkeypatch
):
    """의도 조건의 값 이름 후보는 실행 기록 + 선언 inputs/outputs + 예약 키다(D1)."""
    original = stub.list_skills

    async def declared(snapshot_id):
        listing = await original(snapshot_id)
        listing["items"][2] = {**listing["items"][2], "outputs": ["payment.refund_id"]}
        return listing

    monkeypatch.setattr(stub, "list_skills", declared)
    intent_output = SimpleNamespace(
        text="t",
        checks=["payment.refund_id == null", "order.final_status == 'SHIPPED'", "x.y == 1"],
    )
    monkeypatch.setattr(nodes, "make_structured_llm", stub_llm(intent_output, same_skill_revert()))
    result = await run({"trigger": {"type": "failure", "execution_id": EXECUTION}, "max_steps": 40})
    assert result["intent"]["checks"] == [
        "payment.refund_id == null",
        "order.final_status == 'SHIPPED'",
    ]
    assert [d["check"] for d in result["intent"]["dropped_checks"]] == ["x.y == 1"]
    assert result["intent"]["expectations"] == []


@pytest.mark.asyncio
async def test_workspaces_are_never_patched_after_running(monkeypatch):
    """원인 실험과 후보 검증 모두 새 workspace 에 시험 전에 한 번만 patch 를 넣는다."""
    tools = StubMCPTools()
    ran = set()
    apply_patch, run_scenarios = tools.apply_patch, tools.run_scenarios

    async def guarded_apply(workspace_id, patches):
        if workspace_id in ran:
            raise MCPToolError("apply_patch", {"code": "WORKSPACE_FROZEN", "message": "frozen"})
        return await apply_patch(workspace_id, patches)

    async def recorded_run(workspace_id, scenario_ids, **kwargs):
        ran.add(workspace_id)
        return await run_scenarios(workspace_id, scenario_ids, **kwargs)

    monkeypatch.setattr(tools, "apply_patch", guarded_apply)
    monkeypatch.setattr(tools, "run_scenarios", recorded_run)
    monkeypatch.setattr(nodes, "get_tools", lambda: tools)
    monkeypatch.setattr(
        nodes,
        "make_structured_llm",
        stub_llm(
            SimpleNamespace(text="t", checks=["payment.status == 'PAID'"]), same_skill_revert()
        ),
    )
    result = await run({"trigger": {"type": "failure", "execution_id": EXECUTION}, "max_steps": 40})
    assert result["error"] is None
    assert result["proposal"]["status"] == "proposed"
    assert result["candidates"][0]["valid"] is True


# ── 의도 글만 있을 때 사전 검증 전 expectations 추론 (F1) ─────────────────────────

# 담당자가 저장 화면에서 의도 글만 적은 B-01 요청이다. 주문 Agent 와 service 를 거쳐 오면
# expectations 는 빈 목록이다(skillops-agent/skillops_agent/main.py 의 OwnerIntent).
TEXT_ONLY_INTENT = {"text": OWNER_INTENT["text"], "expectations": []}
PREPAID_RULE = "order.payment_method != 'prepaid'"
# 가짜 LLM 이 의도 글로 만든 B-01 expectations 다(prompts/infer_expectations.md 의 예시와 같다).
# 선입금 주문(g-04)은 scope 밖이라 기존 기대 결과로 채점한다.
INFERRED_EXPECTATIONS = [
    {
        "scope": PREPAID_RULE,
        "checks": ["order.final_status in ['HOLD', 'REJECTED']", "payment.status == null"],
    },
]


def expectation_items(expectations):
    """expectations dict 목록을 LLM 출력 항목 모양(scope, checks 속성)으로 바꾼다."""
    return [SimpleNamespace(scope=e["scope"], checks=list(e["checks"])) for e in expectations]


class RecordingLLM:
    """출력 클래스 이름별 출력을 돌려주고 부른 이름과 프롬프트를 남기는 LLM 대역이다.

    nodes 의 make_structured_llm 자리에 끼운다. 출력이 예외면 ainvoke 에서 던진다. outputs 에 없는
    이름이면 테스트를 실패시킨다.
    """

    def __init__(self, outputs):
        self.outputs = outputs
        self.calls = []

    def __call__(self, schema):
        name = schema.__name__
        if name not in self.outputs:
            pytest.fail(f"unexpected LLM call: {name}")
        calls, output = self.calls, self.outputs[name]

        class Chain:
            async def ainvoke(self, prompt):
                calls.append((name, prompt))
                if isinstance(output, Exception):
                    raise output
                return output

        return Chain()

    def names(self):
        """부른 출력 클래스 이름을 순서대로 돌려준다."""
        return [name for name, _ in self.calls]

    def prompt(self, name):
        """그 출력 클래스로 한 번만 부른 프롬프트를 돌려준다."""
        [prompt] = [text for called, text in self.calls if called == name]
        return prompt


def messages_of(result):
    """노드 이름별 진행 메시지다."""
    return {event["node"]: event["msg"] for event in result["events"]}


@pytest.mark.asyncio
async def test_text_only_b01_infers_expectations_and_encodes_rule(behavior, monkeypatch):
    """의도 글만 있는 B-01 저장본도 추론한 expectations 로 의도 미실현을 찾는다.

    추론한 expectations 로 만든 결정적 encode_rule 후보가 합격한다.
    """
    llm = RecordingLLM(
        {
            "_ExpectationsOutput": SimpleNamespace(
                expectations=expectation_items(INFERRED_EXPECTATIONS)
            ),
            "_CandidatesOutput": SimpleNamespace(candidates=[]),
            "_RankExplanationsOutput": SimpleNamespace(explanations=[]),
        }
    )
    monkeypatch.setattr(nodes, "make_structured_llm", llm)
    result = await run(saved(TEXT_ONLY_INTENT))

    assert result["error"] is None
    assert result["diagnosis_status"] == "EVALUATED"
    assert nodes_of(result) == INTENT_GAP_NODES
    assert result["step_count"] == len(INTENT_GAP_NODES) - 1
    # 추론은 한 번뿐이고 extract_intent 는 LLM 을 다시 부르지 않는다.
    assert llm.names() == ["_ExpectationsOutput", "_CandidatesOutput", "_RankExplanationsOutput"]
    assert result["diagnosis"]["finding"] == "INTENT_NOT_REALIZED"
    assert "intent_note" not in result["diagnosis"]
    assert result["repro_scenario_ids"] == ["g-01", "g-03", "g-05", "g-06"]
    assert result["root_cause"]["kind"] == "incomplete_edit"
    assert result["root_cause"]["proof_run_ids"] == result["preflight_run_ids"]
    assert result["intent"] == {
        "text": TEXT_ONLY_INTENT["text"],
        "checks": [],
        "dropped_checks": [],
        "confirmed": None,
        "source": "inferred",
        "expectations": INFERRED_EXPECTATIONS,
    }
    # 추론한 expectations 를 기준본에 먼저 붙여 기준본이 이미 통과하지 않는지 본다(V3-4 의 c).
    # 그다음 기준본 사전 검증은 의도 없이, 수정본 사전 검증과 후보 검증(두 후보)은 추론한
    # expectations 로 돌린다.
    assert behavior.scenario_calls == [
        (GOLDEN, INFERRED_EXPECTATIONS),
        (GOLDEN, None),
        (GOLDEN, INFERRED_EXPECTATIONS),
        (GOLDEN, INFERRED_EXPECTATIONS),
        (GOLDEN, INFERRED_EXPECTATIONS),
    ]

    candidate, earliest = result["candidates"]
    assert earliest["kind"] == "encode_rule" and earliest["valid"] is True
    assert candidate["kind"] == "encode_rule" and candidate["valid"] is True
    assert (candidate["is_revert"], candidate["weakens_contract"]) == (False, False)
    [patch] = candidate["patches"]
    assert patch["skill"] == "payment" and patch["base_version"] == 1
    assert f'+  - when: "{PREPAID_RULE}"\n' in patch["patch"]
    assert "+    then: hold\n" in patch["patch"]
    assert "+    reason: OWNER_POLICY\n" in patch["patch"]
    assert candidate["results"]["keeps_intent"] is True
    assert candidate["results"]["keeps_old_orders"] == {"passed": 6, "total": 6}

    proposal = behavior.saved[-1]
    assert proposal["status"] == "proposed"
    assert proposal["intent"]["source"] == "inferred"
    assert proposal["intent"]["expectations"] == INFERRED_EXPECTATIONS
    assert proposal["trigger"]["intent"] == TEXT_ONLY_INTENT
    messages = messages_of(result)
    assert messages["fetch_evidence"] == "실제 snapshot과 실행 증거 수집, 의도 기대 결과 1개 추론"
    assert messages["extract_intent"] == "사전 검증 전에 추론한 의도 사용 (기대 1개, 버린 조건 0개)"

    # 프롬프트에는 의도 글, 변경 hunk, 수정본 frontmatter, 기준 주문 표, 값 이름이 들어간다.
    prompt = llm.prompt("_ExpectationsOutput")
    assert TEXT_ONLY_INTENT["text"] in prompt
    assert "### change 1 · payment (body)" in prompt
    assert "+선입금 주문만 결제합니다." in prompt
    assert "### payment\nname: payment\n" in prompt
    assert "inputs: [order.id, order.total, order.payment_method, inventory.reserved]" in prompt
    assert "### order-flow\nname: order-flow\n" in prompt
    assert (
        "| g-06 | 랜턴 4개 카드 결제 고액 정상 주문 | SHIPPED | 'card' | 'c_1001' | 'KR' | false"
        " | 120000 |"
    ) in prompt
    assert "| g-02 | 머그컵 재고 없음 업무 거절(결제 전) | REJECTED |" in prompt
    assert ", ".join(SCOPE_KEYS) in prompt
    value_names = prompt.split("## checks 에 쓸 수 있는 값 이름")[1].split("## 조건 문법")[0]
    for name in ("payment.status", "order.total", "order.final_status", "order.shipments"):
        assert name in value_names
    assert "{" not in prompt.replace("{text, expectations}", "")


@pytest.mark.parametrize(
    ("output", "reason"),
    [
        (RuntimeError("no model"), "RuntimeError 오류"),
        (SimpleNamespace(expectations=[]), "쓸 수 있는 기대 결과를 내지 않았습니다"),
        (SimpleNamespace(), "쓸 수 있는 기대 결과를 내지 않았습니다"),
    ],
    ids=["llm-error", "empty", "missing-field"],
)
@pytest.mark.asyncio
async def test_inference_failure_stops_as_needs_intent_and_notes_why(
    behavior, monkeypatch, output, reason
):
    """추론이 실패하거나 빈 결과면 사전 검증한 뒤 의도 확인 필요(finding NEEDS_INTENT)로 멈춘다.

    사전 검증이 통과해도 담당자 의도를 판정하지 못했으므로 VALIDATED 로 끝내지 않는다.
    """
    llm = RecordingLLM({"_ExpectationsOutput": output})
    monkeypatch.setattr(nodes, "make_structured_llm", llm)
    result = await run(saved(TEXT_ONLY_INTENT))

    assert result["error"] is None
    assert result["diagnosis_status"] == "EVALUATED"
    assert result["diagnosis"]["finding"] == "NEEDS_INTENT"
    assert nodes_of(result) == ["init", "fetch_evidence"]
    assert llm.names() == ["_ExpectationsOutput"]
    assert behavior.scenario_calls == [(GOLDEN, None), (GOLDEN, None)]
    assert result["intent"] is None and result["root_cause"] is None
    diagnosis = result["diagnosis"]
    assert "시험 조건(expectations)으로 바꾸지 못해 의도 시험 없이 사전 검증했습니다" in (
        diagnosis["intent_note"]
    )
    assert reason in diagnosis["intent_note"]
    assert diagnosis["explanation"].startswith("담당자 의도를 시험 조건(기대 결과)으로 바꾸지 못해")
    assert "모두 이전과 같게 처리됐습니다" in diagnosis["explanation"]
    assert diagnosis["explanation"].endswith(diagnosis["intent_note"])
    assert diagnosis["preflight_failed"] is False
    assert diagnosis["intent_scope_keys"] == list(SCOPE_KEYS)
    assert diagnosis["failures"] == []
    assert "intent_dropped_checks" not in diagnosis
    assert messages_of(result)["fetch_evidence"] == (
        "실제 snapshot과 실행 증거 수집, 의도 기대 결과 추론 실패(의도 시험 없이 사전 검증), "
        "의도 확인 필요"
    )


@pytest.mark.asyncio
async def test_source_read_failure_during_inference_does_not_crash(behavior, monkeypatch):
    """frontmatter 를 읽다 도구가 실패해도 LLM 을 부르지 않고 사전 검증한 뒤 의도 확인을 묻는다."""

    async def broken_get_skill(snapshot_id, skill_id):
        raise MCPToolError("get_skill", {"code": "UNAVAILABLE", "message": "down"})

    monkeypatch.setattr(behavior, "get_skill", broken_get_skill)
    llm = RecordingLLM({})
    monkeypatch.setattr(nodes, "make_structured_llm", llm)
    result = await run(saved(TEXT_ONLY_INTENT))
    assert result["error"] is None
    assert result["diagnosis_status"] == "EVALUATED"
    assert result["diagnosis"]["finding"] == "NEEDS_INTENT"
    assert llm.names() == []
    assert "MCPToolError 오류" in result["diagnosis"]["intent_note"]


@pytest.mark.asyncio
async def test_invalid_inferred_lines_are_dropped_with_reasons(behavior, monkeypatch):
    """추론한 expectations 중 scope 키·문법·값 이름이 맞지 않는 것은 이유와 함께 버린다."""
    items = [
        SimpleNamespace(scope="payment.status == 'X'", checks=["order.final_status == 'HOLD'"]),
        SimpleNamespace(scope="order.total>=1", checks=["order.final_status == 'HOLD'"]),
        SimpleNamespace(
            scope=PREPAID_RULE,
            checks=[
                "order.final_status in ['HOLD', 'REJECTED']",
                "inventory.unknown != null",
                "order.total >= 'big'",
                "payment.status == null",
            ],
        ),
        SimpleNamespace(scope=None, checks=["x.y == 1"]),
    ]
    llm = RecordingLLM(
        {
            "_ExpectationsOutput": SimpleNamespace(expectations=items),
            "_CandidatesOutput": SimpleNamespace(candidates=[]),
            "_RankExplanationsOutput": SimpleNamespace(explanations=[]),
        }
    )
    monkeypatch.setattr(nodes, "make_structured_llm", llm)
    result = await run(saved(TEXT_ONLY_INTENT))

    assert result["error"] is None
    kept = [
        {
            "scope": PREPAID_RULE,
            "checks": ["order.final_status in ['HOLD', 'REJECTED']", "payment.status == null"],
        }
    ]
    intent = result["intent"]
    assert intent["source"] == "inferred"
    assert intent["expectations"] == kept
    dropped = [(d["check"], d["reason"]) for d in intent["dropped_checks"]]
    assert [check for check, _ in dropped] == [
        "scope: payment.status == 'X'",
        "scope: order.total>=1",
        "inventory.unknown != null",
        "order.total >= 'big'",
        "x.y == 1",
    ]
    assert "시험 주문의 처음 값" in dropped[0][1]
    assert dropped[1][1].startswith("형식이")
    assert dropped[2][1] == "실행 기록에 없는 값 이름입니다"
    assert dropped[3][1].startswith("숫자 비교")
    assert behavior.scenario_calls[:3] == [(GOLDEN, kept), (GOLDEN, None), (GOLDEN, kept)]
    assert result["diagnosis"]["finding"] == "INTENT_NOT_REALIZED"
    assert [(c["kind"], c["valid"]) for c in result["candidates"]] == [("encode_rule", True)] * 2
    assert result["proposal"]["intent"]["dropped_checks"] == intent["dropped_checks"]


@pytest.mark.asyncio
async def test_only_unusable_inferred_lines_stop_as_needs_intent(behavior, monkeypatch):
    """쓸 수 있는 줄이 하나도 없으면 의도 확인 필요로 멈추고 버린 줄을 diagnosis 에 남긴다."""
    items = [SimpleNamespace(scope="payment.status == 'X'", checks=["order.shipments == 0"])]
    llm = RecordingLLM({"_ExpectationsOutput": SimpleNamespace(expectations=items)})
    monkeypatch.setattr(nodes, "make_structured_llm", llm)
    result = await run(saved(TEXT_ONLY_INTENT))
    assert result["diagnosis_status"] == "EVALUATED"
    assert result["diagnosis"]["finding"] == "NEEDS_INTENT"
    assert behavior.scenario_calls == [(GOLDEN, None), (GOLDEN, None)]
    diagnosis = result["diagnosis"]
    assert "버린 조건 1개" in diagnosis["intent_note"]
    assert [d["check"] for d in diagnosis["intent_dropped_checks"]] == [
        "scope: payment.status == 'X'"
    ]


@pytest.mark.asyncio
async def test_owner_expectations_skip_inference(behavior, monkeypatch):
    """담당자가 expectations 를 주면 사전 검증 전 추론을 하지 않고 그대로 쓴다."""
    llm = RecordingLLM(
        {
            "_CandidatesOutput": SimpleNamespace(candidates=[]),
            "_RankExplanationsOutput": SimpleNamespace(explanations=[]),
        }
    )
    monkeypatch.setattr(nodes, "make_structured_llm", llm)
    result = await run(saved(OWNER_INTENT))
    assert result["error"] is None
    assert "_ExpectationsOutput" not in llm.names()
    assert result["intent"]["source"] == "owner"
    assert messages_of(result)["fetch_evidence"] == "실제 snapshot과 실행 증거 수집"
    assert behavior.scenario_calls[1] == (GOLDEN, OWNER_EXPECTATIONS)


def test_usable_expectations_caps_counts():
    """expectations 는 10개, 항목 하나의 checks 는 10개까지만 쓰고 넘친 것은 이유와 함께 버린다."""
    known = ["order.final_status", "payment.status"]
    many_checks = SimpleNamespace(
        scope=None, checks=[f"payment.status != 'S{i}'" for i in range(12)]
    )
    items = [many_checks] + [
        SimpleNamespace(scope=f"order.total >= {i}", checks=["order.final_status == 'HOLD'"])
        for i in range(11)
    ]
    kept, dropped = nodes._usable_expectations(items, known)
    assert len(kept) == 10
    assert kept[0] == {"scope": None, "checks": [f"payment.status != 'S{i}'" for i in range(10)]}
    assert kept[-1]["scope"] == "order.total >= 8"
    assert [d["check"] for d in dropped] == [
        "payment.status != 'S10'",
        "payment.status != 'S11'",
        "scope: order.total >= 9",
        "scope: order.total >= 10",
    ]
    assert dropped[0]["reason"] == "checks 는 항목마다 10개까지만 씁니다"
    assert dropped[2]["reason"] == "expectations 는 10개까지만 씁니다"


def test_golden_orders_text_marks_unknown_orders(monkeypatch):
    """GOLDEN_IDS 순서로 표를 만들고, 표에 없는 기준 주문은 처음 값을 모른다고 적는다."""
    monkeypatch.setattr(nodes, "GOLDEN_IDS", ["g-04", "g-99"])
    lines = nodes._golden_orders_text().splitlines()
    assert lines[0] == "| 주문 | 설명 | 지금 기대 최종 상태 | " + " | ".join(SCOPE_KEYS) + " |"
    assert lines[2] == (
        "| g-04 | 랜턴 1개 선입금 결제 정상 주문 | SHIPPED | 'prepaid' | 'c_1001' | 'KR' | false"
        " | 30000 |"
    )
    assert lines[3] == "| g-99 | 처음 값 모름 | ? | ? | ? | ? | ? | ? |"
    assert len(lines) == 4


def test_intent_note_is_appended_to_existing_explanation():
    """설명이 있는 diagnosis 에는 의도 추론 실패 설명을 이어 붙이고 실패 목록은 그대로 둔다."""
    note = nodes._intent_note("이유", [{"check": "x", "reason": "y"}])
    diagnosis = {"explanation": "앞 설명.", "failures": [{"run_id": "r"}]}
    merged = nodes._with_intent_note(diagnosis, note)
    assert merged["explanation"] == "앞 설명. " + note["intent_note"]
    assert merged["failures"] == [{"run_id": "r"}]
    assert merged["intent_dropped_checks"] == [{"check": "x", "reason": "y"}]


# 기준 주문 파일과 주문 Agent 상품 단가다. config.GOLDEN_ORDERS 는 이 둘을 옮긴 표다.
REPO = Path(__file__).resolve().parents[2]
GOLDEN_DIR = REPO / "scenarios" / "order-agent" / "golden"
PRODUCTS_SQL = REPO / "order-agent" / "backend" / "src" / "main" / "resources" / "data.sql"


@pytest.mark.skipif(
    not (GOLDEN_DIR.is_dir() and PRODUCTS_SQL.is_file()),
    reason="기준 주문 파일이나 상품 단가 파일이 없는 배포 모양에서는 건너뛴다",
)
def test_golden_order_table_matches_scenario_files():
    """config.GOLDEN_ORDERS 가 기준 주문 파일의 처음 값·기대 상태와 단가로 계산한 총액과 같다."""
    prices = {
        sku: int(price)
        for sku, price in re.findall(
            r"\('([A-Z0-9-]+)', '[^']*', (\d+),", PRODUCTS_SQL.read_text(encoding="utf-8")
        )
    }
    files = sorted(GOLDEN_DIR.glob("g-*.json"))
    assert tuple(path.stem for path in files) == tuple(config.GOLDEN_ORDERS) == _GOLDEN_FIXTURE_IDS
    for path in files:
        scenario = json.loads(path.read_text(encoding="utf-8"))
        order = scenario["order"]
        entry = config.GOLDEN_ORDERS[scenario["id"]]
        assert entry["final_status"] == scenario["expect"]["final_status"]
        assert entry["values"] == {
            "order.payment_method": order["payment_method"],
            "order.customer": order["customer"],
            "order.shipping.country": order["shipping"]["country"],
            "order.shipping.express": order["shipping"]["express"],
            "order.total": sum(prices[item["sku"]] * item["qty"] for item in order["items"]),
        }
        assert set(entry["values"]) == set(SCOPE_KEYS)


# ── scope 가 null 인 의도와 결과 불일치 진단의 끝 (V3-3, V3-8) ─────────────────────

NULL_SCOPE_INTENT = {
    "text": "모든 주문은 알림을 보낸다",
    "expectations": [{"scope": None, "checks": ["notify.sent == true"]}],
}


class NullScopeStub(BehaviorStub):
    """scope 가 null 인 의도의 수정본 대역이다.

    patch 없는 수정본 workspace 에서 의도 checks 가 붙으면 g-01, g-04 가 떨어진다(scope 가 null 이라
    intent_scoped 는 거짓이다). plain_fails 의 주문은 의도 없이도 수정본에서 떨어진다(결과 불일치).
    flaky 면 수정본의 의도 없는 실행이 두 번째부터 통과한다(대조 실행 흔들림). patch 가 들어간
    workspace 는 모두 통과한다.
    """

    def __init__(self, plain_fails=(), flaky=False):
        super().__init__()
        self.plain_fails = plain_fails
        self.flaky = flaky
        self.plain_candidate_runs = 0

    def passes(self, workspace_id, scenario, intent_expectations):
        if self.applied.get(workspace_id):
            return True
        if self.snapshot_of[workspace_id] != CANDIDATE:
            return True
        if scenario in self.plain_fails and not (self.flaky and self.plain_candidate_runs > 1):
            return False
        return not (intent_expectations and scenario in ("g-01", "g-04"))

    async def run_scenarios(self, workspace_id, scenario_ids, repeat=2, **kwargs):
        if not kwargs.get("intent_expectations") and self.snapshot_of[workspace_id] == CANDIDATE:
            self.plain_candidate_runs += 1
        return await super().run_scenarios(workspace_id, scenario_ids, repeat, **kwargs)


@pytest.mark.asyncio
async def test_null_scope_intent_gap_is_intent_not_realized(monkeypatch):
    """null scope 의 checks 로만 떨어진 주문은 원인 실험(ERROR)이 아니라 의도 미실현이다.

    의도 범위 표시(intent_scoped)가 없으므로 그 주문만 의도 없이 다시 돌려 통과하는지 본다.
    """
    stub = NullScopeStub()
    monkeypatch.setattr(nodes, "get_tools", lambda: stub)
    fixed = PAYMENT_EDIT.replace("선입금 주문만 결제합니다.", "결제 뒤 알림을 남깁니다.")
    monkeypatch.setattr(
        nodes,
        "make_structured_llm",
        llm_with(
            {
                "_CandidatesOutput": SimpleNamespace(
                    candidates=[
                        SimpleNamespace(kind="same_skill", patches=[revision("payment", fixed)])
                    ]
                ),
                "_RankExplanationsOutput": SimpleNamespace(explanations=[]),
            }
        ),
    )
    result = await run(saved(NULL_SCOPE_INTENT))

    assert result["error"] is None
    assert result["diagnosis_status"] == "EVALUATED"
    assert nodes_of(result) == INTENT_GAP_NODES
    assert result["diagnosis"]["finding"] == "INTENT_NOT_REALIZED"
    assert result["repro_scenario_ids"] == ["g-01", "g-04"]
    assert result["root_cause"]["kind"] == "incomplete_edit"
    expectations = NULL_SCOPE_INTENT["expectations"]
    assert stub.scenario_calls == [
        (GOLDEN, None),
        (GOLDEN, expectations),
        (["g-01", "g-04"], None),
        (GOLDEN, expectations),
    ]
    [candidate] = result["candidates"]
    assert candidate["valid"] is True
    assert candidate["results"]["keeps_intent"] is True
    assert result["proposal"]["status"] == "proposed"


@pytest.mark.asyncio
async def test_null_scope_failure_also_without_intent_is_mismatch_and_ends_validation_failed(
    monkeypatch,
):
    """의도 없이도 떨어지는 주문은 결과 불일치다. 가설이 없으면 ERROR 대신 VALIDATION_FAILED 다."""
    stub = NullScopeStub(plain_fails=("g-04",))
    monkeypatch.setattr(nodes, "get_tools", lambda: stub)
    monkeypatch.setattr(
        nodes,
        "make_structured_llm",
        llm_with({"_HypothesesOutput": SimpleNamespace(hypotheses=[])}),
    )
    result = await run(saved(NULL_SCOPE_INTENT))

    assert result["error"] is None
    assert result["diagnosis_status"] == "VALIDATION_FAILED"
    assert nodes_of(result) == [
        "init", "fetch_evidence", "hypothesize", "ablate", "check_ablation",
    ]
    assert result["repro_scenario_ids"] == ["g-04"]
    assert "finding" not in result["diagnosis"]
    assert "원인 가설을 세우지 못해" in result["diagnosis"]["explanation"]
    assert result["diagnosis"]["failures"]
    assert stub.saved == []


@pytest.mark.asyncio
async def test_flaky_control_on_mismatch_ends_validation_failed(monkeypatch):
    """결과 불일치 원인 실험의 대조 실행이 통과하면(결과 흔들림) VALIDATION_FAILED 다.

    ERROR 로 끝내지 않는다(V3-8).
    """
    stub = NullScopeStub(plain_fails=("g-04",), flaky=True)
    monkeypatch.setattr(nodes, "get_tools", lambda: stub)
    monkeypatch.setattr(
        nodes,
        "make_structured_llm",
        llm_with(
            {
                "_HypothesesOutput": SimpleNamespace(
                    hypotheses=[SimpleNamespace(change_no=1, summary="본문", confidence=0.5)]
                )
            }
        ),
    )
    result = await run(saved())

    assert result["error"] is None
    assert result["diagnosis_status"] == "VALIDATION_FAILED"
    assert result["ablation_runs"] == []
    assert "결과가 흔들리므로" in result["diagnosis"]["explanation"]
    assert messages_of(result)["ablate"] == "대조 실행 통과(결과 흔들림), 원인 실험 중단"


def test_mismatch_path_is_only_saved_requests_without_failed_execution():
    """VALIDATION_FAILED 로 끝내는 쪽은 결과 불일치 진단뿐이다. 실패 실행 진단은 예전처럼 ERROR 다.

    실패 실행 진단의 빈 가설 ERROR 는 test_flow_characterization.py 의
    test_empty_llm_outputs_end_in_error 가 고정한다.
    """
    state = {"execution_id": EXECUTION, "repro_scenario_ids": []}
    assert nodes._mismatch_path(state) is False
    assert nodes._mismatch_path({"execution_id": None, "repro_scenario_ids": ["g-01"]}) is True


# ── Agent 가 만든 의도의 최소 요건 (V3-4) ───────────────────────────────────────


class VacuousStub(BehaviorStub):
    """기준본이 의도 expectations 를 이미 모두 통과하는 대역이다(늘 참인 의도)."""

    def passes(self, workspace_id, scenario, intent_expectations):
        if self.snapshot_of[workspace_id] == BASELINE:
            return True
        return super().passes(workspace_id, scenario, intent_expectations)


@pytest.mark.parametrize(
    ("items", "stub_class", "reason", "calls"),
    [
        (
            INFERRED_EXPECTATIONS,
            VacuousStub,
            "기준본도 이 기대 결과를 모두 통과해",
            [(GOLDEN, INFERRED_EXPECTATIONS), (GOLDEN, None), (GOLDEN, None)],
        ),
        (
            INFERRED_EXPECTATIONS
            + [
                {
                    "scope": "order.payment_method == 'prepaid'",
                    "checks": ["order.final_status == 'SHIPPED'"],
                }
            ],
            BehaviorStub,
            "모든 기준 주문이 scope 안에 들어",
            [(GOLDEN, None), (GOLDEN, None)],
        ),
        (
            [{"scope": "order.customer == 'c_vip'", "checks": ["order.final_status == 'HOLD'"]}],
            BehaviorStub,
            "LLM 이 쓸 수 있는 기대 결과를 내지 않았습니다(버린 조건 1개)",
            [(GOLDEN, None), (GOLDEN, None)],
        ),
    ],
    ids=["vacuous-on-baseline", "over-broad", "no-matching-golden"],
)
@pytest.mark.asyncio
async def test_inferred_expectations_without_minimum_requirements_are_dropped(
    monkeypatch, items, stub_class, reason, calls
):
    """기준본이 이미 통과하거나, scope 밖 기준 주문이 없거나, 맞는 기준 주문이 없는 의도는 버린다.

    버리면 빈 의도로 보고 의도 시험 없이 사전 검증한 뒤 의도 확인 필요(finding NEEDS_INTENT)로
    멈추며 intent_note 에 이유를 남긴다.
    """
    stub = stub_class()
    monkeypatch.setattr(nodes, "get_tools", lambda: stub)
    llm = RecordingLLM(
        {"_ExpectationsOutput": SimpleNamespace(expectations=expectation_items(items))}
    )
    monkeypatch.setattr(nodes, "make_structured_llm", llm)
    result = await run(saved(TEXT_ONLY_INTENT))

    assert result["error"] is None
    assert result["diagnosis_status"] == "EVALUATED"
    assert result["diagnosis"]["finding"] == "NEEDS_INTENT"
    assert result["intent"] is None
    assert stub.scenario_calls == calls
    diagnosis = result["diagnosis"]
    assert reason in diagnosis["intent_note"]
    dropped = [d["check"] for d in diagnosis["intent_dropped_checks"]]
    assert dropped == [f"scope: {item['scope']}" for item in items]


@pytest.mark.asyncio
async def test_inferred_scope_without_matching_golden_is_dropped_but_others_kept(
    behavior, monkeypatch
):
    """맞는 기준 주문이 없는 scope 만 버리고 나머지 추론한 expectations 로 진단을 잇는다."""
    vip = {"scope": "order.customer == 'c_vip'", "checks": ["order.final_status == 'HOLD'"]}
    llm = RecordingLLM(
        {
            "_ExpectationsOutput": SimpleNamespace(
                expectations=expectation_items(INFERRED_EXPECTATIONS + [vip])
            ),
            "_CandidatesOutput": SimpleNamespace(candidates=[]),
            "_RankExplanationsOutput": SimpleNamespace(explanations=[]),
        }
    )
    monkeypatch.setattr(nodes, "make_structured_llm", llm)
    result = await run(saved(TEXT_ONLY_INTENT))

    assert result["error"] is None
    assert result["intent"]["expectations"] == INFERRED_EXPECTATIONS
    [dropped] = result["intent"]["dropped_checks"]
    assert dropped["check"] == "scope: order.customer == 'c_vip'"
    assert dropped["reason"].startswith("scope 에 맞는 기준 주문이 없어")
    assert result["diagnosis"]["finding"] == "INTENT_NOT_REALIZED"


@pytest.mark.asyncio
async def test_extract_intent_caps_inferred_expectations_and_checks(stub, monkeypatch):
    """실패 진단의 extract_intent 도 expectations 10개, 항목마다 checks 10개까지만 보낸다.

    LLM 이 낸 expectations 와 scope 없는 checks 를 합친 것이 상한을 넘어도 service 가 INVALID_INPUT
    으로 거절하지 않게 사전 검증 전 추론과 같은 규칙으로 자른다.
    """
    card = "order.payment_method == 'card'"
    intent_output = SimpleNamespace(
        text="t",
        checks=["payment.status == 'PAID'"],
        expectations=[
            SimpleNamespace(scope=card, checks=[f"payment.status != 'S{i}'" for i in range(12)])
            for _ in range(11)
        ],
    )
    candidates = [
        SimpleNamespace(
            kind="same_skill",
            patches=[revision("inventory-check", FIXED_INVENTORY.replace("body", "body 2"))],
        )
    ]
    monkeypatch.setattr(nodes, "make_structured_llm", stub_llm(intent_output, candidates))
    owner = {"text": "카드 주문은 결제 상태를 남긴다", "expectations": []}
    trigger = {"type": "failure", "execution_id": EXECUTION, "intent": owner}
    result = await run({"trigger": trigger, "max_steps": 40})

    assert result["error"] is None
    expectations = result["intent"]["expectations"]
    assert len(expectations) == 10
    assert all(len(e["checks"]) == 10 for e in expectations)
    dropped = result["intent"]["dropped_checks"]
    assert [d["check"] for d in dropped[-2:]] == [f"scope: {card}", "scope: None"]
    assert all(d["reason"] == "expectations 는 10개까지만 씁니다" for d in dropped[-2:])
    sent = [kwargs.get("intent_expectations") for _, kwargs in stub.calls]
    assert all(len(e or []) <= 10 for e in sent)
    assert all(len(item["checks"]) <= 10 for e in sent for item in e or [])


@pytest.mark.asyncio
async def test_extract_intent_drops_vacuous_inferred_expectations(stub, monkeypatch):
    """실패 진단에서도 기준본이 이미 통과하는 추론 expectations 는 버리고 intent_note 를 남긴다."""
    stub.baseline_passes_intent = True
    intent_output = SimpleNamespace(
        text="t",
        checks=[],
        expectations=[
            SimpleNamespace(
                scope="order.payment_method == 'card'", checks=["payment.status == 'PAID'"]
            )
        ],
    )
    monkeypatch.setattr(nodes, "make_structured_llm", stub_llm(intent_output, same_skill_revert()))
    owner = {"text": "카드 주문은 결제한다", "expectations": []}
    trigger = {"type": "failure", "execution_id": EXECUTION, "intent": owner}
    result = await run({"trigger": trigger, "max_steps": 40})

    assert result["error"] is None
    assert result["intent"]["expectations"] == []
    assert "기준본도 이 기대 결과를 모두 통과해" in result["diagnosis"]["intent_note"]
    assert result["proposal"]["status"] == "no_valid_fix"


# ── 담당자 expectations 가운데 읽을 수 없는 것 (V3-2) ─────────────────────────────


def test_owner_intent_drops_unreadable_items_without_rejecting():
    """문법·scope 키·개수가 맞지 않는 담당자 항목은 빼고 이유를 남긴다. trigger 는 그대로다."""
    trigger = {
        "intent": {
            "text": "카드 주문 보류",
            "expectations": [
                {"scope": "payment.status == 'X'", "checks": ["order.final_status == 'HOLD'"]},
                {"scope": "order.shipping == 'KR'", "checks": ["order.final_status == 'HOLD'"]},
                {"scope": "order.total>=1", "checks": ["order.final_status == 'HOLD'"]},
                {"scope": None, "checks": []},
                {"scope": CARD_RULE, "checks": ["order.total >= 'big'", "payment.status == null"]},
                {"scope": None, "checks": [f"payment.status != 'S{i}'" for i in range(11)]},
            ],
        }
    }
    owner = nodes._owner_intent({"trigger": trigger})
    assert owner["expectations"] == [
        {"scope": CARD_RULE, "checks": ["payment.status == null"]},
        {"scope": None, "checks": [f"payment.status != 'S{i}'" for i in range(10)]},
    ]
    assert [d["check"] for d in owner["dropped"]] == [
        "scope: payment.status == 'X'",
        "scope: order.shipping == 'KR'",
        "scope: order.total>=1",
        "scope: None",
        "order.total >= 'big'",
        "payment.status != 'S10'",
    ]
    assert "시험 주문의 처음 값" in owner["dropped"][0]["reason"]
    assert trigger["intent"]["expectations"][0]["scope"] == "payment.status == 'X'"


@pytest.mark.asyncio
async def test_unreadable_owner_items_are_noted_and_the_rest_is_used(behavior, monkeypatch):
    """담당자 항목 일부를 읽을 수 없어도 나머지로 진단하고 intent_note·dropped_checks 에 남긴다."""
    monkeypatch.setattr(nodes, "make_structured_llm", no_candidates_llm())
    bad = {"scope": "order.items == 'x'", "checks": ["order.final_status == 'HOLD'"]}
    intent = {**OWNER_INTENT, "expectations": OWNER_EXPECTATIONS + [bad]}
    result = await run(saved(intent))

    assert result["error"] is None
    assert result["diagnosis"]["finding"] == "INTENT_NOT_REALIZED"
    assert "조건 1개를 읽을 수 없어" in result["diagnosis"]["intent_note"]
    assert result["diagnosis"]["intent_dropped_checks"] == result["intent"]["dropped_checks"]
    assert [d["check"] for d in result["intent"]["dropped_checks"]] == ["scope: order.items == 'x'"]
    assert result["intent"]["expectations"] == OWNER_EXPECTATIONS
    assert behavior.scenario_calls[1] == (GOLDEN, OWNER_EXPECTATIONS)
    assert result["proposal"]["trigger"]["intent"] == intent


# ── 의도가 실제로 시험됐는지 (V3-4) ────────────────────────────────────────────


def test_intent_tested_needs_a_scoped_run_for_every_scoped_expectation():
    prepaid = {"scope": "order.payment_method == 'prepaid'", "checks": ["x == 1"]}
    card = {"scope": CARD_RULE, "checks": ["x == 1"]}

    def result(scenario, scoped=True):
        return {"scenario_id": scenario, "passed": True, "intent_scoped": scoped}

    assert nodes._intent_tested([result("g-01"), result("g-04")], [card, prepaid]) is True
    # g-04(선입금)만 의도로 채점됐으면 카드 expectation 은 시험되지 않았다.
    assert nodes._intent_tested([result("g-01", False), result("g-04")], [card, prepaid]) is False
    # 처음 값을 모르는 재현 주문은 scope 있는 expectation 이 하나뿐일 때만 그것에 맞은 것으로 본다.
    assert nodes._intent_tested([result(REPLAY)], [card]) is True
    assert nodes._intent_tested([result(REPLAY)], [card, prepaid]) is False
    # scope 가 모두 null 이면 의도와 함께 돌린 실행이 하나 이상이면 된다.
    unscoped = {"scope": None, "checks": ["x == 1"]}
    assert nodes._intent_tested([result("g-01", False)], [unscoped]) is True
    assert nodes._intent_tested([], [unscoped]) is False


def test_rank_key_demotes_reverts_only_with_intent():
    """의도가 없는 요청의 순위는 예전과 같다. 되돌리기·계약 약화는 의도가 있을 때만 뒤로 간다."""
    results = {"keeps_intent": True, "no_broken_links": True, "changed_skill_count": 1}
    revert = {"candidate_no": 1, "is_revert": True, "weakens_contract": False, "results": results}
    other = {"candidate_no": 2, "is_revert": False, "weakens_contract": False, "results": results}
    assert nodes._rank_key(revert) == nodes._rank_key(other) == (0, 0, 0, 1)
    assert sorted([revert, other], key=nodes._rank_key)[0] is revert
    assert nodes._rank_key(revert, True) == (1, 0, 0, 1)


def sequence_llm(outputs):
    """출력 클래스 이름별 출력 목록을 부를 때마다 차례로 돌려주는 가짜 LLM 이다.

    목록 끝에 닿으면 마지막 출력을 되풀이한다. 출력이 예외면 던진다.
    """
    counts = {}

    def make(schema):
        name = schema.__name__
        if name not in outputs:
            pytest.fail(f"unexpected LLM call: {name}")
        sequence = outputs[name]
        index = counts.get(name, 0)
        counts[name] = index + 1
        return FakeChain(sequence[min(index, len(sequence) - 1)])

    return make


@pytest.mark.asyncio
async def test_retry_llm_failure_ends_no_valid_fix_instead_of_error(stub, monkeypatch):
    """재시도에서 LLM 이 실패해도 ERROR 가 아니다(V3-8).

    앞 회차 후보를 둔 채 재시도 한도에 닿아 no_valid_fix 로 끝난다.
    """
    broken = SimpleNamespace(kind="same_skill", patches=[revision("inventory-check", "no front")])
    monkeypatch.setattr(
        nodes,
        "make_structured_llm",
        sequence_llm(
            {
                "_HypothesesOutput": [
                    SimpleNamespace(
                        hypotheses=[SimpleNamespace(change_no=1, summary="o", confidence=0.9)]
                    )
                ],
                "_IntentOutput": [SimpleNamespace(text="t", checks=[])],
                "_CandidatesOutput": [
                    SimpleNamespace(candidates=[broken]),
                    RuntimeError("no model"),
                ],
            }
        ),
    )
    result = await run({"trigger": {"type": "failure", "execution_id": EXECUTION}, "max_steps": 40})

    assert result["error"] is None
    assert result["diagnosis_status"] == "EVALUATED"
    assert result["proposal"]["status"] == "no_valid_fix"
    [candidate] = result["candidates"]
    assert candidate["valid"] is False
    assert candidate["validation_error"] == "수정 후 전체 SKILL.md 원문이 필요합니다."
    messages = [e["msg"] for e in result["events"] if e["node"] == "generate_candidates"]
    assert messages[0] == "후보 1개 생성 (retry=0)"
    assert messages[1:] == [
        f"새 후보 없음 (retry={retry}), LLM 오류 RuntimeError, 앞 회차 후보 유지"
        for retry in (1, 2)
    ]


# ── 결정적 후보의 위치 (V3-6: B-02, B-05) ──────────────────────────────────────

SHIPPING_EDIT = SOURCES["shipping-reserve"].replace(
    "배송을 예약합니다.", "배송을 예약합니다.\n해외 배송은 받지 않습니다."
)
B02_INTENT = {
    "text": "해외 배송 주문은 받지 않는다",
    "expectations": [
        {
            "scope": "order.shipping.country != 'KR'",
            "checks": ["order.final_status == 'REJECTED'", "payment.status == null"],
        }
    ],
}


def rule_skills(patches, key):
    """patch 가운데 key 를 보는 rules 항목을 더한 skill 이름이다."""
    return {
        patch["skill"]
        for patch in patches
        for line in patch["patch"].splitlines()
        if line.startswith("+") and "when:" in line and key in line
    }


class B02Stub(BehaviorStub):
    """B-02 대역이다. shipping-reserve 본문에 '해외 배송은 받지 않음'만 더했다.

    해외 배송 주문 g-05 는 결제 전 단계(order-intake)에 거절 규칙이 있어야 의도대로 끝난다.
    shipping-reserve 에 걸면 결제가 남아 payment.status == null 이 떨어진다.
    """

    changed = "shipping-reserve"
    edited = SHIPPING_EDIT
    hunk = {
        "index": 0,
        "hunk_id": "h_domestic",
        "part": "body",
        "header": "@@ -12 +12,2 @@",
        "before": "배송을 예약합니다.",
        "after": "배송을 예약합니다.\n해외 배송은 받지 않습니다.",
        "diff": "@@ -12 +12,2 @@\n 배송을 예약합니다.\n+해외 배송은 받지 않습니다.\n",
    }

    def passes(self, workspace_id, scenario, intent_expectations):
        skills = rule_skills(self.applied.get(workspace_id, []), "order.shipping.country")
        if not intent_expectations:
            return not skills or scenario != "g-05"
        return scenario != "g-05" or skills == {"order-intake"}


@pytest.mark.asyncio
async def test_b02_second_deterministic_candidate_at_earliest_step_passes(monkeypatch):
    """바뀐 skill(shipping-reserve)에 건 규칙은 떨어지고 가장 이른 단계 후보가 합격한다.

    가장 이른 단계는 결제 전의 order-intake 다.

    LLM 이 모두 실패해도 결정적 후보만으로 끝난다.
    """
    stub = B02Stub()
    monkeypatch.setattr(nodes, "get_tools", lambda: stub)
    monkeypatch.setattr(
        nodes,
        "make_structured_llm",
        llm_with(
            {
                "_CandidatesOutput": RuntimeError("no model"),
                "_RankExplanationsOutput": RuntimeError("no model"),
            }
        ),
    )
    result = await run(saved(B02_INTENT))

    assert result["error"] is None
    assert result["diagnosis"]["finding"] == "INTENT_NOT_REALIZED"
    assert result["repro_scenario_ids"] == ["g-05"]
    late, early = result["candidates"]
    assert [p["skill"] for p in late["patches"]] == ["shipping-reserve"]
    assert late["valid"] is False and late["results"]["fixes_failure"] is False
    assert [p["skill"] for p in early["patches"]] == ["order-intake"]
    assert early["valid"] is True and early["rank"] == 1
    assert "+    then: reject\n" in early["patches"][0]["patch"]
    assert result["proposal"]["recommended_candidate_no"] == 2


PAYMENT_B05 = skill(
    "payment",
    PAYMENT_INPUTS,
    ["payment.id", "payment.status"],
    "결제 도구로 결제합니다.",
    extra=(
        "rules:\n  - when: \"order.customer == 'c_declined'\"\n    then: hold\n"
        "    reason: DECLINED_HISTORY\n"
    ),
)
B05_INTENT = {
    "text": "결제 거절 이력 고객은 결제 시도 전 보류한다",
    "expectations": [
        {
            "scope": "order.customer == 'c_declined'",
            "checks": ["order.final_status == 'HOLD'", "payment.status == null"],
        }
    ],
}


class B05Stub(BehaviorStub):
    """B-05 대역이다. 담당자가 payment 에 rules 를 넣었지만 조건 키 order.customer 를 inputs 에 넣지
    않아 모든 실행이 계약 오류로 떨어진다. payment inputs 에 order.customer 를 더한 workspace 만
    통과한다.
    """

    changed = "payment"
    edited = PAYMENT_B05

    def passes(self, workspace_id, scenario, intent_expectations):
        return any(
            patch["skill"] == "payment"
            and any(
                line.startswith("+inputs:") and "order.customer" in line
                for line in patch["patch"].splitlines()
            )
            for patch in self.applied.get(workspace_id, [])
        )


@pytest.mark.asyncio
async def test_b05_deterministic_candidate_only_adds_the_missing_input(monkeypatch):
    """같은 when 의 규칙이 이미 있으면 규칙은 두고 빠진 input 만 더한 후보가 합격한다."""
    stub = B05Stub()
    monkeypatch.setattr(nodes, "get_tools", lambda: stub)
    monkeypatch.setattr(
        nodes, "make_structured_llm", llm_with({"_CandidatesOutput": RuntimeError("no model")})
    )
    trigger = {"type": "failure", "execution_id": EXECUTION, "intent": B05_INTENT}
    state = {
        "trigger": trigger,
        "snapshot_id": CANDIDATE,
        "baseline_snapshot_id": BASELINE,
        "execution_id": EXECUTION,
        "skill_versions": dict.fromkeys(SOURCES, 1),
        "root_cause": {"skill": "payment", "change_no": 1},
        "changes": [{"skill": "payment", "change_no": 1}],
        "intent": nodes._owner_intent_value(nodes._owner_intent({"trigger": trigger})),
        "diff": {},
        "candidate_retry": 0,
        "candidates": [],
        "repro_scenario_ids": [],
        "step_count": 0,
        "events": [],
    }
    state.update(await nodes.generate_candidates(state))
    state.update(await nodes.verify_candidates(state))

    inputs_only, intake = state["candidates"]
    [patch] = inputs_only["patches"]
    assert patch["skill"] == "payment"
    assert "+inputs: [" + ", ".join(PAYMENT_INPUTS + ["order.customer"]) + "]\n" in patch["patch"]
    assert "+rules:" not in patch["patch"] and "when:" not in patch["patch"]
    assert inputs_only["valid"] is True
    assert (inputs_only["is_revert"], inputs_only["weakens_contract"]) == (False, False)
    assert [p["skill"] for p in intake["patches"]] == ["order-intake"]
    assert intake["valid"] is False


def test_scope_on_result_value_names_the_value_in_the_reason():
    """scope 에 처리 결과 값(order.final_status)을 쓰면 그 값 이름과 처음 값을 이유에 적는다."""
    reason = nodes._scope_problem("order.final_status != 'REJECTED'")

    assert "'order.final_status' 는 주문을 처리한 뒤에야 정해지는 값" in reason
    assert all(key in reason for key in SCOPE_KEYS)
    assert nodes._scope_problem("order.shipping.country != 'KR'") is None
    other = nodes._scope_problem("order.items.length < 3")
    assert "'order.items.length' 는 scope 로 주문을 고를 수 있는 값이 아닙니다" in other
    assert "처리한 뒤에야" not in other


def test_dropped_conditions_are_logged_with_reasons(caplog):
    """버린 조건이 있으면 LLM 이 낸 원래 기대 결과와 조건마다의 이유를 로그로 남긴다."""
    proposed = [{"scope": "order.final_status != 'REJECTED'", "checks": ["payment.status == null"]}]
    dropped = [{"check": "scope: order.final_status != 'REJECTED'", "reason": "처리 결과 값"}]

    with caplog.at_level("INFO", logger="skillops_agent.nodes"):
        nodes._log_dropped("fetch_evidence", "예약 건은 제외한다.", proposed, dropped)
        nodes._log_dropped("extract_intent", "의도", proposed, [])

    lines = [r.getMessage() for r in caplog.records]
    assert any("payment.status == null" in line and "예약 건은 제외한다." in line for line in lines)
    expected = "[fetch_evidence] 버린 조건 scope: order.final_status != 'REJECTED': 처리 결과 값"
    assert expected in lines
    assert not any(line.startswith("[extract_intent]") for line in lines)


@pytest.mark.asyncio
async def test_failing_save_without_usable_intent_stops_as_needs_intent(monkeypatch):
    """수정본이 기준 주문에서 떨어져도 의도를 시험할 수 없으면 의도 확인 필요로 멈춘다.

    후보는 만들지 않는다. 예전에는 원인 실험과 후보 생성을 세 번까지 하고 빈 의도라 모든 후보를
    불합격시켰다. 사전 검증에서
    떨어진 주문(failures)과 실패 여부(preflight_failed)는 diagnosis 에 남긴다.
    """
    stub = NullScopeStub(plain_fails=("g-05",))
    monkeypatch.setattr(nodes, "get_tools", lambda: stub)
    llm = RecordingLLM({"_ExpectationsOutput": SimpleNamespace(expectations=[])})
    monkeypatch.setattr(nodes, "make_structured_llm", llm)
    result = await run(saved(TEXT_ONLY_INTENT))

    assert result["error"] is None
    assert result["diagnosis_status"] == "EVALUATED"
    assert result["diagnosis"]["finding"] == "NEEDS_INTENT"
    assert nodes_of(result) == ["init", "fetch_evidence"]
    assert llm.names() == ["_ExpectationsOutput"]
    assert result["candidates"] == [] and result["root_cause"] is None
    diagnosis = result["diagnosis"]
    assert diagnosis["preflight_failed"] is True
    assert "실패한 주문이 있습니다" in diagnosis["explanation"]
    assert diagnosis["failures"]
