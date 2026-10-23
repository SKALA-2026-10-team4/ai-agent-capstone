"""리팩토링 전후 동작이 같아야 하는 경계 경우를 고정한다.

적대적 검토에서 찾은 차이가 다시 생기지 않게 한다. 그래프로는 닿기 어렵지만 노드 함수를
직접 부르거나 손으로 만든 요청을 보내면 드러나는 차이들이다. 이 파일의 테스트는 리팩토링 전
코드(커밋 ec39713)에서도 그대로 통과하는 것을 확인했다.
"""

from types import SimpleNamespace
from uuid import uuid4

import pytest

from skillops_agent import nodes
from skillops_agent.graph import graph
from skillops_agent.stub_tools import StubMCPTools


class _FakeChain:
    def __init__(self, output, prompts):
        self.output = output
        self.prompts = prompts

    async def ainvoke(self, prompt, *args):
        self.prompts.append(prompt)
        return self.output


def _outputs():
    return {
        "_HypothesesOutput": SimpleNamespace(
            hypotheses=[
                SimpleNamespace(change_no=1, summary="inventory output deletion", confidence=0.9)
            ]
        ),
        "_IntentOutput": SimpleNamespace(text="keep payment", checks=[]),
        "_CandidatesOutput": SimpleNamespace(candidates=[]),
        "_RankExplanationsOutput": SimpleNamespace(explanations=[]),
    }


@pytest.fixture
def stub(monkeypatch):
    tools = StubMCPTools()
    prompts = []
    outputs = _outputs()
    monkeypatch.setattr(nodes, "get_tools", lambda: tools)
    monkeypatch.setattr(
        nodes, "make_structured_llm", lambda schema: _FakeChain(outputs[schema.__name__], prompts)
    )
    tools.prompts = prompts
    return tools


async def test_validated_save_keeps_empty_run_id_as_given(stub, monkeypatch):
    """저장 직후 검증이 VALIDATED 로 끝나면 trigger 의 실행 번호("")를 그대로 남긴다.

    None 으로 바꾸지 않는다.
    """
    original = stub.create_workspace

    async def healthy(**kwargs):
        ws = await original(**kwargs)
        stub._workspaces[ws["workspace_id"]] = True
        return ws

    monkeypatch.setattr(stub, "create_workspace", healthy)
    trigger = {"type": "skill_saved", "candidate_snapshot_id": StubMCPTools.CANDIDATE, "run_id": ""}
    result = await graph.ainvoke(
        {"trigger": trigger}, {"configurable": {"thread_id": str(uuid4())}, "recursion_limit": 100}
    )
    assert result["diagnosis_status"] == "VALIDATED"
    assert result["execution_id"] == ""
    assert result["trace"] is None


def test_check_ablation_unknown_confirmed_change_raises_stop_iteration():
    """확정 실험의 change_no 가 changes 에 없으면 StopIteration 이 난다(TypeError 가 아니다)."""
    state = {
        "ablation_runs": [
            {"change_no": 7, "run_ids": ["r_1"], "control_run_ids": [], "confirmed": True}
        ],
        "changes": [
            {"change_no": 1, "skill": "payment", "hunk_id": "h", "before": "a", "after": "b"}
        ],
        "skill_versions": {"payment": 1},
        "step_count": 0,
        "events": [],
        "hypothesis_retry": 0,
    }
    with pytest.raises(StopIteration):
        nodes.check_ablation(state)


def test_proposal_payload_does_not_read_change_no_when_changes_are_empty():
    """changes 가 비면 root_cause 의 change_no 를 읽지 않고 before·after 를 None 으로 둔다."""
    payload = nodes.proposal_payload(
        {"trigger": {}, "root_cause": {"skill": "x"}, "changes": [], "candidates": []}
    )
    assert payload["root_cause"] == {"skill": "x", "before": None, "after": None}


async def test_first_hypothesis_attempt_ignores_ablation_run_shape(stub):
    """첫 시도(hypothesis_retry=0)에서는 ablation_runs 원소의 키를 읽지 않는다."""
    state = {
        "trace": {},
        "changes": [],
        "hypothesis_retry": 0,
        "ablation_runs": [{"change_no": 1}],
        "step_count": 0,
        "events": [],
    }
    result = await nodes.hypothesize(state)
    assert result["hypotheses"][0]["change_no"] == 1
    assert "없음" in stub.prompts[-1]


async def test_first_candidate_attempt_ignores_previous_candidate_shape(stub):
    """첫 시도(candidate_retry=0)에서는 앞선 후보 목록 원소의 키를 읽지 않는다."""
    state = {
        "root_cause": {"skill": "inventory-check"},
        "intent": {},
        "diff": {},
        "candidate_retry": 0,
        "candidates": [{"valid": False}],
        "snapshot_id": StubMCPTools.CANDIDATE,
        "trigger": {},
        "skill_versions": {"inventory-check": 1},
        "step_count": 0,
        "events": [],
    }
    with pytest.raises(Exception) as caught:
        await nodes.generate_candidates(state)
    # 후보가 없다는 계약 오류까지 가야 한다.
    # 첫 시도에서 목록 원소의 키를 읽었다면 KeyError 가 먼저 난다.
    assert "LLM returned no candidates" in str(caught.value)
    assert not isinstance(caught.value, KeyError)


@pytest.mark.parametrize("node", ["extract_intent", "rank_and_explain"])
async def test_node_prompt_is_loaded_before_style(stub, monkeypatch, node):
    """노드 프롬프트 파일을 먼저 읽고 style 프롬프트를 나중에 읽는다."""
    loaded = []
    real_load = nodes.load_prompt

    def recording_load(name):
        loaded.append(name)
        return real_load(name)

    monkeypatch.setattr(nodes, "load_prompt", recording_load)
    state = {
        "trace": {},
        "diff": {},
        "root_cause": {},
        "intent": {},
        "ablation_runs": [],
        "candidates": [],
        "step_count": 0,
        "events": [],
    }
    await getattr(nodes, node)(state)
    assert loaded == [node, "style"]
