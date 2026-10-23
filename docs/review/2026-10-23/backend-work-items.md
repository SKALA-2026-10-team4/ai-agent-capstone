# 백엔드 현황과 수정·추가 작업

> **과거 검토 기록 — 기준 `dc261ba`.** 이 문서의 “현재”, 미완료 항목, 테스트 수와 줄 번호는 작성 당시 기준입니다. 최신 구현·계약·#72 완료 상태는 [리뷰 문서 안내](../README.md)를 우선합니다. 아래 제안을 현재 API나 새 작업 승인으로 간주하지 않습니다.

작성일: 2026-10-23 · 코드 기준: `dc261ba01ec6a13e6c7f7b488be4dfc17e270781` (PR #70 병합 후)

이 문서는 백엔드 코드와 저장소를 정적으로 검토한 작업안이다. 구현 완료 보고서나 확정 API 명세가 아니다. 프론트엔드 구현·디자인·화면 테스트는 범위에서 제외한다. MCP 정리 내역은 [별도 문서](mcp-cleanup.md)에 있다.

## 1. 검토 기준과 최근 변경

- 현재 추적 파일의 미커밋 diff와 staged diff는 없다. 작업 시작 전부터 `docs/review/2026-10-22/`, `order-agent/docs/runner-integration-design.md`가 untracked 상태로 있었으며 수정하지 않았다.
- 최근 PR #70은 service의 판정·제안서·snapshot 공통 코드 정리, Python 진단 흐름표·튜닝 상수 정리, 주문 Harness 정책 통합과 설명 주석 추가를 포함한다. 이 변경만으로 영속 비동기 평가나 운영 반영이 구현된 것은 아니다.
- 이전 통합 변경에서 `save_proposal`, 승인·반려, 주문 Agent 실제 모의 실행 연동이 들어왔다. 따라서 이 기능들을 처음부터 새로 만들기보다 현재 구현의 미완성 경계를 보완해야 한다.
- 이전 [Runner 설계 초안](../../../order-agent/docs/runner-integration-design.md)의 “scenario/workspace 구현 없음” 등은 현재 코드와 다르다. 현행 구현 여부는 소스와 테스트 파일로 다시 확인했다.
- 이번 산출물은 새 문서 두 개뿐이다. 기존 코드·기존 명세·설정·DB는 변경하지 않았다. 빌드, 테스트, 서버 기동, 유료 모델 호출은 실행하지 않았다.

## 2. 백엔드 디렉토리와 책임

| 위치 | 현재 책임 | 작업 시 유의점 |
|---|---|---|
| `service/.../snapshot/`, `mcp/` | 주문 snapshot 조회, diff, 정적 의존성 분석, MCP 진입점 | 기존 snapshot 기반 계약 유지 |
| `service/.../workspace/` | 후보 복사본, patch, 파일 export | 전체 workspace revision·동시 수정 제어 보완 |
| `service/.../scenario/` | 시나리오 실행·채점·실행 근거 원장 | 영속 평가 작업과 상세 판정 저장 추가 |
| `service/.../proposal/`, `approval/`, `api/`, `auth/` | 제안서 저장·조회, 승인·반려, 사용자 인증 | 실제 평가 판정 검증 및 승인 대상 고정 |
| `service/.../diagnosis/` | 저장 이벤트와 Python 진단 서버 중계 | 접수 실패와 실행 실패 구분 |
| `order-agent/backend/.../workspace/`, `trace/` | 불변 snapshot·파일 원문·실행 이벤트 저장 | 현재 실행 증거 원본을 유지 |
| `order-agent/backend/.../harness/`, `service/` | Skill 실행, LLM 제한, 업무 효과, dry-run | 운영 재고 격리와 실행 멱등성 협업 필요 |
| `skillops-agent/skillops_agent/` | 진단 요청 접수, 그래프, LLM 호출, MCP/REST 클라이언트, 진단 상태 | 백엔드 연동 범위이나 B 담당 코드 변경 전 사용자 확인 |
| `docs/contracts/`, 각 서버의 API 문서 | 백엔드 간 요청·응답·오류·스키마 계약 | 공동 관리 문서 수정 전 사용자 확인 |

`service/trace`와 `service/registry`는 현재 `package-info.java`만 있다. 이 폴더가 비었다는 이유로 주문 Agent에 이미 있는 저장소를 중복 구현하지 않는다. 과거 역할 문서의 service 내부 일체형 구조보다 현재 독립 주문 Agent 구조를 기준으로 한다.

## 3. 요청한 여섯 항목의 구현 상태

| 항목 | 현재 확인한 구현 | 남은 작업 |
|---|---|---|
| Skill 버전 관리 | 주문 Agent의 불변 snapshot, 파일별·전체 해시, 편집 저장·변경 이벤트 | Skill별 정수 버전 이력과 snapshot 매핑 정책, 운영본 포인터, workspace revision 고정 |
| 변경 내용 diff | `SkillDiffService`, `SkillDiffer`, `diff_skill`: 두 snapshot 비교, 고정 hunk ID, frontmatter/body 구분 | 명세 불일치 수정. resource는 해시 변경만 제공하므로 본문 diff 지원 여부 결정 |
| 오래 걸리는 평가 비동기 처리 | Python `/diagnose`는 202와 BackgroundTasks, SQLite 상태 저장 | 평가 자체는 동기. 영속 job/worker, 복구·취소·멱등성·진행 조회 추가 |
| 실행 기록 저장소 | 주문 DB의 `agent_executions`, `execution_events`; service의 `scenario_runs`; Python의 `diagnosis_runs` | 평가 전체 원장, 판정 근거·예산·고정 revision/hash 연결 |
| 승인·반려 API | Spring의 승인·반려·조회, 역할 검사, 트랜잭션, Proposal `@Version` | 실제 합격 근거와 승인 대상 hash 결합. 운영 반영은 별도 미구현 기능 |
| LLM 재시도·토큰 제한 / MCP 서버 | 주문 Harness 재시도·개별 실행 한도, Spring MCP 서버 7개 도구 | Python LLM·평가 전체 예산, 오류 분류 통일, MCP 계약·인증 정리 |

## 4. 먼저 해야 할 백엔드 작업

P0는 평가 결론·데이터 정합성을 위한 선행 작업, P1은 요청 기능 완성, P2는 운영·유지보수 보완이다. 공개 배포를 한다면 아래 인증 작업도 배포 전 필수다.

### BE-01 · P0 · 평가 결과를 서버 원장으로 검증

근거: [ProposalValidator](../../../service/src/main/java/com/capstone4/skillops/proposal/ProposalValidator.java), [JpaRunLedger](../../../service/src/main/java/com/capstone4/skillops/scenario/JpaRunLedger.java), [ScenarioRunRecord](../../../service/src/main/java/com/capstone4/skillops/scenario/ScenarioRunRecord.java).

현재 `save_proposal`은 근거 실행 ID의 존재와 workspace 소속을 검사한다. 합격 여부는 제출된 `results`의 논리식을 다시 계산한다. 저장된 실제 `passed`와 전체 시험 구성을 조회해 대조하지 않으므로, 실패 실행에 성공 결과를 붙이거나 일부 결과만 제출하는 경우를 충분히 검증하지 못한다.

- `evaluations`와 `evaluation_trials`를 추가하거나 현재 원장을 확장한다.
- 평가에 진단 ID, 목적(regression/repro/intent/control/restore), workspace revision, snapshot ID/hash, 시나리오·기대값·fixture 버전, 반복 횟수를 저장한다.
- trial마다 실행 ID, 실제 판정, 실패 이유, 관찰 결과 참조, usage와 오류를 저장한다. 목표대로 실패한 원인 실험도 있으므로 모든 proof가 PASS여야 한다는 단순 규칙은 사용하지 않는다.
- `save_proposal`은 평가 참조로 전체 계획의 실행 여부와 판정을 조회해 `valid`, 회귀 집계, 원인 실험 연결을 계산한다. 제출된 boolean만으로 승인 가능 후보를 만들지 않는다.
- 완료 기준: 다른 진단·revision의 근거, 실패 결과 위조, 반복 누락, 일부 합격 실행만 제출한 제안서를 거부한다.

### BE-02 · P0 · 평가 파일과 재현 조건 고정

근거: [WorkspaceService](../../../service/src/main/java/com/capstone4/skillops/workspace/WorkspaceService.java), [WorkspaceRecord](../../../service/src/main/java/com/capstone4/skillops/workspace/WorkspaceRecord.java), [HarnessFlowRunner](../../../service/src/main/java/com/capstone4/skillops/scenario/HarnessFlowRunner.java), [Expectation](../../../service/src/main/java/com/capstone4/skillops/scenario/Expectation.java).

- 현재 Skill별 편집 횟수 `WorkspaceSkill.revision`은 존재하지만, workspace 전체의 충돌 제어 revision과 `@Version`은 없다. `base_version` 확인만으로 동시 편집을 막을 수 없다.
- workspace에 전체 revision과 낙관적 잠금 또는 조건부 UPDATE를 적용하고 patch 요청의 `expected_revision`을 검증한다.
- 평가 접수 시 파일을 한 번 export하여 불변 후보 snapshot을 고정한다. 현재처럼 trial마다 `filesOf()`를 다시 읽어 서로 다른 내용을 실행하지 않게 한다.
- 현재 replay는 최종 상태가 FAILED가 아니면 합격한다. 정상 배송을 기대한 주문이 REJECTED/HOLD로 끝나도 복구 성공이 될 수 있으므로 기대 업무 결과·필수 도구 효과를 명시한다.
- fixture, 가격·재고 초기값, Clock과 기대값을 고정한다. 의도 조건이 없으면 PASS 대신 NOT_EVALUATED로 표현하고 자동 합격 정책을 정한다.
- 완료 기준: 평가 중 편집해도 trial의 snapshot/hash가 모두 같고, 동시 patch는 충돌하며, 잘못된 업무 거절을 복구 성공으로 판정하지 않는다.

### BE-03 · P0 · 모의 실행의 운영 재고 격리

근거: [DryRunService](../../../order-agent/backend/src/main/java/com/capstone4/orderagent/service/DryRunService.java), [InventoryRecovery](../../../order-agent/backend/src/main/java/com/capstone4/orderagent/service/InventoryRecovery.java), [HarnessFlowRunner](../../../service/src/main/java/com/capstone4/skillops/scenario/HarnessFlowRunner.java).

현재 dry-run은 실제 주문·상품 저장소를 사용해 재고를 예약하고 마지막에 복원한다. service의 `ReentrantLock`은 해당 프로세스의 시험만 직렬화하며 운영 주문과 다른 service 인스턴스까지 제어하지 않는다. 결제 후 cleanup 전에 중단되면 미결제만 해제하는 현재 복구 경로로는 예약이 남을 가능성도 있다. 이 부분은 정적 검토상 위험이며 이번에 재현 실험하지 않았다.

- 같은 Harness를 유지하면서 재고·결제·배송 효과 접근을 실행 환경 인터페이스로 분리한다.
- dry-run은 trial별 격리 상태를 사용하고 운영 테이블의 재고를 변경하지 않게 한다. 실행 증거 저장은 유지한다.
- 임시 보완이 필요하면 dry-run 예약의 전용 복구 원장과 cleanup 재시도를 먼저 추가한다.
- 완료 기준: 운영 주문과 동시 실행, 결제 후 강제 종료, cleanup 도중 재시작에서도 운영 재고가 시험 전후 동일하다.
- 담당 경계: 주문 Agent 담당 코드 변경이 필요하므로 구현 전 사용자 확인 대상이다.

### BE-04 · P1 · 평가를 영속 비동기 작업으로 전환

근거: [ScenarioRunner](../../../service/src/main/java/com/capstone4/skillops/scenario/ScenarioRunner.java), [Python main](../../../skillops-agent/skillops_agent/main.py), [RunStore](../../../skillops-agent/skillops_agent/run_store.py), [graph](../../../skillops-agent/skillops_agent/graph.py).

현재 진단 HTTP 요청만 202로 분리되어 있고, `run_scenarios`와 `/api/dry-runs`는 완료까지 기다린다. Python BackgroundTasks는 프로세스 메모리에서 실행되며 재시작 시 미완료 진단을 ERROR로 바꾼다. SQLite에 상태가 남는 것과 실행을 이어서 처리하는 것은 별개다. LangGraph checkpoint도 현재 MemorySaver다.

- 우선 service의 기존 PostgreSQL을 활용한 job 테이블과 worker를 권장한다. 현재 규모에서 별도 브로커 추가는 필수로 두지 않는다. 진단 작업의 영속화는 Python 담당과 별도 연결한다.
- 접수 트랜잭션에서 작업을 저장한 뒤 ID를 즉시 반환한다. worker는 짧은 트랜잭션으로 claim하고 lease·heartbeat를 갱신한다. 모델/HTTP 호출 동안 DB row lock을 유지하지 않는다.
- 작업 상태(QUEUED/RUNNING/SUCCEEDED/FAILED/CANCELLED)와 평가 결과(COMPLETED/INCOMPLETE/ERROR), trial 판정(PASS/FAIL/ERROR/NOT_RUN)을 구분한다. 시험 불합격은 작업 시스템 장애와 다르다.
- 재시작 복구, 최대 동시 실행, 취소 요청, deadline, 재처리 횟수, 동일 키 중복 접수를 구현한다. worker 재실행은 중복될 수 있으므로 trial과 대상 dry-run에도 멱등 키를 둔다.
- MCP는 새 접수·조회 도구를 추가하는 호환 방식을 우선 검토한다. 기존 `run_scenarios`의 완료 응답을 접수 응답으로 갑자기 바꾸지 않는다. Python 호출자도 접수→조회로 함께 전환해야 한다.
- 완료 기준: 재시작·worker 중복 claim·응답 유실에도 같은 trial의 업무 효과가 중복되지 않고, 부분 결과와 중단 이유를 조회할 수 있다.

### BE-05 · P1 · LLM 재시도와 전체 토큰 예산

근거: [SkillExecutor](../../../order-agent/backend/src/main/java/com/capstone4/orderagent/harness/SkillExecutor.java), [ExecutionControl](../../../order-agent/backend/src/main/java/com/capstone4/orderagent/harness/ExecutionControl.java), [OrderFlowPolicy](../../../order-agent/backend/src/main/java/com/capstone4/orderagent/harness/OrderFlowPolicy.java), [Python llm](../../../skillops-agent/skillops_agent/llm.py), [nodes._ask_llm](../../../skillops-agent/skillops_agent/nodes.py).

- 주문 Harness는 첫 호출 포함 최대 2회, timeout/rate-limit/transient만 재시도한다. 실행 기본 한도는 45초·12 API 시도·관측 토큰 10,000이며 회당 최대 출력은 512다. 한도는 이미 있으므로 중복 구현하지 않는다.
- Python `ChatOpenAI` 생성에는 재시도·timeout·usage 수집 정책을 명시하지 않는다. SDK 내부 기본 재시도가 있을 수 있으므로 “재시도 없음”으로 단정하지 않고, 애플리케이션 정책과 SDK 설정의 중복부터 확인한다.
- 진단 전체와 평가 전체에 입력+출력 토큰, API 시도, trial 수, deadline 예산을 둔다. 가설/후보 배열 길이도 제한한다. 단계 수·후보 재생성 횟수 제한은 토큰 예산을 대체하지 못한다.
- 429·일시적 5xx·연결 오류에는 제한된 backoff/jitter와 서버 대기 지시를 적용하고, 인증·잘못된 입력·형식 오류·예산 초과는 구분한다. 한 호출의 재시도 주체는 한 계층으로 정한다.
- 모든 재시도를 같은 예산에 합산한다. 남은 예산을 호출 전에 확인하고 출력 상한을 조정하며 초과 시 다음 모델·업무 도구 실행을 중단한다. 진행 중 병렬 호출이 있다면 예산 예약도 원자적으로 처리한다.
- 사용량 미보고·응답 유실은 0 사용으로 확정하지 않는다. 관측값과 추정/미확정 상태를 기록하고 보수적 정책을 둔다. 이미 사용한 원격 토큰까지 되돌리거나 청구량 상한을 완벽히 보장할 수 있다고 표현하지 않는다.
- 완료 기준: 재시도 포함 누적 예산 초과 후 추가 호출이 없고, TOKEN_BUDGET_EXCEEDED 등 중단 이유·usage·미실행 trial이 저장된다.

### BE-06 · P1 · Skill 버전과 운영본의 기준 정리

근거: [SnapshotService](../../../order-agent/backend/src/main/java/com/capstone4/orderagent/workspace/SnapshotService.java), [SkillVersionStore](../../../service/src/main/java/com/capstone4/skillops/workspace/SkillVersionStore.java), [LiveFolderSkillVersionStore](../../../service/src/main/java/com/capstone4/skillops/workspace/LiveFolderSkillVersionStore.java), [SkillDiffService](../../../service/src/main/java/com/capstone4/skillops/snapshot/SkillDiffService.java).

- 실행 재현의 원본은 기존 불변 snapshot으로 유지한다. frontmatter의 정수 version, snapshot UUID/hash, workspace 편집 횟수는 다른 개념으로 문서화한다.
- Skill별 정수 이력이 제품 요구사항이면 `(skill_id, version)`을 snapshot/path/file_hash에 연결하는 메타데이터 저장소를 추가한다. 원문을 양쪽에서 각각 수정하는 이중 원장은 만들지 않는다.
- 현행 `LiveFolderSkillVersionStore`는 현재 폴더 버전만 읽는다. 과거 버전 복원 요구가 있다면 기존 `SkillVersionStore` 접점에 실제 이력 조회 구현을 연결한다.
- 운영본의 기준을 루트 `skills/`, 주문 번들, snapshot 중 어디에 둘지 확정하고, 권장안으로 운영 snapshot 포인터를 관리한다. publish/promote 시 현재 운영본의 예상 hash를 확인한다.
- diff는 이미 구현된 hunk ID·unified diff를 유지한다. resource 본문 diff, 신규·삭제 Skill 지원은 별도 범위로 정하고 미지원 입력을 명확한 상태로 반환한다.
- 완료 기준: 동일 버전 조회는 동일 바이트/hash를 반환하고, 과거 버전으로부터 workspace를 재현하며, 같은 비교의 hunk ID가 안정적이다.

### BE-07 · P1 · 승인·반려 보강과 운영 반영 분리

근거: [ApprovalService](../../../service/src/main/java/com/capstone4/skillops/approval/ApprovalService.java), [ApprovalController](../../../service/src/main/java/com/capstone4/skillops/api/ApprovalController.java), [Approval](../../../service/src/main/java/com/capstone4/skillops/approval/Approval.java), [Proposal](../../../service/src/main/java/com/capstone4/skillops/proposal/Proposal.java).

- 현재 승인·반려 API, OPERATOR 권한, 의도 확인, 중복 결정 방지, 트랜잭션과 낙관적 잠금은 유지한다. 새 승인 API를 중복 생성할 필요가 없다.
- BE-01 결과로 검증된 candidate/evaluation/workspace revision/snapshot hash를 승인 기록에 고정한다. 이후 workspace를 편집해도 승인 대상은 바뀌지 않아야 한다.
- 반려 사유와 결정 요청 멱등성은 추가 요구로 제안한다. 필수화 여부·기존 요청 호환성을 정한 뒤 계약을 갱신한다.
- 현재 `approval_token` 발급은 운영 반영 완료가 아니다. `used_at` 소비 로직, `promote_workspace`, `rollback`은 없다.
- 운영 반영까지 범위에 포함하면 서비스 전용 publish 작업, 승인 번호 만료·원자적 1회 소비, 운영본 충돌 검사, 고정 bundle 반영, smoke와 이전 snapshot 복귀를 구현한다. 반영 실패와 승인 상태는 별도로 저장한다.
- Python의 사용되지 않는 `/approve`, `_pending_approvals`, 그래프 재개 경로는 제거 또는 분리 후보이며 사용자 확인 전 삭제하지 않는다.
- 완료 기준: 승인 후보와 다른 hash의 반영은 거부하고, 중복 반영·토큰 재사용을 막으며, 실패 시 이전 운영본과 반영 실패 기록을 확인할 수 있다.

### BE-08 · P1/P2 · 전달 복구·인증·저장소 운영

- [SkillChangeDispatcher](../../../order-agent/backend/src/main/java/com/capstone4/orderagent/workspace/SkillChangeDispatcher.java)는 오래된 미전달 1건을 선택해 재전송한다. 영구 실패가 다음 이벤트를 지연시키지 않도록 next_retry_at, backoff, 최대 시도, 영구 실패 상태와 재처리 경로를 추가한다.
- 전달 성공(thread_id 획득)과 진단 성공은 구분한다. 같은 change_id 중복 접수가 ERROR 작업을 자동 재실행하지 않으므로 별도 재시도 작업 정책을 둔다.
- [SecurityConfig](../../../service/src/main/java/com/capstone4/skillops/auth/SecurityConfig.java)의 MCP 허용과 주문 Agent의 후보 발행·편집·dry-run 경계를 사용자/서비스 권한으로 정리한다. 개발 모드와 배포 모드를 분리한다. 인증 변경은 다른 담당자의 연결을 바꾸므로 사전 확인 대상이다.
- 주문 trace를 service에 재복제하기보다 `diagnosis → evaluation → trial → execution → snapshot` 참조를 저장한다. 서버 간 FK 대신 ID/hash 검증을 사용한다.
- 실패 목록·실행 조회에 pagination, 보관 기간, 이벤트 민감정보 마스킹·크기 제한·색인을 설계한다. 평가·승인 근거로 참조 중인 snapshot/trace는 보관 정책에서 보호한다.
- service의 `ddl-auto:update`와 주문 Agent의 `schema.sql` 변경 방식은 버전 있는 migration으로 정리한다. 기존 DB 업그레이드·rollback 전략을 함께 검토한다.

## 5. 권장 모듈 경계와 저장 구조 — 구현 제안

| 모듈 | 책임 | 권장 위치 |
|---|---|---|
| EvaluationService / Store | 입력 검증, 평가 계획·불변 대상 저장, 집계·조회 | service의 `evaluation/` 신설 또는 scenario 하위 |
| EvaluationWorker / JobStore | claim·lease·heartbeat·취소·재처리 | service의 `jobs/` |
| ScenarioRunner / Grader | 고정 입력으로 trial 실행, 업무 판정 | 기존 `scenario/` 재사용 |
| UsageBudget / RetryPolicy | 예산 확인·예약·사용량 기록·재시도 정책 | 각 서버의 작은 모듈; 언어 간 공통 계약·fixture 공유 |
| DeploymentService | 승인 대상 검증·반영·점검·복귀 | 반영 범위 확정 시 service에 신설 |
| OrderExecutionEnvironment | 운영/모의 업무 효과 접근 분리 | 주문 Agent 담당과 협의 |

기존 `ToolResults`가 성공/오류 직렬화를 공유하고, `DependencyAnalyzer`가 graph와 patch 분석에 재사용되는 구조는 유지한다. 이미 공통화한 부분을 다시 복제하지 않는다. Python과 Java를 하나의 코드 모듈로 묶기보다 JSON Schema·오류 코드·계약 fixture를 공유한다.

추가 저장 항목의 최소안은 다음과 같다. 이름과 실제 ERD는 구현 전 확정한다.

| 저장 단위 | 핵심 필드 |
|---|---|
| evaluation | id, diagnosis_id, workspace_id/revision, snapshot_id/hash, plan/hash, status, limits, usage, 집계, 시작·종료 시각 |
| trial | evaluation_id, scenario_id, 반복 순번, 목적, request fingerprint, execution_id/run_id, expectation/hash, verdict/reasons, usage |
| job | id, 종류, 대상 id, idempotency key/fingerprint, status, attempt, lease owner/until, heartbeat, next_attempt_at, cancel_requested, error |
| Skill 이력 메타데이터 | skill_id/version, snapshot_id, path/file_hash, 작성자·시각, 변경 사유 |
| 승인/반영 확장 | candidate/evaluation/revision/hash, 만료·소비 시각, 결정 사유, deployment 상태·이전 운영 snapshot |

## 6. 백엔드 API 변경안 — 미구현·미확정

| 경계 | 현행 | 제안 |
|---|---|---|
| Python 진단 접수·조회 | `POST /diagnose` 202, `GET /runs`, `GET /runs/{thread_id}/events` | 기존 thread_id 유지. 작업 복구·재시도 참조·종료 이유 확장 |
| service 진단 조회 | `GET /api/diagnoses`, `GET /api/diagnoses/{threadId}` | 현재 중계 유지, 새 작업 상태와 오류 계약 매핑 |
| 평가 REST | 평가 작업 전용 REST 없음 | `POST /api/evaluations` 202, `GET /api/evaluations/{id}`, `GET /api/evaluations/{id}/trials`, `POST /api/evaluations/{id}/cancel` |
| 평가 MCP | 동기 `run_scenarios` | `submit_evaluation`, `get_evaluation`, `cancel_evaluation` 추가안. 기존 이름·응답은 전환 기간 유지 |
| patch | workspace_id, patches | expected_revision과 멱등 키 추가, 새 revision 응답. 충돌 오류 정의 |
| 승인·반려 | `POST /api/proposals/{id}/approve`, `.../reject`, `GET /api/approvals` | 서버에 저장된 평가 참조·hash 고정, 반려 사유·멱등 처리 검토 |
| 대상 실행 | `POST /api/dry-runs` 동기 | trial 멱등 키와 동일 실행 조회 계약. 필요 시 별도 비동기 실행 경계 |
| 운영 반영 | 없음 | 범위 확정 후 서비스 전용 반영·상태 조회 API. Agent 진단 도구에 자동 포함하지 않음 |

평가 접수 계약에는 workspace/revision, scenarios/replay, checks, repeat, limits와 멱등 키를 정의한다. 응답에는 evaluation/job ID와 조회 참조를 담고, 조회에는 계획·실행 수, trial별 판정, usage, 중단 이유를 담는다. 같은 키·같은 요청은 기존 작업을 반환하고 같은 키·다른 요청은 충돌로 처리한다. REST의 202와 MCP JSON 결과는 별개 계약이다.

변경 시 함께 갱신할 대상: `docs/contracts/mcp-tools-and-rest.md`, `mcp-tools-input-output.md`, 관련 JSON Schema, `order-agent/docs/api.md`, `diagnosis-read-api.md`, `skillops-agent/README.md`, 공개 DTO/어노테이션 설명, 연동 fixture. 이 목록은 갱신 계획이며 이번에 기존 명세를 수정한 것은 아니다.

## 7. 실행 순서와 담당자 확인

1. 백엔드 계약 정리: MCP 현행 7개, REST 조회, snapshot/version 의미, 실제 평가 profile을 문서로 합의한다.
2. 평가 원장과 불변 입력부터 설계하고 재현 합격 기준을 확정한다(BE-01/02). 모의 재고 격리도 함께 진행한다(BE-03).
3. job/worker와 조회·취소·복구를 구현한다(BE-04). 전체 예산과 멱등 실행을 같은 변경 묶음에 연결한다(BE-05).
4. 저장된 실제 평가로 제안서 합격을 검증하고 승인 대상 hash를 고정한다(BE-01/07).
5. Skill 정수 이력·운영본 관리와 필요 시 반영·복귀를 구현한다(BE-06/07).
6. 배포 전 인증, 전달 재처리, migration·보관 정책을 완료한다(BE-08).

사용자의 실제 담당 파일은 아직 지정되지 않았다. 따라서 모든 기존 코드를 다른 담당자의 코드일 수 있는 것으로 취급한다. 구현 전 파일별로 **현재 작성자/관리 담당, 바꾸는 이유, 공통 영향, 요청·응답 diff, 테스트 계획**을 정리해 사용자에게 확인받는다. 기존 명세 수정과 코드 정리·삭제에도 같은 기준을 적용한다. 이번 문서 작성은 코드 수정 승인을 대신하지 않는다.

## 8. 구현 시 주석·검증·변경 보고 규칙

- 새로 만들거나 수정하는 각 함수에 짧은 기능 주석을 남긴다. Java는 짧은 Javadoc, Python 내부 함수는 짧은 docstring을 기본으로 한다. 기존 함수를 주석만 추가하려고 일괄 수정하지 않는다.
- 모델로 보내는 Pydantic schema 설명과 MCP `description`은 동작 계약이므로 일반 주석처럼 임의 수정하지 않는다. FastAPI 엔드포인트·요청 모델은 기존 방침대로 짧은 `#` 주석을 사용해 OpenAPI 설명 변화와 구분한다.
- 예시: `submit`: “입력을 검증하고 고정 snapshot의 평가 작업을 접수한다.” / `claim`: “실행 가능한 작업 하나의 lease를 원자적으로 획득한다.” / `grade`: “고정 기대값과 관찰 결과를 비교해 판정한다.” / `consumeApproval`: “승인 대상 hash를 확인하고 승인 번호를 한 번 사용한다.”
- 의미 있는 검증: 동시 patch 충돌, 평가 중 편집, 재시작 복구, 중복 실행·응답 유실, 누락/위조 근거, 예산 초과, 운영 재고 불변, 동시 승인과 승인 후 hash 변경을 검사한다.
- fixed 모델 시험과 실제 모델 시험을 구분한다. fixed 통과만으로 자연어 정책 변경이 검증되었다고 보고하지 않는다.
- 구현 보고에는 바뀐 모듈·API와 명세 파일, 변경 이유, 실제 실행한 검사와 결과, 남은 제한을 요약한다. 이번 검토에서는 코드 실행 검증을 하지 않았으므로 런타임 정상 동작을 보증하지 않는다.
