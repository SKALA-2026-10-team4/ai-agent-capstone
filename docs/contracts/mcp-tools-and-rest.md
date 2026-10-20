# 계약 5 · MCP tool 12개 + REST + webhook

버전: v0 (초안) · 동결 예정: 1일차 09시

## MCP (service → SkillOps Agent)

전송: Streamable HTTP, `POST http://localhost:8080/mcp` (Spring AI MCP Server, `protocol: STREAMABLE`).
입출력은 JSON. 아래 "반환"의 객체는 모두 `docs/contracts/*.schema.json` 을 따른다.

| tool | 인자 | 반환 | D |
|---|---|---|---|
| `ping` | — | `"pong <time>"` (배선 확인용, 이미 있음) | 0 |
| `list_skills` | — | `[{name, version, owner, description, inputs, outputs, depends_on, steps?}]` | 1 |
| `get_skill` | `name`, `version?` (없으면 live) | `{name, version, frontmatter, body}` | 1 |
| `diff_skill` | `name`, `from_version`, `to_version` | `{hunks: [{index, header, before, after}]}` — hunk 단위, ablation 의 입력 | 1 |
| `get_dependency_graph` | — | `{steps: [...], edges: [{from, to, keys: [...]}], violations: [{skill, missing_input}]}` | 1 |
| `list_failed_runs` | `since?` | `[{run_id, order_id, failed_skill, error, at}]` | 1 |
| `get_trace` | `run_id` | Trace | 1 |
| `create_workspace` | `base?: {skill: version}` (없으면 live 전체) | `{ws_id}` | 1 |
| `run_scenarios` | `ws_id`, `scenario_ids[]`, `k?` | `[{scenario_id, passed, runs: [run_id], failures: [{run_id, step, error}]}]` | 1 |
| `apply_patch` | `ws_id`, `patches[]` (proposal.candidate.patches) | `{ws_id, applied: [{skill, version}], contract_violations: [...]}` | 2 |
| `save_proposal` | `proposal` (Proposal) | `{id}` — `evidence` 비면 400 | 2 |
| `promote_workspace` | `ws_id`, `approval_token` | `{promoted: [{skill, version}]}` — 토큰은 REST approve 가 발급 | 2 |
| `rollback` | `skill`, `version` | `{skill, version}` | 2 |

`ablation` 은 별도 tool 이 아니다: W3 가 `create_workspace(base={skill: from_version})` → hunk 하나만 다시 적용(`apply_patch`) → `run_scenarios` 를 반복한다.

## REST (Vue → service)

| 메서드 | 경로 | 설명 |
|---|---|---|
| GET | `/api/health` | 200 `{status, service}` (이미 있음) |
| GET | `/api/skills` | `list_skills` 와 동일 |
| GET | `/api/skills/{name}` | live SKILL.md 전문 + frontmatter |
| PUT | `/api/skills/{name}` | `{body}` 저장 → 새 version. webhook `skill_saved` 발송 |
| GET | `/api/skills/{name}/versions` | `[{version, saved_at, by}]` |
| POST | `/api/orders` | 주문 생성 + live 실행 → `{order_id, run_id, final_status}`. FAILED 면 webhook `failure` |
| GET | `/api/orders/{id}/trace` | Trace |
| GET | `/api/proposals` · `/api/proposals/{id}` | Proposal 목록/상세 |
| POST | `/api/proposals/{id}/approve` | `{candidate: index, intent_confirmed: bool}` → `{approval_token}` 발급 후 서비스가 `promote_workspace` 수행, 스모크 실행 결과 반환 |
| POST | `/api/rollback` | `{skill, version}` |

Swagger UI: `http://localhost:8080/swagger-ui.html` (springdoc).

## webhook (service → SkillOps Agent)

`POST http://localhost:8000/diagnose`

```json
{ "type": "failure",     "run_id": "r_01J9…" }
{ "type": "skill_saved", "skill": "payment", "version": 5 }
```

응답 202 `{accepted: true}`. 결과는 SkillOps Agent 가 `save_proposal` 로 저장한다.

## 포트

service 8080 (REST + MCP `/mcp`) · skillops-agent 8000 · frontend 5173 (`/api` → 8080 프록시) · postgres 5432
