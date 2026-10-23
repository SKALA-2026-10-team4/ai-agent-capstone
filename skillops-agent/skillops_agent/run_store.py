"""진단 요청의 접수, 중복 판정, 진행 state 를 SQLite 파일에 저장하고 읽는다.

테이블 diagnosis_runs 한 행이 진단 한 건이다. 행에는 thread_id(id 칸), change_id, 중복 판정
fingerprint, 요청 trigger, 진행 state, 접수 시각이 들어간다. 파일 DB 에 두는 이유는 프로세스가 다시
떠도 접수 기록과 진행 상황이 남고, 같은 변경의 재전송을 알아보기 위해서다.

연결부는 다음과 같다.
- skillops-agent/skillops_agent/main.py 의 diagnose 가 accept 를, _run_pipeline 이 update 를,
  get_run_events 가 get 을, list_runs 가 list 를, lifespan 이 recover 를 부른다.
- skillops-agent/skillops_agent/graph.py 의 guarded 가 노드가 끝날 때마다 update 로 부분 결과를
  기록한다. 그래서 파이프라인이 도는 동안에도 진행 상황을 조회할 수 있다.
- 화면은 service/src/main/java/com/capstone4/skillops/diagnosis/DiagnosisController.java 를 거쳐
  get 과 list 결과를 읽는다.

조회 한도, sqlite 대기 시간, 복구 대상 상태와 문구, DB 경로는 아래 모듈 상수에서 바꾼다.
"""

import hashlib
import json
import os
import sqlite3
import uuid
from datetime import UTC, datetime
from pathlib import Path

from .state import DiagnosisStatus

# DB 파일 경로를 바꾸는 환경 변수 이름이다. skillops-agent/tests/conftest.py 가 테스트마다 임시
# 경로를 넣는다.
RUN_DB_ENV = "SKILLOPS_RUN_DB"
# 환경 변수가 없을 때 쓰는 DB 경로다. skillops-agent/.data/runs.sqlite3 를 가리킨다.
DEFAULT_DB_PATH = Path(__file__).resolve().parents[1] / ".data/runs.sqlite3"
# 다른 연결이 잠금을 쥐고 있을 때 기다리는 최대 시간(초)이다.
SQLITE_TIMEOUT_SECONDS = 10
# list() 가 돌려주는 최근 진단의 최대 개수다. GET /runs 목록 화면에 쓰인다.
RECENT_RUNS_LIMIT = 100
# recover() 가 중단된 것으로 보는 상태다. 접수 직후(QUEUED)와 노드 실행 중(RUNNING)이 여기에 든다.
ACTIVE_STATUSES = (DiagnosisStatus.QUEUED, DiagnosisStatus.RUNNING)
# recover() 가 중단된 실행의 error 칸에 남기는 문구다. 테스트가 이 문구를 글자 그대로 비교한다.
INTERRUPTED_ERROR = "Agent restarted during execution; this run was interrupted."
# diagnosis_runs 테이블이다. change_id 는 UNIQUE 라서 같은 변경이 두 행이 되지 않는다. SQLite 의
# UNIQUE 는 NULL 을 여러 개 허락하므로 change_id 가 없는 요청은 늘 새 행이 된다.
_SCHEMA = (
    "CREATE TABLE IF NOT EXISTS diagnosis_runs ("
    "id TEXT PRIMARY KEY, change_id TEXT UNIQUE, fingerprint TEXT NOT NULL, "
    "trigger_json TEXT NOT NULL, state_json TEXT NOT NULL, created_at TEXT NOT NULL)"
)


def _fingerprint(trigger):
    """trigger 의 중복 판정 키를 만든다. sort_keys JSON 의 sha256 hex 다.

    이미 저장한 행과 재전송 이벤트의 키가 같아야 하므로 직렬화 옵션을 바꾸지 않는다. accept 가 쓴다.
    """
    return hashlib.sha256(json.dumps(trigger, sort_keys=True).encode()).hexdigest()


def _queued_state():
    """접수 직후의 진단 state 를 새로 만든다.

    diagnosis_status 는 QUEUED, events 는 빈 목록, error 는 None 이다. accept 가 새 행에 넣는다.
    """
    return {"diagnosis_status": DiagnosisStatus.QUEUED, "events": [], "error": None}


def _row_to_run(row):
    """diagnosis_runs 행 하나를 조회 결과 dict 로 바꾼다.

    행 칸(thread_id, change_id, trigger, created_at)을 먼저 넣고 그 뒤에 state 를 펼쳐
    덮는다. 그래서 state 에 같은 이름(trigger 등)이 있으면 state 값이 이긴다. get 과 _query_runs 가
    쓴다.
    """
    return {
        "thread_id": row["id"],
        "change_id": row["change_id"],
        "trigger": json.loads(row["trigger_json"]),
        "created_at": row["created_at"],
        **json.loads(row["state_json"]),
    }


class RunStore:
    """SQLite 파일 하나에 진단 실행을 저장하고 읽는 저장소다.

    인스턴스는 경로 말고 상태를 들고 있지 않아서, skillops-agent/skillops_agent/main.py 와
    skillops-agent/skillops_agent/graph.py 는 쓸 때마다 RunStore() 를 새로 만든다. 메서드마다
    connect() 로 새 연결을 열고, 쓰기는 BEGIN IMMEDIATE 로 쓰기 잠금을 먼저 잡는다.
    """

    def __init__(self, path=None):
        """DB 경로를 정하고 상위 폴더와 diagnosis_runs 테이블을 만든다.

        path 가 없으면 환경 변수 SKILLOPS_RUN_DB 를, 그것도 없으면 DEFAULT_DB_PATH 를 쓴다. 환경
        변수는 인스턴스를 만들 때마다 읽으므로 테스트가 테스트마다 다른 DB 를 쓸 수 있다.
        """
        self.path = Path(path or os.getenv(RUN_DB_ENV, str(DEFAULT_DB_PATH)))
        self.path.parent.mkdir(parents=True, exist_ok=True)
        with self.connect() as db:
            db.execute(_SCHEMA)

    def connect(self):
        """이 DB 에 새 sqlite 연결을 열어 돌려준다.

        행을 칸 이름으로 읽도록 row_factory 를 sqlite3.Row 로 둔다. 잠금을 만나면
        SQLITE_TIMEOUT_SECONDS 만큼 기다린다. 부르는 쪽의 with 블록은 트랜잭션을 커밋하거나 되돌릴
        뿐 연결을 닫지 않는다.
        """
        db = sqlite3.connect(self.path, timeout=SQLITE_TIMEOUT_SECONDS)
        db.row_factory = sqlite3.Row
        return db

    def accept(self, trigger):
        """진단 요청 하나를 접수하고 (thread_id, 새로 만들었는지) 를 돌려준다.

        change_id 가 있으면 같은 change_id 의 행을 찾는다. fingerprint 까지 같으면 재전송으로 보고
        기존 thread_id 와 False 를 돌려주고, 다르면 ValueError 를 던진다.
        skillops-agent/skillops_agent/main.py 의 diagnose 가 이 ValueError 를 409 로 바꾼다. 찾는
        행이 없거나 change_id 가 없으면 uuid4 thread_id 로 QUEUED 행을 넣고 True 를 돌려준다. BEGIN
        IMMEDIATE 로 쓰기 잠금을 먼저 잡아, 동시에 들어온 같은 change_id 가 둘 다 새 행을 만들지
        못하게 한다. trigger_json 은 json.dumps 기본값(ASCII 이스케이프)으로 저장한다.
        """
        fingerprint = _fingerprint(trigger)
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
            state = _queued_state()
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
        """thread 의 state 에 updates 를 키 단위로 덮어써 저장한다.

        얕게 합치므로 노드가 돌려준 부분 결과를 차례로 쌓을 수 있다. 모르는 thread 면 아무것도 하지
        않는다. state_json 은 ensure_ascii=False 로 써서 한글을 이스케이프하지 않는다.
        skillops-agent/skillops_agent/main.py 의 _run_pipeline,
        skillops-agent/skillops_agent/graph.py 의 guarded, 그리고 recover 가 부른다.
        """
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
        """thread 하나의 조회 결과를 돌려주고, 없으면 None 을 돌려준다.

        skillops-agent/skillops_agent/main.py 의 get_run_events 가 이 결과를 그대로 응답한다.
        """
        with self.connect() as db:
            row = db.execute("SELECT * FROM diagnosis_runs WHERE id=?", (thread,)).fetchone()
        if not row:
            return None
        return _row_to_run(row)

    def list(self):
        """최근 진단을 한 번의 쿼리로 읽되 원문 evidence는 상세 조회에만 포함한다.

        skillops-agent/skillops_agent/main.py 의 list_runs 가 부른다.
        """
        return self._query_runs(
            "SELECT id, change_id, fingerprint, trigger_json, created_at, "
            "json_remove(state_json, '$.evidence') AS state_json "
            "FROM diagnosis_runs ORDER BY created_at DESC LIMIT ?", (RECENT_RUNS_LIMIT,)
        )

    def recover(self):
        """QUEUED 나 RUNNING 으로 남은 실행을 ERROR 와 INTERRUPTED_ERROR 문구로 바꾼다.

        다른 상태의 실행과 이미 쌓인 events 는 그대로 둔다. 백그라운드 파이프라인은 프로세스와 함께
        사라지므로, 재시작 뒤에 남은 진행 중 상태는 끝나지 않는다. 그런 실행을 성공이나 진행 중으로
        보이지 않게 하려고 skillops-agent/skillops_agent/main.py 의 lifespan 이 앱 시작 때 부른다.
        """
        for run in self.list_all():
            if run.get("diagnosis_status") in ACTIVE_STATUSES:
                self.update(
                    run["thread_id"],
                    {
                        "diagnosis_status": DiagnosisStatus.ERROR,
                        "error": INTERRUPTED_ERROR,
                    },
                )

    def list_all(self):
        """모든 실행을 한도 없이 thread_id 순으로 돌려준다. recover 와 테스트가 쓴다."""
        return self._query_runs("SELECT * FROM diagnosis_runs ORDER BY id")

    def _query_runs(self, sql, params=()):
        """SQL 에 맞는 행을 한 번에 읽어 조회 결과 목록으로 바꾼다.

        list 와 list_all 이 쓴다. 새 조회 조건이 필요하면 SQL 만 바꿔 이 함수를 부른다.
        """
        with self.connect() as db:
            rows = db.execute(sql, params).fetchall()
        return [_row_to_run(row) for row in rows]
