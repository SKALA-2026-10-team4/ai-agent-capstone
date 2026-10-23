"""Node별 프롬프트 파일을 읽는 로더다.

프롬프트 문구를 skillops-agent/prompts/*.md 파일로 코드와 나눠 두어, 코드를 고치지 않고 문구를
다듬을 수 있게 한다. skillops-agent/skillops_agent/nodes.py 가 load 를 load_prompt 라는 이름으로
import 해 쓴다.
"""

from pathlib import Path

# 이 파일에서 두 단계 위 폴더(skillops-agent/)의 prompts 폴더다.
_PROMPTS_DIR = Path(__file__).parent.parent / "prompts"


def exists(name: str) -> bool:
    """prompts/<name>.md 가 있는지 본다. 호출 진단 노드가 <name>.system.md 유무를 볼 때 쓴다."""
    return (_PROMPTS_DIR / f"{name}.md").exists()


def load(name: str) -> str:
    """prompts/<name>.md 를 읽어 반환한다.

    skillops-agent/prompts/<name>.md 를 UTF-8 로 읽은 글을 그대로 돌려주고, 파일이 없으면
    FileNotFoundError 를 던진다. {trace} 같은 자리는 부르는 쪽이 str.format 으로 채운다.
    skillops-agent/skillops_agent/nodes.py 의 _ask_llm, extract_intent, rank_and_explain 이 부르고,
    skillops-agent/tests/test_foundation.py 가 파일 존재와 문체 규칙 포함을 확인한다.
    """
    path = _PROMPTS_DIR / f"{name}.md"
    if not path.exists():
        raise FileNotFoundError(f"프롬프트 파일 없음: {path}")
    return path.read_text(encoding="utf-8")
