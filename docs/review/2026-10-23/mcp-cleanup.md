# MCP 정리와 백엔드 변경 제안 — dc261ba 검토 기록

> **과거 검토 기록 — 기준 `dc261ba`.** 이 문서의 “현재”, 미완료 항목, 테스트 수와 줄 번호는 작성 당시 기준입니다. 최신 구현·계약·#72 완료 상태는 [리뷰 문서 안내](../README.md)를 우선합니다. 아래 제안을 현재 API나 새 작업 승인으로 간주하지 않습니다.

작성일: 2026-10-23 · 코드 기준: `dc261ba` · 백엔드 전용 정적 검토

이 문서는 실제 등록 도구와 오래된 계약의 차이를 정리한 검토 문서다. 기존 도구·클라이언트·명세를 수정하지 않았다. 아래 추가 도구·필드는 확정 명세가 아닌 제안이다. 전체 백엔드 우선순위는 [작업 목록](backend-work-items.md)을 따른다.

## 1. 현재 서버는 이미 구축되어 있다

| 구분 | 현재 값 |
|---|---|
| 서버 | `service`, 기본 주소 `http://localhost:8080/mcp` |
| 구성 | Spring AI MCP Server, Streamable HTTP, `protocol: STREAMABLE`, `type: SYNC` |
| 등록 | annotation scanner가 `@McpTool` 메서드 탐색 |
| 서버 이름/버전 | `skillops-service` / `0.1.0` |
| 클라이언트 | Python `mcp_client.py`의 `mcp.Client`, `tools.py`의 호출 어댑터 |
| 인증 | 현재 Spring Security에서 `/mcp`는 인증 없이 허용 |
| 결과 | ping은 문자열, 나머지는 단일 text content에 JSON 문자열 |
| 오류 | `isError=true`와 구조화 오류 JSON. HTTP 성공만으로 도구 성공을 판단하면 안 됨 |

근거: [application.yml](../../../service/src/main/resources/application.yml), [SecurityConfig](../../../service/src/main/java/com/capstone4/skillops/auth/SecurityConfig.java), [MCP 클라이언트](../../../skillops-agent/skillops_agent/mcp_client.py), [ToolResults](../../../service/src/main/java/com/capstone4/skillops/mcp/ToolResults.java).

새 MCP 서버를 별도 프로젝트로 다시 만드는 작업은 현재 요구에 필요하지 않다. 서버 구축 과제는 기존 서버의 계약·평가 작업·인증·오류 정책을 완성하는 쪽으로 정리한다. 서버 버전 `0.1.0`은 Skill 버전, 문서의 v0/v1, SDK가 협상하는 MCP protocolVersion과 서로 다른 값이다.

## 2. 실제 등록된 7개 도구

| 도구 | 현행 입력 | 구현·역할 |
|---|---|---|
| `ping` | `{}` | PingTools. 서버 배선 확인 |
| `diff_skill` | skill_id, before_snapshot_id, after_snapshot_id | SkillDiffTools → SkillDiffService. 두 불변 snapshot 비교 |
| `get_dependency_graph` | snapshot_id, entry_skill_id | DependencyGraphTools → DependencyGraphService. 선언 기반 연결 분석 |
| `create_workspace` | start_versions?, snapshot_id? | WorkspaceTools → WorkspaceService. 둘 중 한 시작점으로 후보 복사본 생성 |
| `apply_patch` | workspace_id, patches[{skill, base_version, patch}] | WorkspaceService. 후보에 unified diff 적용 |
| `run_scenarios` | workspace_id, scenario_ids?, replay_run_ids?, extra_checks?, repeat? | ScenarioRunner. 동기 실행·채점. repeat 1~5, 기본 2 |
| `save_proposal` | proposal 객체 | ProposalTools → ProposalService. 제안서 검증·저장 |

`create_workspace`의 snapshot_id와 비어 있지 않은 start_versions는 함께 사용할 수 없다. `run_scenarios`는 scenario_ids/replay_run_ids 중 적어도 하나가 필요하다. 재현 실행은 harness 설정과 snapshot에서 생성한 workspace가 필요하다.

등록 수의 근거는 `service/src/main/java/com/capstone4/skillops/mcp/`의 `@McpTool` 7개다. 이번에는 서버를 띄워 실제 `tools/list` 응답을 수집하지 않았으므로 배포 환경의 등록 수까지 확인한 것은 아니다.

## 3. MCP가 아닌 REST와 미구현 기능

Python 클래스 이름이 `MCPTools`여도 다음 메서드는 주문 Agent의 REST API를 호출한다.

| Python 기능 | 실제 백엔드 경계 |
|---|---|
| list_failed_runs | `GET :8081/api/executions/failed` |
| get_trace | `GET :8081/api/executions/{execution_id}/trace` |
| list_skills | `GET :8081/api/snapshots/{snapshot_id}/skills` |
| get_skill | `GET :8081/api/snapshots/{snapshot_id}/skills/{skill_id}` |
| get_snapshot | `GET :8081/api/snapshots/{snapshot_id}` |

`promote_workspace`와 `rollback`은 기존 계약에 있지만 현재 MCP 미등록·서비스 구현 없음이다. 승인·반려는 Spring REST가 처리하며 Agent 그래프는 `save_proposal → END`로 끝난다. 주문 Harness 내부 `order.inspect`, `payment.charge` 등의 업무 도구도 이 MCP 등록 목록과 구분해야 한다.

권장안은 현행 MCP+REST 구조를 유지하고 목록을 정확하게 문서화하는 것이다. “12개 도구”라는 과거 개수를 맞추기 위해 REST 조회를 중복 노출하거나 운영 반영을 진단 Agent 도구에 추가하지 않는다. 외부 MCP 소비자가 조회까지 요구할 때만 별도 facade 필요성을 결정한다.

## 4. 정리가 필요한 계약·문서

| 위치 | 현재 차이 | 정리할 내용 |
|---|---|---|
| `docs/contracts/mcp-tools-and-rest.md` | 과거 12개 도구·정수 버전 중심 계약 | 구현/REST 전환/미구현 상태를 구분하고 현행 기준 버전·날짜 명시 |
| `docs/contracts/mcp-tools-input-output.md` §diff_skill | skill/from_version/to_version 중심 입력 | skill_id/before_snapshot_id/after_snapshot_id와 실제 출력으로 정합화 |
| 같은 문서 §get_dependency_graph | 빈 입력으로 운영본 분석 설명 | snapshot_id/entry_skill_id 필수, 고정 snapshot 기반 분석으로 정합화 |
| 같은 문서 저장소 목록 | skill_versions, trace_run, scenario_results 등 계획과 실제 혼재 | 주문 DB snapshot/execution/event, service scenario_runs/proposals/approvals, Python diagnosis_runs를 실제 위치와 연결 |
| `skillops-agent/docs/mcp-v1-migration.md` | diff 결과를 changes/change_no/location으로, graph를 run_order/links로 설명 | 실제 hunks/index/header, steps/edges/keys/missing을 기준으로 수정 |
| 같은 migration 문서 | wait_for_approval 흐름의 과거 설명과 최신 주석 혼재 | 현행 save_proposal→END, 결정은 Spring REST라는 경계로 정리 |
| `order-agent/docs/runner-integration-design.md` | Runner 미구현과 /internal/... API 초안 등 과거 상태 | 역사적 설계로 표시하고 현행 /api/snapshots/candidates, /api/dry-runs와 연결 |
| `docs/contracts/trace.schema.json`, `proposal.schema.json` 등 | 과거 형식과 현재 런타임 검증 혼동 가능 | 실제 사용하는 스키마/계획용 스키마를 표시하고 버전·호환 정책 정의 |
| `docs/review/2026-10-22/` | 과거 커밋 기준 리뷰, 함수 위치 일부 변경 | 과거 기록은 보존하고 현재 작업 문서에서 최신 코드 위치 참조 |

실제 diff 응답은 `skill_id`, `before/after`, `status`, `frontmatter_changes`, `hunks`, `frontmatter_parse_error`, `related_files`다. 실제 graph 응답은 `snapshot_id/hash`, `entry_skill_id`, `steps`, `nodes`, `edges`, `missing`, `ambiguous`, `complete`다. Python 현재 호출은 서버의 snapshot 입력과 맞으므로 필드 이름을 과거 문서에 맞춰 되돌릴 이유가 없다.

이 문서는 별도 정리 문서 생성 요청만 수행한다. 위 기존 공동 문서의 실제 수정은 사용자 확인 후 진행한다.

## 5. 도구별 구현 보완

| 도구 | 보완할 점 | 완료 기준 |
|---|---|---|
| diff_skill | snapshot 기반 계약 유지, resource 본문 diff 지원 범위 결정 | 두 snapshot의 동일 변경은 동일 hunk ID. resource 해시 비교를 본문 diff로 오인하지 않음 |
| get_dependency_graph | complete와 missing을 함께 설명, 실행 검증과 정적 분석 구분 | complete=true여도 missing이 있으면 위반이라는 계약 고정 |
| create_workspace | 실제 과거 버전 저장소 또는 snapshot 경로 명확화 | 현재 폴더만 제공하는 버전 fallback을 전체 이력 지원으로 표현하지 않음 |
| apply_patch | workspace 전체 expected_revision·멱등성·동시 수정 제어 | 충돌 patch를 거부하고 재시도 시 중복 적용하지 않음 |
| run_scenarios | 영속 평가 접수/조회 분리, 파일·기대값 고정, 전체 예산 | 재시작 복구·부분 결과·취소·NOT_RUN 저장 |
| save_proposal | 존재/소속 검사에 더해 서버 판정 원장 대조 | 실패/누락/다른 revision 근거로 합격 후보를 만들 수 없음 |
| ping | 단순 liveness 유지 | 업무 저장소·모델 상태는 별도 health/readiness 검사로 구분 |

### 비동기 평가용 추가 도구안

| 제안 도구 | 입력안 | 출력안 |
|---|---|---|
| submit_evaluation | workspace_id, expected_revision, scenarios/replay, checks, repeat, limits, idempotency_key | evaluation_id, job_id, status, 조회 참조 |
| get_evaluation | evaluation_id, 필요 시 trial 조회 cursor | 상태, planned/executed/pass/fail/error/not_run, usage, trial 참조·오류 |
| cancel_evaluation | evaluation_id | 취소 요청 접수 여부, 현재 상태 |

기존 `run_scenarios`는 전환 기간 유지하고 Python 진단 호출자까지 함께 마이그레이션한다. 단순히 `spring.ai.mcp.server.type`을 바꾸거나 Java Future/Python async를 쓰는 것만으로 영속 작업·재시작 복구가 생기지 않는다. 위 도구는 애플리케이션 작업 계약 제안이며, 특정 MCP 표준 Tasks 기능을 현재 SDK가 지원한다고 가정하지 않는다.

## 6. 오류·재시도·결과 포맷 정리

현행 구조화 오류는 다음 필드를 사용한다.

```json
{
  "code": "INVALID_INPUT",
  "message": "입력이 올바르지 않습니다.",
  "retryable": false,
  "upstream_status": null,
  "snapshot_id": null
}
```

- C-1 `ToolResults.run`은 OrderAgentException만 구조화한다. 예상하지 못한 예외는 SDK의 문자열 오류가 되어 Python에서 ContractError로 처리될 수 있다.
- C-2 `WorkspaceToolResults.run`은 IllegalArgumentException을 INVALID_INPUT, 나머지 RuntimeException을 INTERNAL_ERROR/retryable=false로 바꾼다. 연결·timeout·5xx의 재시도 가능성과 upstream 정보를 잃는 경우를 보완한다.
- Python `get_snapshot`만 `order_agent.py`의 조회 재시도·입력 검증 계층을 우회한다. 다른 조회와 같은 정책으로 옮기는 것이 정리 대상이다.
- 공통 직렬화는 이미 ToolResults로 합쳐져 있다. 새 래퍼를 복제하지 말고 두 예외 정책의 의도된 차이와 공통 오류 분류부터 합의한다.
- 읽기 조회 재시도와 patch/실행/반영 재시도를 구분한다. `retryable=true`만 보고 쓰기를 재호출하지 말고 멱등 키로 완료 여부부터 확인한다.
- 하위 호출의 재시도도 평가 전체 deadline·토큰·API 예산 안에서 처리한다. 모델 호출 재시도와 후보 재생성 루프를 같은 것으로 집계하지 않는다.
- execution/evaluation/job/correlation 참조는 필요한 오류에 점진적으로 추가하고, 운영 오류 메시지에 내부 비밀값이 노출되지 않도록 한다.
- 현재 클라이언트는 text JSON을 읽으므로 이 반환 형식을 먼저 보존한다. structuredContent/output schema 추가는 사용 SDK 지원을 확인하고 별도 호환 변경으로 진행한다.

## 7. 저장소와 권한 경계

| 데이터 | 원본/관리 위치 | MCP에서 할 일 |
|---|---|---|
| snapshot 원문·hash | 주문 Agent `skill_snapshots`, `snapshot_files` | 기존 HTTP 클라이언트로 조회·후보 등록 |
| 실행 증거 | 주문 Agent `agent_executions`, `execution_events` | 실행 ID와 snapshot/hash 참조 연결 |
| workspace·trial 원장 | service `workspaces`, `scenario_runs` | revision 고정과 상세 평가 저장 확장 |
| 제안서·결정 | service `proposals`, `approvals` | 실제 평가로 검증하고 승인 대상 고정 |
| 진단 진행 | Python SQLite `diagnosis_runs` | 영속 작업 복구와 진단-평가 참조 연결 |

service에 별도 trace 원본을 다시 만드는 방식은 권장하지 않는다. 조회 facade가 필요하면 원본을 조회하고 계약 변환만 한다. 기존 PostgreSQL 데이터가 서로 다른 DB에 있으므로 타 서버 테이블을 직접 join/FK로 결합하는 설계는 전제하지 않는다.

현재 MCP 쓰기·평가는 인증 없이 호출될 수 있다. 서비스 간 MCP 호출에는 서비스 인증과 호출 권한을 설계하고, 요청 크기·patch 수·시나리오 수·동시 평가·전체 예산을 제한한다. 사용자 승인·운영 반영은 Spring 사용자 권한으로 검증하고 진단 Agent 자격과 구분한다. 인증을 켤 때 Python MCP/HTTP 클라이언트의 자격 전달도 함께 바꿔야 한다.

## 8. 통합 설정과 검증 순서

루트 예제 기본값은 stub MCP + contract-only Runner다. Python 기본 golden은 g-01~03인데 service 기본 경로의 golden 파일은 g-01 하나이며 실제 주문 시나리오는 별도 경로에 있다. 실제 백엔드 연동용 설정을 명시해야 한다.

```dotenv
SKILLOPS_USE_STUB_MCP=false
SKILLOPS_FLOW_RUNNER=harness
SKILLOPS_SCENARIOS_DIR=../scenarios/order-agent
SKILLOPS_GOLDEN_IDS=g-01,g-02,g-03
ORDER_AGENT_BASE_URL=http://localhost:8081
SKILLOPS_AGENT_URL=http://localhost:8000
```

경로는 service 디렉토리에서 기동하는 기준이다. 각 프로세스에 필요한 변수를 실제로 주입해야 하며 루트 .env를 Spring이 자동으로 읽는다고 가정하지 않는다. 모델 키와 서비스 토큰은 별도 비밀 설정을 사용한다. 주문 Agent의 fixed 설정이 Python 진단 LLM 호출까지 가짜로 바꾸지는 않는다.

1. 기존 코드·명세 관리 담당과 수정 범위를 사용자에게 확인한다.
2. 현재 서버의 initialize/tools/list로 도구 이름·필수 입력을 저장하고 클라이언트 호출과 대조한다.
3. 계약 문서와 정상/오류 fixture를 현재 snapshot 필드로 정합화한다.
4. revision·평가 원장·격리 실행을 보완한 뒤 비동기 작업과 예산을 연결한다.
5. 모의·실제 연동 profile을 나누고 중복 patch·중복 실행·timeout·재시작·취소·예산 초과를 확인한다.
6. 공개 API 설명과 스키마를 갱신하고 변경사항·검증 결과를 요약한다.

기존 검증 자산: `service/.../mcp/*Test.java`, `scenario/*Test.java`, `proposal/*Test.java`, `skillops-agent/tests/test_tools_layer.py`, `test_main_api.py`, `test_run_store.py`, `scripts/mcp_smoke.py`. 이번 문서는 해당 소스의 검토 결과이며 테스트 통과나 실제 MCP 연결 성공을 새로 확인한 보고서는 아니다.
