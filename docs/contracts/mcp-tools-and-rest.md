# 계약 5 · MCP 도구 · REST · 저장 이벤트

2026-10-26 현재 코드 기준. 프로세스 사이를 오가는 경로를 한곳에 모은 개요다. 도구별 입출력 JSON 은 `docs/contracts/mcp-tools-input-output.md` 에 있다.

변경 기록: 2026-10-26 · 행동 변경 수정본([계획](../plans/2026-10-26-behavior-edit-root-fix.md)) — `/mcp` 서비스 토큰, `run_scenarios` 의 `intent_expectations`·`results[].intent_scoped`, 조건 문법 v2, `apply_patch` v1.4(`rules` 정적 검사, `WORKSPACE_FROZEN`), `save_proposal` 재판정과 `server_overrides`. 계약 보정 v3(계획 2.10 절): 재판정은 의도·기준본을 주문 Agent snapshot metadata 에서 읽고 `snapshot_id` 필수, patches 없는 후보 불합격, 재현 근거 확인, scoped expectation 별 실행 확인(장부 `intent_matches`), `weakens_contract` 단일 정의. 상세는 [상세 명세](mcp-tools-input-output.md) 맨 위 갱신 메모를 따른다.

변경 기록: 2026-10-23 · #72 apply_patch v1.3 — 최신 요청·응답은 아래 apply_patch 행과 [상세 명세 8절](mcp-tools-input-output.md#8-수정-적용하기--apply_patch)을 따른다. 다른 행의 v0 설계와 구분한다. [성공 응답 Schema](apply-patch-result.schema.json)

변경 기록: 2026-10-23 · #72 partial 진단은 기존 주문 Agent 원문 파일 REST와 service diff_skill을 이용해 증거만 저장한다. 진단 상세 REST에 추가한 evidence와 오류 의미는 [진단 증거 조회 계약](diagnosis-evidence.md)을 따른다. 새 MCP tool·필드 추가 및 자동 수정은 없다.

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

전송은 Streamable HTTP `POST http://localhost:8080/mcp` 다. `SKILLOPS_SERVICE_TOKEN` 이 있으면 `/mcp` 요청마다 같은 값의 `X-Service-Token` 헤더가 필요하고(없거나 다르면 401 `{code: UNAUTHORIZED, message}`), 비어 있으면 열어 두고 기동 때 경고를 남긴다(`service/src/main/java/com/capstone4/skillops/auth/McpServiceTokenFilter.java`). 도구는 기존 7개와 #88 평가 도구 4개를 합해 11개다. 새 평가 도구는 JWT를 요구하지 않지만 같은 MCP 서비스 토큰 정책을 따른다. 새 평가 REST는 JWT 권한을 유지하며 [평가 계약](evaluation-backend.md)을 따른다.

| 도구 | 인자 | 구현 |
|---|---|---|
| `ping` | 없음 | `service/src/main/java/com/capstone4/skillops/mcp/PingTools.java` |
| `diff_skill` | `skill_id`, `before_snapshot_id`, `after_snapshot_id` | `mcp/SkillDiffTools.java` |
| `get_dependency_graph` | `snapshot_id`, `entry_skill_id` | `mcp/DependencyGraphTools.java` |
| `create_workspace` | `snapshot_id` 또는 `start_versions` (둘 다 선택) | `mcp/WorkspaceTools.java` |
| `apply_patch` | `workspace_id`, `patches` (응답 v1.4. `run_scenarios` 를 돌린 연습 공간은 `WORKSPACE_FROZEN`) | `mcp/WorkspaceTools.java` |
| `run_scenarios` | `workspace_id` (필수), `scenario_ids`, `replay_run_ids`, `repeat`, `extra_checks`, `intent_expectations` (`[{scope, checks}]`) | `mcp/WorkspaceTools.java` |
| `save_proposal` | `proposal` (`snapshot_id` 필수. 서비스가 장부·연습 공간 기록·`snapshot_id` 의 snapshot metadata(의도, 기준본)로 판정을 다시 계산하고 다르면 덮어 `server_overrides` 에 남긴다) | `mcp/ProposalTools.java` |

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
| POST | `/internal/skill-changes` | `X-Service-Token` | 주문 Agent 가 보낸 수정본 저장 이벤트를 SkillOps Agent `/diagnose` 로 넘긴다. 이벤트의 선택 칸 `intent` 도 본문 그대로 넘긴다 |
| POST·GET·DELETE | `/mcp` | `X-Service-Token`(토큰을 설정했을 때) | MCP Streamable HTTP. 위 MCP 절 |

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

`/diagnose` 요청은 `type`(`skill_saved` 또는 `failure`)과 `candidate_snapshot_id`, `baseline_snapshot_id`, `execution_id`, `change_id`, `changed_paths`, `entry_skill_id`, `intent_checks`, 담당자 의도 `intent`(`{text, expectations: [{scope, checks}]}`, [계획](../plans/2026-10-26-behavior-edit-root-fix.md) 2.3) 등을 받는다. 칸 설명은 `skillops-agent/skillops_agent/main.py` 의 `DiagnoseRequest` 에 있다. Agent 는 `intent.expectations` 를 `run_scenarios` 의 `intent_expectations` 로 그대로 넘겨야 `save_proposal` 재판정이 그 실행을 찾는다. 재판정은 요청 본문이 아니라 주문 Agent 가 저장한 snapshot metadata 의 `intent` 를 진실로 보므로(담당자 expectations 가 없을 때만 제안서의 `intent.expectations` 를 쓴다), Agent 가 의도 칸을 비우거나 바꿔 보내도 판정은 달라지지 않는다.

## REST · 주문 Agent (8081)

주문 생성, 상품, 모의 실행(`/api/dry-runs`), 실행 기록(`/api/executions`, `/api/traces/{run_id}`), snapshot 과 skill 조회·저장(`/api/snapshots/...`), 저장 이벤트 전달 상태(`/api/skill-changes/{id}`)를 제공한다. 문서는 아래에 있다.

- `order-agent/docs/api.md`: 주문 API
- `order-agent/docs/diagnosis-read-api.md`: 진단용 조회 API
- `order-agent/docs/skill-edit-save-api.md`: skill 수정본 저장
- `order-agent/docs/agent-evidence.md`: 실행 기록 형식

## 수정본 저장 이벤트

1. 운영 화면이 주문 Agent 의 `POST /api/snapshots/edits` 로 수정본을 저장한다. 선택 칸 `intent` 를 넣으면 snapshot metadata 와 저장 이벤트에 그대로 실린다.
2. 주문 Agent 가 snapshot 과 전달 대기 이벤트를 한 트랜잭션에 저장하고, 전달기가 service 의 `POST /internal/skill-changes` 로 보낸다.
3. service 가 `X-Service-Token` 을 붙여 SkillOps Agent 의 `POST /diagnose` 로 넘긴다.
4. SkillOps Agent 가 진단을 마치면 MCP `save_proposal` 로 제안서를 저장한다.

중복 방지와 재전송 규칙은 `docs/skill-save-auto-diagnosis.md` 에 있다.
