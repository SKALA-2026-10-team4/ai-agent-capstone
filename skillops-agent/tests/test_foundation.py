"""PB-001 인수 조건 확인: LLM 래퍼, 토큰 인증, 도구 로드, 프롬프트 로드."""

import asyncio

import pytest
from fastapi.testclient import TestClient

import skillops_agent.config as cfg
import skillops_agent.main as _main_module
from skillops_agent.llm import make_llm, make_structured_llm
from skillops_agent.main import app
from skillops_agent.prompts import load as load_prompt
from skillops_agent.tools import StubMCPTools, get_tools

client = TestClient(app)


@pytest.fixture(autouse=True)
def patch_pipeline(monkeypatch):
    """HTTP 응답 코드 테스트에서 실제 파이프라인 실행을 방지한다."""
    async def _noop(req):
        pass
    monkeypatch.setattr(_main_module, "_run_pipeline", _noop)


# ── LLM 래퍼 ──────────────────────────────────────────────────────────────────


def test_make_llm_returns_chat_openai():
    from langchain_openai import ChatOpenAI

    llm = make_llm()
    assert isinstance(llm, ChatOpenAI)
    assert llm.model_name == cfg.MODEL


def test_make_structured_llm_wraps_schema():
    from pydantic import BaseModel

    class Echo(BaseModel):
        message: str

    chain = make_structured_llm(Echo)
    assert chain is not None


# ── 토큰 인증 ──────────────────────────────────────────────────────────────────


def test_diagnose_no_token_passes_when_token_not_configured(monkeypatch):
    monkeypatch.setattr(cfg, "SERVICE_TOKEN", "")
    import skillops_agent.main as m
    monkeypatch.setattr(m, "SERVICE_TOKEN", "")
    res = client.post("/diagnose", json={"type": "failure", "run_id": "r_01"})
    assert res.status_code == 202


def test_diagnose_wrong_token_returns_401(monkeypatch):
    monkeypatch.setattr(cfg, "SERVICE_TOKEN", "secret")
    import skillops_agent.main as m
    monkeypatch.setattr(m, "SERVICE_TOKEN", "secret")
    res = client.post(
        "/diagnose",
        json={"type": "failure", "run_id": "r_01"},
        headers={"x-service-token": "wrong"},
    )
    assert res.status_code == 401


def test_diagnose_correct_token_returns_202(monkeypatch):
    monkeypatch.setattr(cfg, "SERVICE_TOKEN", "secret")
    import skillops_agent.main as m
    monkeypatch.setattr(m, "SERVICE_TOKEN", "secret")
    res = client.post(
        "/diagnose",
        json={"type": "failure", "run_id": "r_01"},
        headers={"x-service-token": "secret"},
    )
    assert res.status_code == 202


# ── Stub 도구 ──────────────────────────────────────────────────────────────────


def test_get_tools_returns_stub_when_stub_mode(monkeypatch):
    monkeypatch.setattr("skillops_agent.tools.USE_STUB_MCP", True)
    tools = get_tools()
    assert isinstance(tools, StubMCPTools)


def test_stub_ping():
    async def _run():
        async with StubMCPTools() as t:
            result = await t.ping()
        return result

    result = asyncio.run(_run())
    assert result.startswith("pong")


def test_stub_get_trace_returns_failed_trace():
    async def _run():
        async with StubMCPTools() as t:
            return await t.get_trace("r_stub_F1")

    trace = asyncio.run(_run())
    assert trace["final_status"] == "FAILED"
    error_steps = [s for s in trace["steps"] if s["status"] == "error"]
    assert len(error_steps) == 1
    assert "inventory.reserved" in error_steps[0]["error"]


def test_stub_diff_skill_returns_hunks():
    async def _run():
        async with StubMCPTools() as t:
            return await t.diff_skill("inventory-check", 4, 5)

    diff = asyncio.run(_run())
    assert len(diff["hunks"]) >= 1
    hunk = diff["hunks"][0]
    assert "index" in hunk
    assert "before" in hunk
    assert "after" in hunk


# ── 프롬프트 파일 로드 ─────────────────────────────────────────────────────────


@pytest.mark.parametrize("name", ["hypothesize", "extract_intent", "generate_candidates", "rank_and_explain"])
def test_prompt_files_exist(name):
    text = load_prompt(name)
    assert len(text) > 0
