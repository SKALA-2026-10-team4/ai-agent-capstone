"""Node별 프롬프트 파일 로더. prompts/*.md 를 코드에서 분리한다."""

from pathlib import Path

_PROMPTS_DIR = Path(__file__).parent.parent / "prompts"


def load(name: str) -> str:
    """prompts/<name>.md 를 읽어 반환한다."""
    path = _PROMPTS_DIR / f"{name}.md"
    if not path.exists():
        raise FileNotFoundError(f"프롬프트 파일 없음: {path}")
    return path.read_text(encoding="utf-8")
