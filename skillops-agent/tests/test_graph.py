"""C 모양 fixture 와 가짜 LLM 으로 진단 그래프 전체를 돌리는 흐름 테스트다.

네트워크와 유료 호출은 없다. skillops-agent/skillops_agent/graph.py 의 graph 를 끝까지 실행하고,
nodes 모듈의 get_tools 와 make_structured_llm 을 skillops-agent/skillops_agent/stub_tools.py 의
StubMCPTools 와 가짜 LLM 으로 바꿔 끼운다. skillops-agent/skillops_agent/nodes.py 의 노드가 지켜야
할 다음 동작을 보호한다.
- 정상 경로에서 원인을 확정하고 후보를 검증해 계약 모양의 제안서를 저장한다(EVALUATED).
- MCP 오류, 빈 결과, 기준 snapshot 누락, changed_paths 불일치, 수정 없는 후보의 재현 실패,
  모르는 change_no, 빈 LLM 응답, save_proposal 거절을 성공으로 보지 않는다.
- workspace 는 늘 후보 snapshot 으로 만들고, 의도 조건은 재현 시나리오에만 붙이며, 근거 실행 수와
  tools_called 를 남긴다.
- 끊긴 연결이 있는 후보는 무효이고, 거절된 후보 하나가 다른 후보 검증을 멈추지 않는다. patch 와
  후보 생성 프롬프트는 현재 원문을 기준으로 한다.
- 저장 직후 사전 검증(VALIDATED, BASELINE_FAILED)과 diagnosis_only(S5)는 LLM 을 부르지 않는다.
- 읽을 수 없는 의도 조건은 run_scenarios 로 보내지 않고 이유와 함께 버리며, 의도 프롬프트에는
  원인을 되돌려 통과한 실행의 값 이름이 들어간다.
"""

from types import SimpleNamespace
from uuid import uuid4

import pytest

from skillops_agent import nodes
from skillops_agent.graph import graph
from skillops_agent.stub_tools import StubMCPTools
from skillops_agent.tools import MCPToolError


def initial(**extra):
    """stub 의 고정 실패 실행을 진단하는 실패 진단 요청(type=failure)의 초기 상태를 만든다."""
    return {
        "trigger": {"type": "failure", "execution_id": StubMCPTools.EXECUTION, **extra},
        "max_steps": 40,
    }


class FakeChain:
    """with_structured_output 체인의 대역이다. ainvoke 가 정해 둔 출력을 돌려준다."""

    def __init__(self, output):
        self.output = output

    async def ainvoke(self, *args):
        return self.output


def fake_llm(schema):
    """make_structured_llm 의 대역이다. 출력 클래스 이름(schema.__name__)으로 고정 출력을 고른다."""
    values = {
        "_HypothesesOutput": {
            "hypotheses": [
                SimpleNamespace(change_no=1, summary="inventory output deletion", confidence=0.9)
            ]
        },
        "_IntentOutput": {"text": "keep payment behavior", "checks": ["payment.status == 'PAID'"]},
        "_CandidatesOutput": {
            "candidates": [
                SimpleNamespace(
                    kind="same_skill",
                    patches=[
                        SimpleNamespace(
                            skill="inventory-check",
                            full_text=(
                                "---\nname: inventory-check\n"
                                "outputs: [inventory.reserved]\n---\nbody\n"
                            ),
                        )
                    ],
                )
            ]
        },
        "_RankExplanationsOutput": {
            "explanations": [SimpleNamespace(candidate_no=1, explanation="trial evidence")]
        },
    }
    return FakeChain(SimpleNamespace(**values[schema.__name__]))


@pytest.fixture
def tools(monkeypatch):
    """StubMCPTools 하나를 만들어 nodes 의 get_tools 와 make_structured_llm 을 바꿔 끼운다."""
    stub = StubMCPTools()
    monkeypatch.setattr(nodes, "get_tools", lambda: stub)
    monkeypatch.setattr(nodes, "make_structured_llm", fake_llm)
    return stub


async def run(state):
    """새 thread_id 와 recursion_limit 100 으로 그래프를 끝까지 돌린다."""
    return await graph.ainvoke(
        state, {"configurable": {"thread_id": str(uuid4())}, "recursion_limit": 100}
    )


@pytest.mark.asyncio
async def test_real_event_shape_and_changed_skill_are_used(tools):
    result = await run(initial())
    assert result["error"] is None
    # 실패 위치는 payment 지만 원인은 inventory-check 변경이다.
    assert result["root_cause"]["skill"] == "inventory-check"
    assert result["root_cause"]["hunk_id"] == "fixture-hunk"
    assert result["root_cause"]["proof_run_ids"]
    assert result["candidates"][0]["valid"]
    assert result["proposal"]["id"].startswith("p_stub")
    assert result["proposal"]["storage_status"] == "SAVED"
    assert result["proposal"]["valid_candidate_nos"] == [1]
    assert result["diagnosis_status"] == "EVALUATED"
    assert "__interrupt__" not in result


@pytest.mark.asyncio
async def test_mcp_failure_stops_without_false_confirmation(tools, monkeypatch):
    async def fail(*args, **kwargs):
        raise ValueError("MCP INVALID_INPUT")

    monkeypatch.setattr(tools, "run_scenarios", fail)
    result = await run(initial())
    assert result["diagnosis_status"] == "ERROR"
    assert result["root_cause"] is None
    assert result["proposal"] is None


@pytest.mark.asyncio
async def test_missing_results_are_not_success(tools, monkeypatch):
    async def empty(workspace_id, *args, **kwargs):
        return {"workspace_id": workspace_id, "all_passed": True, "results": []}

    monkeypatch.setattr(tools, "run_scenarios", empty)
    result = await run(initial())
    assert result["diagnosis_status"] == "ERROR"
    assert not result["root_cause"]


@pytest.mark.asyncio
async def test_baseline_is_required_without_metadata(tools, monkeypatch):
    original = tools.get_snapshot

    async def no_metadata(snapshot_id):
        manifest = await original(snapshot_id)
        manifest["metadata"] = {}
        return manifest

    monkeypatch.setattr(tools, "get_snapshot", no_metadata)
    result = await run(initial())
    assert "baseline_snapshot_id" in result["error"]


@pytest.mark.asyncio
async def test_changed_paths_must_match_manifest(tools):
    result = await run(initial(changed_paths=["skills/payment/SKILL.md"]))
    assert result["diagnosis_status"] == "ERROR"


@pytest.mark.asyncio
async def test_workspace_always_uses_candidate_snapshot(tools, monkeypatch):
    calls = []
    original = tools.create_workspace

    async def create(**kwargs):
        calls.append(kwargs)
        return await original(**kwargs)

    monkeypatch.setattr(tools, "create_workspace", create)
    await run(initial())
    assert calls and all(c == {"snapshot_id": tools.CANDIDATE} for c in calls)


@pytest.mark.asyncio
async def test_checks_are_scoped_to_replay_and_all_trials_have_evidence(tools, monkeypatch):
    calls = []
    original = tools.run_scenarios

    async def scenarios(ws, ids, **kwargs):
        calls.append((ids, kwargs))
        return await original(ws, ids, **kwargs)

    monkeypatch.setattr(tools, "run_scenarios", scenarios)
    result = await run(initial())
    assert any(ids == ["g-01", "g-02", "g-03"] for ids, _ in calls)
    assert all(all(i.startswith("f-") for i in ids) for ids, kw in calls if kw.get("extra_checks"))
    # 회귀 3개, 재현 1개, 의도 1개를 각각 VERIFY_REPEAT(2)번 돌린 실행 ID 다.
    assert len(result["candidates"][0]["evidence_run_ids"]) == 10
    assert "inventory.reserve" in result["candidates"][0]["results"]["tools_called"]


@pytest.mark.asyncio
@pytest.mark.parametrize(
    ("field", "violation"),
    [
        ("broken_links", {"skill": "payment", "missing_input": "inventory.reserved"}),
        ("dependency_violations", {"skill": "payment", "missing_dependency": "notify"}),
    ],
)
async def test_broken_contract_cannot_be_valid(tools, monkeypatch, field, violation):
    """입력 공급 또는 선행 단계만 어겨도 합격 후보와 추천 제안서를 만들지 않는다."""
    original = tools.apply_patch

    async def broken(*args):
        """실행은 통과하는 stub 응답에 지정한 연결 오류만 추가한다."""
        result = await original(*args)
        result[field] = [violation]
        return result

    monkeypatch.setattr(tools, "apply_patch", broken)
    result = await run(initial())
    assert result["error"] is None
    assert not any(c["valid"] for c in result["candidates"])
    assert all(c["results"]["no_broken_links"] is False for c in result["candidates"])
    assert result["proposal"]["status"] == "no_valid_fix"


@pytest.mark.asyncio
async def test_normal_saved_change_ends_without_llm(tools, monkeypatch):
    original = tools.create_workspace

    async def ready(**kwargs):
        ws = await original(**kwargs)
        tools._workspaces[ws["workspace_id"]] = True
        return ws

    monkeypatch.setattr(tools, "create_workspace", ready)
    monkeypatch.setattr(
        nodes, "make_structured_llm", lambda _: pytest.fail("normal change called LLM")
    )
    result = await run(
        {"trigger": {"type": "skill_saved", "candidate_snapshot_id": tools.CANDIDATE}}
    )
    assert result["diagnosis_status"] == "VALIDATED"
    assert result["proposal"] is None


@pytest.mark.asyncio
async def test_s5_diagnosis_only_reports_check_failure_without_candidates(tools, monkeypatch):
    original = tools.run_scenarios

    async def violation(*args, **kwargs):
        result = await original(*args, **kwargs)
        result["all_passed"] = False
        for r in result["results"]:
            r["passed"] = False
            r["failures"] = [
                {
                    "run_id": i,
                    "skill": None,
                    "reason": "확인 조건 실패: payment.status != 'PAID' (실제 'PAID')",
                }
                for i in r["run_ids"]
            ]
        return result

    monkeypatch.setattr(tools, "run_scenarios", violation)
    monkeypatch.setattr(nodes, "make_structured_llm", lambda _: pytest.fail("S5 called repair LLM"))
    result = await run(initial(diagnosis_only=True, intent_checks=["payment.status != 'PAID'"]))
    assert result["diagnosis_status"] == "INTENT_VIOLATION"
    assert result["diagnosis"]["evidence_run_ids"]
    assert not result["candidates"]


@pytest.mark.asyncio
async def test_unmodified_candidate_must_still_reproduce_failure(tools, monkeypatch):
    original = tools.create_workspace

    async def already_healthy(**kwargs):
        ws = await original(**kwargs)
        tools._workspaces[ws["workspace_id"]] = True
        return ws

    monkeypatch.setattr(tools, "create_workspace", already_healthy)
    result = await run(initial())
    assert result["diagnosis_status"] == "ERROR"
    assert "no longer reproduces" in result["error"]
    assert not result["root_cause"]


@pytest.mark.asyncio
async def test_unknown_hypothesis_never_skips_patch_and_confirms(tools, monkeypatch):
    def invalid(schema):
        if schema.__name__ == "_HypothesesOutput":
            return FakeChain(
                SimpleNamespace(
                    hypotheses=[SimpleNamespace(change_no=99, summary="bad", confidence=1)]
                )
            )
        return fake_llm(schema)

    monkeypatch.setattr(nodes, "make_structured_llm", invalid)
    result = await run(initial())
    assert result["diagnosis_status"] == "ERROR"
    assert "unknown change_no" in result["error"]
    assert not result["root_cause"]


@pytest.mark.asyncio
async def test_empty_llm_response_has_no_fake_patch_or_evidence(tools, monkeypatch):
    monkeypatch.setattr(
        nodes, "make_structured_llm", lambda _: FakeChain(SimpleNamespace(hypotheses=[]))
    )
    result = await run(initial())
    assert result["diagnosis_status"] == "ERROR"
    assert not result["root_cause"]
    assert not result["candidates"]


@pytest.mark.asyncio
async def test_baseline_fixture_failure_does_not_call_diagnosis_llm(tools, monkeypatch):
    original = tools.create_workspace

    async def invalid_environment(**kwargs):
        ws = await original(**kwargs)
        tools._workspaces[ws["workspace_id"]] = False
        return ws

    monkeypatch.setattr(tools, "create_workspace", invalid_environment)
    monkeypatch.setattr(
        nodes, "make_structured_llm", lambda _: pytest.fail("baseline failure called LLM")
    )
    result = await run(
        {"trigger": {"type": "skill_saved", "candidate_snapshot_id": tools.CANDIDATE}}
    )
    assert result["diagnosis_status"] == "BASELINE_FAILED"
    assert not result["root_cause"]
    assert result["diagnosis"]["failures"]


@pytest.mark.asyncio
async def test_generated_patch_uses_current_file_not_old_diff(tools, monkeypatch):
    applied = []
    original = tools.apply_patch

    async def capture(workspace, patches):
        applied.extend(patches)
        return await original(workspace, patches)

    monkeypatch.setattr(tools, "apply_patch", capture)
    result = await run(initial())
    assert not result["error"]
    candidate_patch = result["candidates"][0]["patches"][0]
    assert "-outputs: []" in candidate_patch["patch"]
    assert "+outputs: [inventory.reserved]" in candidate_patch["patch"]
    assert candidate_patch["base_version"] == 1
    assert "--- a/skills/inventory-check/SKILL.md" in candidate_patch["patch"]


@pytest.mark.asyncio
async def test_rejected_candidate_does_not_abort_other_candidates(tools, monkeypatch):
    from skillops_agent.tools import MCPToolError

    original_apply = tools.apply_patch
    count = 0

    async def reject_first_candidate(workspace, patches):
        nonlocal count
        count += 1
        # 첫 적용은 원인 실험이고 두 번째 적용이 첫 수정 후보다.
        if count == 2:
            raise MCPToolError(
                "apply_patch", {"code": "INVALID_INPUT", "message": "candidate conflict"}
            )
        return await original_apply(workspace, patches)

    def two_candidates(schema):
        chain = fake_llm(schema)
        if schema.__name__ == "_CandidatesOutput":
            chain.output.candidates = chain.output.candidates * 2
        return chain

    monkeypatch.setattr(tools, "apply_patch", reject_first_candidate)
    monkeypatch.setattr(nodes, "make_structured_llm", two_candidates)
    result = await run(initial())
    assert result["error"] is None
    assert result["candidates"][0]["valid"] is False
    assert result["candidates"][0]["validation_error"] == "candidate conflict"
    assert result["candidates"][1]["valid"] is True


@pytest.mark.asyncio
async def test_generation_prompt_contains_real_current_sources(tools, monkeypatch):
    prompts = []

    def capture(schema):
        chain = fake_llm(schema)
        invoke = chain.ainvoke

        async def recorded(prompt):
            if schema.__name__ == "_CandidatesOutput":
                prompts.append(prompt)
            return await invoke(prompt)

        chain.ainvoke = recorded
        return chain

    monkeypatch.setattr(nodes, "make_structured_llm", capture)
    result = await run(initial())
    assert not result["error"]
    assert prompts and "outputs: []" in prompts[0]
    assert "full_text" in prompts[0]


@pytest.mark.asyncio
async def test_proposal_is_saved_with_contract_payload(tools):
    result = await run(initial())
    saved = tools.saved[-1]
    assert saved["status"] == "proposed"
    assert saved["failure"]["skill"] == "payment"
    assert saved["failure"]["category"] == "CONTRACT_VIOLATION"
    assert saved["failure"]["missing_inputs"] == ["inventory.reserved"]
    assert saved["failure"]["execution_id"] == StubMCPTools.EXECUTION
    assert saved["root_cause"]["skill"] == "inventory-check"
    assert saved["root_cause"]["before"] and saved["root_cause"]["after"]
    assert saved["snapshot_id"] == StubMCPTools.CANDIDATE
    assert saved["baseline_snapshot_id"] == StubMCPTools.BASELINE
    assert saved["recommended_candidate_no"] == 1
    assert all(c["workspace_id"].startswith("w_") for c in saved["candidates"])
    assert "workspace" not in saved["candidates"][0]
    assert result["proposal"]["storage_status"] == "SAVED"
    assert result["proposal"]["id"].startswith("p_stub")
    assert result["diagnosis_status"] == "EVALUATED"


@pytest.mark.asyncio
async def test_rejected_save_ends_in_error(tools, monkeypatch):
    async def reject(proposal):
        raise MCPToolError(
            "save_proposal", {"code": "INVALID_INPUT", "message": "후보 1 의 근거 실행"}
        )

    monkeypatch.setattr(tools, "save_proposal", reject)
    result = await run(initial())
    assert result["diagnosis_status"] == "ERROR"
    assert "save_proposal" in result["error"]


def intent_llm(checks):
    """의도 출력의 checks 만 바꾼 가짜 LLM(make_structured_llm 대역)을 만든다."""

    def make(schema):
        chain = fake_llm(schema)
        if schema.__name__ == "_IntentOutput":
            chain.output = SimpleNamespace(text="결제가 끝까지 처리되게 한다.", checks=checks)
        return chain

    return make


@pytest.mark.asyncio
async def test_unreadable_intent_checks_are_dropped_not_sent(tools, monkeypatch):
    sent = []
    original = tools.run_scenarios

    async def capture(workspace_id, scenario_ids, repeat=2, replay_run_ids=None, extra_checks=None):
        if extra_checks:
            sent.append(list(extra_checks))
        return await original(workspace_id, scenario_ids, repeat, replay_run_ids, extra_checks)

    monkeypatch.setattr(tools, "run_scenarios", capture)
    monkeypatch.setattr(
        nodes,
        "make_structured_llm",
        intent_llm(
            [
                "inventory-check.outputs == ['inventory.available']",
                "payment.status == 'PAID'",
                "inventory.unknown != null",
            ]
        ),
    )
    result = await run(initial())
    assert result["error"] is None
    assert sent and all(checks == ["payment.status == 'PAID'"] for checks in sent)
    assert result["intent"]["checks"] == ["payment.status == 'PAID'"]
    assert [d["check"] for d in result["intent"]["dropped_checks"]] == [
        "inventory-check.outputs == ['inventory.available']",
        "inventory.unknown != null",
    ]
    assert result["proposal"]["storage_status"] == "SAVED"


@pytest.mark.asyncio
async def test_all_checks_dropped_continues_without_intent_check(tools, monkeypatch):
    sent = []
    original = tools.run_scenarios

    async def capture(workspace_id, scenario_ids, repeat=2, replay_run_ids=None, extra_checks=None):
        sent.append(extra_checks)
        return await original(workspace_id, scenario_ids, repeat, replay_run_ids, extra_checks)

    monkeypatch.setattr(tools, "run_scenarios", capture)
    monkeypatch.setattr(
        nodes,
        "make_structured_llm",
        intent_llm(["inventory-check.outputs == ['inventory.available']"]),
    )
    result = await run(initial())
    assert result["error"] is None
    assert all(not checks for checks in sent)
    assert result["intent"]["checks"] == []
    assert result["diagnosis_status"] == "EVALUATED"
    assert result["proposal"]["storage_status"] == "SAVED"


@pytest.mark.asyncio
async def test_intent_prompt_lists_values_seen_in_a_passing_run(tools, monkeypatch):
    prompts = []

    def capture(schema):
        chain = fake_llm(schema)
        if schema.__name__ == "_IntentOutput":
            original = chain.ainvoke

            async def ainvoke(prompt, *args):
                prompts.append(prompt)
                return await original(prompt, *args)

            chain.ainvoke = ainvoke
        return chain

    monkeypatch.setattr(nodes, "make_structured_llm", capture)
    await run(initial())
    assert prompts and "## 실행 기록에 있는 값 이름" in prompts[0]
    section = prompts[0].split("## 실행 기록에 있는 값 이름", 1)[1].split("##", 1)[0]
    assert "payment.status" in section
