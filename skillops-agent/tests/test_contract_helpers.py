"""C 계약 읽기·검증·patch 도우미(contracts.py)의 경계와 오류 문구를 확인한다.

보호하는 대상은 skillops-agent/skillops_agent/contracts.py 의 실행 기록 읽기(error_event),
run_scenarios 결과 펼치기(run_ids_of, failures_of, all_passed), 재현 시나리오 ID, snapshot manifest
와 skill 경로, list_skills 와 diff_skill 응답 검사, 후보 patch 생성(unified_patch)이다. 지운
trace_context 가 다시 생기지 않는지도 본다. 오류 문구는 진단 기록의 error 에 그대로 남으므로 글자
그대로 단언한다. 문구를 바꾸면 이 테스트와 skillops-agent/tests/test_flow_characterization.py 를
함께 고친다. CHECK_FAILURE_PREFIX 는
service/src/main/java/com/capstone4/skillops/scenario/ScenarioGrader.java 의 문구와 같아야 한다.
"""

import pytest

from skillops_agent import contracts
from skillops_agent.contracts import (
    CHECK_FAILURE_PREFIX,
    ContractError,
    all_passed,
    check_diff_evidence,
    check_manifest,
    check_skill_listing,
    error_event,
    failures_of,
    file_hashes,
    replay_scenario_id,
    run_ids_of,
    skill_file_path,
    skill_of_file,
    unified_patch,
)

# 통과한 시나리오 하나와 두 번 모두 실패한 시나리오 하나로 된 run_scenarios 결과 견본이다.
RESULTS = [
    {
        "scenario_id": "g-01",
        "passed": True,
        "run_ids": ["r_a", "r_b"],
        "failures": [],
    },
    {
        "scenario_id": "g-02",
        "passed": False,
        "run_ids": ["r_c", "r_d"],
        "failures": [{"run_id": "r_c", "reason": "x"}, {"run_id": "r_d", "reason": "y"}],
    },
]


# ── 실행 기록 ─────────────────────────────────────────────────────────────────


def test_error_event_returns_first_execution_error():
    first = {"kind": "EXECUTION_ERROR", "data": {"category": "A"}}
    trace = {
        "events": [
            {"kind": "SKILL_STARTED", "data": {}},
            first,
            {"kind": "EXECUTION_ERROR", "data": {"category": "B"}},
        ]
    }
    assert error_event(trace) is first


@pytest.mark.parametrize("trace", [{}, {"events": []}, {"events": [{"kind": "SKILL_STARTED"}]}])
def test_error_event_without_error_is_an_empty_dict_not_none(trace):
    assert error_event(trace) == {}
    assert error_event(trace) is not None


def test_trace_context_was_removed():
    """어디서도 쓰지 않던 trace_context 를 지운 상태를 지킨다."""
    assert not hasattr(contracts, "trace_context")


def test_check_failure_prefix_matches_c_grader_text():
    assert CHECK_FAILURE_PREFIX == "확인 조건 실패:"


# ── run_scenarios 결과 ────────────────────────────────────────────────────────


def test_results_are_flattened_in_scenario_order():
    assert run_ids_of(RESULTS) == ["r_a", "r_b", "r_c", "r_d"]
    assert failures_of(RESULTS) == RESULTS[1]["failures"]
    assert all_passed(RESULTS) is False
    assert all_passed(RESULTS[:1]) is True


def test_empty_results_pass_and_have_no_evidence():
    assert all_passed([]) is True
    assert run_ids_of([]) == []
    assert failures_of([]) == []


def test_replay_scenario_id_prefixes_the_execution():
    execution = "33333333-3333-4333-8333-333333333333"
    assert replay_scenario_id(execution) == "f-33333333-3333-4333-8333-333333333333"


# ── snapshot manifest 와 skill 경로 ────────────────────────────────────────────


def test_file_hashes_maps_paths_to_hashes():
    manifest = {"files": [{"path": "skills/a/SKILL.md", "hash": "1"}, {"path": "x", "hash": "2"}]}
    assert file_hashes(manifest) == {"skills/a/SKILL.md": "1", "x": "2"}
    assert file_hashes({"files": []}) == {}


def test_skill_paths_round_trip():
    assert skill_file_path("inventory-check") == "skills/inventory-check/SKILL.md"
    assert skill_of_file(skill_file_path("inventory-check")) == "inventory-check"


@pytest.mark.parametrize(
    "path",
    [
        "skills/x/resources/a.md",
        "skills/x/README.md",
        "other/x/SKILL.md",
        "skills/SKILL.md",
        "SKILL.md",
    ],
)
def test_non_skill_paths_are_none(path):
    assert skill_of_file(path) is None


def test_empty_skill_name_keeps_the_skill_path_shape():
    assert skill_of_file("skills//SKILL.md") == ""


def test_check_manifest_returns_the_manifest():
    manifest = {"snapshot_id": "s1", "hash": "h"}
    assert check_manifest(manifest, "s1", "candidate") is manifest


@pytest.mark.parametrize(
    "manifest",
    [{"snapshot_id": "s2", "hash": "h"}, {"snapshot_id": "s1", "hash": ""}, {"snapshot_id": "s1"}],
)
@pytest.mark.parametrize("side", ["candidate", "baseline"])
def test_check_manifest_rejects_wrong_or_unhashed_snapshot(manifest, side):
    with pytest.raises(ContractError) as error:
        check_manifest(manifest, "s1", side)
    assert str(error.value) == f"invalid {side} snapshot manifest"


def _listing(**changes):
    """정상 list_skills 응답에 changes 를 덮어쓴 사본을 만든다."""
    listing = {
        "snapshot_id": "s1",
        "snapshot_hash": "h",
        "items": [{"skill_id": "b", "version": 2}, {"skill_id": "a", "version": 1}],
        "partial": False,
        "parse_errors": [],
    }
    listing.update(changes)
    return listing


def test_check_skill_listing_keeps_item_order():
    versions = check_skill_listing(_listing(), "s1", "h")
    assert list(versions.items()) == [("b", 2), ("a", 1)]


@pytest.mark.parametrize(
    ("listing", "message"),
    [
        (_listing(snapshot_id="s2"), "skill list and snapshot differ"),
        (_listing(snapshot_hash="other"), "skill list and snapshot differ"),
        (_listing(partial=True), "candidate skill metadata is incomplete"),
        (_listing(parse_errors=["bad"]), "candidate skill metadata is incomplete"),
        (_listing(snapshot_id="s2", partial=True), "skill list and snapshot differ"),
    ],
)
def test_check_skill_listing_errors(listing, message):
    with pytest.raises(ContractError) as error:
        check_skill_listing(listing, "s1", "h")
    assert str(error.value) == message


# ── diff_skill 증거 ───────────────────────────────────────────────────────────

# 고정한 두 파일의 기대값과, check_diff_evidence 가 내는 오류 문구 세 가지다.
BEFORE = {"snapshot_id": "base", "file_hash": "old", "snapshot_hash": "hb"}
AFTER = {"snapshot_id": "cand", "file_hash": "new", "snapshot_hash": "hc"}
MISMATCH = "diff evidence does not match the fixed snapshots"
NO_HUNKS = "changed file returned no C hunks"
BAD_HUNK = "C hunk identity/patch missing"


def _diff(**changes):
    """정상 diff_skill 응답에 changes 를 덮어쓴 사본을 만든다."""
    diff = {
        "before": dict(BEFORE),
        "after": dict(AFTER),
        "hunks": [{"hunk_id": "h1", "diff": "@@ -1 +1 @@\n-a\n+b\n"}],
    }
    diff.update(changes)
    return diff


def test_check_diff_evidence_returns_hunks():
    diff = _diff()
    assert check_diff_evidence(diff, BEFORE, AFTER) is diff["hunks"]


@pytest.mark.parametrize(
    ("diff", "message"),
    [
        (_diff(before={**BEFORE, "file_hash": "x"}), MISMATCH),
        (_diff(after={**AFTER, "snapshot_id": "x"}), MISMATCH),
        (_diff(after={**AFTER, "snapshot_hash": "x"}), MISMATCH),
        ({"hunks": [{"hunk_id": "h", "diff": "d"}]}, MISMATCH),
        (_diff(hunks=[]), NO_HUNKS),
        (_diff(hunks=None), NO_HUNKS),
        (_diff(hunks="@@"), NO_HUNKS),
        (_diff(hunks=[{"hunk_id": "", "diff": "d"}]), BAD_HUNK),
        (_diff(hunks=[{"hunk_id": "h", "diff": "d"}, {"hunk_id": "h2"}]), BAD_HUNK),
        (_diff(before={}, hunks=[]), MISMATCH),
    ],
)
def test_check_diff_evidence_errors_in_check_order(diff, message):
    """두 파일 대조, hunk 목록, hunk 칸 순서로 검사해 먼저 걸린 문구가 나는지 본다."""
    with pytest.raises(ContractError) as error:
        check_diff_evidence(diff, BEFORE, AFTER)
    assert str(error.value) == message


def test_check_diff_evidence_side_must_be_an_object():
    """before 칸이 None 이면 ContractError 가 아니라 AttributeError 가 나는 현재 동작을 고정한다."""
    with pytest.raises(AttributeError):
        check_diff_evidence(_diff(before=None), BEFORE, AFTER)


# ── 후보 patch ────────────────────────────────────────────────────────────────


def test_unified_patch_has_headers_and_trailing_newline():
    patch = unified_patch("inventory-check", "a\nb\n", "a\nc\n")
    assert patch == (
        "--- a/skills/inventory-check/SKILL.md\n"
        "+++ b/skills/inventory-check/SKILL.md\n"
        "@@ -1,2 +1,2 @@\n"
        " a\n"
        "-b\n"
        "+c\n"
    )


def test_unified_patch_of_identical_text_is_empty():
    assert unified_patch("inventory-check", "a\nb\n", "a\nb\n") == ""
    assert unified_patch("inventory-check", "a\nb\n", "a\nb") == ""
