"""skillops-agent/skillops_agent/stub_tools.py 의 StubMCPTools 동작을 고정하는 특성 테스트다.

보호하는 동작은 세 가지다. 첫째, 공개 async 메서드의 이름과 매개변수(이름, 기본값, 종류)가
skillops-agent/skillops_agent/tools.py 의 MCPTools 와 같아서, 한쪽만 바꾸면 이 테스트가
깨진다. 둘째, 고정 응답의 값과 키 순서가 그대로이고, 노드가 교차 검사하는 snapshot 해시와 파일
해시가 응답끼리 맞는다. 셋째, _traces 와 _workspaces 는 클래스 공유 상태이고 saved 는 인스턴스마다
따로이며, uuid4 를 workspace, trial, 제안서 순서로 쓴다. 클래스 공유 dict 는
isolated_shared_state 가 테스트마다 비우고 끝나면 되돌린다.
"""

import inspect
import json
import uuid
from datetime import UTC, datetime

import pytest

import skillops_agent.stub_tools as stub_module
from skillops_agent.contracts import ContractError, execution_uuid
from skillops_agent.stub_tools import StubMCPTools
from skillops_agent.tools import MCPTools

BASELINE = StubMCPTools.BASELINE
CANDIDATE = StubMCPTools.CANDIDATE
EXECUTION = StubMCPTools.EXECUTION
# apply_patch 에 넣는 최소 패치 하나다. stub 은 내용을 보지 않는다.
PATCH = [{"skill": "payment", "base_version": 2, "patch": "p"}]


def assert_same_json(actual, expected):
    """값과 키 순서까지 같은지 JSON 글자로 비교한다."""
    assert json.dumps(actual, ensure_ascii=False) == json.dumps(expected, ensure_ascii=False)


def numbered_uuid(n):
    """n 번째로 발급한 것을 알아볼 수 있는 고정 UUID 를 만든다."""
    return uuid.UUID(f"{n:08x}-0000-4000-8000-{n:012x}")


def trial_trace(execution):
    """run_scenarios 가 기록하는 통과 trial trace 의 기대값을 만든다."""
    return {
        "execution_id": execution,
        "events": [
            {
                "kind": "SKILL_FINISHED",
                "skill_id": "payment",
                "data": {"output": {"payment.status": "PAID"}},
            },
            {
                "kind": "TOOL_REQUEST",
                "skill_id": "inventory-check",
                "data": {"tool": "inventory.reserve"},
            },
        ],
    }


@pytest.fixture(autouse=True)
def isolated_shared_state():
    """클래스 공유 dict 를 테스트마다 비우고, 끝나면 같은 dict 객체에 원래 내용을 되돌린다."""
    traces, workspaces = StubMCPTools._traces, StubMCPTools._workspaces
    saved = dict(traces), dict(workspaces)
    traces.clear()
    workspaces.clear()
    yield
    traces.clear()
    traces.update(saved[0])
    workspaces.clear()
    workspaces.update(saved[1])


@pytest.fixture
def issued(monkeypatch):
    """stub 이 부르는 uuid4 를 1, 2, 3 ... 번 고정 UUID 로 바꾸고 발급 순서를 기록한다."""
    values = []

    def fake_uuid4():
        """다음 번호의 고정 UUID 를 발급하고 기록한다."""
        values.append(numbered_uuid(len(values) + 1))
        return values[-1]

    monkeypatch.setattr(stub_module, "uuid4", fake_uuid4)
    return values


@pytest.fixture
def stub():
    """새 stub 인스턴스를 만든다."""
    return StubMCPTools()


# ── MCPTools 와의 대칭 ──────────────────────────────────────────────────────────


def public_async_methods(cls):
    """cls 의 공개 async 메서드를 이름에서 함수로의 dict 로 돌려준다. _ 로 시작하는 이름은 뺀다."""
    return {
        name: fn
        for name, fn in inspect.getmembers(cls, inspect.iscoroutinefunction)
        if not name.startswith("_")
    }


def parameter_shape(fn):
    """fn 의 매개변수를 self 를 빼고 (이름, 기본값, 종류) 목록으로 돌려준다."""
    return [
        (p.name, p.default, p.kind)
        for p in inspect.signature(fn).parameters.values()
        if p.name != "self"
    ]


# MCPTools 의 공개 async 메서드 이름이다. 시그니처 대칭 테스트의 매개변수로 쓴다.
TOOL_METHODS = sorted(public_async_methods(MCPTools))


def test_stub_offers_the_same_tool_methods_as_real_tools():
    """두 클래스의 공개 async 메서드 이름 집합이 같다."""
    assert set(public_async_methods(StubMCPTools)) == set(TOOL_METHODS)
    assert {"get_trace", "create_workspace", "apply_patch", "run_scenarios", "save_proposal"} <= (
        set(TOOL_METHODS)
    )


@pytest.mark.parametrize("name", TOOL_METHODS)
def test_stub_and_real_tool_signatures_match(name):
    """메서드마다 매개변수의 이름, 기본값, 종류가 같다."""
    assert parameter_shape(getattr(StubMCPTools, name)) == parameter_shape(getattr(MCPTools, name))


def test_shared_state_lives_on_the_class():
    """_traces 와 _workspaces 는 클래스 공유 dict 이고 saved 는 인스턴스마다 따로다."""
    first, second = StubMCPTools(), StubMCPTools()
    assert first._traces is second._traces is StubMCPTools._traces
    assert first._workspaces is second._workspaces is StubMCPTools._workspaces
    assert first.saved == [] and first.saved is not second.saved


# ── 고정 응답 ──────────────────────────────────────────────────────────────────


async def test_context_manager_returns_itself(stub):
    """async with 는 같은 인스턴스를 돌려주고 닫을 때 아무것도 하지 않는다."""
    async with stub as entered:
        assert entered is stub
    assert await stub.__aexit__(None, None, None) is None


async def test_ping_returns_pong_with_utc_time(stub):
    """ping 은 'pong <UTC ISO 시각>' 이다."""
    text = await stub.ping()
    assert text.startswith("pong ")
    assert datetime.fromisoformat(text.removeprefix("pong ")).utcoffset() == UTC.utcoffset(None)


async def test_list_failed_runs_returns_the_fixed_failure(stub):
    """실패 목록은 고정 실행 하나다."""
    assert_same_json(
        await stub.list_failed_runs(),
        {"items": [{"execution_id": EXECUTION, "snapshot_id": CANDIDATE}]},
    )


@pytest.mark.parametrize(("snapshot", "file_hash"), [(BASELINE, "old"), (CANDIDATE, "new")])
async def test_get_snapshot_returns_manifest(stub, snapshot, file_hash):
    """manifest 해시는 'snapshot-<id>' 이고 BASELINE 만 파일 해시가 old 다."""
    assert_same_json(
        await stub.get_snapshot(snapshot),
        {
            "snapshot_id": snapshot,
            "hash": "snapshot-" + snapshot,
            "metadata": {"baseline_snapshot_id": BASELINE},
            "files": [{"path": "skills/inventory-check/SKILL.md", "hash": file_hash}],
        },
    )


@pytest.mark.parametrize("snapshot", [EXECUTION, "unknown", None])
async def test_get_snapshot_rejects_unknown_snapshot(stub, snapshot):
    """BASELINE 이나 CANDIDATE 가 아니면 ContractError 다."""
    with pytest.raises(ContractError, match="^unknown stub snapshot$"):
        await stub.get_snapshot(snapshot)


async def test_list_skills_accepts_any_snapshot(stub):
    """skill 목록은 어떤 snapshot 이든 같은 세 skill 이다."""
    assert_same_json(
        await stub.list_skills("any"),
        {
            "snapshot_id": "any",
            "snapshot_hash": "snapshot-any",
            "items": [
                {"skill_id": "order-flow", "version": 1},
                {"skill_id": "inventory-check", "version": 1},
                {"skill_id": "payment", "version": 1},
            ],
            "partial": False,
            "parse_errors": [],
        },
    )


async def test_snapshot_hash_requires_a_string(stub):
    """snapshot 해시는 문자열 결합이라 None 이면 TypeError 다."""
    with pytest.raises(TypeError):
        await stub.list_skills(None)
    with pytest.raises(TypeError):
        await stub.diff_skill("payment", None, CANDIDATE)


async def test_get_skill_returns_minimal_skill_file(stub):
    """skill 본문은 이름만 바꾼 고정 frontmatter 다."""
    assert_same_json(
        await stub.get_skill("snap", "payment"),
        {
            "snapshot_id": "snap",
            "skill_id": "payment",
            "version": 1,
            "content": "---\nname: payment\noutputs: []\n---\nbody\n",
        },
    )


# 고정 실패 실행(EXECUTION)의 기대 trace 다.
FAILED_TRACE = {
    "execution_id": EXECUTION,
    "snapshot_id": CANDIDATE,
    "snapshot_hash": "snapshot-" + CANDIDATE,
    "completion": "FAILED",
    "final_status": "FAILED",
    "events": [
        {
            "kind": "SKILL_SELECTED",
            "skill_id": None,
            "data": {"raw_name": "order-flow", "resolved_skill_id": None},
        },
        {
            "kind": "EXECUTION_ERROR",
            "skill_id": "payment",
            "data": {
                "category": "CONTRACT_VIOLATION",
                "skill_id": "payment",
                "missing_inputs": ["inventory.reserved"],
            },
        },
    ],
}


@pytest.mark.parametrize("execution", [EXECUTION, "r_" + EXECUTION.replace("-", "")])
async def test_get_trace_returns_the_fixed_failed_execution(stub, execution):
    """고정 실행은 UUID 나 r_ 별칭으로 읽고 CONTRACT_VIOLATION 실패 trace 다."""
    assert_same_json(await stub.get_trace(execution), FAILED_TRACE)


async def test_fixed_failed_trace_is_a_new_object_each_time(stub):
    """고정 실행 trace 는 부를 때마다 새 dict 라 한쪽을 바꿔도 다음 응답은 그대로다."""
    first = await stub.get_trace(EXECUTION)
    first["events"].clear()
    first["final_status"] = "CHANGED"
    assert_same_json(await stub.get_trace(EXECUTION), FAILED_TRACE)


async def test_get_trace_rejects_unknown_and_malformed_execution(stub):
    """기록에 없으면 'unknown stub execution', UUID 가 아니면 execution_uuid 의 오류다."""
    with pytest.raises(ContractError, match="^unknown stub execution$"):
        await stub.get_trace(str(uuid.uuid4()))
    with pytest.raises(ContractError, match="^execution_id must be a UUID or r_ \\+ UUID hex$"):
        await stub.get_trace("r_bad")


async def test_diff_skill_uses_fixed_file_hashes(stub):
    """diff 의 file_hash 는 늘 old 에서 new 로 바뀌고, snapshot 해시만 인자를 따른다."""
    assert_same_json(
        await stub.diff_skill("payment", "b", "a"),
        {
            "skill_id": "payment",
            "before": {"snapshot_id": "b", "snapshot_hash": "snapshot-b", "file_hash": "old"},
            "after": {"snapshot_id": "a", "snapshot_hash": "snapshot-a", "file_hash": "new"},
            "status": "modified",
            "hunks": [
                {
                    "index": 0,
                    "hunk_id": "fixture-hunk",
                    "part": "frontmatter",
                    "header": "@@ -1 +1 @@",
                    "before": "outputs: [inventory.reserved]",
                    "after": "outputs: []",
                    "diff": "@@ -1 +1 @@\n-outputs: [inventory.reserved]\n+outputs: []\n",
                }
            ],
        },
    )


async def test_hashes_agree_across_stub_reads(stub):
    """nodes 가 교차 검사하는 snapshot 해시와 파일 해시가 응답끼리 맞는다."""
    base = await stub.get_snapshot(BASELINE)
    cand = await stub.get_snapshot(CANDIDATE)
    skills = await stub.list_skills(CANDIDATE)
    trace = await stub.get_trace(EXECUTION)
    diff = await stub.diff_skill("inventory-check", BASELINE, CANDIDATE)
    assert cand["hash"] == skills["snapshot_hash"] == trace["snapshot_hash"]
    assert cand["hash"] == diff["after"]["snapshot_hash"]
    assert base["hash"] == diff["before"]["snapshot_hash"]
    assert diff["before"]["file_hash"] == base["files"][0]["hash"]
    assert diff["after"]["file_hash"] == cand["files"][0]["hash"]


async def test_get_dependency_graph_only_knows_order_flow(stub):
    """order-flow 만 완전한 흐름이고 다른 시작점은 INVALID_FLOW 다."""
    assert_same_json(
        await stub.get_dependency_graph("snap", "order-flow"),
        {
            "snapshot_id": "snap",
            "entry_skill_id": "order-flow",
            "complete": True,
            "steps": ["inventory-check", "payment"],
            "edges": [],
            "missing": [],
            "ambiguous": [],
            "nodes": [],
        },
    )
    with pytest.raises(ContractError, match="^INVALID_FLOW$"):
        await stub.get_dependency_graph("snap", "payment")


# ── workspace·patch ────────────────────────────────────────────────────────────


@pytest.mark.parametrize(("snapshot", "passing"), [(BASELINE, True), (CANDIDATE, False)])
async def test_create_workspace_registers_pass_state(stub, issued, snapshot, passing):
    """BASELINE workspace 는 통과, CANDIDATE 는 실패 상태로 등록된다."""
    workspace = await stub.create_workspace(snapshot_id=snapshot)
    assert_same_json(
        workspace,
        {
            "workspace_id": "w_" + numbered_uuid(1).hex,
            "skill_versions": {"inventory-check": 1, "payment": 1},
        },
    )
    assert StubMCPTools._workspaces == {workspace["workspace_id"]: passing}


@pytest.mark.parametrize("kwargs", [{}, {"snapshot_id": EXECUTION}, {"start_versions": {"a": 1}}])
async def test_create_workspace_requires_a_known_snapshot(stub, issued, kwargs):
    """알려진 snapshot 이 아니면 uuid 를 쓰지 않고 거부한다."""
    with pytest.raises(ContractError, match="^stub requires candidate snapshot$"):
        await stub.create_workspace(**kwargs)
    assert issued == []
    assert StubMCPTools._workspaces == {}


async def test_apply_patch_makes_the_workspace_pass(stub):
    """패치를 적용하면 그 workspace 가 통과 상태가 되고 version 이 하나 오른다."""
    workspace_id = (await stub.create_workspace(snapshot_id=CANDIDATE))["workspace_id"]
    patches = [*PATCH, {"skill": "inventory-check", "base_version": 1, "patch": "q"}]
    assert_same_json(
        await stub.apply_patch(workspace_id, patches),
        {
            "workspace_id": workspace_id,
            "changed_skills": [
                {"skill": "payment", "version": 3},
                {"skill": "inventory-check", "version": 2},
            ],
            "broken_links": [],
        },
    )
    assert StubMCPTools._workspaces[workspace_id] is True


@pytest.mark.parametrize("case", ["empty-patches", "unknown-workspace"])
async def test_apply_patch_rejects_invalid_requests(stub, case):
    """패치가 없거나 모르는 workspace 면 ContractError 다."""
    workspace_id = (await stub.create_workspace(snapshot_id=CANDIDATE))["workspace_id"]
    args = (workspace_id, []) if case == "empty-patches" else ("w_missing", PATCH)
    with pytest.raises(ContractError, match="^invalid stub patch request$"):
        await stub.apply_patch(*args)
    assert StubMCPTools._workspaces[workspace_id] is False


# ── run_scenarios ──────────────────────────────────────────────────────────────


async def test_run_scenarios_reports_passing_workspace(stub, issued):
    """통과 workspace 는 시나리오마다 repeat 개 run_id 와 빈 failures 다."""
    workspace_id = (await stub.create_workspace(snapshot_id=BASELINE))["workspace_id"]
    report = await stub.run_scenarios(workspace_id, ["g-01", "g-02"])
    runs = ["r_" + numbered_uuid(n).hex for n in (2, 3, 4, 5)]
    assert_same_json(
        report,
        {
            "workspace_id": workspace_id,
            "all_passed": True,
            "results": [
                {"scenario_id": "g-01", "passed": True, "run_ids": runs[:2], "failures": []},
                {"scenario_id": "g-02", "passed": True, "run_ids": runs[2:], "failures": []},
            ],
        },
    )
    assert list(StubMCPTools._traces) == [str(numbered_uuid(n)) for n in (2, 3, 4, 5)]


async def test_run_scenarios_reports_failing_workspace(stub, issued):
    """실패 workspace 는 run 마다 payment CONTRACT_VIOLATION 실패를 남긴다."""
    workspace_id = (await stub.create_workspace(snapshot_id=CANDIDATE))["workspace_id"]
    report = await stub.run_scenarios(workspace_id, ["g-03"], repeat=3)
    runs = ["r_" + numbered_uuid(n).hex for n in (2, 3, 4)]
    assert_same_json(
        report,
        {
            "workspace_id": workspace_id,
            "all_passed": False,
            "results": [
                {
                    "scenario_id": "g-03",
                    "passed": False,
                    "run_ids": runs,
                    "failures": [
                        {"run_id": run, "skill": "payment", "reason": "CONTRACT_VIOLATION"}
                        for run in runs
                    ],
                }
            ],
        },
    )


async def test_trial_traces_are_stored_and_returned_as_is(stub):
    """trial 마다 통과 trace 를 기록하고 get_trace 는 기록한 그 객체를 돌려준다."""
    workspace_id = (await stub.create_workspace(snapshot_id=BASELINE))["workspace_id"]
    report = await stub.run_scenarios(workspace_id, ["g-01"], repeat=3)
    run_ids = report["results"][0]["run_ids"]
    assert len(run_ids) == len(set(run_ids)) == 3
    for run_id in run_ids:
        execution = execution_uuid(run_id)
        trace = await stub.get_trace(run_id)
        assert_same_json(trace, trial_trace(execution))
        assert trace is StubMCPTools._traces[execution]
        assert await stub.get_trace(execution) is trace


async def test_run_scenarios_appends_replay_scenarios(stub):
    """replay_run_ids 는 'f-<하이픈 UUID>' 시나리오로 뒤에 붙는다."""
    workspace_id = (await stub.create_workspace(snapshot_id=BASELINE))["workspace_id"]
    replay = "r_" + EXECUTION.replace("-", "")
    report = await stub.run_scenarios(
        workspace_id, ["g-01"], repeat=1, replay_run_ids=[replay, EXECUTION]
    )
    assert [r["scenario_id"] for r in report["results"]] == [
        "g-01",
        "f-" + EXECUTION,
        "f-" + EXECUTION,
    ]
    only_replay = await stub.run_scenarios(workspace_id, None, repeat=1, replay_run_ids=[replay])
    assert [r["scenario_id"] for r in only_replay["results"]] == ["f-" + EXECUTION]


async def test_run_scenarios_accepts_explicit_failure_scenarios(stub):
    """'f-<UUID>' 를 직접 넘겨도 된다."""
    workspace_id = (await stub.create_workspace(snapshot_id=BASELINE))["workspace_id"]
    report = await stub.run_scenarios(workspace_id, ["f-" + EXECUTION], repeat=1)
    assert report["results"][0]["scenario_id"] == "f-" + EXECUTION


@pytest.mark.parametrize(
    ("scenario", "message"),
    [
        ("x-01", "^unknown scenario$"),
        ("g-04", "^unknown scenario$"),
        ("f-bad", "^execution_id must be a UUID or r_ \\+ UUID hex$"),
    ],
)
async def test_run_scenarios_rejects_unknown_scenarios(stub, scenario, message):
    """golden 세 개와 f-<UUID> 가 아닌 시나리오는 ContractError 다."""
    workspace_id = (await stub.create_workspace(snapshot_id=BASELINE))["workspace_id"]
    with pytest.raises(ContractError, match=message):
        await stub.run_scenarios(workspace_id, [scenario])


async def test_run_scenarios_records_earlier_trials_before_a_later_error(stub):
    """시나리오마다 검사 후 바로 실행하므로, 뒤 시나리오가 틀리면 앞 trial 은 이미 남아 있다."""
    workspace_id = (await stub.create_workspace(snapshot_id=BASELINE))["workspace_id"]
    with pytest.raises(ContractError, match="^unknown scenario$"):
        await stub.run_scenarios(workspace_id, ["g-01", "x-01"], repeat=2)
    assert len(StubMCPTools._traces) == 2


async def test_run_scenarios_checks_replay_ids_before_any_trial(stub, issued):
    """replay_run_ids 는 실행 전에 먼저 모두 변환하므로 잘못되면 trial 이 없다."""
    workspace_id = (await stub.create_workspace(snapshot_id=BASELINE))["workspace_id"]
    with pytest.raises(ContractError):
        await stub.run_scenarios(workspace_id, ["g-01"], replay_run_ids=["bad"])
    assert StubMCPTools._traces == {}
    assert len(issued) == 1


async def test_run_scenarios_checks_scenario_before_workspace(stub):
    """시나리오 검사가 workspace 조회보다 먼저이고, 모르는 workspace 는 KeyError 다."""
    with pytest.raises(ContractError, match="^unknown scenario$"):
        await stub.run_scenarios("w_missing", ["x-01"])
    with pytest.raises(KeyError):
        await stub.run_scenarios("w_missing", ["g-01"])
    assert StubMCPTools._traces == {}
    assert_same_json(
        await stub.run_scenarios("w_missing", []),
        {"workspace_id": "w_missing", "all_passed": True, "results": []},
    )


# ── 인스턴스 사이 공유와 save_proposal ──────────────────────────────────────────


async def test_workspaces_and_traces_are_shared_between_instances():
    """노드마다 새 인스턴스를 만들어도 앞 인스턴스의 workspace 와 trace 를 읽는다."""
    first, second = StubMCPTools(), StubMCPTools()
    workspace_id = (await first.create_workspace(snapshot_id=CANDIDATE))["workspace_id"]
    report = await first.run_scenarios(workspace_id, ["g-01"], repeat=1)
    run_id = report["results"][0]["run_ids"][0]
    assert (await second.get_trace(run_id))["execution_id"] == execution_uuid(run_id)
    await second.apply_patch(workspace_id, PATCH)
    assert (await StubMCPTools().run_scenarios(workspace_id, ["g-02"], repeat=1))["all_passed"]


async def test_save_proposal_keeps_proposals_per_instance(issued):
    """save_proposal 은 그 인스턴스의 saved 에 쌓고 valid 후보 번호만 돌려준다."""
    first, second = StubMCPTools(), StubMCPTools()
    proposal = {
        "status": "no_valid_fix",
        "candidates": [
            {"candidate_no": 1, "valid": True},
            {"candidate_no": 2, "valid": False},
            {"candidate_no": 3},
            {"candidate_no": 4, "valid": True},
        ],
    }
    assert_same_json(
        await first.save_proposal(proposal),
        {
            "proposal_id": "p_stub" + numbered_uuid(1).hex[:12],
            "status": "no_valid_fix",
            "valid_candidate_nos": [1, 4],
        },
    )
    assert first.saved == [proposal] and first.saved[0] is proposal
    assert second.saved == []


async def test_uuid4_is_called_in_workspace_trial_proposal_order(stub, issued):
    """uuid4 는 workspace, 시나리오별 trial, 제안서 순서로 하나씩 쓴다."""
    workspace_id = (await stub.create_workspace(snapshot_id=BASELINE))["workspace_id"]
    report = await stub.run_scenarios(workspace_id, ["g-01", "g-02"], repeat=2)
    saved = await stub.save_proposal({"status": "proposed", "candidates": []})
    assert workspace_id == "w_" + numbered_uuid(1).hex
    assert [r["run_ids"] for r in report["results"]] == [
        ["r_" + numbered_uuid(2).hex, "r_" + numbered_uuid(3).hex],
        ["r_" + numbered_uuid(4).hex, "r_" + numbered_uuid(5).hex],
    ]
    assert saved["proposal_id"] == "p_stub" + numbered_uuid(6).hex[:12]
    assert len(issued) == 6
