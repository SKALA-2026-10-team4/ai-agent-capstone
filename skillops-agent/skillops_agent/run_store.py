"""Durable request deduplication and queryable graph progress for the local agent."""

import hashlib
import json
import os
import sqlite3
import uuid
from datetime import UTC, datetime
from pathlib import Path


class RunStore:
    def __init__(self, path=None):
        self.path = Path(
            path
            or os.getenv(
                "SKILLOPS_RUN_DB", str(Path(__file__).resolve().parents[1] / ".data/runs.sqlite3")
            )
        )
        self.path.parent.mkdir(parents=True, exist_ok=True)
        with self.connect() as db:
            db.execute(
                "CREATE TABLE IF NOT EXISTS diagnosis_runs ("
                "id TEXT PRIMARY KEY, change_id TEXT UNIQUE, fingerprint TEXT NOT NULL, "
                "trigger_json TEXT NOT NULL, state_json TEXT NOT NULL, created_at TEXT NOT NULL)"
            )

    def connect(self):
        db = sqlite3.connect(self.path, timeout=10)
        db.row_factory = sqlite3.Row
        return db

    def accept(self, trigger):
        fingerprint = hashlib.sha256(json.dumps(trigger, sort_keys=True).encode()).hexdigest()
        change = trigger.get("change_id")
        with self.connect() as db:
            db.execute("BEGIN IMMEDIATE")
            row = (
                db.execute("SELECT * FROM diagnosis_runs WHERE change_id=?", (change,)).fetchone()
                if change
                else None
            )
            if row:
                if row["fingerprint"] != fingerprint:
                    raise ValueError("change_id already refers to a different request")
                return row["id"], False
            thread = str(uuid.uuid4())
            state = {"diagnosis_status": "QUEUED", "events": [], "error": None}
            db.execute(
                "INSERT INTO diagnosis_runs VALUES (?,?,?,?,?,?)",
                (
                    thread,
                    change,
                    fingerprint,
                    json.dumps(trigger),
                    json.dumps(state),
                    datetime.now(UTC).isoformat(),
                ),
            )
            return thread, True

    def update(self, thread, updates):
        with self.connect() as db:
            db.execute("BEGIN IMMEDIATE")
            row = db.execute(
                "SELECT state_json FROM diagnosis_runs WHERE id=?", (thread,)
            ).fetchone()
            if row:
                state = json.loads(row["state_json"])
                state.update(updates)
                db.execute(
                    "UPDATE diagnosis_runs SET state_json=? WHERE id=?",
                    (json.dumps(state, ensure_ascii=False), thread),
                )

    def get(self, thread):
        with self.connect() as db:
            row = db.execute("SELECT * FROM diagnosis_runs WHERE id=?", (thread,)).fetchone()
        if not row:
            return None
        return {
            "thread_id": row["id"],
            "change_id": row["change_id"],
            "trigger": json.loads(row["trigger_json"]),
            "created_at": row["created_at"],
            **json.loads(row["state_json"]),
        }

    def list(self):
        with self.connect() as db:
            ids = [
                r["id"]
                for r in db.execute(
                    "SELECT id FROM diagnosis_runs ORDER BY created_at DESC LIMIT 100"
                )
            ]
        return [self.get(thread) for thread in ids]

    def recover(self):
        for run in self.list_all():
            if run.get("diagnosis_status") in ("QUEUED", "RUNNING"):
                self.update(
                    run["thread_id"],
                    {
                        "diagnosis_status": "ERROR",
                        "error": "Agent restarted during execution; this run was interrupted.",
                    },
                )

    def list_all(self):
        with self.connect() as db:
            ids = [r["id"] for r in db.execute("SELECT id FROM diagnosis_runs")]
        return [self.get(thread) for thread in ids]
