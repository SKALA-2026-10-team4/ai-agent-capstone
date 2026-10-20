"""LLM 클라이언트 팩토리. ChatOpenAI(GPT mini) 기반, structured output 지원."""

from typing import Any

from langchain_openai import ChatOpenAI

from .config import MODEL


def make_llm(temperature: float = 0.0) -> ChatOpenAI:
    return ChatOpenAI(model=MODEL, temperature=temperature)


def make_structured_llm(schema: Any) -> Any:
    """Pydantic 모델을 받아 structured output 체인을 반환한다."""
    return make_llm().with_structured_output(schema)


__all__ = ["MODEL", "make_llm", "make_structured_llm"]
