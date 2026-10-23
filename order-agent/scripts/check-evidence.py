#!/usr/bin/env python3
# 실행 중인 주문 Agent 에서 실행 증거가 보존되는지 읽기만 해서 확인하는 스크립트다.
# 최근 실행(또는 --compare 로 준 저장 상태의 실행)의 trace, snapshot manifest, 루트 flow
# (order-flow)의 SKILL.md 를 읽고 파일 본문의 SHA-256 이 응답의 hash 와 같은지 본다. --save 로
# 그 상태를 파일에 저장하고, 컨테이너를 다시 만든 뒤 --compare 로 같은 값이 남았는지 비교한다.
# order-agent/docs/agent-evidence.md 의 '화면에서 확인' 5단계가 이 스크립트를 안내한다.
# 부르는 API 는 GET /api/executions, GET /api/executions/{id}/trace, GET /api/snapshots/{id},
# GET /api/snapshots/{id}/files 이며, 받는 쪽은 주문 Agent 의
# order-agent/backend/src/main/java/com/capstone4/orderagent/controller/EvidenceController.java 다.
# 아래 모듈 docstring 은 argparse 의 --help 설명(description=__doc__)으로 쓰이므로
# 문구를 바꾸지 않는다.
"""Read-only verification of one persisted execution, snapshot and related file."""
import argparse
import hashlib
import json
from pathlib import Path
from urllib.request import urlopen
from urllib.parse import quote

# 로컬 데모(order-agent/compose.yml)가 주문 Agent backend 를 여는 주소다. --base-url 로 바꾼다.
DEFAULT_BASE_URL = 'http://127.0.0.1:8081/api'
# 해시를 확인할 snapshot 안 파일이다. 루트 flow 인 order-flow 의 SKILL.md 다.
FLOW_PATH = 'skills/order-flow/SKILL.md'


def parse_args(argv=None):
    """명령행 인자 --base-url, --save(상태를 쓸 파일), --compare(비교할 저장 상태 파일)를 읽는다."""
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--base-url', default=DEFAULT_BASE_URL)
    parser.add_argument('--save', type=Path)
    parser.add_argument('--compare', type=Path)
    return parser.parse_args(argv)


def fetch_json(base, path):
    """API 경로 하나를 GET 해 JSON 으로 읽는다.

    시간 제한은 15초이고 HTTP 오류는 urllib 예외로 그대로 올라간다.
    """
    with urlopen(base + path, timeout=15) as response:
        return json.load(response)


def pick_execution_id(records, saved):
    """비교 기준이 있으면 그 실행을, 없으면 가장 최근 실행을 고른다.

    records 는 GET /api/executions 의 items 이며 시작 시각 내림차순이라 첫 항목이 가장 최근
    실행이다. 둘 다 없으면 Agent 주문을 먼저 실행하라는 SystemExit 로 멈춘다. 과거 MVP 주문은
    trace 가 없어 records 에 들어 있지 않다.
    """
    if not records and not saved:
        raise SystemExit('Run an Agent order first; historical MVP orders have no trace')
    return saved['execution_id'] if saved else records[0]['execution_id']


def collect_state(base, execution_id):
    """실행 trace, snapshot manifest, order-flow 파일을 읽어 보존 여부를 비교할 상태를 만든다.

    snapshot 을 고정하기 전에 실패한 실행은 snapshot_id 가 없어 SystemExit 로 멈춘다. 파일이
    UTF-8 로 왔고 본문의 SHA-256 이 응답 hash 와 같은지 assert 로 확인한다. 상태는 실행 id,
    snapshot id 와 해시, flow 파일 해시, completion, 이벤트 수다.
    """
    trace = fetch_json(base, f'/executions/{execution_id}/trace')
    if not trace['snapshot_id']:
        raise SystemExit('Select an execution which fixed a snapshot before failing')
    snapshot = fetch_json(base, f'/snapshots/{trace["snapshot_id"]}')
    file = fetch_json(base, f'/snapshots/{trace["snapshot_id"]}/files?path=' + quote(FLOW_PATH))
    assert file['encoding'] == 'utf-8'
    assert hashlib.sha256(file['content'].encode()).hexdigest() == file['hash']
    return {'execution_id': execution_id, 'snapshot_id': trace['snapshot_id'],
            'snapshot_hash': snapshot['hash'], 'flow_hash': file['hash'],
            'completion': trace['completion'], 'event_count': len(trace['events'])}


def main(argv=None):
    """최근(또는 저장해 둔) 실행의 증거를 읽고 저장하거나 이전 상태와 비교한다.

    --compare 가 있으면 지금 상태가 저장 상태와 같아야 하고 다르면 AssertionError 로 멈춘다.
    --save 가 있으면 상태를 JSON 으로 쓴다. 끝까지 가면 PASS 줄을 찍는다.
    """
    args = parse_args(argv)
    base = args.base_url.rstrip('/')
    saved = json.loads(args.compare.read_text()) if args.compare else None
    records = fetch_json(base, '/executions')['items']
    execution_id = pick_execution_id(records, saved)
    state = collect_state(base, execution_id)
    if saved:
        assert state == saved, 'Persisted evidence changed after recreation'
    if args.save:
        args.save.write_text(json.dumps(state))
    print('PASS: persisted execution, snapshot, flow bytes and trace events' + (' preserved' if saved else ' readable'))


if __name__ == '__main__':
    main()
