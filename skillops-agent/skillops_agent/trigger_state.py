"""호출(트리거) 진단 그래프의 상태와 평가셋 형식을 정의한다.

기획안 F4(호출 진단), F6(개선안 생성·재검증)을 맡는 그래프다. 회귀 진단 그래프의 AgentState 와
섞지 않으려고 따로 둔다. 노드는 skillops-agent/skillops_agent/trigger_nodes.py, 흐름은
skillops-agent/skillops_agent/trigger_graph.py 에 있다.
"""

from typing import TypedDict

from .state import DiagnosisStatus

# 어떤 skill 도 고르지 않은 선택이다. 주문 Agent 의 미선택 응답 이름과 같다.
NONE_SKILL = "none"


class SkillCard(TypedDict):
    """선택 Agent 가 보는 skill 정보다. 본문은 보지 않고 이름과 description 만 본다."""

    name: str
    description: str


class TriggerCase(TypedDict):
    """평가셋의 질의 하나다.

    should_trigger 가 참이면 expected_skill 이 불려야 하는 질의이고(expected_skill 이 none 이면 아무
    skill 도 불리면 안 된다), 거짓이면 expected_skill 이 불리면 안 되는 질의다.
    """

    query: str
    expected_skill: str
    should_trigger: bool


class TriggerState(TypedDict, total=False):
    """트리거 진단 그래프가 노드 사이에 주고받는 상태다."""

    # 입력
    target_skill: str
    skills: list[SkillCard]
    cases: list[TriggerCase]
    seed_queries: list[str]  # cases 가 비었을 때 평가 질의 생성의 씨앗 문장
    repeat: int
    pipeline_thread_id: str  # 있으면 graph.guarded 가 RunStore 에 진행을 저장한다

    # 진행
    quality: dict  # {"findings": [...]} skill_quality 규칙 검사 결과
    baseline: dict | None  # 원래 description 으로 잰 결과(trigger_metrics.measure)
    diagnosis: dict | None  # 마지막 진단
    proposal: dict | None  # 마지막 개선안
    attempts: list[dict]  # 재검증 기록. 길이가 시도 횟수다.
    events: list[dict]
    step_count: int

    # 결과
    diagnosis_status: str
    report: dict | None
    error: str | None


class TriggerStatus:
    """트리거 진단의 diagnosis_status 값이다. RUNNING 과 ERROR 는 회귀 진단과 같은 글자를 쓴다."""

    RUNNING = DiagnosisStatus.RUNNING
    # SKILL.md 규칙 검사에서 BLOCKER 가 나와 LLM 을 부르지 않고 멈췄다.
    BLOCKED = "BLOCKED"
    # 원래 description 으로 이미 모든 질의를 맞혀 고칠 것이 없다.
    NO_ISSUE = "NO_ISSUE"
    # 재검증을 통과한 개선안이 있다.
    PROPOSED = "PROPOSED"
    # 시도를 다 써도 통과한 개선안이 없다.
    NO_VALID_FIX = "NO_VALID_FIX"
    ERROR = DiagnosisStatus.ERROR
