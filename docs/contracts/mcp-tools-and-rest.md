# 계약 5 · MCP 도구 · REST · 저장 이벤트

2026-10-23 현재 코드 기준. 프로세스 사이를 오가는 경로를 한곳에 모은 개요다. 도구별 입출력 JSON 은 `docs/contracts/mcp-tools-input-output.md` 에 있다.

## 포트

| 프로세스 | 포트 | 위치 |
|---|---|---|
| service (REST + MCP `/mcp`) | 8080 | `service/` |
| SkillOps Agent | 8000 | `skillops-agent/` |
| 운영 화면 | 5173 | `frontend/` (`/api` 는 주문 Agent 8081, `/service` 는 service 8080 으로 proxy) |
| 주문 Agent backend | 8081 | `order-agent/backend` (Docker Compose) |
| 주문 화면 | 5174 | `order-agent/frontend` (Docker Compose) |
| PostgreSQL | 5432 | 루트 `docker-compose.yml`(service 용), 주문 Agent 는 자기 compose 의 DB 를 쓴다 |

## MCP (service, SkillOps Agent 가 부른다)

전송은 Streamable HTTP `POST http://localhost:8080/mcp` 이고 인증은 없다. 도구는 7개다.

| 도구 | 인자 | 구현 |
|---|---|---|
| `ping` | 없음 | `service/src/main/java/com/capstone4/skillops/mcp/PingTools.java` |
| `diff_skill` | `skill_id`, `before_snapshot_id`, `after_snapshot_id` | `mcp/SkillDiffTools.java` |
| `get_dependency_graph` | `snapshot_id`, `entry_skill_id` | `mcp/DependencyGraphTools.java` |
| `create_workspace` | `snapshot_id` 또는 `start_versions` (둘 다 선택) | `mcp/WorkspaceTools.java` |
| `apply_patch` | `workspace_id`, `patches` | `mcp/WorkspaceTools.java` |
| `run_scenarios` | `workspace_id` (필수), `scenario_ids`, `replay_run_ids`, `repeat`, `extra_checks` | `mcp/WorkspaceTools.java` |
| `save_proposal` | `proposal` | `mcp/ProposalTools.java` |

`diff_skill` 과 `get_dependency_graph` 의 인자는 모두 필수다. 기본값과 칸 설명은 `docs/contracts/mcp-tools-input-output.md` 를 따른다. 실행 기록, 실패 실행 목록, snapshot, skill 원문 조회는 MCP 도구가 아니다. SkillOps Agent 의 `skillops-agent/skillops_agent/tools.py` 와 `order_agent.py` 가 주문 Agent REST 를 직접 부른다.

## REST · service (8080)

| 메서드 | 경로 | 인증 | 설명 |
|---|---|---|---|
| GET | `/api/health` | 없음 | `{service, status}` |
| POST | `/api/auth/login` | 없음 | `{username, password}` 를 받아 JWT 를 돌려준다 |
| GET | `/api/auth/me` | 로그인 | 토큰의 사용자 |
| GET | `/api/proposals` · `/api/proposals/{id}` | 로그인 | 제안서 목록과 상세(본문 `document`, 제안서, 결정) |
| POST | `/api/proposals/{id}/approve` | OPERATOR | `{candidate_no, intent_confirmed}`. 합격 후보와 의도 확인이 있어야 하며, 승인 기록과 `approval_token` 을 돌려준다 |
| POST | `/api/proposals/{id}/reject` | OPERATOR | 거절 기록. 쓸 수 있는 안이 없는 제안서를 닫을 때도 쓴다 |
| GET | `/api/approvals` | 로그인 | 최근 승인 기록 50건 |
| GET | `/api/diagnoses` · `/api/diagnoses/{thread_id}` | 로그인 | SkillOps Agent 의 `/runs`, `/runs/{thread_id}/events` 를 중계한다 |
| POST | `/internal/skill-changes` | `X-Service-Token` | 주문 Agent 가 보낸 수정본 저장 이벤트를 SkillOps Agent `/diagnose` 로 넘긴다 |

로그인 계정과 권한 규칙은 루트 `README.md` 의 "로그인 · 승인" 절과 `service/src/main/java/com/capstone4/skillops/auth/SecurityConfig.java` 에 있다. Swagger UI 는 `http://localhost:8080/swagger-ui.html` 이다.

## REST · SkillOps Agent (8000)

`SKILLOPS_SERVICE_TOKEN` 을 설정하면 `/health` 를 뺀 경로가 같은 값의 `X-Service-Token` 헤더를 요구한다. 브라우저는 이 경로를 직접 부르지 않고 service 를 거친다.

| 메서드 | 경로 | 설명 |
|---|---|---|
| GET | `/health` | `{status, service}` |
| POST | `/diagnose` | 진단 요청. 202 와 `thread_id` 를 돌려준다. 같은 `change_id` 로 다시 보내면 기존 `thread_id` 를 돌려준다 |
| GET | `/runs` | 최근 진단 목록 |
| GET | `/runs/{thread_id}/events` | 진단 상태, 단계 기록, 결과 |
| POST | `/approve` | 옛 승인 재개 경로다. 지금 그래프에는 승인 대기가 없고 부르는 코드도 없다 |

`/diagnose` 요청은 `type`(`skill_saved` 또는 `failure`)과 `candidate_snapshot_id`, `baseline_snapshot_id`, `execution_id`, `change_id`, `changed_paths`, `entry_skill_id`, `intent_checks` 등을 받는다. 칸 설명은 `skillops-agent/skillops_agent/main.py` 의 `DiagnoseRequest` 에 있다.

## REST · 주문 Agent (8081)

주문 생성, 상품, 모의 실행(`/api/dry-runs`), 실행 기록(`/api/executions`, `/api/traces/{run_id}`), snapshot 과 skill 조회·저장(`/api/snapshots/...`), 저장 이벤트 전달 상태(`/api/skill-changes/{id}`)를 제공한다. 문서는 아래에 있다.

- `order-agent/docs/api.md`: 주문 API
- `order-agent/docs/diagnosis-read-api.md`: 진단용 조회 API
- `order-agent/docs/skill-edit-save-api.md`: skill 수정본 저장
- `order-agent/docs/agent-evidence.md`: 실행 기록 형식

## 수정본 저장 이벤트

1. 운영 화면이 주문 Agent 의 `POST /api/snapshots/edits` 로 수정본을 저장한다.
2. 주문 Agent 가 snapshot 과 전달 대기 이벤트를 한 트랜잭션에 저장하고, 전달기가 service 의 `POST /internal/skill-changes` 로 보낸다.
3. service 가 `X-Service-Token` 을 붙여 SkillOps Agent 의 `POST /diagnose` 로 넘긴다.
4. SkillOps Agent 가 진단을 마치면 MCP `save_proposal` 로 제안서를 저장한다.

중복 방지와 재전송 규칙은 `docs/skill-save-auto-diagnosis.md` 에 있다.
