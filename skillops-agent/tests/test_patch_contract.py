"""Java MCP 응답 fixture와 Python 후보 판정의 선행 단계 계약을 확인한다."""

import json
from pathlib import Path

import pytest

from skillops_agent.contracts import ContractError, patch_contract_ok
from skillops_agent.nodes import _check_applied, _judge


@pytest.fixture
def dependency_response():
    """Java MCP 직렬화 테스트와 공유하는 선행 단계 위반 응답을 읽는다."""
    path = (
        Path(__file__).resolve().parents[2]
        / "docs/contracts/examples/apply-patch-dependency-violation.json"
    )
    return json.loads(path.read_text())


def test_dependency_only_violation_invalidates_passing_candidate(dependency_response):
    """모든 실행이 통과해도 선행 단계 위반만 있는 후보는 무효다."""
    patches = [{"skill": "payment", "base_version": 1, "patch": "diff"}]
    skills = _check_applied(dependency_response, "w_abc", patches)
    passed = [{"passed": True}]

    results, valid = _judge(passed, passed, passed, dependency_response, skills, [])

    assert results["fixes_failure"] is True
    assert results["keeps_old_orders"] == {"passed": 1, "total": 1}
    assert results["keeps_intent"] is True
    assert results["no_broken_links"] is False
    assert valid is False


def test_both_empty_lists_allow_contract_check(dependency_response):
    """두 연결 오류 목록이 명시적으로 비었을 때만 연결 검사를 통과한다."""
    dependency_response["dependency_violations"] = []
    assert patch_contract_ok(dependency_response) is True


@pytest.mark.parametrize("field", ["broken_links", "dependency_violations"])
@pytest.mark.parametrize("value", [None, {}, "", [None], [{}], [{"skill": "payment"}]])
def test_malformed_lists_are_contract_errors(dependency_response, field, value):
    """누락된 검사나 잘못된 오류 항목을 정상 빈 목록으로 간주하지 않는다."""
    dependency_response[field] = value
    with pytest.raises(ContractError, match="^invalid apply_patch response$"):
        patch_contract_ok(dependency_response)


def test_old_server_without_dependency_field_is_rejected(dependency_response):
    """구버전 서버가 선행 단계 검사 결과를 생략하면 평가 전에 중단한다."""
    del dependency_response["dependency_violations"]
    with pytest.raises(ContractError, match="^invalid apply_patch response$"):
        _check_applied(dependency_response, "w_abc", [{"skill": "payment"}])
