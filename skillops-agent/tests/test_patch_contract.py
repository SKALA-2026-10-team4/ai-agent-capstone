"""Java MCP 응답 fixture와 Python 후보 판정의 선행 단계 계약을 확인한다."""

import json
from pathlib import Path

import pytest

from skillops_agent.contracts import ContractError, patch_contract_ok, patch_metadata_ok
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


@pytest.fixture
def metadata_response():
    """Java MCP 테스트가 사용하는 owner 누락 응답을 읽는다."""
    path = (
        Path(__file__).resolve().parents[2]
        / "docs/contracts/examples/apply-patch-metadata-error.json"
    )
    return json.loads(path.read_text())


def test_metadata_error_invalidates_candidate_without_changing_link_result(metadata_response):
    """모든 실행과 연결 검사가 통과해도 필수 정보 오류가 있으면 무효다."""
    skills = _check_applied(metadata_response, "w_abc", [{"skill": "payment"}])
    passed = [{"passed": True}]

    results, valid = _judge(passed, passed, passed, metadata_response, skills, [])

    assert results["no_broken_links"] is True
    assert results["metadata_valid"] is False
    assert valid is False


def test_metadata_check_requires_explicit_empty_list(metadata_response):
    """명시적 빈 목록만 필수 정보 검사를 통과시킨다."""
    metadata_response["metadata_errors"] = []
    assert patch_metadata_ok(metadata_response) is True
    passed = [{"passed": True}]
    results, valid = _judge(passed, passed, passed, metadata_response, {"payment"}, [])
    assert results["metadata_valid"] is True
    assert valid is True


@pytest.mark.parametrize("value", [None, {}, "", [None], [{}], [{"skill": "payment"}]])
def test_malformed_metadata_list_is_contract_error(metadata_response, value):
    """메타데이터 검사 응답의 잘못된 타입을 검사 통과로 바꾸지 않는다."""
    metadata_response["metadata_errors"] = value
    with pytest.raises(ContractError, match="^invalid apply_patch response$"):
        _check_applied(metadata_response, "w_abc", [{"skill": "payment"}])


@pytest.mark.parametrize("field", ["skill", "field", "code", "message"])
@pytest.mark.parametrize("value", [None, "", " ", 1])
def test_malformed_metadata_item_is_contract_error(metadata_response, field, value):
    """오류 항목의 필수 문자열도 검증한다."""
    metadata_response["metadata_errors"][0][field] = value
    with pytest.raises(ContractError, match="^invalid apply_patch response$"):
        patch_metadata_ok(metadata_response)


def test_old_server_without_metadata_field_is_rejected(metadata_response):
    """메타데이터 검사를 하지 않는 구버전 서버 응답은 평가 전에 중단한다."""
    del metadata_response["metadata_errors"]
    with pytest.raises(ContractError, match="^invalid apply_patch response$"):
        _check_applied(metadata_response, "w_abc", [{"skill": "payment"}])
