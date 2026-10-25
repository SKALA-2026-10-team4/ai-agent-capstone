"""SKILL.md 폴더를 읽어 검사와 선택 테스트에 쓸 skill 목록을 만든다. 검사는 하지 않는다.

폴더 구조는 <skills_dir>/<skill 이름>/SKILL.md 이다(저장소 루트 skills/ 와 같다). 이름은 폴더
이름을 쓰고, frontmatter 의 name 과 다르면 rule_checker.check_skill_md 가 잡는다. description 은
비워 두고 load_eval_set 이 frontmatter 에서 채운다.
"""

from pathlib import Path


def load_skill_dir(skills_dir: str | Path) -> list[dict]:
    """폴더 아래 SKILL.md 를 이름순으로 읽어 [{name, folder, skill_md}] 를 돌려준다."""
    root = Path(skills_dir)
    if not root.is_dir():
        raise FileNotFoundError(f"skills_dir 가 폴더가 아닙니다: {root}")
    skills = [
        {"name": path.parent.name, "folder": path.parent.name,
         "skill_md": path.read_text(encoding="utf-8")}
        for path in sorted(root.glob("*/SKILL.md"))
    ]
    if not skills:
        raise FileNotFoundError(f"SKILL.md 가 하나도 없습니다: {root}")
    return skills


def resolve_skills(data: dict, base_dir: str | Path, override: str | Path | None = None) -> list:
    """평가셋 JSON 의 skill 목록을 정한다. 원문 폴더가 있으면 원문을 우선한다.

    1. override(실행 옵션 --skills-dir)가 있으면 그 폴더
    2. 평가셋의 "skills_dir"(평가셋 파일 기준 상대 경로)가 있으면 그 폴더
    3. 둘 다 없으면 평가셋의 "skills"({name, description})를 그대로 쓴다(원문 검사 없음)
    """
    if override:
        return load_skill_dir(override)
    if data.get("skills_dir"):
        return load_skill_dir(Path(base_dir) / data["skills_dir"])
    return data.get("skills") or []
