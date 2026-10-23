"""LLM 클라이언트를 만드는 팩토리 모듈이다.

langchain_openai 의 ChatOpenAI(GPT mini 계열)를 skillops-agent/skillops_agent/config.py 의 MODEL 과
LLM_TEMPERATURE 로 만들고, Pydantic 스키마를 붙인 structured output 체인도 만든다. API 키는
ChatOpenAI 가 OPENAI_API_KEY 환경 변수에서 읽는다.
skillops-agent/skillops_agent/nodes.py 는 make_structured_llm 만 쓴다. 테스트와
skillops-agent/scripts/run_demo.py, skillops-agent/scripts/b_c_contract_smoke.py 는 nodes 모듈의
make_structured_llm 이름을 가짜로 바꿔 끼워 유료 호출을 막는다.
skillops-agent/scripts/run_real_llm.py 는 바꾸지 않고 실제 API 를 부른다.
"""

from typing import Any

from langchain_openai import ChatOpenAI

from .config import LLM_TEMPERATURE, MODEL


def make_llm(temperature: float = LLM_TEMPERATURE) -> ChatOpenAI:
    """config 의 MODEL 과 온도로 ChatOpenAI 를 만든다.

    make_structured_llm 이 부른다. skillops-agent/tests/test_foundation.py 와
    skillops-agent/tests/test_pipeline_defaults.py 가 모델 이름과 온도를 확인한다.
    """
    return ChatOpenAI(model=MODEL, temperature=temperature)


def make_structured_llm(schema: Any) -> Any:
    """Pydantic 모델을 받아 structured output 체인을 반환한다.

    체인의 ainvoke 결과는 schema 객체다. schema 의 클래스 이름, 필드, docstring, Field 설명이
    OpenAI 로 보내는 JSON schema 가 되므로 출력 스키마 클래스의 docstring 은 바꾸지 않는다.
    skillops-agent/skillops_agent/nodes.py 의 _ask_llm 이 LLM 을 부르는 노드마다 이 함수를 찾아
    부른다.
    """
    return make_llm().with_structured_output(schema)


__all__ = ["MODEL", "make_llm", "make_structured_llm"]
