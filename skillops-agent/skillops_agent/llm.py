"""Anthropic 클라이언트 팩토리.

키는 코드에 넣지 않는다. anthropic.Anthropic() 이 ANTHROPIC_API_KEY, ANTHROPIC_AUTH_TOKEN,
또는 `ant auth login` 프로필 순서로 자격을 찾는다.
"""

import anthropic

from .config import MODEL


def make_client() -> anthropic.Anthropic:
    return anthropic.Anthropic()


__all__ = ["MODEL", "make_client"]
