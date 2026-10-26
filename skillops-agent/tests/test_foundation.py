"""PB-001 인수 조건(LLM 래퍼, 서비스 토큰 인증, 도구 선택, 프롬프트 파일)을 확인하는 테스트다.

보호하는 동작은 다음과 같다.
- skillops-agent/skillops_agent/llm.py 의 make_llm 과 make_structured_llm 이 설정한 모델로
  ChatOpenAI 와 structured output 체인을 만든다.
- skillops-agent/skillops_agent/main.py 의 verify_token 이 SERVICE_TOKEN 이 비면 통과시키고, 틀린
  토큰은 401 로 막고, 맞는 토큰은 /diagnose 의 202 까지 보낸다.
- skillops-agent/skillops_agent/tools.py 의 get_tools 가 stub 모드에서 StubMCPTools 를 고르고,
  stub 의 ping, get_trace, diff_skill 이 C 모양 응답을 준다.
- skillops-agent/skillops_agent/prompts.py 가 노드 프롬프트 파일을 읽고, 운영자에게 보이는
  프롬프트에 skillops-agent/prompts/style.md 의 문체 규칙이 들어간다.

/diagnose 를 부르는 테스트는 patch_pipeline 으로 실제 파이프라인 실행을 막는다.
"""

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
    """HTTP 응답 코드 테스트에서 실제 파이프라인이 돌지 않게 _run_pipeline 을 빈 함수로 바꾼다."""

    async def _noop(req, thread_id):
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
    res = client.post(
        "/diagnose", json={"type": "failure", "run_id": "33333333-3333-4333-8333-333333333333"}
    )
    assert res.status_code == 202


def test_diagnose_wrong_token_returns_401(monkeypatch):
    monkeypatch.setattr(cfg, "SERVICE_TOKEN", "secret")
    import skillops_agent.main as m

    monkeypatch.setattr(m, "SERVICE_TOKEN", "secret")
    res = client.post(
        "/diagnose",
        json={"type": "failure", "run_id": "33333333-3333-4333-8333-333333333333"},
        headers={"x-service-token": "wrong"},
    )
    assert res.status_code == 401


def test_diagnose_correct_token_returns_202(monkeypatch):
    monkeypatch.setattr(cfg, "SERVICE_TOKEN", "secret")
    import skillops_agent.main as m

    monkeypatch.setattr(m, "SERVICE_TOKEN", "secret")
    res = client.post(
        "/diagnose",
        json={"type": "failure", "run_id": "33333333-3333-4333-8333-333333333333"},
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
            return await t.get_trace("33333333-3333-4333-8333-333333333333")

    trace = asyncio.run(_run())
    assert trace["final_status"] == "FAILED"
    # trace 는 계약 문서의 steps[] 가 아니라 주문 Agent 의 실제 형식인 events[] 에 실행 기록을
    # 담는다.
    error_events = [e for e in trace["events"] if e["kind"] == "EXECUTION_ERROR"]
    assert len(error_events) == 1
    assert "inventory.reserved" in error_events[0]["data"]["missing_inputs"][0]


def test_stub_diff_skill_returns_changes():
    async def _run():
        async with StubMCPTools() as t:
            return await t.diff_skill(
                "inventory-check", StubMCPTools.BASELINE, StubMCPTools.CANDIDATE
            )

    diff = asyncio.run(_run())
    # v1 계약 문서는 hunks[] 를 changes[] 로 바꿨지만, 지금 C-1 구현과 stub 은 hunks[] 를 돌려준다.
    assert len(diff["hunks"]) >= 1
    change = diff["hunks"][0]
    assert "hunk_id" in change
    assert "before" in change
    assert "after" in change


# ── 프롬프트 파일 로드 ─────────────────────────────────────────────────────────


@pytest.mark.parametrize(
    "name",
    [
        "hypothesize",
        "infer_expectations",
        "extract_intent",
        "generate_candidates",
        "rank_and_explain",
        "style",
    ],
)
def test_prompt_files_exist(name):
    text = load_prompt(name)
    assert len(text) > 0


@pytest.mark.parametrize("name", ["rank_and_explain", "extract_intent"])
def test_style_rules_reach_operator_facing_prompts(name):
    """운영자에게 보이는 글을 만드는 프롬프트에 style 프롬프트가 채워지는지 본다.

    style.md 의 금지 표현 목록과 고친 예문이 채운 글에 그대로 들어 있어야 한다.
    """
    text = load_prompt(name).format(
        owner_intent="(없음)",
        root_cause={},
        intent={},
        candidates=[],
        diff={},
        context_keys=[],
        style=load_prompt("style"),
    )
    assert "을 통해" in text
    assert "이 기능으로 데이터를 모을 수 있습니다" in text
