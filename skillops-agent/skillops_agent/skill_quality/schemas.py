"""Skill 품질 검사 결과(Finding)의 형식과 등급 계산을 정의한다.

검사는 두 종류다.
- rule: 코드로 정답이 정해지는 검사(형식, 길이, 참조, 지표). 이 모듈의 BLOCKER 만 흐름을 막는다.
- semantic: LLM 이 의미를 판단하는 검사(2차). 개선 권고로만 쓰고 흐름을 막지 않는다.
"""

from typing import Literal, TypedDict

Kind = Literal["rule", "semantic"]
Status = Literal["PASS", "FAIL", "UNVERIFIED"]
Severity = Literal["BLOCKER", "MAJOR", "MINOR"]


class Finding(TypedDict):
    """검사 항목 하나의 결과다. report 의 quality.findings 에 그대로 들어간다."""

    id: str  # 예: rule.description_length
    kind: Kind
    status: Status
    severity: Severity
    target: str | None  # 검사한 skill 이름. 평가셋 전체 검사면 None
    reason: str
    fix: str


def finding(
    id: str,
    status: Status,
    severity: Severity,
    reason: str = "",
    fix: str = "",
    *,
    target: str | None = None,
    kind: Kind = "rule",
) -> Finding:
    """Finding 하나를 만든다."""
    return {
        "id": id,
        "kind": kind,
        "status": status,
        "severity": severity,
        "target": target,
        "reason": reason,
        "fix": fix,
    }


def failed(findings: list[Finding]) -> list[Finding]:
    """FAIL 인 항목만 남긴다."""
    return [f for f in findings if f["status"] == "FAIL"]


def blockers(findings: list[Finding]) -> list[Finding]:
    """흐름을 막는 항목이다. rule 검사의 BLOCKER FAIL 만 센다(semantic 은 막지 않는다)."""
    return [
        f for f in failed(findings) if f["kind"] == "rule" and f["severity"] == "BLOCKER"
    ]


def grade(findings: list[Finding]) -> str:
    """S~F 등급을 코드로 계산한다.

    F: rule BLOCKER 1개 이상 · S: 그 밖의 MAJOR 0 · A: 1~2 · B: 3~4 · C: 5 이상.
    semantic BLOCKER 는 F 를 만들지 않고 MAJOR 로 센다. MINOR 와 UNVERIFIED 는 등급에 넣지 않는다.
    """
    if blockers(findings):
        return "F"
    major = sum(1 for f in failed(findings) if f["severity"] in ("MAJOR", "BLOCKER"))
    if major == 0:
        return "S"
    if major <= 2:
        return "A"
    if major <= 4:
        return "B"
    return "C"


def reasons(findings: list[Finding]) -> list[str]:
    """FAIL 항목의 이유 문장 목록이다. 오류 문구와 재시도 근거로 쓴다."""
    return [f["reason"] for f in failed(findings)]
