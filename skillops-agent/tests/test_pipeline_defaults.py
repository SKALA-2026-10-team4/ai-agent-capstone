"""진단 파이프라인의 상태 상수, 상태 기본값, 튜닝 값 기본 설정을 확인한다.

보호하는 대상은 skillops-agent/skillops_agent/state.py 의 DiagnosisStatus 와 empty_run_fields,
skillops-agent/skillops_agent/config.py 의 튜닝 값, skillops-agent/skillops_agent/llm.py 의 온도
설정이다. DiagnosisStatus 는 화면과 RunStore 가 글자 그대로 읽는 9개 값이어야 하고,
empty_run_fields 의 키 순서와 기본값은 RunStore 기록 모양을 정한다. 튜닝 값 표는 파이프라인을
검증한 값을 고정한다. config.py 의 한도나 반복 횟수를 의도적으로 바꾸면 이 표와, 그 값에 기대는
skillops-agent/tests/test_flow_characterization.py, skillops-agent/tests/test_graph.py,
skillops-agent/tests/test_main_api.py 의 단언을 함께 고친다.
"""

import json

from skillops_agent import config
from skillops_agent.llm import make_llm
from skillops_agent.state import DiagnosisStatus, empty_run_fields

# 화면 라벨(frontend/src/utils/diagnosisView.js)이 아는 진단 상태 9개를 선언 순서대로 적는다.
STATUS_NAMES = [
    "QUEUED",
    "RUNNING",
    "VALIDATED",
    "BASELINE_FAILED",
    "VALIDATION_FAILED",
    "NO_SKILL_CHANGES",
    "INTENT_VIOLATION",
    "EVALUATED",
    "ERROR",
]


def test_diagnosis_status_has_the_nine_screen_values():
    names = [name for name in vars(DiagnosisStatus) if not name.startswith("_")]
    assert names == STATUS_NAMES
    assert all(getattr(DiagnosisStatus, name) == name for name in names)


def test_diagnosis_status_values_serialize_as_plain_strings():
    assert all(type(getattr(DiagnosisStatus, name)) is str for name in STATUS_NAMES)
    assert json.dumps({"diagnosis_status": DiagnosisStatus.ERROR}) == (
        '{"diagnosis_status": "ERROR"}'
    )


def test_empty_run_fields_keeps_key_order_and_defaults():
    assert list(empty_run_fields().items()) == [
        ("trace", None),
        ("diff", None),
        ("skill_versions", {}),
        ("snapshot_id", None),
        ("baseline_snapshot_id", None),
        ("execution_id", None),
        ("changes", []),
        ("diagnosis_status", "RUNNING"),
        ("diagnosis", None),
        ("preflight_run_ids", []),
        ("hypotheses", []),
        ("ablation_runs", []),
        ("root_cause", None),
        ("hypothesis_retry", 0),
        ("intent", None),
        ("candidates", []),
        ("candidate_retry", 0),
        ("recommended", None),
        ("proposal", None),
        ("approval_status", None),
        ("approval_token", None),
        ("smoke_passed", None),
        ("error", None),
    ]


def test_empty_run_fields_builds_new_containers_on_every_call():
    first, second = empty_run_fields(), empty_run_fields()
    assert first == second
    for key, value in first.items():
        if isinstance(value, (list, dict)):
            assert value is not second[key], key
    first["changes"].append({"change_no": 1})
    assert empty_run_fields()["changes"] == []


def test_tuning_defaults_match_the_values_the_pipeline_was_verified_with():
    """재시도 한도나 반복 횟수를 바꾸면 이 표부터 고친다."""
    assert {
        "MAX_STEPS_DEFAULT": config.MAX_STEPS_DEFAULT,
        "MAX_HYPOTHESIS_RETRIES": config.MAX_HYPOTHESIS_RETRIES,
        "MAX_CANDIDATE_RETRIES": config.MAX_CANDIDATE_RETRIES,
        "PREFLIGHT_REPEAT": config.PREFLIGHT_REPEAT,
        "INTENT_CHECK_REPEAT": config.INTENT_CHECK_REPEAT,
        "ABLATION_REPEAT": config.ABLATION_REPEAT,
        "VERIFY_REPEAT": config.VERIFY_REPEAT,
        "DEFAULT_ENTRY_SKILL_ID": config.DEFAULT_ENTRY_SKILL_ID,
        "RANK_FALLBACK_EVIDENCE_COUNT": config.RANK_FALLBACK_EVIDENCE_COUNT,
        "LLM_TEMPERATURE": config.LLM_TEMPERATURE,
    } == {
        "MAX_STEPS_DEFAULT": 40,
        "MAX_HYPOTHESIS_RETRIES": 3,
        "MAX_CANDIDATE_RETRIES": 3,
        "PREFLIGHT_REPEAT": 2,
        "INTENT_CHECK_REPEAT": 2,
        "ABLATION_REPEAT": 1,
        "VERIFY_REPEAT": 2,
        "DEFAULT_ENTRY_SKILL_ID": "order-flow",
        "RANK_FALLBACK_EVIDENCE_COUNT": 2,
        "LLM_TEMPERATURE": 0.0,
    }


def test_retry_limits_and_repeats_are_positive_integers():
    for value in (
        config.MAX_STEPS_DEFAULT,
        config.MAX_HYPOTHESIS_RETRIES,
        config.MAX_CANDIDATE_RETRIES,
        config.PREFLIGHT_REPEAT,
        config.INTENT_CHECK_REPEAT,
        config.ABLATION_REPEAT,
        config.VERIFY_REPEAT,
        config.RANK_FALLBACK_EVIDENCE_COUNT,
    ):
        assert type(value) is int and value > 0


def test_llm_factory_uses_the_configured_temperature(monkeypatch):
    # 기본 모델(gpt-5 계열)은 온도를 받지 않아 langchain_openai 가 값을 뺀다.
    # 온도를 받는 모델로 본다.
    monkeypatch.setattr("skillops_agent.llm.MODEL", "gpt-4.1-mini")
    assert make_llm().temperature == config.LLM_TEMPERATURE
