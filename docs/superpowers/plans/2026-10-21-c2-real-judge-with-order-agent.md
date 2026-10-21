# C-2 실제 판정 경로: 주문 Agent 모의 실행 연결 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 후보 수정안을 C-2 연습 공간에 넣고 `run_scenarios`를 부르면, **주문 Agent의 실제 harness**가 그 skill 묶음으로 시험 주문을 모의 실행하고 C-2가 그 실제 실행 기록으로 채점한다. 하드코딩된 가짜 실행기 없이 F1 장애 하나를 끝까지 판정하는 실험을 CLI로 돌릴 수 있게 한다.

**Architecture:**
- 주문 Agent에 두 가지를 더한다.
  - 파일 묶음으로 후보 snapshot을 만드는 API
  - 그 snapshot으로 주문을 `dry_run` 모드로 돌리고 끝나면 재고를 되돌리는 API
- C-2는 다음을 더한다.
  - 연습 공간을 주문 Agent snapshot에서 시작하는 기능
  - ⑧ `apply_patch`
  - 주문 Agent를 부르는 실제 실행기 `HarnessFlowRunner`(`skillops.flow-runner=harness`)
- 실제 실행기는 연습 공간 파일을 후보 snapshot으로 올린 뒤 모의 실행을 요청한다. 그 실행 기록(events)을 `RunOutcome`으로 바꿔 기존 채점기에 넘긴다.
- 끊긴 연결 검사는 C-1의 `DependencyAnalyzer`를 그대로 쓴다.

**Tech Stack:** Java 21 · Spring Boot 4.1 · Spring AI 2.0 `@McpTool` · Jackson 3 · java-diff-utils 4.15 · Spring `RestClient` + `MockRestServiceServer` · 주문 Agent(Spring Boot, JdbcTemplate, PostgreSQL 17, Docker Compose) · Python 3.11 + uv(실험 스크립트)

**Spec:**
- 이 대화에서 확인한 현재 연결 상태(아래 "출발점")
- `order-agent/docs/mcp-order-api-draft.md` 4장: 후보 평가는 Runner가 격리 실행하고, 진단용 실행이 주문·재고를 바꾸면 안 된다
- `order-agent/docs/diagnosis-read-api.md`: 조회 API와 실행 기록 형식
- `docs/contracts/mcp-tools-input-output.md` 7·8·9번: C-2 도구 입출력 v1. B가 #52(`skillops-agent/docs/mcp-v1-migration.md`)에서 이 이름으로 옮겼다
- `skillops-agent/skillops_agent/tools.py`·`nodes.py`(develop `02d6db9`): B가 실제로 보내고 읽는 모양. 재현 주문은 시나리오 번호 `f-<execution_id>`로 보낸다
- `scenarios/failures/F1.md`: 기대하는 수정안 세 가지

## 출발점 (2026-10-21 실측)

| 연결 | 상태 |
|---|---|
| 주문 Agent: 정상 snapshot 주문 → SHIPPED, F1 snapshot 주문 → FAILED(`CONTRACT_VIOLATION`, payment의 `inventory.reserved` 누락), `/api/executions/failed`에 표시 | 동작 |
| 주문 Agent → C-1 `diff_skill`·`get_dependency_graph`(실제 snapshot) | 동작 |
| C-2 `run_scenarios` → 주문 Agent | **연결 안 됨.** 실행 전후 주문 Agent 실행 수가 그대로이고, 실행 번호가 `r_fake…`이다 |
| C-2 연습 공간 원본 | 루트 `skills/`(주문 Agent skill 묶음과 다름) |
| 시험 주문 g-01 상품 | `SKU-APPLE-1KG`. 주문 Agent 상품(`CAMP-…`)에 없음 |
| 주문 Agent의 후보 snapshot 생성 | 시연 고정본(`baseline`·`broken-contract`·`repaired`)만 가능 |
| 주문 Agent의 모의 실행 | 없음. 모든 실행이 `mode='live'`이고 재고가 줄어든다 |

## Global Constraints

- **주문 Agent 코드(`order-agent/`)의 주인은 hjyoon99다.** Task 1·2는 담당자의 동의를 받은 뒤 진행하거나 담당자에게 넘긴다. 기존 API의 응답 모양과 `live` 주문 동작은 바꾸지 않는다.
- 모의 실행은 실제 재고·실패 목록에 흔적을 남기지 않는다.
  - 끝난 뒤 상품 재고가 실행 전과 같아야 한다.
  - `GET /api/executions/failed`에 나오지 않는다(`mode='dry_run'`).
- C-2 도구 이름은 **v1**을 따른다(B #52, 사용자 결정 2026-10-21).
  - `create_workspace(start_versions?, snapshot_id?)` → `{workspace_id, skill_versions, created_at}`
  - `apply_patch(workspace_id, patches[{skill, base_version, patch}])` → `{workspace_id, changed_skills[{skill, version}], broken_links[{skill, missing_input}]}`
  - `run_scenarios(workspace_id, scenario_ids?, replay_run_ids?, extra_checks?, repeat?)` → `{workspace_id, all_passed, results[{scenario_id, replay_of_run_id, passed, run_ids, failures[{run_id, skill, reason}]}]}`
  - 오류는 `WorkspaceToolResults` 모양(`INVALID_INPUT`, `INTERNAL_ERROR`)을 그대로 쓴다.
- 재현 주문은 두 가지로 받는다. 시나리오 번호 `f-<주문 Agent execution_id>`(B `nodes.py`가 쓰는 방식)와 `replay_run_ids`(execution_id 또는 `r_<하이픈 없는 UUID>`)다. 결과의 `scenario_id`는 둘 다 `f-<execution_id>`이고, `replay_of_run_id`에 받은 값을 적는다. 합격 기준은 `Expectation.replay()`(FAILED가 아닐 것)에 `extra_checks`를 더한 것이다.
- B의 `tools.py`가 지금 보내는 호출(`create_workspace()` 인자 없음, `run_scenarios(workspace_id, scenario_ids, repeat=…)`)은 그대로 동작해야 한다.
- service의 `./gradlew test`는 PostgreSQL과 주문 Agent 없이 통과해야 한다. HTTP는 `MockRestServiceServer`로 대신한다.
- 주문 Agent 통합 테스트는 기존 방식(`docker compose --profile test up -d --wait db-test` → `ORDER_DEMO_INTEGRATION_TESTS=true ./gradlew test`)을 따른다.
- C-1 파일(`snapshot/`, `mcp/SkillDiffTools.java`, `mcp/DependencyGraphTools.java`, `mcp/ToolResults.java`)은 고치지 않는다. `OrderAgentClient`·`DependencyAnalyzer`는 호출만 한다.
- 주문 Agent는 기본 `ORDER_AGENT_MODEL_MODE=fixed`로 시험한다. 정해진 규칙으로 도구를 고르니 LLM 비용이 없다. `openai` 모드는 선택 실험이다. 이때 셸의 `OPENAI_API_KEY` 끝에 줄바꿈이 없어야 한다.
- 커밋 접두어는 `[FEAT]`·`[TEST]`·`[DOCS]`·`[FIX]`다. Claude 공동 저자 줄은 넣지 않는다.

## Review Focus

1. 모의 실행 뒤 재고가 그대로인지. SHIPPED로 끝난 모의 실행도 재고를 줄이면 안 된다. → Task 2 `dryRunLeavesStockUnchanged`
2. 모의 실행이 실패해도 실패 목록에 나오지 않는지. 나오면 진단이 끝없이 다시 시작된다. → Task 2 `dryRunFailureIsNotListedAsFailed`
3. 다른 버전을 기준으로 만든 수정 내용(문맥 줄 불일치)은 `INVALID_INPUT`이고 연습 공간이 그대로인지. → Task 5 `contextMismatchRejectsAndKeepsWorkspace`
4. 주문 Agent가 꺼져 있거나 5xx일 때 `run_scenarios`가 오래 멈추지 않고 이유가 담긴 오류를 내는지. → Task 6 `orderAgentDownIsReported`
5. 파일 끝 줄바꿈과 skill 이외 파일(`skills/inventory-check/resources/policy.md`)이 연습 공간 → 후보 snapshot으로 빠짐없이 옮겨지는지. → Task 4 `copiesResourcesAndTrailingNewline`, Task 6 `publishesAllWorkspaceFiles`
6. 없는 execution_id를 재현하라고 하면 주문을 하나도 돌리기 전에 `INVALID_INPUT`이고 그 번호를 짚는지. → Task 6 `unknownReplayExecutionRejectedBeforeRunning`

---

### Task 0: 이슈와 브랜치

- [ ] **Step 1:** GitHub에 FEAT 이슈를 만든다.
  - 제목: `[FEAT] C-2 실제 판정 경로: 주문 Agent 모의 실행 연결`
  - 본문: 이 문서의 Goal, 출발점 표, Task 목록. 담당자는 `@me`.
- [ ] **Step 2:** develop 최신(`02d6db9` 이후)에서 `feat/#<이슈번호>_c2-real-judge` 브랜치를 딴다.
- [ ] **Step 3:** `.env.example`을 합친다. 원격 내용은 모두 그대로 두고, stash(`local .env.example`)에만 있는 것을 더한다: 맨 위 "읽는 쪽" 설명, `SKILLOPS_USE_STUB_MCP`, `SKILLOPS_SKILLS_DIR`, `SKILLOPS_SCENARIOS_DIR`, `SKILLOPS_FLOW_RUNNER`. 키 값은 비워 둔다. stash는 합친 뒤 버린다.
- [ ] **Step 4:** `.env.example`, 이 계획 파일, `skillops-agent/scripts/mcp_call.py`를 `[DOCS]`로 커밋한다. `CheckTest.java` 변경과 `run_real_llm.py`는 커밋하지 않는다.

---

### Task 1: 주문 Agent — 파일 묶음으로 후보 snapshot 만들기 (hjyoon99 동의 필요)

**Files:**
- Modify: `order-agent/backend/src/main/java/com/capstone4/orderagent/controller/EvidenceController.java`
- Test: `order-agent/backend/src/test/java/com/capstone4/orderagent/integration/CandidateSnapshotIntegrationTest.java`

**Interfaces:**
- Produces: `POST /api/snapshots/candidates`
  - 요청: `{"files": {"skills/<id>/SKILL.md": "<UTF-8 글>", ...}, "metadata": {...}}`
  - 응답: `GET /api/snapshots/{id}`와 같은 manifest `{snapshot_id, hash, created_at, metadata, files[{path, hash, size}]}`
  - 저장은 기존 `SnapshotService.publish(SortedMap<String, byte[]>, Map<String,Object>)`를 그대로 쓴다. 경로·크기 검사도 기존 그대로다.
  - `metadata`에는 `"source": "candidate"`를 덧붙인다.
  - `order-agent.demo-enabled=false`면 기존 fixture API처럼 `SNAPSHOT_NOT_FOUND`를 낸다.

- [ ] **Step 1: 통합 테스트 작성** (`DiagnosisReadApiIntegrationTest`와 같은 설정·MockMvc 방식)
  - `publishesCandidateFiles`: baseline snapshot의 파일을 그대로 보내면 200이고, `files` 경로 집합이 같고, `metadata.source == "candidate"`다.
  - `orderRunsWithCandidateSnapshot`: 만든 snapshot으로 `POST /api/orders`(`snapshot_id` 포함, 랜턴 1개 card KR)를 보내면 `status == "SHIPPED"`다.
  - `rejectsPathOutsideSkills`: `{"files": {"../x": "a"}}`는 4xx이고 기존 경로 오류 코드다.
- [ ] **Step 2: 실패 확인**

  ```bash
  cd order-agent
  docker compose --profile test up -d --wait db-test
  (cd backend && ORDER_DEMO_INTEGRATION_TESTS=true ./gradlew test --tests '*CandidateSnapshotIntegrationTest')
  ```
  Expected: 404 또는 405로 3개 실패

- [ ] **Step 3: 구현.** `record CandidateRequest(Map<String,String> files, Map<String,Object> metadata)`, 글을 UTF-8 bytes로 바꿔 `TreeMap`에 담고 `publish` 호출, 응답은 기존 `manifest(...)`
- [ ] **Step 4: 통과 확인** — 같은 명령, 3 passed. 이어서 전체 `ORDER_DEMO_INTEGRATION_TESTS=true ./gradlew test`가 통과해야 한다.
- [ ] **Step 5: Commit** `[FEAT] 주문 Agent 후보 snapshot 생성 API (POST /api/snapshots/candidates)`

---

### Task 2: 주문 Agent — 모의 실행 API (hjyoon99 동의 필요)

**Files:**
- Modify: `order-agent/backend/src/main/resources/schema.sql`(`orders.dry_run BOOLEAN NOT NULL DEFAULT FALSE` 추가, `ALTER TABLE … ADD COLUMN IF NOT EXISTS`)
- Modify: `entity/Order.java`(`dryRun` 필드와 `markDryRun()`), `service/OrderToolService.java`(`Initial`에 `dryRun`), `harness/OrderHarness.java`(실행 시작 때 run mode 전달), `trace/TraceService.java`·`trace/ExecutionRepository.java`(`begin`에 run mode 인자, `'live'` 고정값 대신 인자)
- Create: `service/DryRunService.java`, `controller/DryRunController.java`
- Test: `order-agent/backend/src/test/java/com/capstone4/orderagent/integration/DryRunIntegrationTest.java`

**Interfaces:**
- Consumes: Task 1의 후보 snapshot
- Produces: `POST /api/dry-runs`
  - 요청: `{"snapshot_id": "<UUID>", "order": {customer, items, payment_method, shipping}}`(주문 생성과 같은 검증)
  - 응답: `{"execution_id", "run_id": "r_<하이픈 없는 UUID>", "order_id", "final_status", "completion"}`
  - 실행 기록은 기존 `GET /api/executions/{id}/trace`로 읽고, `mode`가 `"dry_run"`이다.
  - `live` 주문은 지금과 똑같이 `mode='live'`다.

- [ ] **Step 1: 통합 테스트 작성**
  - `dryRunShipsWithBaseline`: baseline으로 `POST /api/dry-runs`를 보내면 `final_status == "SHIPPED"`, trace의 `mode == "dry_run"`
  - `dryRunLeavesStockUnchanged`: 랜턴 재고를 읽고, SHIPPED 모의 실행을 한 뒤 다시 읽으면 같다
  - `dryRunFailureIsNotListedAsFailed`: broken-contract로 모의 실행하면 `final_status == "FAILED"`이고, `GET /api/executions/failed`에 그 `execution_id`가 없다
  - `liveOrderStillLive`: 같은 테스트 안에서 일반 `POST /api/orders`의 trace `mode`는 `"live"`다
- [ ] **Step 2: 실패 확인** — `--tests '*DryRunIntegrationTest'`, 4개 실패
- [ ] **Step 3: 구현.** `DryRunService.run(UUID snapshotId, CreateOrderRequest order)`는 아래 순서를 따른다.
  1. `NormalizedOrder.from(order)`
  2. 무작위 키로 `lifecycle.accept`, `useSnapshot(snapshotId)`, `markDryRun()`
  3. `claim` 후 `processor.process(id)`. 예외는 `OrderService`처럼 잡아 `lifecycle.fail`로 남긴다. 모의 실행 실패는 오류 응답이 아니라 결과다.
  4. 끝난 뒤 정리: 주문이 재고를 잡고 있으면(`isInventoryReserved()`이고 아직 풀지 않음) 결제 상태와 상관없이 `InventoryMockAdapter.release`로 되돌리고, 처리 단계 `RELEASE`·`DRY_RUN_CLEANUP`를 남긴다.
  5. 실행 기록에서 `execution_id`·`final_status`·`completion`을 읽어 응답한다.

  `ExecutionRepository.begin`의 insert에서 `'live'`를 인자 `runMode`로 바꾼다. 실패 목록 쿼리(`mode='live'`)는 그대로 둔다.
- [ ] **Step 4: 통과 확인** — 4 passed, 이어서 전체 통합 테스트 통과
- [ ] **Step 5: Docker 이미지 재빌드 후 수동 확인**

  ```bash
  docker compose up --build -d --wait
  ```
  확인: baseline으로 `curl -X POST localhost:8081/api/dry-runs …`를 보내면 SHIPPED이고, `GET /api/products`의 랜턴 `stock_quantity`가 그대로다.
- [ ] **Step 6: Commit** `[FEAT] 주문 Agent 모의 실행 API (POST /api/dry-runs, 재고 원복·실패 목록 제외)`

---

### Task 3: C-2 — 도구 입출력을 v1으로 (B #52에 맞춤)

**Files:**
- Modify: `service/src/main/java/com/capstone4/skillops/mcp/WorkspaceTools.java`(파라미터 이름 `start_versions`, `workspace_id`, `repeat`, `extra_checks`, `scenario_ids`. 설명 문구도 v1)
- Modify: `workspace/WorkspaceService.java`(`CreatedWorkspace(String workspaceId, …)`), `scenario/ScenarioRunReport.java`(`ScenarioResult(scenarioId, replayOfRunId, passed, runIds, failures)`, `Failure(runId, skill, reason)`), `scenario/ScenarioRunner.java`
- Modify: 오류 문구를 입력 이름에 맞춘다: `workspace_id <id> 이 없습니다. create_workspace 로 먼저 만드세요`, `repeat 는 1~5 사이여야 합니다 (받은 값 N)`, `start_versions 의 <skill> 에 버전 번호를 넣으세요`
- Modify: `skillops-agent/scripts/c2_smoke.py`(B의 v1 `MCPTools`로 부르기)
- Modify: `docs/contracts/mcp-tools-input-output.md` 맨 위 #38 메모 아래에 #52 메모(C-2 도구를 다시 v1으로), 7·9번을 v1 모양으로
- Test: `service/src/test/java/com/capstone4/skillops/mcp/WorkspaceToolsTest.java`, `scenario/ScenarioRunnerTest.java`, `workspace/WorkspaceServiceTest.java`

**Interfaces:**
- Produces: Global Constraints의 v1 `create_workspace`·`run_scenarios` 모양. `run_scenarios`는 `{workspace_id, all_passed, results}` 묶음이다(`ScenarioRunReport` 전체를 돌려준다). 이 Task에서 `replay_run_ids`는 아직 받지 않는다(Task 6).

- [ ] **Step 1: 테스트를 v1 이름으로 먼저 고친다**
  - `WorkspaceToolsTest`: 키 `workspace_id`, 결과 키 `[workspace_id, all_passed, results]`, `results[0]` 키 `[scenario_id, replay_of_run_id, passed, run_ids, failures]`, `failures[0]` 키 `[run_id, skill, reason]`
  - `ScenarioRunnerTest`·`WorkspaceServiceTest`: 오류 문구를 위 v1 문구로
- [ ] **Step 2: 실패 확인** — `cd service && ./gradlew test`, 이름 차이로 실패
- [ ] **Step 3: 구현**
- [ ] **Step 4: 통과 확인** — `./gradlew test` 전체. 이어서 service를 띄우고 `SKILLOPS_USE_STUB_MCP=false uv run scripts/c2_smoke.py`가 `C2 SMOKE OK`인지 확인한다.
- [ ] **Step 5: Commit** `[FIX] C-2 도구 입출력을 B #52 의 v1 이름으로 다시 맞춤`

---

### Task 4: C-2 — 주문 Agent snapshot에서 연습 공간 시작하기

**Files:**
- Modify: `service/src/main/java/com/capstone4/skillops/workspace/WorkspaceRecord.java`
  - `resources`(`Map<String,String>`, JSON 칸, skill 이외 파일 경로 → 글) 추가
  - `sourceSnapshotId`(문자열, null 허용) 추가
- Modify: `service/src/main/java/com/capstone4/skillops/workspace/WorkspaceService.java`
- Modify: `service/src/main/java/com/capstone4/skillops/mcp/WorkspaceTools.java`(`create_workspace`에 선택 입력 `snapshot_id`. Task 3의 v1 이름 위에)
- Test: `service/src/test/java/com/capstone4/skillops/workspace/WorkspaceFromSnapshotTest.java`

**Interfaces:**
- Consumes: C-1 `OrderAgentClient.getSnapshot(String)` → `SnapshotManifest(files[{path, hash, size}])`, `OrderAgentClient.readText(String snapshotId, String path)` → `String`
- Produces:
  - `CreatedWorkspace create(Map<String,Integer> startVersions, String snapshotId)`
    - `snapshotId`가 null이면 지금과 같다(`start_versions` 기준).
    - null이 아니면 manifest의 `skills/<id>/SKILL.md`를 `WorkspaceSkill(헤더 version, 원문)`으로, 그 밖의 `skills/**` 파일을 `resources`로 복사한다.
    - `start_versions`와 함께 넣으면 `INVALID_INPUT` "snapshot_id 와 start_versions 는 함께 쓸 수 없습니다"를 낸다.
  - `Map<String,String> filesOf(String workspaceId)`: `skills/<id>/SKILL.md` → 원문과 `resources`를 합친 전체 파일. Task 6이 쓴다.
  - `create(Map<String,Integer>)`는 `create(startVersions, null)`로 남긴다. 기존 테스트가 그대로 통과해야 한다.

- [ ] **Step 1: 테스트 작성** (`OrderAgentClient`는 Mockito mock)
  - `copiesSkillsFromSnapshot`: manifest에 `skills/payment/SKILL.md`, `skills/order-flow/SKILL.md`가 있으면 `skill_versions`가 `{order-flow:1, payment:1}`이고 `sourceSnapshotId`가 저장된다
  - `copiesResourcesAndTrailingNewline`: `skills/inventory-check/resources/policy.md`가 `resources`에 들어가고, 끝 줄바꿈이 있는 원문이 바이트 그대로 보존된다
  - `rejectsBaseWithSnapshot`: `create(Map.of("payment",1), "uuid")`는 IAE
  - `filesOfMergesSkillsAndResources`
- [ ] **Step 2: 실패 확인** — `cd service && ./gradlew test --tests '*WorkspaceFromSnapshotTest'`
- [ ] **Step 3: 구현**
- [ ] **Step 4: 통과 확인** — `./gradlew test` 전체
- [ ] **Step 5: Commit** `[FEAT] C-2 연습 공간을 주문 Agent snapshot 에서 시작 (create_workspace snapshot_id)`

---

### Task 5: C-2 — ⑧ 수정 적용하기 `apply_patch`

**Files:**
- Create: `service/src/main/java/com/capstone4/skillops/workspace/PatchApplier.java`
- Modify: `workspace/WorkspaceService.java`(`apply`), `workspace/WorkspaceRecord.java`(skills 갱신 메서드), `mcp/WorkspaceTools.java`(도구 `apply_patch`)
- Test: `service/src/test/java/com/capstone4/skillops/workspace/PatchApplierTest.java`, `service/src/test/java/com/capstone4/skillops/workspace/ApplyPatchTest.java`

**Interfaces:**
- Consumes: C-1 `DependencyAnalyzer.analyze(String entrySkillId, Map<String, SkillSource> sources)` → `DependencyGraph.missing()[{skillId, missingInputs, missingDependencies}]`. `SkillSource(path, fileHash, text)`이고 path는 `skills/<id>/SKILL.md`, 해시는 SHA-256 hex다.
- Produces:
  - `static String PatchApplier.apply(String original, String unifiedDiff)`
    - java-diff-utils `UnifiedDiffUtils.parseUnifiedDiff` + `DiffUtils.patch`를 쓴다.
    - 원문의 끝 줄바꿈 여부를 결과에도 그대로 둔다.
    - 실패하면 IAE `"<줄 번호>번째 줄 근처가 수정 내용과 맞지 않아 적용하지 못했습니다"`.
  - `PatchResult WorkspaceService.apply(String workspaceId, List<PatchInput> patches)`
    - `record PatchInput(String skill, int baseVersion, String patch)`
    - `record PatchResult(String workspaceId, List<ChangedSkill> changedSkills, List<BrokenLink> brokenLinks)`, `ChangedSkill(skill, version)`, `BrokenLink(skill, missingInput)`. 모두 snake_case `@JsonNaming`.
    - `version`은 `baseVersion + 그 skill에 수정이 들어간 횟수`다. `WorkspaceSkill`에 `revision`(정수, 기본 0)을 더한다.
    - 전부 적용하거나 하나도 적용하지 않는다. 실패하면 저장하지 않는다.
    - `base_version`이 연습 공간 skill의 `baseVersion`과 다르면 IAE `"<skill> 의 기준 버전이 맞지 않습니다 (연습 공간 v<a>, 수정 내용 v<b>)"`.
    - 없는 skill이면 IAE.
    - `brokenLinks`는 적용 뒤 `DependencyAnalyzer.analyze("order-flow", …)`의 `missing`을 `{skill, missing_input}`으로 펼친 것이다.
  - MCP 도구 `apply_patch(workspace_id, patches[{skill, base_version, patch}])` → `{workspace_id, changed_skills[{skill, version}], broken_links[{skill, missing_input}]}`(B `tools.py`가 읽는 모양)

- [ ] **Step 1: `PatchApplierTest` 작성**
  - `appliesSingleHunk`: F1 broken `outputs:` 줄을 repaired 줄로 바꾸는 diff
  - `appliesMultiHunk`
  - `keepsTrailingNewline`
  - `contextMismatchFails`: 메시지에 "맞지 않아"
  - `appliesC1HunkDiff`: C-1 `SkillDiffer.diff(...)`가 만든 `hunks[0].diff`를 그대로 적용하면 새 글과 같다
- [ ] **Step 2: `ApplyPatchTest` 작성** (저장소는 Mockito, 연습 공간은 F1 broken skill 6개)
  - `appliesAndReportsNoViolations`: repaired 수정 → `broken_links` 빈 목록, `changed_skills[0].version == 2`
  - `reportsViolationsForBreakingPatch`: baseline 상태에서 broken 수정 → `{skill: payment, missing_input: inventory.reserved}`
  - `contextMismatchRejectsAndKeepsWorkspace`: 두 수정 가운데 두 번째가 실패하면 IAE이고 `repository.save` 호출 없음
  - `baseVersionMismatchRejects`
- [ ] **Step 3: 실패 확인** → **Step 4: 구현** → **Step 5: 통과 확인**(`./gradlew test` 전체)
- [ ] **Step 6: Commit** `[FEAT] C-2 apply_patch: 연습 공간에 수정 적용과 끊긴 연결 검사`

---

### Task 6: C-2 — 주문 Agent를 부르는 실제 실행기 `HarnessFlowRunner`와 재현 주문

**Files:**
- Create: `service/src/main/java/com/capstone4/skillops/scenario/OrderAgentRunClient.java`(C-2 전용 HTTP. base-url은 `skillops.order-agent.base-url`)
- Create: `service/src/main/java/com/capstone4/skillops/scenario/HarnessFlowRunner.java`
- Create: `service/src/main/java/com/capstone4/skillops/scenario/OrderAgentTraceConverter.java`
- Modify: `scenario/ScenarioRunner.java`(재현 주문), `mcp/WorkspaceTools.java`(`replay_run_ids`)
- Modify: `service/src/main/resources/application.yml`(`skillops.flow-runner` 설명에 `harness` 추가)
- Test: `service/src/test/java/com/capstone4/skillops/scenario/OrderAgentTraceConverterTest.java`, `HarnessFlowRunnerTest.java`, `ScenarioRunnerReplayTest.java`
- Test resources: `service/src/test/resources/order-agent/trace-shipped.json`, `trace-contract-violation.json`
  - 실측 trace를 저장한다: 주문 Agent를 띄우고 baseline·broken-contract로 주문한 뒤 `curl localhost:8081/api/executions/<id>/trace > …`

**Interfaces:**
- Consumes: Task 1·2의 HTTP, Task 4 `WorkspaceService.filesOf(String)`, Task 3의 v1 `ScenarioRunner`, 이미 있는 `RunOutcome`·`FlowRunner.DryRunRequest`(2026-10-20 계획에서 만든 것)
- Produces:
  - `OrderAgentRunClient`
    - `String publishCandidate(Map<String,String> files, Map<String,Object> metadata)` → snapshot_id
    - `DryRunStarted dryRun(String snapshotId, Map<String,Object> order)` → `record DryRunStarted(String executionId, String runId, String finalStatus)`
    - `Map<String,Object> trace(String executionId)`
    - `Map<String,Object> originalOrder(String executionIdOrRunId)`: `r_<32자리 hex>`면 execution_id로 바꾼다. trace의 `order_id`로 `GET /api/orders/{id}`를 불러 `{customer, items[{sku, qty}], payment_method, shipping{country, express}}`만 돌려준다. 없으면 IAE `"재현할 실행 <id> 을 주문 Agent 에서 찾을 수 없습니다"`.
    - 연결 실패·5xx는 `IllegalStateException("주문 Agent 에 연결할 수 없습니다: <url> (<원인>)")`. 연결 2초, 응답 30초 timeout.
  - `static RunOutcome OrderAgentTraceConverter.toOutcome(String runId, Map<String,Object> trace)`
    - `SKILL_STARTED`의 `input`과 `SKILL_FINISHED`의 `status`·`output`으로 단계를 만든다(`step_no` 순서).
    - `EXECUTION_ERROR`가 있으면 그 `skill_id`의 `error` 단계를 덧붙인다. 오류 문구는 `"<category>: <message> (빠진 값 <missing_inputs>)"`이다.
    - 최종 상태는 trace의 `final_status`다.
  - `@Component @ConditionalOnProperty(name="skillops.flow-runner", havingValue="harness") HarnessFlowRunner implements FlowRunner`
    1. `filesOf(workspaceId)`
    2. 파일 묶음 SHA-256으로 snapshot을 캐시한다(같은 묶음이면 다시 올리지 않음).
    3. 시나리오 `order`를 주문 Agent 요청(`customer`, `items`, `payment_method`, `shipping`)으로 그대로 넘긴다.
    4. `dryRun` 후 `trace`, 그리고 `toOutcome`
  - 재현 주문(`ScenarioRunner`)
    - `run(String workspaceId, List<String> scenarioIds, List<String> replayRunIds, List<String> extraChecks, Integer repeat)`. MCP 도구에 `replay_run_ids`(선택)를 더하고, `scenario_ids`도 선택으로 바꾼다. 둘 다 비면 IAE `"scenario_ids 나 replay_run_ids 가운데 하나는 넣으세요"`.
    - `scenario_ids`의 `f-<UUID>`와 `replay_run_ids`의 각 값을 재현 주문으로 바꾼다: `originalOrder`로 주문을 꺼내고, 기대값은 `Expectation.replay()`, 결과 `scenario_id`는 `f-<execution_id>`, `replay_of_run_id`는 받은 값이다.
    - 재현 주문의 원래 주문은 **주문을 돌리기 전에** 모두 꺼내 둔다. 하나라도 없으면 아무것도 돌리지 않는다.
    - 가짜 실행기(`contract-only`)에서는 재현 주문을 받지 않는다. IAE `"재현 주문은 skillops.flow-runner=harness 에서만 돌릴 수 있습니다"`.

- [ ] **Step 1: `OrderAgentTraceConverterTest` 작성** (저장한 실측 trace 사용)
  - `shippedTraceBecomesFiveOkSteps`: 5단계 모두 `ok`, `finalStatus == SHIPPED`, 출력에 `payment.status == PAID`
  - `contractViolationBecomesErrorStep`: 마지막 단계가 payment `error`이고 오류에 `inventory.reserved`, `finalStatus == FAILED`
- [ ] **Step 2: `HarnessFlowRunnerTest` 작성** (`MockRestServiceServer`로 주문 Agent를 대신함)
  - `publishesAllWorkspaceFiles`: candidates 요청 본문의 `files`에 `resources`까지 들어 있다
  - `reusesSnapshotForSameFiles`: 같은 연습 공간으로 두 번 돌리면 candidates 요청이 1번이다
  - `orderAgentDownIsReported`: 503이면 `IllegalStateException`이고 메시지에 "주문 Agent"
  - `runsAndConverts`: 결과의 `runId`가 `r_`로 시작하고 `finalStatus`가 trace와 같다
  - `ScenarioRunnerReplayTest`(새 파일, `OrderAgentRunClient`는 Mockito):
    - `replaysFailedExecutionAsF`: `scenario_ids=["f-<uuid>"]`면 결과 `scenario_id`가 같은 `f-<uuid>`이고 `replay_of_run_id`가 `<uuid>`다
    - `replayRunIdAliasAccepted`: `replay_run_ids=["r_<hex>"]`가 같은 execution으로 풀린다
    - `replayPassesUnlessFailed`
    - `unknownReplayExecutionRejectedBeforeRunning`: `originalOrder`가 IAE면 `FlowRunner` 호출 0번
    - `replayNeedsHarnessRunner`
- [ ] **Step 3: 실패 확인** → **Step 4: 구현** → **Step 5: 통과 확인**(`./gradlew test` 전체)
- [ ] **Step 6: Commit** `[FEAT] C-2 HarnessFlowRunner: 주문 Agent 모의 실행과 재현 주문(f-<execution_id>, replay_run_ids)`

---

### Task 7: 주문 Agent용 기준 주문과 끝까지 돌리는 실험

**Files:**
- Create: `scenarios/order-agent/golden/g-01.json`, `g-02.json`, `g-03.json`. D의 `scenarios/golden/`은 건드리지 않는다.
- Create: `skillops-agent/scripts/c2_real_judge.py`
- Modify: `docs/contracts/mcp-tools-input-output.md` 7·8·9번(`snapshot_id`, `apply_patch` 구현됨, 실제 실행기)

**Interfaces:**
- Consumes: Task 1~6 전부. MCP `create_workspace(snapshot_id)`, `apply_patch`, `run_scenarios`(재현 포함), C-1 `diff_skill`.

기준 주문은 주문 Agent README의 시연 규칙에서 가져온다.

| id | 주문 | expect |
|---|---|---|
| g-01 | c_1001, CAMP-LANTERN-LED 1개, card, KR | `final_status: SHIPPED`, `shipments: 1`, `skills_called` 5개 전부, `assertions: ["payment.status == 'PAID'", "notify.sent == true"]` |
| g-02 | c_1001, CAMP-MUG-STEEL 1개, card, KR | `final_status: REJECTED`, `shipments: 0` |
| g-03 | c_declined, CAMP-LANTERN-LED 1개, card, KR | `final_status: HOLD`, `shipments: 0` |

- [ ] **Step 1: 기준 주문 파일 3개 작성**
  - 실제 모의 실행으로 기대값이 맞는지 확인한다. 연습 공간을 baseline snapshot에서 만들고 `run_scenarios(g-01,g-02,g-03)`을 돌려 3개 모두 `passed: true`여야 한다.
  - 맞지 않으면 주문 Agent README 규칙과 실제 실행 기록 가운데 무엇이 틀렸는지 적고 담당자에게 알린다. 기대값을 실행 결과에 맞춰 고쳐 쓰지 않는다.
- [ ] **Step 2: `c2_real_judge.py` 작성** (`mcp_call.py` 모양, 주문 Agent HTTP는 `httpx`)
  1. baseline·broken-contract·repaired fixture snapshot을 만든다.
  2. broken으로 일반 주문을 넣어 실제 실패를 만들고, `/api/executions/failed`의 오류를 찍는다.
  3. C-1 `diff_skill`(broken→repaired)의 `hunks[].diff`로 후보 (나)를 만든다.
  4. `difflib.unified_diff`로 후보 (가) 되돌리기(broken→baseline)와 (다) payment 입력 변경(`inventory.reserved` → `inventory.reservable_quantity`)을 만든다.
  5. 후보마다 `create_workspace(snapshot_id=broken)` → `apply_patch` → `run_scenarios(scenario_ids=[g-01,g-02,g-03, "f-<2단계 실패 execution_id>"], extra_checks=["inventory.reservable_quantity != null"], repeat=1)`
  6. 표로 찍는다: 후보 / 끊긴 연결 / 시나리오별 합격 / 실제 실패 주문 재현 합격 / 의도 확인 / 유효
  7. 시작과 끝의 랜턴 재고, 실패 목록 개수를 비교해 같지 않으면 종료 코드 1
- [ ] **Step 3: 실험 실행과 기대 결과 확인**

  ```bash
  (cd order-agent && docker compose up --build -d --wait)
  (cd service && SKILLOPS_FLOW_RUNNER=harness SKILLOPS_SCENARIOS_DIR=../scenarios/order-agent ./gradlew bootRun)   # 다른 창
  (cd skillops-agent && uv run scripts/c2_real_judge.py)
  ```
  Expected:
  - (가) 되돌리기: 끊긴 연결 0, g-01~g-03 통과, 의도 확인 실패 → 무효
  - (나) repaired: 실제 실패 주문 재현까지 전부 통과 → 유효
  - (다): 결과가 무엇이든 실제 실행 기록 근거와 함께 찍힌다. 판정은 실험 결과로 정한다.
  - 재고와 실패 목록 개수는 그대로다.

  (가)·(나)가 기대와 다르면 기대값이나 코드를 맞춰 고치지 않는다. 실행 기록과 함께 원인을 적고 멈춘다. 그 차이 자체가 이 실험의 결과다.
- [ ] **Step 4: 문서 반영** — 7번에 `snapshot_id`, 8번을 "구현됨"으로, 9번에 실제 실행기·재현 주문(`f-<execution_id>`, `replay_run_ids`)·`SKILLOPS_SCENARIOS_DIR` 설명
- [ ] **Step 5: Commit** `[FEAT] C-2 실제 판정 실험: 주문 Agent 기준 주문과 c2_real_judge.py`

---

## 다음 계획 (이 문서 범위 밖)

- **AI를 넣은 실험**: B의 파이프라인을 실제 증거(주문 Agent 조회 API + C-1 MCP)와 실제 판정(이 계획의 C-2 도구)에 잇는 실험용 연결 계층. AI가 만든 후보를 이 계획의 판정 경로로 채점한다.
- **B에게 요청할 것**:
  - `tools.py`의 조회 4개를 #39 API로, `diff_skill`·`get_dependency_graph` 인자를 snapshot 기준으로 바꾸기
  - 원인 skill을 "고친 skill"로 잡기
  - 확인 조건 문법 검사
  - 후보 생성에 실제 파일 본문 넘기기
  - `create_workspace`에 실패 snapshot의 `snapshot_id` 넘기기, 원인 확인(ablate)의 `"f-01"`을 `f-<execution_id>`로 바꾸기
