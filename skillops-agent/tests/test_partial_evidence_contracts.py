"""partial 진단 증거의 출처·완전성·원본 바이트 검증을 확인한다."""

import base64
from copy import deepcopy
from hashlib import sha256

import pytest

from skillops_agent.contracts import ContractError
from skillops_agent.partial_evidence import (
    MAX_FILE_BYTES,
    check_snapshot_file,
    manifest_files,
    prepare_partial_evidence,
)


@pytest.fixture
def evidence_case():
    """정상 파일과 오류 파일이 함께 있는 최소 manifest·목록·원문 응답을 만든다."""
    files = [
        {
            "path": f"skills/{skill}/SKILL.md",
            "hash": sha256(skill.encode()).hexdigest(),
            "size": len(skill),
        }
        for skill in ("order-flow", "payment")
    ]
    after = {"snapshot_id": "candidate", "hash": "candidate-hash", "files": files}
    before = {**deepcopy(after), "snapshot_id": "baseline", "hash": "baseline-hash"}
    listing = {
        "snapshot_id": "candidate",
        "snapshot_hash": "candidate-hash",
        "partial": True,
        "items": [{"skill_id": "order-flow", "version": 1, **files[0]}],
        "parse_errors": [
            {"path": files[1]["path"], "code": "INVALID_SKILL_FORMAT", "message": "bad metadata"}
        ],
    }
    response = {"snapshot_id": "candidate", **files[1], "encoding": "utf-8", "content": "payment"}
    return before, after, listing, response


def test_partial_listing_keeps_normal_metadata_and_parse_errors(evidence_case):
    """정상 항목과 오류 항목이 전체 manifest를 설명하면 둘을 함께 보존한다."""
    before, after, listing, _ = evidence_case
    result = prepare_partial_evidence(listing, before, after)
    assert result["metadata"] == listing
    listing["items"].clear()
    assert len(result["metadata"]["items"]) == 1


@pytest.mark.parametrize(
    "change",
    [
        {"partial": False},
        {"partial": "true"},
        {"parse_errors": []},
        {"parse_errors": ["bad"]},
        {"items": None},
        {"items": []},
        {"snapshot_id": "other"},
        {"snapshot_hash": "other"},
    ],
)
def test_inconsistent_or_untrusted_listing_is_rejected(evidence_case, change):
    """partial의 타입·출처·누락 항목을 검사하고 불완전한 응답을 정상화하지 않는다."""
    before, after, listing, _ = evidence_case
    listing.update(change)
    with pytest.raises(ContractError):
        prepare_partial_evidence(listing, before, after)


@pytest.mark.parametrize(
    "field,value",
    [
        ("version", True),
        ("version", 0),
        ("version", "1"),
        ("version", 2_147_483_648),
        ("hash", "wrong"),
        ("skill_id", "Bad_ID"),
        ("path", "skills/other/SKILL.md"),
    ],
)
def test_normal_metadata_is_not_trusted_without_checks(evidence_case, field, value):
    """partial 응답에 포함된 정상 항목도 버전·경로·파일 해시를 검증한다."""
    before, after, listing, _ = evidence_case
    listing["items"][0][field] = value
    with pytest.raises(ContractError):
        prepare_partial_evidence(listing, before, after)


def test_duplicate_or_conflicting_paths_are_rejected(evidence_case):
    """동일 파일이 정상 항목과 오류 항목에 동시에 나타나면 거부한다."""
    before, after, listing, _ = evidence_case
    listing["parse_errors"].append(
        {"path": listing["items"][0]["path"], "code": "INVALID_SKILL_FORMAT", "message": "conflict"}
    )
    with pytest.raises(ContractError):
        prepare_partial_evidence(listing, before, after)


@pytest.mark.parametrize(
    "field,value",
    [
        ("path", "skills/../SKILL.md"),
        ("path", "skills//SKILL.md"),
        ("hash", "wrong"),
        ("size", True),
        ("size", -1),
        ("size", MAX_FILE_BYTES + 1),
    ],
)
def test_invalid_manifest_file_is_rejected(evidence_case, field, value):
    """조회 전에 경로와 해시·크기 한도를 확인한다."""
    _, after, _, _ = evidence_case
    after["files"][0][field] = value
    with pytest.raises(ContractError):
        manifest_files(after)


def test_duplicate_manifest_path_is_rejected(evidence_case):
    """중복 경로를 합치면서 증거를 잃지 않도록 중복 manifest를 거부한다."""
    _, after, _, _ = evidence_case
    after["files"].append(deepcopy(after["files"][0]))
    with pytest.raises(ContractError):
        manifest_files(after)


@pytest.mark.parametrize(
    "field,value",
    [
        ("snapshot_id", "other"),
        ("path", "skills/other/SKILL.md"),
        ("hash", "wrong"),
        ("size", 0),
        ("size", True),
        ("encoding", "latin1"),
        ("content", "tampered"),
        ("content", None),
    ],
)
def test_tampered_file_response_is_rejected(evidence_case, field, value):
    """파일 응답의 참조와 실제 바이트를 독립적으로 확인한다."""
    _, after, _, response = evidence_case
    response[field] = value
    with pytest.raises(ContractError):
        check_snapshot_file(response, "candidate", after["files"][1])


@pytest.mark.parametrize("raw,encoding", [(b"a\r\nb\n", "utf-8"), (b"\xff\x00", "base64")])
def test_original_bytes_are_preserved(raw, encoding):
    """CRLF와 비 UTF-8 바이트를 정규화하거나 대체하지 않고 보존한다."""
    entry = {"path": "skills/payment/SKILL.md", "hash": sha256(raw).hexdigest(), "size": len(raw)}
    response = {
        "snapshot_id": "candidate",
        **entry,
        "encoding": encoding,
        "content": raw.decode() if encoding == "utf-8" else base64.b64encode(raw).decode(),
    }
    assert check_snapshot_file(response, "candidate", entry) == response


def test_malformed_base64_is_rejected(evidence_case):
    """검증할 수 없는 base64를 원본 바이트로 취급하지 않는다."""
    _, after, _, response = evidence_case
    response.update(encoding="base64", content="%%%")
    with pytest.raises(ContractError):
        check_snapshot_file(response, "candidate", after["files"][1])


@pytest.mark.parametrize(
    "files",
    [
        [{"path": f"skills/a/{n}", "hash": "a" * 64, "size": 0} for n in range(257)],
        [{"path": f"skills/a/{n}", "hash": "a" * 64, "size": MAX_FILE_BYTES} for n in range(6)],
    ],
)
def test_manifest_file_count_and_total_byte_limits(files):
    """파일 개수와 snapshot 합계 한도를 독립적으로 제한한다."""
    with pytest.raises(ContractError):
        manifest_files({"files": files})
