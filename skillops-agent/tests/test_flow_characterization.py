"""진단 그래프의 현재 동작을 고정하는 특성 테스트다. 네트워크와 유료 호출은 없다.

리팩토링 전 코드에서 관찰한 흐름, 분기, 한도, 진행 문구, 키 순서, 도구 호출 모양을 그대로 단언한다.
공개 경로(graph.graph, nodes.get_tools 와 make_structured_llm 교체, nodes.proposal_payload,
RunStore, StubMCPTools)만 쓰므로 내부 도우미 이름을 바꿔도 깨지지 않는다.

보호하는 production 코드와 동작은 다음과 같다.
- skillops-agent/skillops_agent/graph.py 의 FLOW 와 분기 판정을 보호한다. 정상 경로의 노드 11개,
  가설 재시도와 후보 재시도의 한도(skillops-agent/skillops_agent/config.py 의
  MAX_HYPOTHESIS_RETRIES, MAX_CANDIDATE_RETRIES), 단계 한도(max_steps), 원인이 확정되면 단계
  한도를 보지 않는 순서를 고정한다.
- skillops-agent/skillops_agent/nodes.py 의 노드를 보호한다. 진행 메시지, step_count, 도구 호출
  순서와 인자, 반복 횟수, 프롬프트에 들어가는 값, 후보 형식 검사, 순위와 설명 대체, 의도 조건
  필터, 사전 검증(VALIDATED, BASELINE_FAILED, VALIDATION_FAILED), diagnosis_only, 계약 오류 문구와
  검사 순서, proposal_payload 의 경계를 고정한다.
- graph.py 의 guarded 와 skillops-agent/skillops_agent/run_store.py 가 노드마다 저장한 진행 기록,
  최종 상태, 오류 기록, 키 순서를 고정한다.
흐름이나 config.py 의 튜닝 값을 의도적으로 바꾸면 HAPPY_NODES, HYPOTHESIS_LOOP, REPAIR_LOOP,
메시지 목록, step_count, 실행 ID 개수와 repeat 단언, EVIDENCE_ERRORS 와 LATER_ERRORS 표를 함께
고친다. 상수마다 깨지는 테스트 이름은 config.py 의 상수 주석에 있다.
"""

from types import SimpleNamespace
from uuid import uuid4

import pytest

from skillops_agent import nodes
from skillops_agent.contracts import execution_uuid
from skillops_agent.graph import graph
from skillops_agent.run_store import RunStore
from skillops_agent.stub_tools import StubMCPTools
from skillops_agent.tools import MCPToolError

# StubMCPTools 의 기준 snapshot, 후보 snapshot, 고정 실패 실행과 그 실행의 재현 시나리오 ID 다.
# GOLDEN 은 skillops-agent/skillops_agent/config.py 의 GOLDEN_IDS 기본값과 같은 회귀 세트다.
BASELINE = StubMCPTools.BASELINE
CANDIDATE = StubMCPTools.CANDIDATE
EXECUTION = StubMCPTools.EXECUTION
REPLAY = f"f-{EXECUTION}"
GOLDEN = ["g-01", "g-02", "g-03"]

# 정상 경로에서 실행되는 노드 11개의 순서다.
# skillops-agent/skillops_agent/graph.py 의 FLOW 를 바꾸면 함께 고친다.
HAPPY_NODES = [
    "init",
    "fetch_evidence",
    "hypothesize",
    "ablate",
    "check_ablation",
    "extract_intent",
    "generate_candidates",
    "verify_candidates",
    "check_valid",
    "rank_and_explain",
    "save_proposal",
]
# 가설 재시도 한 회차와 후보 재시도 한 회차의 노드 순서다.
HYPOTHESIS_LOOP = ["hypothesize", "ablate", "check_ablation"]
REPAIR_LOOP = ["generate_candidates", "verify_candidates", "check_valid"]

# stub 의 현재 inventory-check 원문과, 가짜 LLM 이 내는 수정 후 원문이다.
CURRENT_INVENTORY = "---\nname: inventory-check\noutputs: []\n---\nbody\n"
FIXED_INVENTORY = "---\nname: inventory-check\noutputs: [inventory.reserved]\n---\nbody\n"
FIXED_PAYMENT = "---\nname: payment\ninputs: [inventory.reserved]\n---\nbody\n"

# record_tools 가 감싸 호출을 기록하는 stub 도구 이름이다.
TOOL_NAMES = [
    "get_snapshot",
    "list_skills",
    "get_skill",
    "get_trace",
    "diff_skill",
    "get_dependency_graph",
    "create_workspace",
    "apply_patch",
    "run_scenarios",
    "save_proposal",
]


# ── 가짜 LLM ──────────────────────────────────────────────────────────────────


def revision(skill, full_text):
    """LLM 후보 출력의 파일 하나(_PatchSpec 모양)를 만든다."""
    return SimpleNamespace(skill=skill, full_text=full_text)


def candidate_spec(kind, *patches):
    """LLM 후보 출력의 후보 하나(_CandidateSpec 모양)를 만든다."""
    return SimpleNamespace(kind=kind, patches=list(patches))


def default_outputs():
    """정상 경로를 끝까지 통과하는 LLM 출력 네 가지를 출력 클래스 이름별로 만든다."""
    return {
        "_HypothesesOutput": SimpleNamespace(
            hypotheses=[
                SimpleNamespace(change_no=1, summary="inventory output deletion", confidence=0.9)
            ]
        ),
        "_IntentOutput": SimpleNamespace(
            text="keep payment behavior", checks=["payment.status == 'PAID'"]
        ),
        "_CandidatesOutput": SimpleNamespace(
            candidates=[candidate_spec("same_skill", revision("inventory-check", FIXED_INVENTORY))]
        ),
        "_RankExplanationsOutput": SimpleNamespace(
            explanations=[SimpleNamespace(candidate_no=1, explanation="trial evidence")]
        ),
    }


class FakeChain:
    """구조화 출력 체인의 대역이다. 받은 프롬프트를 기록하고 정해 둔 출력을 돌려준다."""

    def __init__(self, name, output, prompts):
        self.name = name
        self.output = output
        self.prompts = prompts

    async def ainvoke(self, prompt, *args):
        self.prompts.append((self.name, prompt))
        return self.output


class FakeLLM:
    """schema 이름으로 고정 출력을 고르고, 받은 프롬프트를 순서대로 모은다.

    nodes.make_structured_llm 자리에 끼운다. 테스트는 outputs 를 바꿔 LLM 응답을 정하고,
    prompts_for 로 출력 클래스별 프롬프트를 꺼내 본다.
    """

    def __init__(self):
        self.outputs = default_outputs()
        self.prompts = []

    def __call__(self, schema):
        return FakeChain(schema.__name__, self.outputs[schema.__name__], self.prompts)

    def prompts_for(self, name):
        """출력 클래스 이름이 name 인 호출에서 받은 프롬프트를 순서대로 돌려준다."""
        return [prompt for schema, prompt in self.prompts if schema == name]


# ── fixture 와 도우미 ─────────────────────────────────────────────────────────


@pytest.fixture
def stub(monkeypatch):
    """StubMCPTools 하나를 nodes.get_tools 자리에 끼운다."""
    tools = StubMCPTools()
    monkeypatch.setattr(nodes, "get_tools", lambda: tools)
    return tools


@pytest.fixture
def llm(monkeypatch):
    """FakeLLM 을 nodes.make_structured_llm 자리에 끼운다."""
    fake = FakeLLM()
    monkeypatch.setattr(nodes, "make_structured_llm", fake)
    return fake


@pytest.fixture
def no_llm(monkeypatch):
    """LLM 을 부르면 테스트가 실패하게 한다. LLM 없이 끝나야 하는 경로에 쓴다."""
    monkeypatch.setattr(nodes, "make_structured_llm", lambda _: pytest.fail("LLM was called"))


def initial(max_steps=40, **extra):
    """stub 의 고정 실패 실행을 진단하는 실패 진단 요청의 초기 상태를 만든다."""
    return {
        "trigger": {"type": "failure", "execution_id": EXECUTION, **extra},
        "max_steps": max_steps,
    }


def saved_trigger(**extra):
    """후보 snapshot 저장 직후 요청(type=skill_saved)의 초기 상태를 만든다. max_steps 는 없다."""
    return {"trigger": {"type": "skill_saved", "candidate_snapshot_id": CANDIDATE, **extra}}


async def run(state):
    """새 thread_id 와 recursion_limit 100 으로 그래프를 끝까지 돌린다."""
    return await graph.ainvoke(
        state, {"configurable": {"thread_id": str(uuid4())}, "recursion_limit": 100}
    )


def nodes_of(result):
    """진행 기록(events)에 남은 노드 이름을 순서대로 돌려준다."""
    return [e["node"] for e in result["events"]]


def messages_of(result, node=None):
    """진행 기록의 메시지를 순서대로 돌려준다. node 를 주면 그 노드의 메시지만 고른다."""
    return [e["msg"] for e in result["events"] if node is None or e["node"] == node]


def change_result(monkeypatch, stub, name, change):
    """stub 도구 응답을 change(result, *args, **kwargs) 로 그 자리에서 고친다."""
    original = getattr(stub, name)

    async def changed(*args, **kwargs):
        result = await original(*args, **kwargs)
        change(result, *args, **kwargs)
        return result

    monkeypatch.setattr(stub, name, changed)


def record_tools(monkeypatch, stub):
    """모든 stub 도구 호출을 (이름, 위치 인자, 키워드 인자, 결과) 로 기록한다."""
    calls = []
    for name in TOOL_NAMES:
        original = getattr(stub, name)

        def make(name=name, original=original):
            async def recorded(*args, **kwargs):
                result = await original(*args, **kwargs)
                calls.append((name, args, kwargs, result))
                return result

            return recorded

        monkeypatch.setattr(stub, name, make())
    return calls


def scenario_calls(calls):
    """기록한 호출에서 run_scenarios 의 (시나리오 목록, 키워드 인자)만 순서대로 뽑는다."""
    return [(args[1], kwargs) for name, args, kwargs, _ in calls if name == "run_scenarios"]


def run_ids_of(report):
    """run_scenarios 응답의 실행 ID 를 시나리오 순서대로 펼친다."""
    return [i for r in report["results"] for i in r["run_ids"]]


def healthy_workspaces(monkeypatch, stub):
    """새로 만든 workspace 를 모두 통과 상태로 둔다. 수정 없는 후보도 시나리오를 통과한다."""
    original = stub.create_workspace

    async def ready(**kwargs):
        ws = await original(**kwargs)
        stub._workspaces[ws["workspace_id"]] = True
        return ws

    monkeypatch.setattr(stub, "create_workspace", ready)


def broken_workspaces(monkeypatch, stub):
    """새로 만든 workspace 를 모두 실패 상태로 둔다. 기준 snapshot 의 회귀 세트도 실패한다."""
    original = stub.create_workspace

    async def invalid(**kwargs):
        ws = await original(**kwargs)
        stub._workspaces[ws["workspace_id"]] = False
        return ws

    monkeypatch.setattr(stub, "create_workspace", invalid)


def ablation_never_confirms(monkeypatch, stub):
    """patch 를 적용해도 workspace 가 실패 상태로 남게 해 원인 실험이 늘 기각되게 한다."""
    original = stub.apply_patch

    async def apply(workspace_id, patches):
        result = await original(workspace_id, patches)
        stub._workspaces[workspace_id] = False
        return result

    monkeypatch.setattr(stub, "apply_patch", apply)


def broken_links(monkeypatch, stub):
    """apply_patch 응답마다 끊긴 연결을 넣어 모든 후보가 무효가 되게 한다."""

    def change(result, *args):
        result["broken_links"] = [{"skill": "payment", "missing_input": "inventory.reserved"}]

    change_result(monkeypatch, stub, "apply_patch", change)


def failed_preflight_trace(monkeypatch, stub, snapshot_id=CANDIDATE):
    """사전 검증에서 실패한 첫 실행의 기록을 EXECUTION 의 실패 기록으로 바꾼다.

    바꾼 기록의 execution_id 는 그 실행의 것으로, snapshot_id 는 인자 snapshot_id 로 고친다. 바꾼
    실행 ID 는 돌려주는 dict 의 execution 키에 담긴다.
    """
    original = stub.get_trace
    replaced = {}

    async def get_trace(execution_id):
        execution = execution_uuid(execution_id)
        if execution != EXECUTION and not replaced:
            trace = await original(EXECUTION)
            trace["execution_id"] = execution
            trace["snapshot_id"] = snapshot_id
            replaced["execution"] = execution
            return trace
        return await original(execution_id)

    monkeypatch.setattr(stub, "get_trace", get_trace)
    return replaced


# ── 정상 흐름 ─────────────────────────────────────────────────────────────────


@pytest.mark.asyncio
async def test_happy_path_progress_log_and_step_count(stub, llm):
    result = await run(initial())
    assert result["error"] is None
    assert result["diagnosis_status"] == "EVALUATED"
    assert nodes_of(result) == HAPPY_NODES
    assert messages_of(result) == [
        "파이프라인 시작",
        "실제 snapshot과 실행 증거 수집",
        "가설 1개 생성 (retry=0)",
        "실제 hunk 복원 실험 1개",
        "원인 실험 통과: inventory-check",
        "의도 추출 완료 (조건 1개, 버린 조건 0개)",
        "후보 1개 생성 (retry=0)",
        "후보 1개 실제 C 계약 검증",
        "유효 후보 1개",
        "순위 매기기 완료, recommended=0",
        f"제안서 저장: {result['proposal']['id']}",
    ]
    assert result["step_count"] == 10
    assert result["max_steps"] == 40
    assert all(list(e) == ["at", "node", "msg", "run_id"] for e in result["events"])
    assert all(e["run_id"] is None for e in result["events"])
    assert result["hypothesis_retry"] == 0
    assert result["candidate_retry"] == 0


@pytest.mark.asyncio
async def test_happy_path_evidence_and_root_cause(stub, llm):
    result = await run(initial())
    assert result["execution_id"] == EXECUTION
    assert result["snapshot_id"] == CANDIDATE
    assert result["baseline_snapshot_id"] == BASELINE
    assert result["preflight_run_ids"] == []
    assert result["diagnosis"] is None
    assert list(result["skill_versions"].items()) == [
        ("order-flow", 1),
        ("inventory-check", 1),
        ("payment", 1),
    ]
    [change] = result["changes"]
    assert list(change) == [
        "index",
        "hunk_id",
        "part",
        "header",
        "before",
        "after",
        "diff",
        "change_no",
        "skill",
    ]
    assert change["change_no"] == 1 and change["skill"] == "inventory-check"
    assert list(result["diff"]) == ["files", "hunks"]
    assert result["diff"]["hunks"] == result["changes"]
    assert [f["skill_id"] for f in result["diff"]["files"]] == ["inventory-check"]
    assert result["hypotheses"] == [
        {"change_no": 1, "summary": "inventory output deletion", "confidence": 0.9}
    ]

    [ablation] = result["ablation_runs"]
    assert list(ablation) == [
        "change_no",
        "workspace_id",
        "run_ids",
        "control_run_ids",
        "confirmed",
    ]
    assert ablation["change_no"] == 1 and ablation["confirmed"] is True
    assert len(ablation["run_ids"]) == 1 and len(ablation["control_run_ids"]) == 1

    root_cause = dict(result["root_cause"])
    assert list(root_cause) == [
        "skill",
        "change_no",
        "hunk_id",
        "from_version",
        "to_version",
        "summary",
        "proof_run_ids",
    ]
    assert root_cause.pop("proof_run_ids") == ablation["control_run_ids"] + ablation["run_ids"]
    assert root_cause == {
        "skill": "inventory-check",
        "change_no": 1,
        "hunk_id": "fixture-hunk",
        "from_version": 1,
        "to_version": 1,
        "summary": "outputs: [inventory.reserved] → outputs: []",
    }


@pytest.mark.asyncio
async def test_happy_path_candidate_intent_and_proposal_shape(stub, llm):
    result = await run(initial())
    assert list(result["intent"].items()) == [
        ("text", "keep payment behavior"),
        ("checks", ["payment.status == 'PAID'"]),
        ("dropped_checks", []),
        ("confirmed", None),
    ]
    [candidate] = result["candidates"]
    assert list(candidate) == [
        "candidate_no",
        "kind",
        "workspace",
        "patches",
        "results",
        "valid",
        "evidence_run_ids",
        "rank",
        "explanation",
        "validation_error",
        "metadata_errors",
        "flow_errors",
    ]
    assert list(candidate["results"].items()) == [
        ("fixes_failure", True),
        ("keeps_old_orders", {"passed": 3, "total": 3}),
        ("keeps_intent", True),
        ("no_broken_links", True),
        ("metadata_valid", True),
        ("flow_valid", True),
        ("changed_skill_count", 1),
        ("tools_called", ["inventory.reserve", "inventory.reserve"]),
    ]
    assert candidate["candidate_no"] == 1 and candidate["kind"] == "same_skill"
    assert candidate["workspace"].startswith("w_")
    assert candidate["valid"] is True and candidate["validation_error"] is None
    assert candidate["rank"] == 1 and candidate["explanation"] == "trial evidence"
    assert len(candidate["evidence_run_ids"]) == 10
    assert result["recommended"] == 0
    [patch] = candidate["patches"]
    assert list(patch.items()) == [
        ("skill", "inventory-check"),
        ("base_version", 1),
        (
            "patch",
            "--- a/skills/inventory-check/SKILL.md\n"
            "+++ b/skills/inventory-check/SKILL.md\n"
            "@@ -1,5 +1,5 @@\n"
            " ---\n"
            " name: inventory-check\n"
            "-outputs: []\n"
            "+outputs: [inventory.reserved]\n"
            " ---\n"
            " body\n",
        ),
    ]

    proposal = result["proposal"]
    assert list(proposal) == [
        "thread_id",
        "trigger",
        "baseline_snapshot_id",
        "snapshot_id",
        "failure",
        "root_cause",
        "intent",
        "candidates",
        "recommended_candidate_no",
        "status",
        "id",
        "storage_status",
        "valid_candidate_nos",
    ]
    assert proposal["thread_id"] is None
    assert list(proposal["failure"].items()) == [
        ("execution_id", EXECUTION),
        ("skill", "payment"),
        ("category", "CONTRACT_VIOLATION"),
        ("message", None),
        ("missing_inputs", ["inventory.reserved"]),
        ("at", None),
    ]
    assert proposal["root_cause"] == {
        **result["root_cause"],
        "before": "outputs: [inventory.reserved]",
        "after": "outputs: []",
    }
    assert list(proposal["root_cause"])[-2:] == ["before", "after"]
    [saved_candidate] = proposal["candidates"]
    assert list(saved_candidate)[-1] == "workspace_id"
    assert "workspace" not in saved_candidate
    assert saved_candidate["workspace_id"] == candidate["workspace"]
    assert proposal["recommended_candidate_no"] == 1
    assert proposal["status"] == "proposed"
    assert proposal["storage_status"] == "SAVED"
    assert proposal["valid_candidate_nos"] == [1]
    sent = {
        k: v
        for k, v in proposal.items()
        if k not in ("id", "storage_status", "valid_candidate_nos")
    }
    assert stub.saved == [sent]


@pytest.mark.asyncio
async def test_happy_path_tool_calls_in_order(stub, llm, monkeypatch):
    calls = record_tools(monkeypatch, stub)
    result = await run(initial())
    assert result["error"] is None
    assert [name for name, *_ in calls] == [
        "get_trace",
        "get_snapshot",
        "get_snapshot",
        "list_skills",
        "diff_skill",
        "create_workspace",
        "run_scenarios",
        "create_workspace",
        "apply_patch",
        "run_scenarios",
        "get_trace",
        "get_dependency_graph",
        "get_skill",
        "get_skill",
        "get_skill",
        "create_workspace",
        "apply_patch",
        "run_scenarios",
        "run_scenarios",
        "run_scenarios",
        "get_trace",
        "get_trace",
        "save_proposal",
    ]
    by_name = {}
    for name, args, kwargs, _ in calls:
        by_name.setdefault(name, []).append((args, kwargs))
    assert by_name["get_trace"][0] == ((EXECUTION,), {})
    assert [len(args) for args, _ in by_name["get_trace"][1:]] == [1, 1, 1]
    assert all(args[0].startswith("r_") for args, _ in by_name["get_trace"][1:])
    assert by_name["get_snapshot"] == [((CANDIDATE,), {}), ((BASELINE,), {})]
    assert by_name["list_skills"] == [((CANDIDATE,), {})]
    assert by_name["diff_skill"] == [(("inventory-check", BASELINE, CANDIDATE), {})]
    assert by_name["create_workspace"] == [((), {"snapshot_id": CANDIDATE})] * 3
    assert by_name["get_dependency_graph"] == [
        ((), {"snapshot_id": CANDIDATE, "entry_skill_id": "order-flow"})
    ]
    assert by_name["get_skill"] == [
        ((CANDIDATE, "order-flow"), {}),
        ((CANDIDATE, "inventory-check"), {}),
        ((CANDIDATE, "payment"), {}),
    ]
    assert all(len(args) == 2 and not kwargs for args, kwargs in by_name["apply_patch"])
    [ablation_patch] = by_name["apply_patch"][0][0][1]
    assert ablation_patch == {
        "skill": "inventory-check",
        "base_version": 1,
        "patch": "@@ -1 +1 @@\n+outputs: [inventory.reserved]\n-outputs: []\n",
    }
    assert all(len(args) == 2 for name, args, *_ in calls if name == "run_scenarios")
    assert scenario_calls(calls) == [
        ([REPLAY], {"repeat": 1, "extra_checks": None}),
        ([REPLAY], {"repeat": 1, "extra_checks": None}),
        (GOLDEN, {"repeat": 2, "extra_checks": None}),
        ([REPLAY], {"repeat": 2, "extra_checks": None}),
        ([REPLAY], {"repeat": 2, "extra_checks": ["payment.status == 'PAID'"]}),
    ]
    assert by_name["save_proposal"] == [((stub.saved[0],), {})]


@pytest.mark.asyncio
async def test_run_id_alias_trigger_is_normalized(stub, llm, monkeypatch):
    calls = record_tools(monkeypatch, stub)
    alias = "r_" + EXECUTION.replace("-", "")
    result = await run({"trigger": {"type": "failure", "run_id": alias}, "max_steps": 40})
    assert result["diagnosis_status"] == "EVALUATED"
    assert result["execution_id"] == EXECUTION
    assert calls[0][:3] == ("get_trace", (EXECUTION,), {})
    assert scenario_calls(calls)[0] == ([REPLAY], {"repeat": 1, "extra_checks": None})


@pytest.mark.asyncio
async def test_prompts_receive_previous_failures_none_on_first_try(stub, llm):
    await run(initial())
    [hypothesis_prompt] = llm.prompts_for("_HypothesesOutput")
    assert "기각된 가설이 있으면 참고하세요: 없음" in hypothesis_prompt
    [candidate_prompt] = llm.prompts_for("_CandidatesOutput")
    assert "'skill_versions': {'order-flow': 1, 'inventory-check': 1, 'payment': 1}" in (
        candidate_prompt
    )
    assert "\n없음\n" in candidate_prompt
    [intent_prompt] = llm.prompts_for("_IntentOutput")
    assert "payment.status" in intent_prompt
    assert [schema for schema, _ in llm.prompts] == [
        "_HypothesesOutput",
        "_IntentOutput",
        "_CandidatesOutput",
        "_RankExplanationsOutput",
    ]


@pytest.mark.asyncio
async def test_graph_has_the_eleven_pipeline_nodes():
    assert set(graph.get_graph().nodes) == set(HAPPY_NODES) | {"__start__", "__end__"}


# ── 가설 재시도와 단계 한도 ────────────────────────────────────────────────────


@pytest.mark.asyncio
async def test_hypothesis_retry_escalates_to_proposal_after_three_rejections(
    stub, llm, monkeypatch
):
    ablation_never_confirms(monkeypatch, stub)
    result = await run(initial())
    assert result["error"] is None
    assert nodes_of(result) == ["init", "fetch_evidence"] + HYPOTHESIS_LOOP * 3 + ["save_proposal"]
    assert result["hypothesis_retry"] == 3
    assert result["step_count"] == 11
    assert messages_of(result, "check_ablation") == ["가설 기각"] * 3
    assert messages_of(result, "hypothesize") == [
        "가설 1개 생성 (retry=0)",
        "가설 1개 생성 (retry=1)",
        "가설 1개 생성 (retry=2)",
    ]
    prompts = llm.prompts_for("_HypothesesOutput")
    assert len(prompts) == 3
    assert "참고하세요: 없음" in prompts[0]
    assert "참고하세요: change 1: ablation 재현 실패" in prompts[1]
    assert "참고하세요: change 1: ablation 재현 실패" in prompts[2]
    assert result["root_cause"] is None
    saved = stub.saved[-1]
    assert saved["status"] == "no_valid_fix"
    assert saved["root_cause"] is None
    assert saved["intent"] is None
    assert saved["recommended_candidate_no"] is None
    assert saved["candidates"] == []
    assert result["diagnosis_status"] == "EVALUATED"
    assert result["proposal"]["storage_status"] == "SAVED"


@pytest.mark.asyncio
async def test_hypothesis_loop_stops_at_step_limit(stub, llm, monkeypatch):
    """max_steps=3 이면 첫 기각 뒤 step_count 가 4라서 limit_exceeded 로 save_proposal 에 간다."""
    ablation_never_confirms(monkeypatch, stub)
    result = await run(initial(max_steps=3))
    assert nodes_of(result) == ["init", "fetch_evidence"] + HYPOTHESIS_LOOP + ["save_proposal"]
    assert result["hypothesis_retry"] == 1
    assert result["diagnosis_status"] == "EVALUATED"
    assert stub.saved[-1]["status"] == "no_valid_fix"


@pytest.mark.asyncio
async def test_hypothesis_loop_runs_until_step_limit_is_reached(stub, llm, monkeypatch):
    """max_steps=7 이면 두 번째 기각 뒤 step_count 가 7에 닿아 save_proposal 로 간다."""
    ablation_never_confirms(monkeypatch, stub)
    result = await run(initial(max_steps=7))
    assert nodes_of(result) == ["init", "fetch_evidence"] + HYPOTHESIS_LOOP * 2 + ["save_proposal"]
    assert result["hypothesis_retry"] == 2
    assert result["step_count"] == 8


@pytest.mark.asyncio
async def test_first_confirmed_ablation_in_hypothesis_order_is_the_root_cause(
    stub, llm, monkeypatch
):
    second = {
        "index": 1,
        "hunk_id": "second-hunk",
        "part": "body",
        "header": "@@ -5 +5 @@",
        "before": "old body",
        "after": "new body",
        "diff": "@@ -5 +5 @@\n-old body\n+new body\n",
    }

    def two_hunks(result, *args):
        result["hunks"] = result["hunks"] + [second]

    change_result(monkeypatch, stub, "diff_skill", two_hunks)
    llm.outputs["_HypothesesOutput"] = SimpleNamespace(
        hypotheses=[
            SimpleNamespace(change_no=2, summary="body change", confidence=0.8),
            SimpleNamespace(change_no=1, summary="output deletion", confidence=0.7),
        ]
    )
    calls = record_tools(monkeypatch, stub)
    result = await run(initial())
    assert [(c["change_no"], c["hunk_id"]) for c in result["changes"]] == [
        (1, "fixture-hunk"),
        (2, "second-hunk"),
    ]
    assert [r["change_no"] for r in result["ablation_runs"]] == [2, 1]
    assert messages_of(result, "ablate") == ["실제 hunk 복원 실험 2개"]
    assert result["root_cause"]["change_no"] == 2
    assert result["root_cause"]["hunk_id"] == "second-hunk"
    assert result["root_cause"]["summary"] == "old body → new body"
    patches = [args[1][0]["patch"] for name, args, *_ in calls if name == "apply_patch"][:2]
    assert patches == [
        "@@ -5 +5 @@\n+old body\n-new body\n",
        "@@ -1 +1 @@\n+outputs: [inventory.reserved]\n-outputs: []\n",
    ]
    known_value_trace = [args for name, args, *_ in calls if name == "get_trace"][1]
    assert known_value_trace == (result["ablation_runs"][0]["run_ids"][0],)
    saved = stub.saved[-1]
    assert (saved["root_cause"]["before"], saved["root_cause"]["after"]) == (
        "old body",
        "new body",
    )


# ── 후보 재시도와 단계 한도 ────────────────────────────────────────────────────


@pytest.mark.asyncio
async def test_candidate_retry_stops_after_three_rounds(stub, llm, monkeypatch):
    broken_links(monkeypatch, stub)
    result = await run(initial())
    assert result["error"] is None
    assert nodes_of(result) == HAPPY_NODES[:6] + REPAIR_LOOP * 3 + ["save_proposal"]
    assert result["candidate_retry"] == 3
    assert result["step_count"] == 15
    assert messages_of(result, "check_valid") == [
        "유효 후보 없음, 재시도 1/3",
        "유효 후보 없음, 재시도 2/3",
        "유효 후보 없음, 재시도 3/3",
    ]
    assert messages_of(result, "generate_candidates") == [
        "후보 1개 생성 (retry=0)",
        "후보 1개 생성 (retry=1)",
        "후보 1개 생성 (retry=2)",
    ]
    prompts = llm.prompts_for("_CandidatesOutput")
    assert len(prompts) == 3
    feedback = (
        "1(same_skill): fixes_failure=True, keeps_old_orders={'passed': 3, 'total': 3}, "
        "keeps_intent=True, error=None"
    )
    assert feedback not in prompts[0]
    assert feedback in prompts[1] and feedback in prompts[2]
    [candidate] = result["candidates"]
    assert candidate["valid"] is False
    assert candidate["results"]["no_broken_links"] is False
    assert candidate["rank"] is None and candidate["explanation"] is None
    assert result["recommended"] is None
    saved = stub.saved[-1]
    assert saved["status"] == "no_valid_fix"
    assert saved["recommended_candidate_no"] is None
    assert result["diagnosis_status"] == "EVALUATED"


@pytest.mark.asyncio
async def test_candidate_loop_stops_at_step_limit(stub, llm, monkeypatch):
    """max_steps=8 이면 첫 후보 회차 뒤 step_count 가 8에 닿아 no_valid_fix 로 저장한다."""
    broken_links(monkeypatch, stub)
    result = await run(initial(max_steps=8))
    assert nodes_of(result) == HAPPY_NODES[:9] + ["save_proposal"]
    assert result["candidate_retry"] == 1
    assert messages_of(result, "check_valid") == ["유효 후보 없음, 재시도 1/3"]
    assert stub.saved[-1]["status"] == "no_valid_fix"


@pytest.mark.asyncio
async def test_confirmed_root_cause_ignores_step_limit(stub, llm):
    """원인이 확정되면 step_count 가 max_steps(4)에 닿아도 extract_intent 로 간다."""
    result = await run(initial(max_steps=4))
    assert nodes_of(result)[:6] == HAPPY_NODES[:6]
    assert result["root_cause"]["skill"] == "inventory-check"


@pytest.mark.asyncio
async def test_rejected_apply_without_message_uses_error_text_and_feeds_retry(
    stub, llm, monkeypatch
):
    original = stub.apply_patch
    count = 0

    async def reject_candidates(workspace_id, patches):
        nonlocal count
        count += 1
        if count > 1:
            raise MCPToolError("apply_patch", {"code": "INVALID_INPUT"})
        return await original(workspace_id, patches)

    monkeypatch.setattr(stub, "apply_patch", reject_candidates)
    result = await run(initial())
    assert result["error"] is None
    message = 'MCP apply_patch failed: {"code": "INVALID_INPUT"}'
    [candidate] = result["candidates"]
    assert candidate["valid"] is False
    assert candidate["validation_error"] == message
    assert candidate["workspace"].startswith("w_")
    assert candidate["results"] is None and candidate["evidence_run_ids"] == []
    assert list(candidate)[-1] == "validation_error"
    prompts = llm.prompts_for("_CandidatesOutput")
    assert (
        "1(same_skill): fixes_failure=None, keeps_old_orders=None, keeps_intent=None, "
        f"error={message}"
    ) in prompts[1]
    assert stub.saved[-1]["status"] == "no_valid_fix"


# ── 후보 형식 검사 ─────────────────────────────────────────────────────────────


@pytest.mark.asyncio
async def test_candidate_format_errors_keep_partial_patches(stub, llm, monkeypatch):
    llm.outputs["_CandidatesOutput"] = SimpleNamespace(
        candidates=[
            candidate_spec("same_skill", revision("shipping", FIXED_INVENTORY)),
            candidate_spec(
                "same_skill",
                revision("inventory-check", FIXED_INVENTORY),
                revision("inventory-check", FIXED_INVENTORY),
            ),
            candidate_spec("linked_skills", revision("payment", "name: payment\n")),
            candidate_spec("run_order", revision("inventory-check", CURRENT_INVENTORY)),
            candidate_spec("linked_skills", revision("payment", "   ")),
            candidate_spec("run_order"),
        ]
    )
    calls = record_tools(monkeypatch, stub)
    result = await run(initial())
    assert result["error"] is None
    assert [c["validation_error"] for c in result["candidates"]] == [
        "수정 대상이 없거나 같은 파일을 여러 번 제안했습니다.",
        "수정 대상이 없거나 같은 파일을 여러 번 제안했습니다.",
        "수정 후 전체 SKILL.md 원문이 필요합니다.",
        "현재 수정본과 달라진 내용이 없는 후보입니다.",
        "수정 후 전체 SKILL.md 원문이 필요합니다.",
        "현재 수정본과 달라진 내용이 없는 후보입니다.",
    ]
    assert [len(c["patches"]) for c in result["candidates"]] == [0, 1, 0, 0, 0, 0]
    assert [c["candidate_no"] for c in result["candidates"]] == [1, 2, 3, 4, 5, 6]
    assert all(c["valid"] is False for c in result["candidates"])
    assert all(c["workspace"] is None and c["results"] is None for c in result["candidates"])
    assert messages_of(result, "generate_candidates")[0] == "후보 6개 생성 (retry=0)"
    assert nodes_of(result) == HAPPY_NODES[:6] + REPAIR_LOOP * 3 + ["save_proposal"]
    assert [name for name, *_ in calls].count("apply_patch") == 1
    assert stub.saved[-1]["status"] == "no_valid_fix"


# ── 순위와 설명 ───────────────────────────────────────────────────────────────


@pytest.mark.asyncio
async def test_smaller_valid_candidate_ranks_first_and_missing_explanation_cites_runs(
    stub, llm
):
    llm.outputs["_CandidatesOutput"] = SimpleNamespace(
        candidates=[
            candidate_spec(
                "linked_skills",
                revision("inventory-check", FIXED_INVENTORY),
                revision("payment", FIXED_PAYMENT),
            ),
            candidate_spec("same_skill", revision("inventory-check", FIXED_INVENTORY)),
        ]
    )
    llm.outputs["_RankExplanationsOutput"] = SimpleNamespace(
        explanations=[SimpleNamespace(candidate_no=2, explanation="smallest change")]
    )
    result = await run(initial())
    first, second = result["candidates"]
    assert first["valid"] is True and second["valid"] is True
    assert first["results"]["changed_skill_count"] == 2
    assert second["results"]["changed_skill_count"] == 1
    assert (first["rank"], second["rank"]) == (2, 1)
    assert result["recommended"] == 1
    evidence = first["evidence_run_ids"]
    assert first["explanation"] == f"rank 2: {evidence[0]}, {evidence[1]}"
    assert second["explanation"] == "smallest change"
    assert messages_of(result, "rank_and_explain") == ["순위 매기기 완료, recommended=1"]
    assert stub.saved[-1]["recommended_candidate_no"] == 2
    assert result["proposal"]["valid_candidate_nos"] == [1, 2]
    [rank_prompt] = llm.prompts_for("_RankExplanationsOutput")
    assert rank_prompt.index("'candidate_no': 2") < rank_prompt.index("'candidate_no': 1")
    assert "'rank': 1" in rank_prompt and "'rank': 2" in rank_prompt


@pytest.mark.asyncio
async def test_empty_or_missing_llm_explanation_falls_back_to_evidence(stub, llm):
    llm.outputs["_RankExplanationsOutput"] = SimpleNamespace(
        explanations=[SimpleNamespace(candidate_no=1, explanation="")]
    )
    result = await run(initial())
    [candidate] = result["candidates"]
    evidence = candidate["evidence_run_ids"]
    assert candidate["explanation"] == f"rank 1: {evidence[0]}, {evidence[1]}"


@pytest.mark.asyncio
async def test_none_llm_outputs_use_documented_fallbacks(stub, llm):
    llm.outputs["_IntentOutput"] = None
    llm.outputs["_RankExplanationsOutput"] = None
    result = await run(initial())
    assert result["error"] is None
    assert result["intent"] == {
        "text": "의도 추출 실패",
        "checks": [],
        "dropped_checks": [],
        "confirmed": None,
    }
    assert messages_of(result, "extract_intent") == ["의도 추출 완료 (조건 0개, 버린 조건 0개)"]
    [candidate] = result["candidates"]
    assert len(candidate["evidence_run_ids"]) == 8
    assert candidate["results"]["keeps_intent"] is True
    evidence = candidate["evidence_run_ids"]
    assert candidate["explanation"] == f"rank 1: {evidence[0]}, {evidence[1]}"


@pytest.mark.asyncio
@pytest.mark.parametrize(
    ("schema", "output", "error", "events"),
    [
        ("_HypothesesOutput", None, "ContractError: LLM returned no hypotheses", 2),
        (
            "_CandidatesOutput",
            SimpleNamespace(candidates=[]),
            "ContractError: LLM returned no candidates",
            6,
        ),
        ("_CandidatesOutput", None, "ContractError: LLM returned no candidates", 6),
    ],
)
async def test_empty_llm_outputs_end_in_error(stub, llm, schema, output, error, events):
    llm.outputs[schema] = output
    result = await run(initial())
    assert result["diagnosis_status"] == "ERROR"
    assert result["error"] == error
    assert nodes_of(result) == HAPPY_NODES[:events]
    assert stub.saved == []


@pytest.mark.asyncio
async def test_intent_checks_are_filtered_with_reasons(stub, llm):
    llm.outputs["_IntentOutput"] = SimpleNamespace(
        text="결제가 끝까지 처리되게 한다.",
        checks=[
            "inventory-check.outputs == ['inventory.available']",
            "payment.status == 'PAID'",
            "inventory.unknown != null",
            "payment.status in 'PAID'",
            "payment.status == PAID",
        ],
    )
    result = await run(initial())
    assert result["intent"]["checks"] == ["payment.status == 'PAID'"]
    assert result["intent"]["dropped_checks"] == [
        {
            "check": "inventory-check.outputs == ['inventory.available']",
            "reason": "값을 읽을 수 없습니다('글자', true, false, null, 숫자만 됩니다)",
        },
        {"check": "inventory.unknown != null", "reason": "실행 기록에 없는 값 이름입니다"},
        {"check": "payment.status in 'PAID'", "reason": "in 뒤에는 [..] 목록이 와야 합니다"},
        {
            "check": "payment.status == PAID",
            "reason": "값을 읽을 수 없습니다('글자', true, false, null, 숫자만 됩니다)",
        },
    ]
    assert messages_of(result, "extract_intent") == ["의도 추출 완료 (조건 1개, 버린 조건 4개)"]


# ── 저장 직후 사전 검증 (skill_saved) ──────────────────────────────────────────


@pytest.mark.asyncio
async def test_saved_change_validated_without_llm(stub, no_llm, monkeypatch):
    healthy_workspaces(monkeypatch, stub)
    calls = record_tools(monkeypatch, stub)
    result = await run(saved_trigger())
    assert result["diagnosis_status"] == "VALIDATED"
    assert nodes_of(result) == ["init", "fetch_evidence"]
    assert result["max_steps"] == 40
    assert result["step_count"] == 1
    assert result["execution_id"] is None
    assert result["trace"] is None
    assert result["diagnosis"] is None
    assert len(result["changes"]) == 1
    assert list(result["skill_versions"]) == ["order-flow", "inventory-check", "payment"]
    reports = [r for name, _, _, r in calls if name == "run_scenarios"]
    assert result["preflight_run_ids"] == run_ids_of(reports[1])
    assert len(result["preflight_run_ids"]) == 6
    assert [name for name, *_ in calls] == [
        "get_snapshot",
        "get_snapshot",
        "list_skills",
        "diff_skill",
        "create_workspace",
        "run_scenarios",
        "create_workspace",
        "run_scenarios",
    ]
    workspaces = [kwargs for name, _, kwargs, _ in calls if name == "create_workspace"]
    assert workspaces == [{"snapshot_id": BASELINE}, {"snapshot_id": CANDIDATE}]
    assert scenario_calls(calls) == [
        (GOLDEN, {"repeat": 2, "extra_checks": None}),
        (GOLDEN, {"repeat": 2, "extra_checks": None}),
    ]
    assert result["proposal"] is None


@pytest.mark.asyncio
async def test_saved_change_with_failing_baseline_stops_before_candidate(
    stub, no_llm, monkeypatch
):
    broken_workspaces(monkeypatch, stub)
    calls = record_tools(monkeypatch, stub)
    result = await run(saved_trigger())
    assert result["diagnosis_status"] == "BASELINE_FAILED"
    assert nodes_of(result) == ["init", "fetch_evidence"]
    assert list(result["diagnosis"]) == ["explanation", "failures"]
    assert result["diagnosis"]["explanation"] == (
        "기준 세트의 주문도 검증을 통과하지 못했습니다. "
        "재고 등 검증 환경을 확인하세요. 수정본 결함으로 확정하지 않습니다."
    )
    [report] = [r for name, _, _, r in calls if name == "run_scenarios"]
    assert result["preflight_run_ids"] == run_ids_of(report)
    assert result["diagnosis"]["failures"] == [f for r in report["results"] for f in r["failures"]]
    assert len(result["diagnosis"]["failures"]) == 6
    assert result["snapshot_id"] == CANDIDATE
    assert result["baseline_snapshot_id"] == BASELINE
    assert result["changes"] == [] and result["diff"] is None
    assert result["skill_versions"] == {} and result["trace"] is None
    assert result["execution_id"] is None
    workspaces = [kwargs for name, _, kwargs, _ in calls if name == "create_workspace"]
    assert workspaces == [{"snapshot_id": BASELINE}]


@pytest.mark.asyncio
async def test_saved_change_failing_without_execution_error_is_validation_failed(
    stub, no_llm, monkeypatch
):
    calls = record_tools(monkeypatch, stub)
    result = await run(saved_trigger())
    assert result["diagnosis_status"] == "VALIDATION_FAILED"
    assert nodes_of(result) == ["init", "fetch_evidence"]
    assert list(result["diagnosis"]) == ["explanation", "failures"]
    assert result["diagnosis"]["explanation"] == (
        "기대 결과와 실제 업무 결과가 다릅니다. "
        "실행 오류 없이 끝나 자동 원인 확정은 진행하지 않습니다."
    )
    reports = [r for name, _, _, r in calls if name == "run_scenarios"]
    assert len(reports) == 2
    assert result["preflight_run_ids"] == run_ids_of(reports[1])
    assert len(result["preflight_run_ids"]) == 6
    candidate_failures = [f for r in reports[1]["results"] for f in r["failures"]]
    assert result["diagnosis"]["failures"] == candidate_failures
    assert len(candidate_failures) == 6
    assert result["snapshot_id"] == CANDIDATE
    assert result["baseline_snapshot_id"] == BASELINE
    assert result["trace"] is None and result["changes"] == [] and result["diff"] is None
    assert result["skill_versions"] == {} and result["execution_id"] is None
    first_failure = reports[1]["results"][0]["failures"][0]["run_id"]
    assert calls[-1][:3] == ("get_trace", (execution_uuid(first_failure),), {})


@pytest.mark.asyncio
async def test_saved_change_with_failed_trace_continues_diagnosis(stub, llm, monkeypatch):
    replaced = failed_preflight_trace(monkeypatch, stub)
    calls = record_tools(monkeypatch, stub)
    result = await run(saved_trigger())
    assert result["error"] is None
    assert result["diagnosis_status"] == "EVALUATED"
    assert result["proposal"]["storage_status"] == "SAVED"
    assert nodes_of(result) == HAPPY_NODES
    assert result["execution_id"] == replaced["execution"]
    assert result["trace"]["execution_id"] == replaced["execution"]
    reports = [r for name, _, _, r in calls if name == "run_scenarios"]
    assert result["preflight_run_ids"] == run_ids_of(reports[1])
    assert len(result["preflight_run_ids"]) == 6
    replay = f"f-{replaced['execution']}"
    assert scenario_calls(calls)[2:] == [
        ([replay], {"repeat": 1, "extra_checks": None}),
        ([replay], {"repeat": 1, "extra_checks": None}),
        (GOLDEN, {"repeat": 2, "extra_checks": None}),
        ([replay], {"repeat": 2, "extra_checks": None}),
        ([replay], {"repeat": 2, "extra_checks": ["payment.status == 'PAID'"]}),
    ]
    snapshots = [args for name, args, *_ in calls if name == "get_snapshot"]
    assert snapshots == [(CANDIDATE,), (BASELINE,), (CANDIDATE,)]
    assert stub.saved[-1]["failure"]["execution_id"] == replaced["execution"]


@pytest.mark.asyncio
async def test_saved_change_replay_with_other_files_is_rejected(stub, no_llm, monkeypatch):
    failed_preflight_trace(monkeypatch, stub, snapshot_id=BASELINE)
    result = await run(saved_trigger())
    assert result["diagnosis_status"] == "ERROR"
    assert result["error"] == "ContractError: preflight executed different candidate files"
    assert nodes_of(result) == ["init"]


@pytest.mark.asyncio
async def test_identical_manifests_report_no_skill_changes(stub, no_llm, monkeypatch):
    def same_files(result, *args):
        result["files"] = [{"path": "skills/inventory-check/SKILL.md", "hash": "same"}]

    change_result(monkeypatch, stub, "get_snapshot", same_files)
    calls = record_tools(monkeypatch, stub)
    result = await run(initial())
    assert result["diagnosis_status"] == "NO_SKILL_CHANGES"
    assert result["changes"] == []
    assert result["diff"] == {"files": [], "hunks": []}
    assert result["execution_id"] == EXECUTION
    assert result["trace"]["execution_id"] == EXECUTION
    assert result["preflight_run_ids"] == []
    assert nodes_of(result) == ["init", "fetch_evidence"]
    assert "diff_skill" not in [name for name, *_ in calls]


# ── 의도 위반 진단 전용 (diagnosis_only) ───────────────────────────────────────


@pytest.mark.asyncio
async def test_diagnosis_only_without_violation_is_evaluated(stub, no_llm, monkeypatch):
    calls = record_tools(monkeypatch, stub)
    checks = ["payment.status == 'PAID'"]
    result = await run(initial(diagnosis_only=True, intent_checks=checks))
    assert result["diagnosis_status"] == "EVALUATED"
    assert nodes_of(result) == ["init", "fetch_evidence"]
    diagnosis = result["diagnosis"]
    assert list(diagnosis) == ["intent_checks", "failures", "evidence_run_ids", "explanation"]
    assert diagnosis["intent_checks"] == checks
    assert diagnosis["explanation"] == (
        "실행 결과를 확인했습니다. 의도 위반 확정 여부는 failures를 확인하세요."
    )
    [report] = [r for name, _, _, r in calls if name == "run_scenarios"]
    assert diagnosis["evidence_run_ids"] == run_ids_of(report)
    assert len(diagnosis["evidence_run_ids"]) == 2
    assert diagnosis["failures"] == report["results"][0]["failures"]
    assert scenario_calls(calls) == [([REPLAY], {"repeat": 2, "extra_checks": checks})]
    assert result["candidates"] == []
    assert len(result["changes"]) == 1


@pytest.mark.asyncio
async def test_diagnosis_only_check_failure_is_intent_violation(stub, no_llm, monkeypatch):
    def violation(report, *args, **kwargs):
        for r in report["results"]:
            r["failures"] = [
                {"run_id": i, "skill": None, "reason": "확인 조건 실패: payment.status != 'PAID'"}
                for i in r["run_ids"]
            ]

    change_result(monkeypatch, stub, "run_scenarios", violation)
    result = await run(initial(diagnosis_only=True, intent_checks=["payment.status != 'PAID'"]))
    assert result["diagnosis_status"] == "INTENT_VIOLATION"
    assert result["diagnosis"]["explanation"] == (
        "변경 정책의 확인 조건과 실제 실행 결과가 일치하지 않습니다."
    )
    assert len(result["diagnosis"]["failures"]) == 2


@pytest.mark.asyncio
async def test_diagnosis_only_needs_execution_even_after_validated_preflight(
    stub, no_llm, monkeypatch
):
    healthy_workspaces(monkeypatch, stub)
    calls = record_tools(monkeypatch, stub)
    result = await run(saved_trigger(diagnosis_only=True, intent_checks=["payment.status == 'X'"]))
    assert result["diagnosis_status"] == "ERROR"
    assert result["error"] == (
        "ContractError: diagnosis_only requires an execution and intent_checks"
    )
    assert scenario_calls(calls) == [
        (GOLDEN, {"repeat": 2, "extra_checks": None}),
        (GOLDEN, {"repeat": 2, "extra_checks": None}),
    ]


# ── 진행 기록 저장 (RunStore) ──────────────────────────────────────────────────


@pytest.mark.asyncio
async def test_run_store_keeps_progress_and_final_state(stub, llm):
    trigger = {"type": "failure", "execution_id": EXECUTION}
    thread, created = RunStore().accept(trigger)
    assert created
    result = await run({**initial(), "pipeline_thread_id": thread})
    record = RunStore().get(thread)
    assert record["diagnosis_status"] == "EVALUATED"
    assert [e["node"] for e in record["events"]] == HAPPY_NODES
    assert record["events"] == result["events"]
    assert record["proposal"]["storage_status"] == "SAVED"
    assert record["proposal"]["thread_id"] == thread
    assert record["step_count"] == 10
    assert record["error"] is None
    assert list(record) == [
        "thread_id",
        "change_id",
        "trigger",
        "created_at",
        "diagnosis_status",
        "events",
        "error",
        "step_count",
        "max_steps",
        "trace",
        "diff",
        "skill_versions",
        "snapshot_id",
        "baseline_snapshot_id",
        "execution_id",
        "changes",
        "diagnosis",
        "preflight_run_ids",
        "hypotheses",
        "ablation_runs",
        "root_cause",
        "hypothesis_retry",
        "intent",
        "candidates",
        "candidate_retry",
        "recommended",
        "proposal",
        "approval_status",
        "approval_token",
        "smoke_passed",
    ]


@pytest.mark.asyncio
async def test_run_store_records_node_error(stub, llm, monkeypatch):
    async def fail(*args, **kwargs):
        raise ValueError("MCP INVALID_INPUT")

    monkeypatch.setattr(stub, "run_scenarios", fail)
    thread, _ = RunStore().accept({"type": "failure", "execution_id": EXECUTION})
    result = await run({**initial(), "pipeline_thread_id": thread})
    assert result["error"] == "ValueError: MCP INVALID_INPUT"
    record = RunStore().get(thread)
    assert record["diagnosis_status"] == "ERROR"
    assert record["error"] == "ValueError: MCP INVALID_INPUT"
    assert [e["node"] for e in record["events"]] == ["init", "fetch_evidence", "hypothesize"]
    assert record["root_cause"] is None and record["proposal"] is None


@pytest.mark.asyncio
async def test_run_store_keeps_init_values_for_early_preflight_stop(stub, no_llm):
    """사전 검증에서 일찍 멈춰도 init 이 채운 기본값이 RunStore 기록에 남는다."""
    thread, _ = RunStore().accept({"type": "skill_saved", "candidate_snapshot_id": CANDIDATE})
    await run({**saved_trigger(), "pipeline_thread_id": thread})
    record = RunStore().get(thread)
    assert record["diagnosis_status"] == "VALIDATION_FAILED"
    assert record["trace"] is None and record["diff"] is None
    assert record["changes"] == [] and record["skill_versions"] == {}
    assert record["execution_id"] is None
    assert len(record["preflight_run_ids"]) == 6
    assert [e["node"] for e in record["events"]] == ["init", "fetch_evidence"]


# ── 증거 수집 검사 순서와 오류 문구 ────────────────────────────────────────────


def _snapshot_change(change):
    """get_snapshot 응답을 change 로 고치는 stub 조정 함수를 만든다."""

    def adjust(monkeypatch, stub):
        change_result(monkeypatch, stub, "get_snapshot", change)

    return adjust


def _tool_change(name, change):
    """도구 name 의 응답을 change 로 고치는 stub 조정 함수를 만든다."""

    def adjust(monkeypatch, stub):
        change_result(monkeypatch, stub, name, change)

    return adjust


def _only(target, **values):
    """요청한 snapshot 이 target 일 때만 응답에 values 를 덮어쓰는 change 를 만든다."""

    def change(result, requested):
        if requested == target:
            result.update(values)

    return change


def _non_uuid_baseline(monkeypatch, stub):
    """후보 manifest 의 기준 snapshot 을 UUID 가 아닌 'snap_prev' 로 바꾼다."""
    original = stub.get_snapshot

    async def get_snapshot(snapshot_id):
        if snapshot_id == "snap_prev":
            manifest = await original(BASELINE)
            manifest["snapshot_id"] = "snap_prev"
            return manifest
        manifest = await original(snapshot_id)
        manifest["metadata"] = {"baseline_snapshot_id": "snap_prev"}
        return manifest

    monkeypatch.setattr(stub, "get_snapshot", get_snapshot)


def _extra_file(path, both):
    """manifest 에 파일 path 를 더하는 change 를 만든다. both 가 거짓이면 후보에만 더한다."""

    def change(result, requested):
        if both or requested == CANDIDATE:
            result["files"] = result["files"] + [{"path": path, "hash": "h-" + requested}]

    return change


def _hunk(**values):
    """diff_skill 응답의 첫 hunk 에 values 를 덮어쓰는 change 를 만든다."""

    def change(result, *args):
        result["hunks"][0].update(values)

    return change


def _side(side, **values):
    """diff_skill 응답의 before 나 after 칸에 values 를 덮어쓰는 change 를 만든다."""

    def change(result, *args):
        result[side].update(values)

    return change


# fetch_evidence 단계의 계약 오류 표다. 항목은 (id, 초기 상태, stub 조정, 오류 문구) 순서다.
# 오류가 나면 fetch_evidence 가 진행 이벤트를 남기지 못하므로 init 만 진행 기록에 남는다.
# 표는 skillops-agent/skillops_agent/nodes.py 의 fetch_evidence 가 검사하는 순서대로 적었다.
EVIDENCE_ERRORS = [
    (
        "trigger_snapshot_differs",
        initial(candidate_snapshot_id=BASELINE),
        None,
        "trigger snapshot and execution snapshot differ",
    ),
    (
        "missing_candidate",
        {"trigger": {"type": "skill_saved"}},
        None,
        "candidate_snapshot_id or an execution with snapshot is required",
    ),
    (
        "candidate_manifest_id",
        initial(),
        _snapshot_change(_only(CANDIDATE, snapshot_id=BASELINE)),
        "invalid candidate snapshot manifest",
    ),
    (
        "candidate_manifest_hash",
        initial(),
        _snapshot_change(_only(CANDIDATE, hash="")),
        "invalid candidate snapshot manifest",
    ),
    (
        "baseline_manifest_hash",
        initial(),
        _snapshot_change(_only(BASELINE, hash=None)),
        "invalid baseline snapshot manifest",
    ),
    (
        "snapshot_id_not_uuid",
        initial(),
        _non_uuid_baseline,
        "execution_id must be a UUID or r_ + UUID hex",
    ),
    (
        "trace_hash",
        initial(),
        _tool_change("get_trace", lambda r, *a: r.update(snapshot_hash="other")),
        "trace and candidate snapshot hash differ",
    ),
    (
        "skill_list_snapshot",
        initial(),
        _tool_change("list_skills", lambda r, *a: r.update(snapshot_id=BASELINE)),
        "skill list and snapshot differ",
    ),
    (
        "skill_list_hash",
        initial(),
        _tool_change("list_skills", lambda r, *a: r.update(snapshot_hash="other")),
        "skill list and snapshot differ",
    ),
    (
        "skill_list_partial",
        initial(),
        _tool_change("list_skills", lambda r, *a: r.update(partial=True)),
        "candidate skill metadata is incomplete",
    ),
    (
        "skill_list_parse_errors",
        initial(),
        _tool_change("list_skills", lambda r, *a: r.update(parse_errors=["bad"])),
        "candidate skill metadata is incomplete",
    ),
    (
        "changed_paths",
        initial(changed_paths=["skills/payment/SKILL.md"]),
        None,
        "changed_paths does not match the two snapshot manifests",
    ),
    (
        "resource_path",
        initial(),
        _snapshot_change(_extra_file("skills/inventory-check/resources/a.md", both=True)),
        "resource/additional path diagnosis is not supported: "
        "skills/inventory-check/resources/a.md",
    ),
    (
        "empty_skill_name_is_still_a_skill_path",
        initial(),
        _snapshot_change(_extra_file("skills//SKILL.md", both=False)),
        "added/removed skills need a separate ablation policy",
    ),
    (
        "added_skill",
        initial(),
        _snapshot_change(_extra_file("skills/shipping/SKILL.md", both=False)),
        "added/removed skills need a separate ablation policy",
    ),
    (
        "diff_before_file_hash",
        initial(),
        _tool_change("diff_skill", _side("before", file_hash="x")),
        "diff evidence does not match the fixed snapshots",
    ),
    (
        "diff_after_snapshot_hash",
        initial(),
        _tool_change("diff_skill", _side("after", snapshot_hash="x")),
        "diff evidence does not match the fixed snapshots",
    ),
    (
        "diff_after_snapshot_id",
        initial(),
        _tool_change("diff_skill", _side("after", snapshot_id=BASELINE)),
        "diff evidence does not match the fixed snapshots",
    ),
    (
        "no_hunks",
        initial(),
        _tool_change("diff_skill", lambda r, *a: r.update(hunks=[])),
        "changed file returned no C hunks",
    ),
    (
        "hunks_not_list",
        initial(),
        _tool_change("diff_skill", lambda r, *a: r.update(hunks=None)),
        "changed file returned no C hunks",
    ),
    (
        "hunk_without_id",
        initial(),
        _tool_change("diff_skill", _hunk(hunk_id="")),
        "C hunk identity/patch missing",
    ),
    (
        "hunk_without_patch",
        initial(),
        _tool_change("diff_skill", _hunk(diff=None)),
        "C hunk identity/patch missing",
    ),
    (
        "diagnosis_only_missing_checks",
        initial(diagnosis_only=True),
        None,
        "diagnosis_only requires an execution and intent_checks",
    ),
]


@pytest.mark.asyncio
@pytest.mark.parametrize(
    ("state", "adjust", "message"),
    [case[1:] for case in EVIDENCE_ERRORS],
    ids=[case[0] for case in EVIDENCE_ERRORS],
)
async def test_evidence_contract_errors(stub, no_llm, monkeypatch, state, adjust, message):
    """계약 오류마다 ERROR 와 'ContractError: <문구>' 를 남기고 init 기본값이 그대로인지 본다."""
    if adjust:
        adjust(monkeypatch, stub)
    result = await run(state)
    assert result["diagnosis_status"] == "ERROR"
    assert result["error"] == f"ContractError: {message}"
    assert nodes_of(result) == ["init"]
    assert result["changes"] == [] and result["trace"] is None


@pytest.mark.asyncio
async def test_trigger_baseline_wins_over_manifest_metadata(stub, no_llm, monkeypatch):
    calls = record_tools(monkeypatch, stub)
    result = await run(initial(baseline_snapshot_id=CANDIDATE))
    assert result["diagnosis_status"] == "NO_SKILL_CHANGES"
    assert result["baseline_snapshot_id"] == CANDIDATE
    snapshots = [args for name, args, *_ in calls if name == "get_snapshot"]
    assert snapshots == [(CANDIDATE,), (CANDIDATE,)]


@pytest.mark.asyncio
async def test_missing_diff_side_raises_attribute_error(stub, no_llm, monkeypatch):
    change_result(monkeypatch, stub, "diff_skill", lambda r, *a: r.update(before=None))
    result = await run(initial())
    assert result["error"] == "AttributeError: 'NoneType' object has no attribute 'get'"


# ── 원인 실험·후보 생성·검증의 계약 오류 ───────────────────────────────────────


def _error_event_category(category):
    """trace 의 EXECUTION_ERROR category 를 category 로 바꾸는 change 를 만든다."""

    def change(trace, *args):
        for event in trace.get("events", []):
            if event.get("kind") == "EXECUTION_ERROR":
                event["data"]["category"] = category

    return change


def _without_error_event(trace, *args):
    """고정 실패 실행(EXECUTION)의 trace 에서 EXECUTION_ERROR 이벤트를 지운다."""
    if "events" in trace and trace.get("execution_id") == EXECUTION:
        trace["events"] = [e for e in trace["events"] if e["kind"] != "EXECUTION_ERROR"]


def _on_call(name, number, change):
    """도구 name 의 number 번째 응답만 change 로 고치는 stub 조정 함수를 만든다."""

    def adjust(monkeypatch, stub):
        original = getattr(stub, name)
        count = 0

        async def changed(*args, **kwargs):
            nonlocal count
            count += 1
            result = await original(*args, **kwargs)
            if count == number:
                change(result)
            return result

        monkeypatch.setattr(stub, name, changed)

    return adjust


def _raise_on_call(name, number, exc):
    """도구 name 의 number 번째 호출에서 exc 를 던지는 stub 조정 함수를 만든다."""

    def adjust(monkeypatch, stub):
        original = getattr(stub, name)
        count = 0

        async def changed(*args, **kwargs):
            nonlocal count
            count += 1
            if count == number:
                raise exc
            return await original(*args, **kwargs)

        monkeypatch.setattr(stub, name, changed)

    return adjust


def _both(*adjusters):
    """여러 stub 조정 함수를 차례로 적용하는 조정 함수를 만든다."""

    def adjust(monkeypatch, stub):
        for adjuster in adjusters:
            adjuster(monkeypatch, stub)

    return adjust


def _workspace_versions(monkeypatch, stub):
    """새 workspace 가 inventory-check 버전을 2로 알려 후보 patch 의 base_version 과 어긋난다."""
    change_result(
        monkeypatch,
        stub,
        "create_workspace",
        lambda r, **k: r.update(skill_versions={"inventory-check": 2, "payment": 1}),
    )


def _healthy_control(monkeypatch, stub):
    """모든 workspace 를 통과 상태로 둬 수정 없는 후보가 실패를 재현하지 못하게 한다."""
    healthy_workspaces(monkeypatch, stub)


# 의존성 그래프를 미완성(complete=False)으로, skill 원문을 기준 snapshot 것으로 바꾸는 조정이다.
_INCOMPLETE_GRAPH = _tool_change("get_dependency_graph", lambda r, **k: r.update(complete=False))
_WRONG_SOURCE = _tool_change("get_skill", lambda r, *a: r.update(snapshot_id=BASELINE))

# 원인 실험 이후 단계의 계약 오류 표다. 항목은 (id, 초기 상태, stub 조정, error 전문, 진행 기록에
# 남은 노드 수) 순서다. 노드 수 3은 ablate, 6은 generate_candidates, 7은 verify_candidates,
# 10은 save_proposal 에서 멈췄다는 뜻이다.
LATER_ERRORS = [
    (
        "different_error_category",
        initial(),
        _tool_change("get_trace", _error_event_category("TIMEOUT")),
        "ContractError: replay reproduced a different error category",
        3,
    ),
    (
        "trace_without_error_event",
        initial(),
        _tool_change("get_trace", _without_error_event),
        "ContractError: trace event.data must be an object",
        3,
    ),
    (
        "error_event_read_before_reproduction_check",
        initial(),
        _both(_tool_change("get_trace", _without_error_event), _healthy_control),
        "ContractError: trace event.data must be an object",
        3,
    ),
    (
        "control_already_healthy",
        initial(),
        _healthy_control,
        "ContractError: unmodified candidate no longer reproduces the failure",
        3,
    ),
    (
        "ablation_patch_not_applied",
        initial(),
        _tool_change("apply_patch", lambda r, *a: r.update(changed_skills=[])),
        "ContractError: ablation patch was not applied",
        3,
    ),
    (
        "ablation_patch_other_workspace",
        initial(),
        _tool_change("apply_patch", lambda r, *a: r.update(workspace_id="w_other")),
        "ContractError: ablation patch was not applied",
        3,
    ),
    (
        "source_from_other_snapshot",
        initial(),
        _WRONG_SOURCE,
        "ContractError: candidate source does not match the fixed snapshot",
        6,
    ),
    (
        "source_without_text",
        initial(),
        _tool_change("get_skill", lambda r, *a: r.update(content=None)),
        "ContractError: candidate source does not match the fixed snapshot",
        6,
    ),
    (
        "source_error_before_incomplete_graph",
        initial(),
        _both(_INCOMPLETE_GRAPH, _WRONG_SOURCE),
        "ContractError: candidate source does not match the fixed snapshot",
        6,
    ),
    (
        "incomplete_graph",
        initial(),
        _INCOMPLETE_GRAPH,
        "ContractError: dependency graph is incomplete",
        6,
    ),
    (
        "unknown_entry_skill",
        initial(entry_skill_id="checkout"),
        None,
        "ContractError: INVALID_FLOW",
        6,
    ),
    (
        "candidate_base_version_differs",
        initial(),
        _workspace_versions,
        "ContractError: candidate patch base_version differs from workspace",
        7,
    ),
    (
        "invalid_apply_response",
        initial(),
        _on_call("apply_patch", 2, lambda r: r.update(broken_links=None)),
        "ContractError: invalid apply_patch response",
        7,
    ),
    (
        "candidate_patches_not_all_applied",
        initial(),
        _on_call(
            "apply_patch",
            2,
            lambda r: r.update(changed_skills=[{"skill": "payment", "version": 2}]),
        ),
        "ContractError: candidate patches were not all applied",
        7,
    ),
    (
        "apply_tool_error_is_not_candidate_rejection",
        initial(),
        _raise_on_call(
            "apply_patch", 2, MCPToolError("apply_patch", {"code": "CONFLICT", "message": "x"})
        ),
        'MCPToolError: MCP apply_patch failed: {"code": "CONFLICT", "message": "x"}',
        7,
    ),
    (
        "rejected_save",
        initial(),
        _raise_on_call(
            "save_proposal", 1, MCPToolError("save_proposal", {"code": "INVALID_INPUT"})
        ),
        'MCPToolError: MCP save_proposal failed: {"code": "INVALID_INPUT"}',
        10,
    ),
]


@pytest.mark.asyncio
@pytest.mark.parametrize(
    ("state", "adjust", "error", "events"),
    [case[1:] for case in LATER_ERRORS],
    ids=[case[0] for case in LATER_ERRORS],
)
async def test_later_contract_errors(stub, llm, monkeypatch, state, adjust, error, events):
    """오류마다 ERROR 와 error 전문을 남기고, 오류가 난 노드 앞까지만 진행 기록이 남는지 본다."""
    if adjust:
        adjust(monkeypatch, stub)
    result = await run(state)
    assert result["diagnosis_status"] == "ERROR"
    assert result["error"] == error
    assert nodes_of(result) == HAPPY_NODES[:events]


@pytest.mark.asyncio
async def test_default_entry_skill_and_explicit_entry_skill(stub, llm, monkeypatch):
    calls = record_tools(monkeypatch, stub)
    await run(initial(entry_skill_id="order-flow"))
    await run(initial())
    graphs = [kwargs for name, _, kwargs, _ in calls if name == "get_dependency_graph"]
    assert graphs == [{"snapshot_id": CANDIDATE, "entry_skill_id": "order-flow"}] * 2


# ── 제안서 변환 (순수 함수) ────────────────────────────────────────────────────


def test_proposal_payload_boundaries():
    state = {
        "trigger": {"type": "failure"},
        "trace": {"execution_id": EXECUTION, "events": [{"kind": "SKILL_STARTED"}]},
        "root_cause": {"skill": "inventory-check", "change_no": 7, "summary": "s"},
        "changes": [{"change_no": 1, "before": "b", "after": "a"}],
        "candidates": [
            {"candidate_no": 1, "workspace": "w_x", "valid": False, "rank": None},
            {"candidate_no": 2, "valid": None},
        ],
        "recommended": 2,
    }
    payload = nodes.proposal_payload(state)
    assert list(payload) == [
        "thread_id",
        "trigger",
        "baseline_snapshot_id",
        "snapshot_id",
        "failure",
        "root_cause",
        "intent",
        "candidates",
        "recommended_candidate_no",
        "status",
    ]
    assert payload["thread_id"] is None
    assert payload["baseline_snapshot_id"] is None and payload["snapshot_id"] is None
    assert payload["failure"] is None
    assert payload["intent"] is None
    assert payload["recommended_candidate_no"] is None
    assert list(payload["root_cause"].items()) == [
        ("skill", "inventory-check"),
        ("change_no", 7),
        ("summary", "s"),
        ("before", None),
        ("after", None),
    ]
    assert [list(c.items()) for c in payload["candidates"]] == [
        [("candidate_no", 1), ("valid", False), ("rank", None), ("workspace_id", "w_x")],
        [("candidate_no", 2), ("valid", None), ("workspace_id", None)],
    ]
    assert payload["status"] == "no_valid_fix"
    assert state["candidates"][0]["workspace"] == "w_x"


def test_proposal_payload_with_recommendation_failure_and_matching_change():
    trace = {
        "execution_id": EXECUTION,
        "events": [
            {
                "kind": "EXECUTION_ERROR",
                "skill_id": "payment",
                "at": "2026-10-01T00:00:00Z",
                "data": {"category": "TIMEOUT", "message": "slow"},
            },
            {"kind": "EXECUTION_ERROR", "skill_id": "other", "data": {"category": "LATER"}},
        ],
    }
    state = {
        "trigger": {"type": "failure"},
        "pipeline_thread_id": "thread-1",
        "baseline_snapshot_id": BASELINE,
        "snapshot_id": CANDIDATE,
        "trace": trace,
        "root_cause": {"skill": "inventory-check", "change_no": 1},
        "changes": [{"change_no": 1, "before": "b", "after": "a"}],
        "intent": {"text": "t"},
        "candidates": [
            {"candidate_no": 3, "workspace": "w_a", "valid": False},
            {"candidate_no": 4, "workspace": "w_b", "valid": True},
        ],
        "recommended": 1,
    }
    payload = nodes.proposal_payload(state)
    assert payload["thread_id"] == "thread-1"
    assert payload["failure"] == {
        "execution_id": EXECUTION,
        "skill": "payment",
        "category": "TIMEOUT",
        "message": "slow",
        "missing_inputs": [],
        "at": "2026-10-01T00:00:00Z",
    }
    assert payload["root_cause"] == {
        "skill": "inventory-check",
        "change_no": 1,
        "before": "b",
        "after": "a",
    }
    assert payload["recommended_candidate_no"] == 4
    assert payload["status"] == "proposed"
    assert payload["intent"] == {"text": "t"}


def test_proposal_payload_without_root_cause_or_recommendation():
    payload = nodes.proposal_payload({"trigger": {}, "trace": None, "root_cause": None})
    assert payload["root_cause"] is None
    assert payload["failure"] is None
    assert payload["candidates"] == []
    assert payload["recommended_candidate_no"] is None
    assert payload["status"] == "no_valid_fix"
