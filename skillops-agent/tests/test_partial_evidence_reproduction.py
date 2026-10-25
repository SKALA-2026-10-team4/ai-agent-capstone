"""partial 재현 사례의 원문·diff 저장과 자동 수정 경로 차단을 검증한다."""

import base64
from copy import deepcopy
from hashlib import sha256
from unittest.mock import AsyncMock, Mock
from uuid import uuid4

import pytest
from fastapi.testclient import TestClient

from skillops_agent import nodes
from skillops_agent.graph import graph
from skillops_agent.main import app
from skillops_agent.order_agent import OrderAgentError
from skillops_agent.run_store import RunStore
from skillops_agent.stub_tools import StubMCPTools

SOURCE = (
    "---\nname: inventory-check\nversion: 1\nowner: backend\n"
    "description: Check inventory\ninputs: []\noutputs: []\ndepends_on: []\n---\nCheck stock.\n"
)
PATH = "skills/inventory-check/SKILL.md"
BROKEN_SOURCES = [
    pytest.param(SOURCE.replace("owner: backend\n", ""), id="missing-owner"),
    pytest.param(SOURCE.replace("outputs: []", "outputs: ["), id="broken-yaml"),
    pytest.param(SOURCE.replace("version: 1\n", ""), id="missing-version"),
]


@pytest.mark.asyncio
@pytest.mark.parametrize("source", BROKEN_SOURCES)
@pytest.mark.parametrize("trigger_type", ["failure", "skill_saved"])
async def test_partial_listing_preserves_evidence_without_automatic_repair(
    monkeypatch, source, trigger_type
):
    """이전 중단 사례에서 원문·오류·diff를 저장하고 자동 수정은 호출하지 않는다."""
    tools, listing = partial_tools(monkeypatch, source)
    downstream = {}
    for name in (
        "get_skill",
        "get_dependency_graph",
        "create_workspace",
        "apply_patch",
        "run_scenarios",
        "save_proposal",
    ):
        downstream[name] = AsyncMock(side_effect=AssertionError(f"unexpected {name}"))
        monkeypatch.setattr(tools, name, downstream[name])
    llm = Mock(side_effect=AssertionError("unexpected LLM call"))
    monkeypatch.setattr(nodes, "make_structured_llm", llm)
    trigger = {"type": trigger_type}
    if trigger_type == "failure":
        trigger["execution_id"] = tools.EXECUTION
    else:
        trigger["candidate_snapshot_id"] = tools.CANDIDATE
    result, store, thread_id = await run_partial(trigger)

    tools.list_skills.assert_awaited_once_with(tools.CANDIDATE)
    for state in (result, store.get(thread_id)):
        assert state["diagnosis_status"] == "ERROR"
        assert state["error"] == "ContractError: candidate skill metadata is incomplete"
        assert state["snapshot_id"] == tools.CANDIDATE
        assert state["baseline_snapshot_id"] == tools.BASELINE
        assert (state["trace"] is not None) == (trigger_type == "failure")
        assert state["diff"]["files"][0]["frontmatter_parse_error"] == "fixture parse error"
        assert [c["change_no"] for c in state["changes"]] == [1]
        assert state["skill_versions"] == {}
        assert state["proposal"] is None
        evidence = state["evidence"]
        assert evidence["metadata"] == listing
        assert evidence["collection_status"] == "COLLECTED"
        assert evidence["collection_errors"] == []
        assert [s["content"] for s in evidence["sources"]] == [SOURCE, source]
        assert state["diagnosis"]["evidence_only"] is True
    tools.diff_skill.assert_awaited_once_with("inventory-check", tools.BASELINE, tools.CANDIDATE)
    for call in downstream.values():
        call.assert_not_awaited()
    llm.assert_not_called()


def partial_tools(monkeypatch, source, *, unchanged=False):
    """원문 바이트와 manifest·목록·diff가 일치하는 partial 도구 대역을 연결한다."""
    tools = StubMCPTools()
    original_snapshot = tools.get_snapshot
    original_diff = tools.diff_skill

    async def snapshot(snapshot_id):
        """대상 파일의 실제 바이트 SHA-256이 담긴 manifest를 반환한다."""
        manifest = await original_snapshot(snapshot_id)
        text = SOURCE if snapshot_id == tools.BASELINE and not unchanged else source
        raw = text if isinstance(text, bytes) else text.encode()
        manifest["files"] = [{"path": PATH, "hash": sha256(raw).hexdigest(), "size": len(raw)}]
        return manifest

    async def raw_file(snapshot_id, path):
        """파싱 여부와 관계없이 실제 bytes를 보존한 파일 응답을 반환한다."""
        manifest = await snapshot(snapshot_id)
        text = SOURCE if snapshot_id == tools.BASELINE and not unchanged else source
        raw = text if isinstance(text, bytes) else text.encode()
        return {
            "snapshot_id": snapshot_id,
            **manifest["files"][0],
            "encoding": ("base64" if isinstance(text, bytes) else "utf-8"),
            "content": base64.b64encode(raw).decode() if isinstance(text, bytes) else text,
        }

    async def diff(skill, before, after):
        """service diff 응답의 출처를 fixture manifest와 맞춘다."""
        result = await original_diff(skill, before, after)
        for side, snapshot_id in (("before", before), ("after", after)):
            result[side]["file_hash"] = (await snapshot(snapshot_id))["files"][0]["hash"]
        result["frontmatter_parse_error"] = "fixture parse error"
        return result

    listing = {
        "snapshot_id": tools.CANDIDATE,
        "snapshot_hash": "snapshot-" + tools.CANDIDATE,
        "items": [],
        "partial": True,
        "parse_errors": [
            {
                "path": PATH,
                "code": "INVALID_SKILL_FORMAT",
                "message": "Skill 메타데이터를 해석할 수 없습니다: INVALID_SKILL_FORMAT",
            }
        ],
    }
    monkeypatch.setattr(tools, "get_snapshot", AsyncMock(side_effect=snapshot))
    monkeypatch.setattr(tools, "list_skills", AsyncMock(return_value=listing))
    monkeypatch.setattr(tools, "get_snapshot_file", AsyncMock(side_effect=raw_file))
    monkeypatch.setattr(tools, "diff_skill", AsyncMock(side_effect=diff))
    monkeypatch.setattr(nodes, "get_tools", lambda: tools)
    return tools, listing


async def run_partial(trigger=None):
    """partial 그래프를 실제 임시 SQLite 기록과 함께 실행한다."""
    trigger = trigger or {"type": "failure", "execution_id": StubMCPTools.EXECUTION}
    store = RunStore()
    thread_id, _ = store.accept(trigger)
    result = await graph.ainvoke(
        {"trigger": trigger, "pipeline_thread_id": thread_id, "max_steps": 40},
        {"configurable": {"thread_id": str(uuid4())}, "recursion_limit": 100},
    )
    return result, store, thread_id


@pytest.mark.asyncio
async def test_unchanged_broken_skill_still_has_source_and_error_evidence(monkeypatch):
    """해시가 같아 diff가 없어도 오류 파일 원문과 목록 오류를 저장한다."""
    tools, listing = partial_tools(monkeypatch, SOURCE, unchanged=True)
    result, _, _ = await run_partial()
    assert result["evidence"]["metadata"] == listing
    assert len(result["evidence"]["sources"]) == 2
    assert result["changes"] == []
    tools.diff_skill.assert_not_awaited()


@pytest.mark.asyncio
async def test_base64_source_is_preserved_but_not_sent_to_text_diff(monkeypatch):
    """비 UTF-8 원문은 base64로 보존하고 텍스트 diff에 전달하지 않는다."""
    tools, _ = partial_tools(monkeypatch, b"\xff\x00")
    result, _, _ = await run_partial()
    evidence = result["evidence"]
    assert base64.b64decode(evidence["sources"][1]["content"]) == b"\xff\x00"
    assert evidence["collection_status"] == "COLLECTED_WITH_ERRORS"
    assert evidence["collection_errors"][0]["stage"] == "diff"
    tools.diff_skill.assert_not_awaited()


@pytest.mark.asyncio
async def test_tampered_source_stops_but_preserves_verified_baseline(monkeypatch):
    """원문 변조는 중단하며 먼저 검증한 파일과 snapshot 증거를 남긴다."""
    tools, _ = partial_tools(monkeypatch, SOURCE + "edit\n")
    original = tools.get_snapshot_file.side_effect

    async def tamper(snapshot_id, path):
        """후보 응답의 content만 변조해 바이트 해시 불일치를 재현한다."""
        response = await original(snapshot_id, path)
        if snapshot_id == tools.CANDIDATE:
            response["content"] += "tampered"
        return response

    tools.get_snapshot_file.side_effect = tamper
    result, store, thread_id = await run_partial()
    assert result["evidence"]["collection_status"] == "STOPPED"
    assert len(result["evidence"]["sources"]) == 1
    assert store.get(thread_id)["evidence"] == result["evidence"]
    tools.diff_skill.assert_not_awaited()


@pytest.mark.asyncio
async def test_diff_failure_keeps_previously_persisted_sources(monkeypatch):
    """diff 호출 중 오류가 나도 직전 노드에서 저장한 원문과 오류를 잃지 않는다."""
    tools, _ = partial_tools(monkeypatch, SOURCE + "edit\n")

    async def fail_diff(*args):
        """diff 호출 시점에 원문이 이미 SQLite에 저장됐는지 확인하고 조회 오류를 낸다."""
        store = RunStore()
        record = store.get(store.list()[0]["thread_id"])
        assert record["evidence"]["collection_status"] == "SOURCES_COLLECTED"
        assert len(record["evidence"]["sources"]) == 2
        raise OrderAgentError("ORDER_AGENT_UNAVAILABLE", "offline", retryable=True)

    tools.diff_skill.side_effect = fail_diff
    result, store, thread_id = await run_partial()
    assert len(result["evidence"]["sources"]) == 2
    assert result["evidence"]["collection_errors"][0]["code"] == "ORDER_AGENT_UNAVAILABLE"
    assert store.get(thread_id)["evidence"] == result["evidence"]


@pytest.mark.asyncio
async def test_restart_recovery_keeps_sources_collected_before_diff(monkeypatch):
    """수집 후 중단된 기록을 복구해도 영구 저장된 원문 증거가 유지된다."""
    tools, _ = partial_tools(monkeypatch, SOURCE + "edit\n")
    saved = {}

    async def interrupt(*args):
        """노드 경계에서 이미 저장된 상태를 복사해 재시작 직전 기록으로 사용한다."""
        store = RunStore()
        saved.update(deepcopy(store.get(store.list()[0]["thread_id"])))
        raise RuntimeError("simulated process interruption")

    tools.diff_skill.side_effect = interrupt
    _, store, thread_id = await run_partial()
    store.update(thread_id, {"diagnosis_status": "RUNNING", "evidence": saved["evidence"]})
    store.recover()
    assert store.get(thread_id)["evidence"] == saved["evidence"]
    assert store.get(thread_id)["diagnosis_status"] == "ERROR"


@pytest.mark.asyncio
async def test_partial_raw_evidence_is_only_in_run_detail(monkeypatch):
    """새 원문 증거는 인증된 상세 조회로 제공하고 목록 응답에서는 제외한다."""
    import skillops_agent.main as main

    partial_tools(monkeypatch, SOURCE + "edit\n")
    monkeypatch.setattr(main, "SERVICE_TOKEN", "partial-test-token")
    result, _, thread_id = await run_partial()
    client = TestClient(app)
    assert client.get(f"/runs/{thread_id}/events").status_code == 401
    headers = {"X-Service-Token": "partial-test-token"}
    detail = client.get(f"/runs/{thread_id}/events", headers=headers)
    assert detail.status_code == 200
    assert detail.json()["evidence"] == result["evidence"]
    listing = client.get("/runs", headers=headers)
    assert listing.status_code == 200
    assert "evidence" not in listing.json()["items"][0]


@pytest.mark.asyncio
async def test_read_failure_preserves_other_file_and_records_code(monkeypatch):
    """한쪽 원문을 읽지 못해도 다른 쪽 원문과 통신 오류를 함께 저장한다."""
    tools, _ = partial_tools(monkeypatch, SOURCE + "edit\n")
    original = tools.get_snapshot_file.side_effect

    async def fail_candidate(snapshot_id, path):
        """후보 원문만 일시 조회 실패로 만든다."""
        if snapshot_id == tools.CANDIDATE:
            raise OrderAgentError("ORDER_AGENT_UNAVAILABLE", "offline", retryable=True)
        return await original(snapshot_id, path)

    tools.get_snapshot_file.side_effect = fail_candidate
    result, _, _ = await run_partial()
    evidence = result["evidence"]
    assert len(evidence["sources"]) == 1
    assert evidence["collection_errors"][0]["code"] == "ORDER_AGENT_UNAVAILABLE"
    assert evidence["collection_status"] == "COLLECTED_WITH_ERRORS"
    tools.diff_skill.assert_not_awaited()


@pytest.mark.asyncio
@pytest.mark.parametrize(
    "change",
    [
        {"before": None},
        {"hunks": [None]},
        {"hunks": []},
        {"skill_id": "other"},
        {"after": {"snapshot_id": "other"}},
    ],
)
async def test_invalid_diff_stops_without_discarding_sources(monkeypatch, change):
    """잘못된 형식·출처의 diff는 저장하지 않고 검증된 원문을 유지한다."""
    tools, _ = partial_tools(monkeypatch, SOURCE + "edit\n")
    original = tools.diff_skill.side_effect

    async def corrupt(*args):
        """정상 diff 응답에서 검사할 필드만 손상시킨다."""
        result = await original(*args)
        result.update(deepcopy(change))
        return result

    tools.diff_skill.side_effect = corrupt
    result, _, _ = await run_partial()
    assert result["evidence"]["collection_status"] == "STOPPED"
    assert len(result["evidence"]["sources"]) == 2
    assert result["diff"]["files"] == []


@pytest.mark.asyncio
async def test_multiple_hunks_have_sequential_change_numbers(monkeypatch):
    """하나의 diff에 hunk가 여러 개여도 변경 번호를 빠짐없이 붙인다."""
    tools, _ = partial_tools(monkeypatch, SOURCE + "edit\n")
    original = tools.diff_skill.side_effect

    async def two_hunks(*args):
        """두 번째 hunk를 추가해 generator 내부 번호 계산을 검증한다."""
        result = await original(*args)
        result["hunks"].append({**result["hunks"][0], "hunk_id": "second-hunk"})
        return result

    tools.diff_skill.side_effect = two_hunks
    result, _, _ = await run_partial()
    assert [change["change_no"] for change in result["changes"]] == [1, 2]


@pytest.mark.asyncio
@pytest.mark.parametrize(
    "path",
    [
        "skills/inventory-check/resources/extra.txt",
        "skills/added/SKILL.md",
        "skills/Bad_ID/SKILL.md",
    ],
)
async def test_unsupported_changes_keep_manifest_and_available_skill_sources(monkeypatch, path):
    """리소스·추가 Skill·잘못된 ID는 증거를 남기되 자동 처리하지 않는다."""
    tools, listing = partial_tools(monkeypatch, SOURCE + "edit\n")
    original_snapshot = tools.get_snapshot.side_effect
    original_file = tools.get_snapshot_file.side_effect
    entry = {"path": path, "hash": sha256(b"extra").hexdigest(), "size": 5}

    async def snapshot(snapshot_id):
        """후보 manifest에 지원 밖 변경 파일을 추가한다."""
        result = await original_snapshot(snapshot_id)
        if snapshot_id == tools.CANDIDATE:
            result["files"].append(entry)
        return result

    async def raw_file(snapshot_id, requested_path):
        """추가 Skill은 원문까지 읽을 수 있고 리소스 본문은 요청하지 않는다."""
        if requested_path == path:
            assert path.endswith("/SKILL.md")
            return {"snapshot_id": snapshot_id, **entry, "encoding": "utf-8", "content": "extra"}
        return await original_file(snapshot_id, requested_path)

    tools.get_snapshot.side_effect = snapshot
    tools.get_snapshot_file.side_effect = raw_file
    if path.endswith("/SKILL.md"):
        listing["parse_errors"].append(
            {"path": path, "code": "INVALID_SKILL_FORMAT", "message": "bad"}
        )
    result, _, _ = await run_partial()
    evidence = result["evidence"]
    assert evidence["collection_status"] == "COLLECTED_WITH_ERRORS"
    assert any(error["path"] == path for error in evidence["collection_errors"])
    assert len(evidence["sources"]) == (3 if path.endswith("/SKILL.md") else 2)
    assert len(result["changes"]) == 1


@pytest.mark.asyncio
async def test_mixed_listing_keeps_only_trusted_versions_and_all_sources(monkeypatch):
    """정상 항목 버전과 누락된 오류 Skill 원문을 구분해 함께 저장한다."""
    tools, listing = partial_tools(monkeypatch, SOURCE + "edit\n")
    original_snapshot = tools.get_snapshot.side_effect
    original_file = tools.get_snapshot_file.side_effect
    path = "skills/payment/SKILL.md"
    entry = {"path": path, "hash": sha256(b"payment").hexdigest(), "size": 7}
    listing["items"] = [{"skill_id": "payment", "version": 3, **entry}]

    async def snapshot(snapshot_id):
        """양쪽 snapshot에 해석 가능한 동일 파일을 포함한다."""
        result = await original_snapshot(snapshot_id)
        result["files"].append(entry)
        return result

    async def raw_file(snapshot_id, requested_path):
        """정상 파일도 manifest 해시를 검증할 수 있는 원문 응답으로 반환한다."""
        if requested_path == path:
            return {"snapshot_id": snapshot_id, **entry, "encoding": "utf-8", "content": "payment"}
        return await original_file(snapshot_id, requested_path)

    tools.get_snapshot.side_effect = snapshot
    tools.get_snapshot_file.side_effect = raw_file
    result, _, _ = await run_partial()
    assert result["skill_versions"] == {"payment": 3}
    assert len(result["evidence"]["sources"]) == 4
    assert result["evidence"]["metadata"]["parse_errors"] == listing["parse_errors"]


@pytest.mark.asyncio
async def test_ignoring_partial_would_omit_broken_skill_from_repair_sources():
    """partial 검사를 생략해도 목록에서 빠진 Skill은 후보 생성 원문에 포함되지 않는다."""
    tools = StubMCPTools()
    tools.get_dependency_graph = AsyncMock(return_value={"complete": False})
    tools.get_skill = AsyncMock(wraps=tools.get_skill)

    _, sources = await nodes._repair_inputs(
        tools,
        {
            "trigger": {},
            "snapshot_id": tools.CANDIDATE,
            "skill_versions": {"order-flow": 1, "payment": 1},
        },
    )

    assert set(sources) == {"order-flow", "payment"}
    assert "inventory-check" not in sources
    assert [call.args[1] for call in tools.get_skill.await_args_list] == ["order-flow", "payment"]


@pytest.mark.asyncio
async def test_known_version_does_not_make_invalid_metadata_source_readable():
    """버전을 확보해도 엄격한 get_skill이 실패하면 원문 수집이 다시 중단된다."""
    tools = StubMCPTools()
    tools.get_dependency_graph = AsyncMock(return_value={"complete": False})
    tools.get_skill = AsyncMock(
        side_effect=OrderAgentError(
            "SKILL_METADATA_INVALID",
            "SKILL_METADATA_INVALID",
            retryable=False,
            upstream_status=400,
            snapshot_id=tools.CANDIDATE,
        )
    )

    with pytest.raises(OrderAgentError, match="SKILL_METADATA_INVALID"):
        await nodes._repair_inputs(
            tools,
            {
                "trigger": {},
                "snapshot_id": tools.CANDIDATE,
                "skill_versions": {"inventory-check": 1},
            },
        )
