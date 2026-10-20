#!/usr/bin/env python3
"""Read-only verification of one persisted execution, snapshot and related file."""
import argparse
import hashlib
import json
from pathlib import Path
from urllib.request import urlopen
from urllib.parse import quote

parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument('--base-url', default='http://127.0.0.1:8081/api')
parser.add_argument('--save', type=Path)
parser.add_argument('--compare', type=Path)
args = parser.parse_args()
base = args.base_url.rstrip('/')

def get(path):
    with urlopen(base + path, timeout=15) as response:
        return json.load(response)

saved = json.loads(args.compare.read_text()) if args.compare else None
records = get('/executions')['items']
if not records and not saved:
    raise SystemExit('Run an Agent order first; historical MVP orders have no trace')
execution_id = saved['execution_id'] if saved else records[0]['execution_id']
trace = get(f'/executions/{execution_id}/trace')
if not trace['snapshot_id']:
    raise SystemExit('Select an execution which fixed a snapshot before failing')
snapshot = get(f'/snapshots/{trace["snapshot_id"]}')
file = get(f'/snapshots/{trace["snapshot_id"]}/files?path=' + quote('skills/order-flow/SKILL.md'))
assert file['encoding'] == 'utf-8'
assert hashlib.sha256(file['content'].encode()).hexdigest() == file['hash']
state = {'execution_id': execution_id, 'snapshot_id': trace['snapshot_id'],
         'snapshot_hash': snapshot['hash'], 'flow_hash': file['hash'],
         'completion': trace['completion'], 'event_count': len(trace['events'])}
if saved:
    assert state == saved, 'Persisted evidence changed after recreation'
if args.save:
    args.save.write_text(json.dumps(state))
print('PASS: persisted execution, snapshot, flow bytes and trace events' + (' preserved' if saved else ' readable'))
