from concurrent.futures import ThreadPoolExecutor

import pytest

from skillops_agent.run_store import RunStore


def test_concurrent_duplicate_delivery_starts_only_one_run(tmp_path):
    path = tmp_path / "runs.sqlite3"
    RunStore(path)

    def accept(_):
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
    path = tmp_path / "runs.sqlite3"
    store = RunStore(path)
    thread, _ = store.accept({"change_id": "a"})
    store.update(thread, {"diagnosis_status": "VALIDATED"})
    RunStore(path).recover()
    assert store.get(thread)["diagnosis_status"] == "VALIDATED"
