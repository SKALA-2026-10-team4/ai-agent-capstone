"""Graph tests with C-shaped fixtures and fake LLM, no network or paid calls."""

from types import SimpleNamespace
from uuid import uuid4

import pytest

from skillops_agent import nodes
from skillops_agent.graph import graph
from skillops_agent.stub_tools import StubMCPTools


def initial(**extra):
    return {
        "trigger": {"type": "failure", "execution_id": StubMCPTools.EXECUTION, **extra},
        "max_steps": 40,
    }


class FakeChain:
    def __init__(self, output):
        self.output = output

    async def ainvoke(self, *args):
        return self.output


def fake_llm(schema):
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
    stub = StubMCPTools()
    monkeypatch.setattr(nodes, "get_tools", lambda: stub)
    monkeypatch.setattr(nodes, "make_structured_llm", fake_llm)
    return stub


async def run(state):
    return await graph.ainvoke(
        state, {"configurable": {"thread_id": str(uuid4())}, "recursion_limit": 100}
    )


@pytest.mark.asyncio
async def test_real_event_shape_and_changed_skill_are_used(tools):
    result = await run(initial())
    assert result["error"] is None
    assert result["root_cause"]["skill"] == "inventory-check"  # failure location is payment
    assert result["root_cause"]["hunk_id"] == "fixture-hunk"
    assert result["root_cause"]["proof_run_ids"]
    assert result["candidates"][0]["valid"]
    assert result["proposal"]["id"] is None
    assert result["proposal"]["storage_status"] == "NOT_SAVED"
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
    assert len(result["candidates"][0]["evidence_run_ids"]) == 10  # 3*2 + 1*2 + 1*2
    assert "inventory.reserve" in result["candidates"][0]["results"]["tools_called"]


@pytest.mark.asyncio
async def test_broken_contract_cannot_be_valid(tools, monkeypatch):
    original = tools.apply_patch

    async def broken(*args):
        result = await original(*args)
        result["broken_links"] = [{"skill": "payment", "missing_input": "inventory.reserved"}]
        return result

    monkeypatch.setattr(tools, "apply_patch", broken)
    result = await run(initial())
    assert result["error"] is None
    assert not any(c["valid"] for c in result["candidates"])
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
        if count == 2:  # first apply is ablation, second is the first repair candidate
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
