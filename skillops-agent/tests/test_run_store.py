"""skillops-agent/skillops_agent/run_store.py 의 RunStore 동작을 보호하는 테스트다.

앞의 네 테스트는 핵심 약속을 본다. 동시에 들어온 같은 change_id 는 한 번만 접수되고, 같은 change_id
에 다른 내용이 오면 거절된다. 재시작 뒤 recover 는 진행 중 실행을 ERROR 로 바꾸되 events 를 지키고,
끝난 결과는 바꾸지 않는다. 뒤의 특성 테스트는 저장 형식(fingerprint, trigger_json, state_json,
created_at), 조회 결과의 칸 순서, list 의 한도 100 과 정렬, list_all 의 정렬, recover 대상 상태를
고정한다. skillops-agent/skillops_agent/main.py 의 중복 접수(409)와 진행 조회가 이 약속에 기댄다.
"""

import hashlib
import json
import os
import sqlite3
import uuid
from concurrent.futures import ThreadPoolExecutor
from contextlib import closing
from datetime import UTC, datetime, timedelta
from pathlib import Path

import pytest

from skillops_agent.run_store import RunStore


def test_concurrent_duplicate_delivery_starts_only_one_run(tmp_path):
    """스레드 16개가 같은 change_id 를 동시에 접수해도 새 실행은 하나만 생긴다."""
    path = tmp_path / "runs.sqlite3"
    RunStore(path)

    def accept(_):
        """스레드마다 새 RunStore 로 같은 change_id 를 접수한다."""
        return RunStore(path).accept(
            {"change_id": "same-change", "candidate_snapshot_id": "snapshot"}
        )

    with ThreadPoolExecutor(max_workers=8) as workers:
        results = list(workers.map(accept, range(16)))
    assert len({r[0] for r in results}) == 1
    assert sum(r[1] for r in results) == 1


def test_same_change_different_payload_is_rejected(tmp_path):
    store = RunStore(tmp_path / "runs.sqlite3")
    store.accept({"change_id": "a", "candidate_snapshot_id": "one"})
    with pytest.raises(ValueError):
        store.accept({"change_id": "a", "candidate_snapshot_id": "two"})


def test_progress_survives_restart_and_interruption_is_not_success(tmp_path):
    """진행 기록은 재시작 뒤에도 남고, recover 는 RUNNING 실행을 성공이 아닌 ERROR 로 바꾼다.

    같은 change_id 의 재전송은 그 실행의 thread 를 그대로 돌려준다.
    """
    path = tmp_path / "runs.sqlite3"
    store = RunStore(path)
    thread, _ = store.accept({"change_id": "a"})
    store.update(thread, {"diagnosis_status": "RUNNING", "events": [{"msg": "verifying"}]})
    restarted = RunStore(path)
    restarted.recover()
    assert restarted.get(thread)["diagnosis_status"] == "ERROR"
    assert restarted.get(thread)["events"] == [{"msg": "verifying"}]
    assert restarted.accept({"change_id": "a"}) == (thread, False)


def test_completed_result_is_preserved(tmp_path):
    """끝난 실행(VALIDATED)은 recover 가 바꾸지 않는다."""
    path = tmp_path / "runs.sqlite3"
    store = RunStore(path)
    thread, _ = store.accept({"change_id": "a"})
    store.update(thread, {"diagnosis_status": "VALIDATED"})
    RunStore(path).recover()
    assert store.get(thread)["diagnosis_status"] == "VALIDATED"


# ── 저장 형식·조회·복구 특성 테스트 ──────────────────────────────────────────────

# skillops-agent/skillops_agent/run_store.py 의 INTERRUPTED_ERROR 와 같은 문구다. 글자가 바뀌면
# 이 테스트가 알린다.
INTERRUPTED = "Agent restarted during execution; this run was interrupted."


def raw_row(path, thread):
    """diagnosis_runs 행을 sqlite3 로 그대로 읽는다."""
    with closing(sqlite3.connect(path)) as db:
        db.row_factory = sqlite3.Row
        return dict(db.execute("SELECT * FROM diagnosis_runs WHERE id=?", (thread,)).fetchone())


def set_created_at(path, stamps):
    """thread 별 created_at 을 SQL 로 바꾼다."""
    with closing(sqlite3.connect(path)) as db, db:
        for thread, stamp in stamps.items():
            db.execute("UPDATE diagnosis_runs SET created_at=? WHERE id=?", (stamp, thread))


def test_store_path_comes_from_argument_or_env(tmp_path):
    """경로 인자가 없으면 SKILLOPS_RUN_DB 를 쓰고, 상위 폴더를 만든다."""
    nested = tmp_path / "a" / "b" / "runs.sqlite3"
    assert RunStore(nested).path == nested
    assert nested.parent.is_dir()
    assert RunStore(str(nested)).path == nested
    assert RunStore().path == Path(os.environ["SKILLOPS_RUN_DB"])


def test_new_row_stores_fingerprint_trigger_and_queued_state(tmp_path):
    """fingerprint 는 sort_keys JSON 의 sha256, trigger 는 ASCII JSON, 첫 state 는 QUEUED 다."""
    path = tmp_path / "runs.sqlite3"
    trigger = {
        "type": "skill_saved",
        "change_id": "변경-1",
        "candidate_snapshot_id": "스냅샷",
        "changed_paths": ["skills/결제/SKILL.md"],
    }
    thread, created = RunStore(path).accept(trigger)
    row = raw_row(path, thread)
    assert created is True
    assert str(uuid.UUID(thread)) == thread
    assert row["change_id"] == "변경-1"
    expected = hashlib.sha256(json.dumps(trigger, sort_keys=True).encode()).hexdigest()
    assert row["fingerprint"] == expected
    assert row["trigger_json"] == json.dumps(trigger)
    assert row["state_json"] == json.dumps(
        {"diagnosis_status": "QUEUED", "events": [], "error": None}
    )
    assert datetime.fromisoformat(row["created_at"]).utcoffset() == timedelta(0)


def test_get_returns_row_columns_then_state(tmp_path):
    """get 은 thread_id, change_id, trigger, created_at 뒤에 state 를 펼친다."""
    path = tmp_path / "runs.sqlite3"
    store = RunStore(path)
    thread, _ = store.accept({"change_id": "a", "type": "failure"})
    run = store.get(thread)
    assert list(run.items()) == [
        ("thread_id", thread),
        ("change_id", "a"),
        ("trigger", {"change_id": "a", "type": "failure"}),
        ("created_at", raw_row(path, thread)["created_at"]),
        ("diagnosis_status", "QUEUED"),
        ("events", []),
        ("error", None),
    ]


def test_requests_without_change_id_always_start_new_runs(tmp_path):
    """change_id 가 없으면 같은 trigger 도 매번 새 실행이다."""
    store = RunStore(tmp_path / "runs.sqlite3")
    trigger = {"type": "failure", "execution_id": "e"}
    first, second = store.accept(trigger), store.accept({**trigger, "change_id": None})
    assert first[1] is True and second[1] is True
    assert first[0] != second[0]
    assert store.get(first[0])["change_id"] is None


def test_unknown_thread_is_none_and_update_is_ignored(tmp_path):
    """모르는 thread 는 get 이 None 이고 update 는 아무것도 만들지 않는다."""
    store = RunStore(tmp_path / "runs.sqlite3")
    assert store.get("missing") is None
    store.update("missing", {"diagnosis_status": "RUNNING"})
    assert store.get("missing") is None
    assert store.list_all() == []
    assert store.list() == []


def test_update_merges_into_state_and_state_overrides_row_columns(tmp_path):
    """update 는 state 에 합치고, state 의 trigger 가 행 칸 trigger 를 덮는다."""
    store = RunStore(tmp_path / "runs.sqlite3")
    thread, _ = store.accept({"change_id": "a", "type": "failure"})
    store.update(thread, {"diagnosis_status": "RUNNING", "trigger": {"type": "from-state"}})
    store.update(thread, {"events": [{"msg": "one"}]})
    run = store.get(thread)
    assert run["trigger"] == {"type": "from-state"}
    assert list(run) == [
        "thread_id",
        "change_id",
        "trigger",
        "created_at",
        "diagnosis_status",
        "events",
        "error",
    ]
    assert (run["diagnosis_status"], run["events"], run["error"]) == (
        "RUNNING",
        [{"msg": "one"}],
        None,
    )


def test_korean_state_round_trips_and_is_stored_unescaped(tmp_path):
    """한글 events 와 error 가 그대로 돌아오고 state_json 에는 이스케이프 없이 저장된다."""
    path = tmp_path / "runs.sqlite3"
    store = RunStore(path)
    thread, _ = store.accept({"change_id": "a"})
    events = [{"at": "2026-10-22T00:00:00+00:00", "msg": "진단을 시작했습니다"}]
    store.update(thread, {"events": events, "error": "실패: 결제"})
    assert store.get(thread)["events"] == events
    assert store.get(thread)["error"] == "실패: 결제"
    assert "진단을 시작했습니다" in raw_row(path, thread)["state_json"]


def test_list_returns_newest_first_and_at_most_100(tmp_path):
    """list 는 created_at 내림차순 최대 100건이고, 넘치면 가장 오래된 것이 빠진다."""
    path = tmp_path / "runs.sqlite3"
    store = RunStore(path)
    threads = [store.accept({"change_id": f"c-{i}"})[0] for i in range(101)]
    start = datetime(2026, 10, 22, tzinfo=UTC)
    stamps = {
        t: (start + timedelta(minutes=i * 37 % 101)).isoformat() for i, t in enumerate(threads)
    }
    set_created_at(path, stamps)
    runs = store.list()
    newest_first = sorted(threads, key=lambda t: stamps[t], reverse=True)
    assert [r["thread_id"] for r in runs] == newest_first[:100]
    assert newest_first[-1] not in {r["thread_id"] for r in runs}
    assert runs == [store.get(t) for t in newest_first[:100]]


def test_list_all_returns_every_run_in_thread_id_order(tmp_path):
    """list_all 은 한도 없이 모든 실행을 thread_id 순서로 돌려준다."""
    store = RunStore(tmp_path / "runs.sqlite3")
    threads = [store.accept({"change_id": f"c-{i}"})[0] for i in range(101)]
    runs = store.list_all()
    assert [r["thread_id"] for r in runs] == sorted(threads)
    assert runs == [store.get(t) for t in sorted(threads)]


def test_recover_marks_only_queued_and_running_as_interrupted(tmp_path):
    """recover 는 QUEUED 와 RUNNING 만 ERROR 와 고정 문구로 바꾸고 나머지는 그대로 둔다."""
    path = tmp_path / "runs.sqlite3"
    store = RunStore(path)
    statuses = ["QUEUED", "RUNNING", "VALIDATED", "ERROR", "EVALUATED", "BASELINE_FAILED"]
    threads = {}
    for status in statuses:
        thread, _ = store.accept({"change_id": status})
        if status != "QUEUED":
            store.update(thread, {"diagnosis_status": status, "error": f"{status} 원래 값"})
        threads[status] = thread
    before = {status: store.get(thread) for status, thread in threads.items()}
    RunStore(path).recover()
    for status, thread in threads.items():
        run = store.get(thread)
        if status in ("QUEUED", "RUNNING"):
            assert run == {**before[status], "diagnosis_status": "ERROR", "error": INTERRUPTED}
        else:
            assert run == before[status]
