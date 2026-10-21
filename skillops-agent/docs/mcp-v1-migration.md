# MCP v0 → v1 마이그레이션 변경 내역

> skillops-agent 기준으로 Order Agent HTTP API 전환 및 MCP 도구 계약 갱신 내용입니다.

---

## 1. HTTP API 전환 (4개 도구 → Order Agent REST)

기존 MCP 도구 4개가 Order Agent HTTP API로 이동했습니다.

| 기존 MCP 도구 | 새 HTTP 엔드포인트 |
|---|---|
| `list_failed_runs()` | `GET /api/executions/failed` |
| `get_trace(run_id)` | `GET /api/executions/{execution_id}/trace` |
| `list_skills()` | `GET /api/snapshots/{snapshot_id}/skills` |
| `get_skill(name, version)` | `GET /api/snapshots/{snapshot_id}/skills/{skill_id}` |

### `get_trace` 응답 형식 변경

| v0 | v1 |
|---|---|
| `run_id` | `execution_id` |
| `skill_versions: {}` (dict) | `snapshot_id: str` |
| `steps: [{skill, input, output, tools_called, status, error}]` | `events: [{sequence, kind, skill, ...}]` |

**v1 이벤트 종류:**
- `SKILL_SELECTED` — skill + version 정보 포함 (skill_versions 재구성에 사용)
- `EXECUTION_FINISHED` — output 포함 (intent 검증에 사용)
- `EXECUTION_ERROR` — skill + error 포함 (실패 skill 특정에 사용)
- `TOOL_REQUEST` — skill + tool 포함 (도구 호출 증거)

### `list_skills` / `get_skill` 응답 형식 변경

- `list_skills(snapshot_id)` → `{"items": [{"skill_id", "path", "hash", ...}]}`  
- `get_skill(snapshot_id, skill_id)` → `{"skill_id", "content", "hash", ...}`  
- 기존 `version` 필드 없음 — 버전 정보는 trace events에서 추출

---

## 2. 아키텍처 변경: promote/smoke/rollback 제거

`promote_workspace`, `smoke_check`, `rollback`은 **서비스(운영자 화면)가 처리**합니다.  
Agent는 `save_proposal → wait_for_approval → END`로 종료합니다.

```
Before (v0):  save_proposal → wait_for_approval → promote_workspace → smoke_check → END
                                                                                ↘ rollback → END

After  (v1):  save_proposal → wait_for_approval → END
```

> 승인/거부 모두 Agent는 종료합니다. 실제 promote와 smoke check, 실패 시 rollback은 서비스가 담당합니다.

---

## 3. MCP 도구 필드명 변경

### `diff_skill`

| v0 | v1 |
|---|---|
| `skill`, `from_version`, `to_version` (입력) | `skill_id`, `before_snapshot_id`, `after_snapshot_id` |
| `hunks[].index` | `changes[].change_no` |
| `hunks[].header` | `changes[].location` |
| `hunks[]` (배열 키) | `changes[]` |

### `get_dependency_graph`

| v0 | v1 |
|---|---|
| 입력 없음 | `snapshot_id`, `entry_skill_id` 필수 |
| `steps` | `run_order` |
| `edges` | `links` |
| `keys` (edge 필드) | `value_names` |
| `violations` | `broken_links` |

### `create_workspace`

| v0 | v1 |
|---|---|
| `base` (입력) | `start_versions` |
| `ws_id` (응답) | `workspace_id` |

### `apply_patch`

| v0 | v1 |
|---|---|
| `ws_id` | `workspace_id` |
| `patches[].diff` | `patches[].patch` |
| `applied` (응답) | `changed_skills` |
| `contract_violations` | `broken_links` |

### `run_scenarios`

| v0 | v1 |
|---|---|
| `ws_id` | `workspace_id` |
| `k` | `repeat` |
| `runs` (응답, 배열) | `run_ids` |
| 응답 형식: `list[dict]` | `{"results": [...]}`로 래핑 |
| — | `all_passed`, `replay_run_ids`, `extra_checks` 추가 |

### `save_proposal`

| v0 | v1 |
|---|---|
| `candidates[].id` (str) | `candidates[].candidate_no` (int, 1-based) |
| `candidates[].evidence` | `candidates[].evidence_run_ids` |
| `recommended` | `recommended_candidate_no` |
| `results.functional` | `results.fixes_failure` |
| `results.regression` | `results.keeps_old_orders` |
| `results.intent` | `results.keeps_intent` |
| `results.contract` | `results.no_broken_links` |
| `results.footprint` | `results.changed_skill_count` |
| `root_cause.hunk` | `root_cause.change_no` |
| `root_cause.confirmed_by` | `root_cause.proof_run_ids` |
| `intent.assertions` | `intent.checks` |

---

## 4. State / TypedDict 필드명 변경

| TypedDict | v0 필드 | v1 필드 |
|---|---|---|
| `Hypothesis` | `hunk_index` | `change_no` |
| `AblationRun` | `hunk_index`, `ws_id` | `change_no`, `workspace_id` |
| `RootCause` | `hunk`, `confirmed_by` | `change_no`, `proof_run_ids` |
| `Intent` | `assertions` | `checks` |
| `Patch` | `diff` | `patch` |
| `CandidateResults` | `functional`, `regression`, `intent`, `contract`, `footprint` | `fixes_failure`, `keeps_old_orders`, `keeps_intent`, `no_broken_links`, `changed_skill_count` |
| `Candidate` | `id` (str), `evidence`, `kind: local/contract/flow` | `candidate_no` (int), `evidence_run_ids`, `kind: same_skill/linked_skills/run_order` |
| `AgentState` | — | `snapshot_id: str \| None` 추가 |

---

## 5. 변경 파일 목록

| 파일 | 변경 내용 |
|---|---|
| `skillops_agent/config.py` | `ORDER_AGENT_URL` 환경변수 추가 |
| `skillops_agent/state.py` | 전체 TypedDict 필드명 v1 갱신 |
| `skillops_agent/tools.py` | MCPTools HTTP 클라이언트 추가, StubMCPTools v1 응답 형식 |
| `skillops_agent/nodes.py` | 12개 노드로 축소, 모든 필드 v1 반영, events 기반 파싱 |
| `skillops_agent/graph.py` | 15→12 노드, 승인 후 바로 END |
| `tests/test_graph.py` | 스텁 시그니처·반환 형식·단언문 v1 갱신, smoke 테스트 제거 |
| `tests/test_foundation.py` | diff_skill/get_trace 테스트 v1 형식으로 수정 |

---

## 6. 환경변수 추가

```env
# Order Agent HTTP API 기본 URL (default: http://localhost:8081)
SKILLOPS_ORDER_AGENT_URL=http://localhost:8081
```
