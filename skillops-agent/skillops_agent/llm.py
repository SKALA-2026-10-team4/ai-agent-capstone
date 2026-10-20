"""OpenAI 클라이언트 팩토리.

키는 코드에 넣지 않는다. OpenAI() 가 OPENAI_API_KEY 환경 변수를 읽는다.
"""

from openai import OpenAI

from .config import MODEL


def make_client() -> OpenAI:
    return OpenAI()


__all__ = ["MODEL", "make_client"]
