# C-2 연습 공간 만들기(⑦) + 시험 주문 돌리기(⑨) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Agent가 MCP로 `create_workspace`를 불러 연습 공간을 만들고, `run_scenarios`로 기준 주문 g-01을 모의 실행해 채점 결과를 받을 수 있게 한다. C-2의 1일차 15시 마일스톤이다.

**Architecture:** ⑨ 시험 주문 돌리기는 "모의 실행"과 "채점" 두 부분으로 나뉜다. 채점(`ScenarioGrader`)은 C-2 혼자 만들 수 있으니 먼저 만든다. 모의 실행은 `FlowRunner` 인터페이스 뒤에 숨긴다. A의 실행기가 붙기 전까지는 SKILL.md 맨 위 칸(받는 값·내놓는 값)만 보고 흉내 내는 가짜 실행기(`ContractOnlyFlowRunner`)를 쓴다. 이 가짜 실행기로도 F1처럼 연결이 끊긴 장애는 재현된다. 연습 공간은 `workspaces` 테이블 한 줄에 skill 원문을 JSON 칸으로 묶어 저장한다(C-1의 `trace_run`과 같은 방식). skill 원문은 `SkillVersionStore` 인터페이스로 읽는다. C-1의 버전 저장소가 붙기 전에는 운영 폴더 `skills/`를 읽는 구현을 쓴다.

**Tech Stack:** Java 21 · Spring Boot 4.1.0 · Spring AI 2.0.0 (`@McpTool`) · Spring Data JPA + PostgreSQL 17 · Jackson 3 (`tools.jackson`) · JUnit 5 + AssertJ + Mockito · Python 3.11 + uv + mcp 2.3 (확인 스크립트)

**Spec:**
- `docs/contracts/mcp-tools-input-output.md` 의 7번 `create_workspace`, 9번 `run_scenarios` (계약 5 v1 제안)
- `docs/contracts/scenario.schema.json` (시험 주문 모양), `docs/contracts/skill-frontmatter.md` (맨 위 칸 규칙)
- `docs/roles/C2-mcp-workspace-runner.md` (C-2 책임, 마일스톤)
- 채점 규칙: 이 문서의 Global Constraints "채점 규칙" (대화에서 합의한 설계를 옮겨 적음)

## Global Constraints

- 코드는 C-2 폴더에만 둔다: `service/…/scenario/`, `service/…/workspace/`(새 파일만), `service/…/mcp/WorkspaceTools.java`. C-1 파일(`trace/`, `PingTools.java`)은 고치지 않는다.
- JSON 처리는 Jackson 3: databind 클래스는 `tools.jackson.databind.*`, 애노테이션은 `com.fasterxml.jackson.annotation.*`(Jackson 3도 이 패키지를 그대로 쓴다). `com.fasterxml.jackson.databind`는 쓰지 않는다.
- 도구가 돌려주는 JSON 키는 snake_case. 결과 record마다 `@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)`를 붙인다(중첩 record에도 각각).
- 도구가 받는 값의 키는 **Java 메서드 파라미터 이름 그대로**다(`@McpToolParam`에 name 속성이 없다). 그래서 `WorkspaceTools`의 파라미터만 `workspace_id`처럼 snake_case로 쓴다.
- `./gradlew test`는 PostgreSQL 없이 통과해야 한다. 저장소(Repository)는 단위 테스트에서 Mockito로 대신한다. 실제 DB 확인은 Task 5의 확인 스크립트로 한다.
- 오류는 `IllegalArgumentException`에 한국어 한 줄로 "무엇이 잘못됐고 어떻게 고치면 되는지"를 적는다. 예: `workspace_id w_x 이 없습니다. create_workspace 로 먼저 만드세요`
- `repeat` 기본값 2, 허용 범위 1~5.
- 설정 키: `skillops.skills-dir`(기본 `../skills`), `skillops.scenarios-dir`(기본 `../scenarios`), `skillops.flow-runner`(기본 `contract-only`, A 실행기가 붙으면 `harness`).
- 커밋 메시지는 팀 규칙 `[FEAT]`·`[TEST]`·`[DOCS]` 접두어. Claude 공동 저자 줄이나 생성 표기는 넣지 않는다.
- 이번 범위 밖: `replay_run_ids`(재현 주문), `scenarios`·`scenario_results` 테이블, `apply_patch`. 다음 계획(원인 확인, 1일차 18시)에서 한다.

**채점 규칙** (Task 1이 구현)

1. 거친 skill 순서: 결과가 `ok`인 단계의 skill을 순서대로 모아 `skills_called`와 정확히 같아야 한다.
2. 최종 상태: `final_status`와 같아야 한다.
3. 배송 건수: 결과가 `ok`인 `shipping-reserve` 단계 가운데 출력에 `shipping.id`가 있는 단계의 수. (D 확인 필요. 바뀌면 이 함수 하나만 고친다)
4. 확인 조건: 모든 단계의 `input`과 `output`을 순서대로 합친 묶음(뒤 단계가 덮어씀)에서 평가한다. 없는 값은 `null`.
5. 재현 기준(`Expectation.replay()`): 최종 상태가 `FAILED`가 아니어야 한다.
6. 기대값 칸이 비어 있으면(`skills_called`, `final_status`, `shipments`가 `null`, `assertions` 없음) 그 항목은 채점하지 않는다.
7. 문제가 난 skill: 처음 `error`인 단계의 skill. 없으면 `null`.
8. 떨어진 이유 문구와 순서(정확히 이 글자):
   1. `"{skill} 단계 오류: {error}"` (불합격이고 오류 단계가 있을 때만)
   2. `"거친 skill 순서 기대 {expected}, 실제 {actual}"` (목록은 `List.toString()` 모양)
   3. `"최종 상태 기대 {expected}, 실제 {actual}"`
   4. `"오류로 멈추지 않아야 하는데 최종 상태가 FAILED"` (재현 기준)
   5. `"배송 건수 기대 {expected}, 실제 {actual}"`
   6. `"확인 조건 실패: {조건 원문} (실제 {값})"` — 시나리오의 조건, 그다음 추가 조건 순서. 값 표기는 글자면 `'DECLINED'`, 없으면 `null`, 나머지는 `toString()`.

**확인 조건 문법** (Task 1이 구현): `^\s*(\S+)\s+(==|!=|in)\s+(.+?)\s*$`. 오른쪽 값은 `'글자'`, `true`, `false`, `null`, 숫자(`-?\d+(\.\d+)?`), 목록 `[값, 값]`(`in`일 때만, `in`이면 반드시 목록). 숫자끼리는 `BigDecimal.compareTo`로 비교해 `39000`과 `39000.0`을 같게 본다. 읽을 수 없으면 `IllegalArgumentException("확인 조건을 읽을 수 없습니다: " + 원문 + " (예: payment.status == 'PAID')")`.

## Review Focus

1. Agent가 없는 `workspace_id`를 보낼 때(오타, 서버 재시작 뒤). 연습 공간 번호를 짚는 오류를 내고 주문은 하나도 돌리지 않아야 한다. → Task 2 `skillsOfUnknownWorkspaceFails`, Task 4 `rejectsUnknownWorkspaceBeforeRunning`
2. 없는 시험 주문 번호(`g-99`, 아직 없는 `f-01`). 있는 번호 목록을 보여 주는 오류를 내야 한다. → Task 4 `rejectsUnknownScenario`
3. `extra_checks` 오타(`==` 대신 `=`). 주문을 돌리기 전에 거부해야 한다. 일부만 돌고 비용이 나가면 안 된다. → Task 4 `rejectsBadCheckBeforeRunning`
4. `start_versions`에 없는 skill이나 제공할 수 없는 버전. 오류를 내고 아무것도 저장하지 않아야 한다. → Task 2 `createRejectsUnknownSkillAndSavesNothing`, `createRejectsUnavailableVersion`
5. 기대값 칸이 일부 빠진 시나리오 파일(배송 건수나 확인 조건 없음). 그 항목만 건너뛰고 터지지 않아야 한다. → Task 1 `skipsMissingExpectFields`

---

### Task 0: 가져온 문서 먼저 커밋

**Files:** 이미 작업 트리에 있는 `docs/guides/`, `docs/contracts/mcp-tools-input-output.md`, `docs/contracts/README.md`, 그리고 이 계획 파일

- [ ] **Step 1: 문서만 묶어 커밋**

```bash
git add docs/guides docs/contracts/mcp-tools-input-output.md docs/contracts/README.md docs/superpowers/plans/2026-10-20-c2-workspace-and-scenario-runner.md
git commit -m "[DOCS] C-2 이해 자료와 계약 5 v1 제안, ⑦⑨ 구현 계획 추가"
```

Expected: `git status -s`가 비어 있다.

---

### Task 1: 채점기 (⑨의 채점 부분)

**Files:**
- Create: `service/src/main/java/com/capstone4/skillops/scenario/RunOutcome.java`
- Create: `service/src/main/java/com/capstone4/skillops/scenario/Scenario.java`
- Create: `service/src/main/java/com/capstone4/skillops/scenario/Check.java`
- Create: `service/src/main/java/com/capstone4/skillops/scenario/Expectation.java`
- Create: `service/src/main/java/com/capstone4/skillops/scenario/GradeResult.java`
- Create: `service/src/main/java/com/capstone4/skillops/scenario/ScenarioGrader.java`
- Test: `service/src/test/java/com/capstone4/skillops/scenario/CheckTest.java`
- Test: `service/src/test/java/com/capstone4/skillops/scenario/ScenarioGraderTest.java`
- Test: `service/src/test/java/com/capstone4/skillops/scenario/TestFixtures.java` (테스트 공용 도우미. 이 Task에서 `static Scenario g01()`을 만들고 Task 3에서 더한다)

**Interfaces:**
- Produces:
  - `record RunOutcome(String runId, String finalStatus, List<Step> steps)`, 중첩 `record Step(String skill, String status, Map<String,Object> input, Map<String,Object> output, String error)`. `status`는 `"ok"`·`"error"`·`"skipped"`, `finalStatus`는 `"SHIPPED"`·`"REJECTED"`·`"HOLD"`·`"FAILED"`.
  - `record Scenario(String id, String name, String sourceRunId, Map<String,Object> order, Expect expect, Integer k)`, 중첩 `record Expect(List<String> skillsCalled, String finalStatus, Integer shipments, List<String> assertions)`. 둘 다 snake_case `@JsonNaming`, `@JsonIgnoreProperties(ignoreUnknown = true)`.
  - `record Check(String text, String key, Op op, Object expected)`: `static Check parse(String text)`, `boolean holds(Map<String,Object> context)`, `String actualText(Map<String,Object> context)`. `enum Op { EQ, NE, IN }`
  - `record Expectation(List<String> skillsCalled, String finalStatus, Integer shipments, List<Check> checks, boolean mustNotFail)`: `static Expectation of(Scenario.Expect expect)`, `static Expectation replay()`
  - `record GradeResult(boolean passed, String skill, List<String> reasons)`
  - `@Component class ScenarioGrader`: `GradeResult grade(Expectation expectation, List<Check> extraChecks, RunOutcome run)`, `static Map<String,Object> contextOf(RunOutcome run)`

- [ ] **Step 1: 확인 조건 테스트 작성**

```java
class CheckTest {
    @Test void stringEquality() {
        Check c = Check.parse("payment.status == 'PAID'");
        assertThat(c.holds(Map.of("payment.status", "PAID"))).isTrue();
        assertThat(c.holds(Map.of("payment.status", "DECLINED"))).isFalse();
    }
    @Test void booleanAndNullWithKoreanKey() {
        assertThat(Check.parse("notify.sent == true").holds(Map.of("notify.sent", true))).isTrue();
        Check present = Check.parse("inventory.예약가능수량 != null");
        assertThat(present.holds(Map.of())).isFalse();
        assertThat(present.holds(Map.of("inventory.예약가능수량", 12))).isTrue();
    }
    @Test void numbersCompareByValue() {
        Check c = Check.parse("order.total == 39000");
        assertThat(c.holds(Map.of("order.total", 39000))).isTrue();
        assertThat(c.holds(Map.of("order.total", 39000.0))).isTrue();
    }
    @Test void inChecksMembership() {
        Check c = Check.parse("payment.status in ['PAID', 'HOLD']");
        assertThat(c.holds(Map.of("payment.status", "HOLD"))).isTrue();
        assertThat(c.holds(Map.of("payment.status", "FAILED"))).isFalse();
    }
    @Test void rejectsMalformed() {
        assertThatThrownBy(() -> Check.parse("payment.status = PAID"))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessage("확인 조건을 읽을 수 없습니다: payment.status = PAID (예: payment.status == 'PAID')");
        assertThatThrownBy(() -> Check.parse("payment.status in 'PAID'"))
            .isInstanceOf(IllegalArgumentException.class);
    }
    @Test void actualText() {
        Check c = Check.parse("payment.status == 'PAID'");
        assertThat(c.actualText(Map.of())).isEqualTo("null");
        assertThat(c.actualText(Map.of("payment.status", "DECLINED"))).isEqualTo("'DECLINED'");
    }
}
```

- [ ] **Step 2: 실패 확인**

Run: `cd service && ./gradlew test --tests 'com.capstone4.skillops.scenario.CheckTest'`
Expected: `BUILD FAILED`, `cannot find symbol ... Check`

- [ ] **Step 3: `Check` 구현** (`scenario/Check.java`)

Global Constraints의 "확인 조건 문법"대로 만든다. 목록 값은 바깥 대괄호를 벗기고 쉼표로 나눈 뒤 각각을 같은 규칙으로 읽는다. `expected`는 글자는 `String`, 숫자는 `BigDecimal`, 목록은 `List<Object>`로 둔다.

- [ ] **Step 4: 통과 확인**

Run: `cd service && ./gradlew test --tests 'com.capstone4.skillops.scenario.CheckTest'`
Expected: `BUILD SUCCESSFUL`, 6 tests passed

- [ ] **Step 5: 채점기 테스트 작성**

`TestFixtures.g01()`은 `JsonMapper.builder().build().readValue(Path.of("../scenarios/golden/g-01.json").toFile(), Scenario.class)`로 실제 파일을 읽는다.
`shippedRun()`은 5단계가 모두 `ok`이고 최종 상태가 `SHIPPED`인 실행이다.
- 출력값: `order-intake` → `order.id`, `order.total`. `inventory-check` → `inventory.available: 10`, `inventory.reserved: true`. `payment` → `payment.id`, `payment.status: "PAID"`. `shipping-reserve` → `shipping.id: "sh_1"`, `shipping.eta`. `notify` → `notify.sent: true`, `notify.channel: "sms"`.
- `f1Run()`의 단계별 결과:
  - `order-intake` ok
  - `inventory-check` ok, 출력 `inventory.예약가능수량: 12`
  - `payment` error, 오류 `"required input inventory.reserved not found"`
  - `shipping-reserve`, `notify` skipped
  - 최종 상태 `FAILED`

```java
class ScenarioGraderTest {
    ScenarioGrader grader = new ScenarioGrader();

    @Test void passesG01() {
        GradeResult r = grader.grade(Expectation.of(g01().expect()), List.of(), shippedRun());
        assertThat(r.passed()).isTrue();
        assertThat(r.reasons()).isEmpty();
        assertThat(r.skill()).isNull();
    }
    @Test void f1RunFailsWithAllReasonsInOrder() {
        GradeResult r = grader.grade(Expectation.of(g01().expect()), List.of(), f1Run());
        assertThat(r.passed()).isFalse();
        assertThat(r.skill()).isEqualTo("payment");
        assertThat(r.reasons()).containsExactly(
            "payment 단계 오류: required input inventory.reserved not found",
            "거친 skill 순서 기대 [order-intake, inventory-check, payment, shipping-reserve, notify], 실제 [order-intake, inventory-check]",
            "최종 상태 기대 SHIPPED, 실제 FAILED",
            "배송 건수 기대 1, 실제 0",
            "확인 조건 실패: payment.status == 'PAID' (실제 null)",
            "확인 조건 실패: notify.sent == true (실제 null)");
    }
    @Test void failsOnWrongValueOnly() {
        GradeResult r = grader.grade(Expectation.of(g01().expect()), List.of(), shippedRunWith("payment.status", "DECLINED"));
        assertThat(r.reasons()).containsExactly("확인 조건 실패: payment.status == 'PAID' (실제 'DECLINED')");
        assertThat(r.skill()).isNull();
    }
    @Test void extraChecksApplyAfterScenarioChecks() {
        GradeResult r = grader.grade(Expectation.of(g01().expect()),
            List.of(Check.parse("inventory.예약가능수량 != null")), shippedRun());
        assertThat(r.reasons()).containsExactly("확인 조건 실패: inventory.예약가능수량 != null (실제 null)");
    }
    @Test void replayPassesUnlessFailed() {
        assertThat(grader.grade(Expectation.replay(), List.of(), runWithFinalStatus("REJECTED")).passed()).isTrue();
        assertThat(grader.grade(Expectation.replay(), List.of(), f1Run()).reasons())
            .contains("오류로 멈추지 않아야 하는데 최종 상태가 FAILED");
    }
    @Test void skipsMissingExpectFields() {
        var expect = new Scenario.Expect(
            List.of("order-intake", "inventory-check", "payment", "shipping-reserve", "notify"), "SHIPPED", null, null);
        assertThat(grader.grade(Expectation.of(expect), List.of(), shippedRun()).passed()).isTrue();
    }
}
```

- [ ] **Step 6: 실패 확인**

Run: `cd service && ./gradlew test --tests 'com.capstone4.skillops.scenario.ScenarioGraderTest'`
Expected: `BUILD FAILED`, `cannot find symbol ... ScenarioGrader`

- [ ] **Step 7: `RunOutcome`, `Scenario`, `Expectation`, `GradeResult`, `ScenarioGrader` 구현**

Global Constraints의 "채점 규칙"대로 만든다. `Expectation.of`는 `assertions`가 `null`이면 빈 목록으로 두고, 각 조건을 `Check.parse`로 읽는다.

- [ ] **Step 8: 통과 확인**

Run: `cd service && ./gradlew test --tests 'com.capstone4.skillops.scenario.*'`
Expected: `BUILD SUCCESSFUL`, 12 tests passed

- [ ] **Step 9: Commit**

```bash
git add service/src/main/java/com/capstone4/skillops/scenario service/src/test/java/com/capstone4/skillops/scenario
git commit -m "[FEAT] C-2 채점기: 모의 실행 결과를 시험 주문의 기대값과 비교"
```

---

### Task 2: 연습 공간 만들기 (⑦)

**Files:**
- Create: `service/src/main/java/com/capstone4/skillops/workspace/SkillHeader.java`
- Create: `service/src/main/java/com/capstone4/skillops/workspace/SkillVersionStore.java`
- Create: `service/src/main/java/com/capstone4/skillops/workspace/LiveFolderSkillVersionStore.java`
- Create: `service/src/main/java/com/capstone4/skillops/workspace/WorkspaceSkill.java`
- Create: `service/src/main/java/com/capstone4/skillops/workspace/WorkspaceRecord.java`
- Create: `service/src/main/java/com/capstone4/skillops/workspace/WorkspaceRepository.java`
- Create: `service/src/main/java/com/capstone4/skillops/workspace/WorkspaceService.java`
- Modify: `docs/contracts/mcp-tools-input-output.md` (저장 공간 이름)
- Test: `service/src/test/java/com/capstone4/skillops/workspace/SkillHeaderTest.java`
- Test: `service/src/test/java/com/capstone4/skillops/workspace/LiveFolderSkillVersionStoreTest.java`
- Test: `service/src/test/java/com/capstone4/skillops/workspace/WorkspaceServiceTest.java`

**Interfaces:**
- Produces:
  - `record SkillHeader(String name, Integer version, List<String> inputs, List<String> outputs, List<String> steps)`: `static SkillHeader parse(String fullText)`. 없는 목록은 빈 목록, 없는 버전은 `null`. `---` 사이 칸이 없으면 `IllegalArgumentException("SKILL.md 맨 위 칸(--- 사이)을 찾을 수 없습니다")`
  - `interface SkillVersionStore`: `Map<String,Integer> liveVersions()`, `String fullText(String skill, int version)`. Javadoc에 "C-1 버전 저장소가 구현할 읽기 쪽 접점. C-1 구현은 `@Primary`를 붙인다"라고 적는다.
  - `@Component class LiveFolderSkillVersionStore implements SkillVersionStore`, 생성자 `(@Value("${skillops.skills-dir}") Path skillsDir)`
  - `record WorkspaceSkill(int baseVersion, String fullText)`
  - `@Entity @Table(name = "workspaces") class WorkspaceRecord`. 칸은 `workspace_id`(PK), `created_at`, `status`(`enum WorkspaceRecord.Status { OPEN, PROMOTED, DISCARDED }`, 문자열 저장), `skills`(`Map<String, WorkspaceSkill>`, `@JdbcTypeCode(SqlTypes.JSON)`). `static WorkspaceRecord open(String workspaceId, Instant createdAt, Map<String,WorkspaceSkill> skills)`와 getter를 둔다. 모양은 C-1의 `TraceRecord`를 따른다(protected 기본 생성자 + static 생성 메서드).
  - `interface WorkspaceRepository extends JpaRepository<WorkspaceRecord, String>`
  - `@Service class WorkspaceService(WorkspaceRepository, SkillVersionStore)`:
    - `CreatedWorkspace create(Map<String,Integer> startVersions)`
    - `Map<String, WorkspaceSkill> skillsOf(String workspaceId)`
    - `CreatedWorkspace`는 중첩 record `(String workspaceId, Map<String,Integer> skillVersions, Instant createdAt)`이고 snake_case `@JsonNaming`을 붙인다.

- [ ] **Step 1: 맨 위 칸 읽기 테스트 작성**

```java
class SkillHeaderTest {
    @Test void readsRealPaymentSkill() throws Exception {
        SkillHeader h = SkillHeader.parse(Files.readString(Path.of("../skills/payment/SKILL.md")));
        assertThat(h.name()).isEqualTo("payment");
        assertThat(h.version()).isEqualTo(1);
        assertThat(h.inputs()).contains("inventory.reserved");
        assertThat(h.outputs()).containsExactly("payment.id", "payment.status");
    }
    @Test void readsOrderFlowSteps() throws Exception {
        SkillHeader h = SkillHeader.parse(Files.readString(Path.of("../skills/order-flow/SKILL.md")));
        assertThat(h.steps()).containsExactly("order-intake", "inventory-check", "payment", "shipping-reserve", "notify");
        assertThat(h.inputs()).isEmpty();
    }
    @Test void rejectsTextWithoutHeader() {
        assertThatThrownBy(() -> SkillHeader.parse("# 제목만 있음"))
            .hasMessage("SKILL.md 맨 위 칸(--- 사이)을 찾을 수 없습니다");
    }
}
```

- [ ] **Step 2: 실패 확인**

Run: `cd service && ./gradlew test --tests 'com.capstone4.skillops.workspace.SkillHeaderTest'`
Expected: `BUILD FAILED`, `cannot find symbol ... SkillHeader`

- [ ] **Step 3: `SkillHeader` 구현.** `key: [a, b]` 줄은 대괄호 안을 쉼표로 나눠 앞뒤 공백을 뗀다. `version: 3`은 정수로 읽는다.

- [ ] **Step 4: 통과 확인** — 같은 명령, 3 tests passed

- [ ] **Step 5: 운영 폴더 저장소 테스트 작성** (`@TempDir`에 `a/SKILL.md`를 `version: 3`으로 만든다)

```java
class LiveFolderSkillVersionStoreTest {
    @TempDir Path dir;
    LiveFolderSkillVersionStore store;

    @BeforeEach void setUp() throws Exception {
        Files.createDirectories(dir.resolve("a"));
        Files.writeString(dir.resolve("a/SKILL.md"), "---\nname: a\noutputs: [a.x]\nversion: 3\n---\n본문\n");
        store = new LiveFolderSkillVersionStore(dir);
    }
    @Test void liveVersionsReadsHeader() { assertThat(store.liveVersions()).isEqualTo(Map.of("a", 3)); }
    @Test void fullTextOfLiveVersion() { assertThat(store.fullText("a", 3)).contains("본문"); }
    @Test void olderVersionNotInFolder() {
        assertThatThrownBy(() -> store.fullText("a", 1))
            .hasMessage("운영 폴더에는 a 버전 3 만 있습니다 (요청 1). 예전 버전은 C-1 버전 저장소가 붙은 뒤 쓸 수 있습니다");
    }
    @Test void unknownSkill() {
        assertThatThrownBy(() -> store.fullText("zz", 1)).hasMessage("zz 이라는 skill 이 없습니다");
    }
}
```

- [ ] **Step 6: 실패 확인** → **Step 7: `SkillVersionStore`, `LiveFolderSkillVersionStore` 구현** → **Step 8: 통과 확인** (`--tests 'com.capstone4.skillops.workspace.*'`, 7 tests passed)

- [ ] **Step 9: 연습 공간 서비스 테스트 작성**

저장소는 `mock(WorkspaceRepository.class)`로 대신한다. `save`는 받은 값을 그대로 돌려준다.
버전 저장소는 테스트 안의 가짜 구현으로 대신한다.
- 운영 버전: `{order-flow:1, inventory-check:2}`
- 제공하는 원문: `inventory-check` v1·v2, `order-flow` v1. 원문은 `"text-<skill>-v<n>"`이다.

```java
@Test void createDefaultsToLiveVersions() {
    var created = service.create(null);
    assertThat(created.workspaceId()).startsWith("w_");
    assertThat(created.skillVersions()).isEqualTo(Map.of("order-flow", 1, "inventory-check", 2));
    verify(repository).save(argThat(r -> r.getSkills().get("inventory-check").fullText().equals("text-inventory-check-v2")));
}
@Test void createOverridesStartVersion() {
    assertThat(service.create(Map.of("inventory-check", 1)).skillVersions()).containsEntry("inventory-check", 1);
}
@Test void createRejectsUnknownSkillAndSavesNothing() {
    assertThatThrownBy(() -> service.create(Map.of("nope", 1)))
        .hasMessageStartingWith("nope 이라는 skill 이 없습니다. 있는 skill: ");
    verify(repository, never()).save(any());
}
@Test void createRejectsUnavailableVersion() {
    assertThatThrownBy(() -> service.create(Map.of("inventory-check", 9))).isInstanceOf(IllegalArgumentException.class);
    verify(repository, never()).save(any());
}
@Test void skillsOfUnknownWorkspaceFails() {
    when(repository.findById("w_x")).thenReturn(Optional.empty());
    assertThatThrownBy(() -> service.skillsOf("w_x"))
        .hasMessage("workspace_id w_x 이 없습니다. create_workspace 로 먼저 만드세요");
}
```

- [ ] **Step 10: 실패 확인**

- [ ] **Step 11: `WorkspaceSkill`, `WorkspaceRecord`, `WorkspaceRepository`, `WorkspaceService` 구현**

연습 공간 번호는 `"w_"` 뒤에 영문·숫자 8자를 무작위로 붙인다. 원문을 모두 읽은 뒤에 저장해서, 읽다가 오류가 나면 아무것도 저장되지 않게 한다.

- [ ] **Step 12: 통과 확인**

Run: `cd service && ./gradlew test`
Expected: `BUILD SUCCESSFUL` (기존 `HealthControllerTest` 포함)

- [ ] **Step 13: 계약 문서의 저장 공간 이름 맞추기**

`docs/contracts/mcp-tools-input-output.md`에서 고칠 곳:
- `workspace_skills`가 나오는 저장 공간 표의 행과 언급을 모두 `workspaces`(`skills` 칸)로 바꾼다.
- 맨 끝 "저장 공간 목록"에서 `workspace_skills` 행을 지운다.
- "아래 11개입니다"를 "아래 10개입니다"로 고친다.

확인: `grep -c workspace_skills docs/contracts/mcp-tools-input-output.md`의 결과가 `0`이다.

- [ ] **Step 14: Commit**

```bash
git add service/src/main/java/com/capstone4/skillops/workspace service/src/test/java/com/capstone4/skillops/workspace docs/contracts/mcp-tools-input-output.md
git commit -m "[FEAT] C-2 연습 공간 만들기: 운영 skill 을 복사해 workspaces 에 저장"
```

---

### Task 3: 계약만 보는 가짜 실행기

**Files:**
- Create: `service/src/main/java/com/capstone4/skillops/scenario/FlowRunner.java`
- Create: `service/src/main/java/com/capstone4/skillops/scenario/ContractOnlyFlowRunner.java`
- Test: `service/src/test/java/com/capstone4/skillops/scenario/ContractOnlyFlowRunnerTest.java`
- Modify: `service/src/test/java/com/capstone4/skillops/scenario/TestFixtures.java` (`liveSkills()`, `f1Skills()`, `g01Order()` 추가)

**Interfaces:**
- Consumes: `SkillHeader.parse` (Task 2), `WorkspaceSkill` (Task 2), `RunOutcome` (Task 1)
- Produces:
  - `interface FlowRunner { RunOutcome runDryRun(DryRunRequest request); }`
  - 중첩 `record DryRunRequest(String scenarioId, Map<String,Object> order, String workspaceId, Map<String, WorkspaceSkill> skills)`
  - Javadoc에 적을 것: "A의 실행기가 구현한다(`skillops.flow-runner=harness`). 모의 실행은 C-1 `TraceRecorder`로 `DRY_RUN` 기록을 남기고, 그 `Trace`를 `RunOutcome`으로 바꿔 돌려준다."
  - `@Component @ConditionalOnProperty(name = "skillops.flow-runner", havingValue = "contract-only", matchIfMissing = true) class ContractOnlyFlowRunner implements FlowRunner`

- [ ] **Step 1: 테스트 작성**

`TestFixtures`에 도우미 세 개를 더한다.
- `liveSkills()`: `../skills/*/SKILL.md`를 읽어 `Map<String, WorkspaceSkill>`로 만든다(버전 1).
- `f1Skills()`: `liveSkills()`에서 `inventory-check` 원문의 `outputs:    [inventory.available, inventory.reserved]`를 `outputs:    [inventory.예약가능수량]`으로 바꾸고 버전을 2로 둔다.
- `g01Order()`: g-01 파일의 `order`다.

아래 테스트는 이 도우미들을 static import해서 쓴다.

```java
class ContractOnlyFlowRunnerTest {
    FlowRunner runner = new ContractOnlyFlowRunner();

    @Test void liveSkillsShipAndPassG01() {
        RunOutcome out = runner.runDryRun(new FlowRunner.DryRunRequest("g-01", g01Order(), "w_t", liveSkills()));
        assertThat(out.runId()).startsWith("r_fake");
        assertThat(out.finalStatus()).isEqualTo("SHIPPED");
        assertThat(out.steps()).extracting(RunOutcome.Step::status).containsOnly("ok").hasSize(5);
        assertThat(new ScenarioGrader().grade(Expectation.of(g01().expect()), List.of(), out).passed()).isTrue();
    }
    @Test void f1ChangeBreaksPayment() {
        RunOutcome out = runner.runDryRun(new FlowRunner.DryRunRequest("g-01", g01Order(), "w_t", f1Skills()));
        assertThat(out.finalStatus()).isEqualTo("FAILED");
        assertThat(out.steps().get(2).skill()).isEqualTo("payment");
        assertThat(out.steps().get(2).error()).isEqualTo("required input inventory.reserved not found");
        assertThat(out.steps()).extracting(RunOutcome.Step::status)
            .containsExactly("ok", "ok", "error", "skipped", "skipped");
    }
}
```

- [ ] **Step 2: 실패 확인**

Run: `cd service && ./gradlew test --tests 'com.capstone4.skillops.scenario.ContractOnlyFlowRunnerTest'`
Expected: `BUILD FAILED`, `cannot find symbol ... FlowRunner`

- [ ] **Step 3: `FlowRunner`, `ContractOnlyFlowRunner` 구현.** 아래 순서를 정확히 따른다.

1. 실행 순서는 `skills["order-flow"]`의 `steps`다. `order-flow`가 없으면 `IllegalArgumentException("연습 공간에 order-flow 가 없습니다")`를 낸다.
2. 처음 묶음은 `order`의 각 항목에 `order.`을 붙인 것이다. 예: `order.customer`, `order.items`
3. skill을 차례로 처리한다.
   - 받는 값 가운데 묶음에 없는 첫 값이 있으면, 그 단계는 `error`이고 오류 문구는 `"required input " + 값이름 + " not found"`다. 남은 skill은 빈 입출력의 `skipped`가 되고, 최종 상태는 `FAILED`다.
   - 빠진 값이 없으면 `ok`다. `input`은 받는 값마다 묶음에서 꺼낸 값이다. `output`은 내놓는 값마다 `CANNED`에 있으면 그 값, 없으면 `"fake:" + 값이름`이다. `output`은 묶음에 더한다.
4. 모두 `ok`면 최종 상태는 `SHIPPED`다.
5. 실행 번호는 `"r_fake"` 뒤에 영문·숫자 8자를 붙인다.
6. `CANNED = {"inventory.reserved": true, "payment.status": "PAID", "shipping.id": "sh_fake_1", "notify.sent": true}`

입출력 묶음은 `LinkedHashMap`으로 만든다. 순서를 지키고 `null` 값을 받아야 하기 때문이다.

- [ ] **Step 4: 통과 확인** — `./gradlew test` 전체 통과

- [ ] **Step 5: Commit**

```bash
git add service/src/main/java/com/capstone4/skillops/scenario service/src/test/java/com/capstone4/skillops/scenario
git commit -m "[FEAT] C-2 가짜 실행기: SKILL.md 맨 위 칸만 보고 모의 실행을 흉내 냄"
```

---

### Task 4: 시험 주문 돌리기 조립 (⑨)

**Files:**
- Create: `service/src/main/java/com/capstone4/skillops/scenario/ScenarioCatalog.java`
- Create: `service/src/main/java/com/capstone4/skillops/scenario/ScenarioRunReport.java`
- Create: `service/src/main/java/com/capstone4/skillops/scenario/ScenarioRunner.java`
- Test: `service/src/test/java/com/capstone4/skillops/scenario/ScenarioCatalogTest.java`
- Test: `service/src/test/java/com/capstone4/skillops/scenario/ScenarioRunnerTest.java`

**Interfaces:**
- Consumes: `WorkspaceService.skillsOf` (Task 2), `FlowRunner` (Task 3), `ScenarioGrader`·`Expectation`·`Check` (Task 1)
- Produces:
  - `@Component class ScenarioCatalog`:
    - 생성자 `(@Value("${skillops.scenarios-dir}") Path scenariosDir, JsonMapper mapper)`. `mapper`는 Boot 4가 만드는 `tools.jackson.databind.json.JsonMapper` 빈이다. 생성할 때 `golden/*.json`을 모두 읽는다.
    - `Scenario get(String id)`, `Set<String> ids()`
    - 없는 번호면 `IllegalArgumentException("시험 주문 " + id + " 이 없습니다. 있는 번호: " + 정렬된 ids)`를 낸다.
  - `record ScenarioRunReport(String workspaceId, boolean allPassed, List<ScenarioResult> results)`
    - 중첩 `record ScenarioResult(String scenarioId, String replayOfRunId, boolean passed, List<String> runIds, List<Failure> failures)`
    - 중첩 `record Failure(String runId, String skill, String reason)`
    - 셋 다 snake_case `@JsonNaming`을 붙인다. `replayOfRunId`는 이번 범위에서 항상 `null`이다.
  - `@Service class ScenarioRunner(WorkspaceService, ScenarioCatalog, FlowRunner, ScenarioGrader)`: `ScenarioRunReport run(String workspaceId, List<String> scenarioIds, List<String> extraChecks, Integer repeat)`

- [ ] **Step 1: 테스트 작성**

`ScenarioCatalogTest`에는 두 가지를 둔다.
- `loadsG01`: `../scenarios`를 읽으면 `get("g-01").expect().finalStatus()`가 `SHIPPED`다.
- `unknownIdListsAvailable`: 없는 번호를 찾으면 메시지에 `g-99`와 `g-01`이 모두 들어 있다.

`ScenarioRunnerTest`는 다음처럼 준비한다.
- 실제 `ScenarioCatalog`(`../scenarios`)와 실제 `ScenarioGrader`를 쓴다.
- `WorkspaceService`는 Mockito로 만든다. `skillsOf("w_ok")`는 `TestFixtures.liveSkills()`를, `skillsOf("w_f1")`은 `TestFixtures.f1Skills()`를 돌려준다.
- `FlowRunner`는 `ContractOnlyFlowRunner`를 감싸 호출 횟수를 세는 람다다: `calls.incrementAndGet(); return real.runDryRun(req);`

```java
@Test void runsG01TwiceAndPasses() {
    var report = runner.run("w_ok", List.of("g-01"), null, null);
    assertThat(report.allPassed()).isTrue();
    assertThat(report.results()).singleElement().satisfies(r -> {
        assertThat(r.runIds()).hasSize(2);
        assertThat(r.failures()).isEmpty();
    });
}
@Test void f1WorkspaceFailsWithJoinedReasons() {
    var report = runner.run("w_f1", List.of("g-01"), List.of("inventory.예약가능수량 != null"), 2);
    assertThat(report.allPassed()).isFalse();
    var f = report.results().get(0).failures();
    assertThat(f).hasSize(2);
    assertThat(f.get(0).skill()).isEqualTo("payment");
    assertThat(f.get(0).reason()).contains("최종 상태 기대 SHIPPED, 실제 FAILED").contains("; ");
}
@Test void rejectsBadCheckBeforeRunning() {
    assertThatThrownBy(() -> runner.run("w_ok", List.of("g-01"), List.of("payment.status = PAID"), 2))
        .hasMessageStartingWith("확인 조건을 읽을 수 없습니다");
    assertThat(calls).hasValue(0);
}
@Test void rejectsUnknownScenario() {
    assertThatThrownBy(() -> runner.run("w_ok", List.of("g-99"), null, null)).hasMessageContaining("g-01");
    assertThat(calls).hasValue(0);
}
@Test void rejectsUnknownWorkspaceBeforeRunning() {
    when(workspaces.skillsOf("w_x")).thenThrow(new IllegalArgumentException("workspace_id w_x 이 없습니다. create_workspace 로 먼저 만드세요"));
    assertThatThrownBy(() -> runner.run("w_x", List.of("g-01"), null, null)).hasMessageContaining("w_x");
    assertThat(calls).hasValue(0);
}
@Test void rejectsEmptyScenarioIdsAndBadRepeat() {
    assertThatThrownBy(() -> runner.run("w_ok", List.of(), null, null))
        .hasMessageStartingWith("scenario_ids 에 시험 주문 번호를 하나 이상 넣으세요");
    assertThatThrownBy(() -> runner.run("w_ok", List.of("g-01"), null, 0)).hasMessage("repeat 는 1~5 사이여야 합니다 (받은 값 0)");
    assertThatThrownBy(() -> runner.run("w_ok", List.of("g-01"), null, 6)).hasMessage("repeat 는 1~5 사이여야 합니다 (받은 값 6)");
}
```

- [ ] **Step 2: 실패 확인**

Run: `cd service && ./gradlew test --tests 'com.capstone4.skillops.scenario.Scenario*'`
Expected: `BUILD FAILED`, `cannot find symbol ... ScenarioRunner`

- [ ] **Step 3: 구현**

`run`은 주문을 돌리기 전에 아래를 이 순서로 모두 검사한다.
1. `repeat` (`null`이면 2)
2. `scenarioIds` (비어 있으면 오류. 메시지에 있는 번호를 붙인다)
3. `extraChecks`를 `Check.parse`로 읽기
4. `workspaceService.skillsOf`
5. 시나리오마다 `catalog.get`

검사를 마친 뒤 시나리오마다 `repeat`번 `runDryRun`을 부르고 채점한다. 떨어진 실행의 `reason`은 `String.join("; ", grade.reasons())`다. 시나리오가 합격하려면 반복한 실행이 모두 합격해야 하고, `allPassed`는 모든 시나리오가 합격일 때만 `true`다.

- [ ] **Step 4: 통과 확인** — `cd service && ./gradlew test` 전체 통과

- [ ] **Step 5: Commit**

```bash
git add service/src/main/java/com/capstone4/skillops/scenario service/src/test/java/com/capstone4/skillops/scenario
git commit -m "[FEAT] C-2 시험 주문 돌리기: 시나리오를 반복 모의 실행하고 채점"
```

---

### Task 5: MCP 도구로 열고 Python에서 확인

**Files:**
- Create: `service/src/main/java/com/capstone4/skillops/mcp/WorkspaceTools.java`
- Modify: `service/src/main/resources/application.yml` (맨 아래 `skillops:` 블록 추가)
- Create: `skillops-agent/scripts/c2_smoke.py`

**Interfaces:**
- Consumes: `WorkspaceService.create` (Task 2), `ScenarioRunner.run` (Task 4)
- Produces (MCP 도구, Agent가 보는 이름):
  - `create_workspace(start_versions?: {skill: version})` → `{workspace_id, skill_versions, created_at}`
  - `run_scenarios(workspace_id, scenario_ids, extra_checks?, repeat?)` → `{workspace_id, all_passed, results:[{scenario_id, replay_of_run_id, passed, run_ids, failures:[{run_id, skill, reason}]}]}`

- [ ] **Step 1: 설정 추가** (`application.yml` 맨 아래)

```yaml
skillops:
  skills-dir: ${SKILLOPS_SKILLS_DIR:../skills}
  scenarios-dir: ${SKILLOPS_SCENARIOS_DIR:../scenarios}
  # contract-only: SKILL.md 맨 위 칸만 보고 흉내 내는 C-2 가짜 실행기. A 실행기가 붙으면 harness
  flow-runner: ${SKILLOPS_FLOW_RUNNER:contract-only}
```

- [ ] **Step 2: `WorkspaceTools` 작성** (`@Component`, `PingTools`와 같은 모양)

| 도구 | 메서드 | `description` (그대로) |
|---|---|---|
| `create_workspace` | `CreatedWorkspace createWorkspace(@McpToolParam(required = false, description = "skill 이름 → 시작 버전. 적지 않은 skill 은 운영 버전") Map<String,Integer> start_versions)` | `운영 중인 skill 은 그대로 두고 연습용 복사본(연습 공간)을 만든다. 돌려준 workspace_id 를 apply_patch, run_scenarios 에 넣는다.` |
| `run_scenarios` | `ScenarioRunReport runScenarios(String workspace_id, List<String> scenario_ids, List<String> extra_checks, Integer repeat)` | `연습 공간의 skill 로 시험 주문을 모의 실행하고 기대 결과와 비교해 채점한다. 같은 주문을 repeat 번(기본 2) 돌려 모두 통과해야 합격이다. 실제 결제·배송은 일어나지 않는다.` |

`run_scenarios` 파라미터 설명:
- `workspace_id`: "create_workspace 가 돌려준 연습 공간 번호"
- `scenario_ids`: "돌릴 시험 주문 번호 목록. 기준 주문은 g-01~g-06"
- `extra_checks` (required=false): "이번 시험에만 모든 주문에 덧붙이는 확인 조건. 예: inventory.예약가능수량 != null"
- `repeat` (required=false): "같은 주문을 돌릴 횟수(1~5, 기본 2)"

클래스 Javadoc에 이렇게 적는다: "파라미터 이름이 곧 도구 입력 키라서 snake_case로 쓴다. C-1의 읽기 도구와 파일을 나눈다."

- [ ] **Step 3: 확인 스크립트 작성** (`skillops-agent/scripts/c2_smoke.py`, `mcp_smoke.py`와 같은 모양)

순서와 판정:
1. `create_workspace({})` 결과 글자를 JSON으로 읽는다. `workspace_id`가 `w_`로 시작하고 `skill_versions`에 skill이 6개 있어야 한다.
2. `run_scenarios({"workspace_id": id, "scenario_ids": ["g-01"]})`의 `all_passed`가 `true`이고 `results[0].run_ids`가 2개여야 한다.
3. `run_scenarios({"workspace_id": id, "scenario_ids": ["g-01"], "extra_checks": ["payment.status = PAID"]})`는 `is_error`가 `true`이고, 글자에 `확인 조건을 읽을 수 없습니다`가 들어 있어야 한다.
4. 셋 다 맞으면 `C2 SMOKE OK`를 찍고 0으로 끝낸다. 아니면 어긋난 단계를 찍고 1로 끝낸다.

- [ ] **Step 4: 전체 단위 테스트**

Run: `cd service && ./gradlew test`
Expected: `BUILD SUCCESSFUL`

- [ ] **Step 5: 실제로 띄워서 확인**

```bash
docker compose up -d --wait
(cd service && ./gradlew bootRun > /tmp/skillops-boot.log 2>&1 &)
until curl -sf localhost:8080/api/health; do sleep 2; done
grep "Registered tools" /tmp/skillops-boot.log
(cd skillops-agent && uv run scripts/c2_smoke.py)
docker compose exec postgres psql -U skillops -d skillops -c '\d workspaces'
```

Expected:
- 로그에 `Registered tools: 3`
- 스크립트가 `C2 SMOKE OK`를 찍음
- `workspaces` 테이블에 `workspace_id`, `created_at`, `status`, `skills`(jsonb) 칸이 있음

여기서 JSON 칸 저장이 실패하면 C-1의 `TraceRecord`와 같은 설정인지 확인한다. 확인이 끝나면 `bootRun`을 끈다.

- [ ] **Step 6: Commit**

```bash
git add service/src/main/java/com/capstone4/skillops/mcp/WorkspaceTools.java service/src/main/resources/application.yml skillops-agent/scripts/c2_smoke.py
git commit -m "[FEAT] C-2 MCP 도구 create_workspace·run_scenarios 공개와 확인 스크립트"
```

---

## 다음 계획 (이 문서 범위 밖)

- 1일차 18시 원인 확인: `apply_patch`(java-diff-utils, 끊긴 연결은 A의 검사 기능), `run_scenarios`의 `replay_run_ids`와 `scenarios` 테이블
- A 실행기 연결: `skillops.flow-runner=harness`, C-1 `Trace` → `RunOutcome` 변환
- 2일차: `save_proposal`과 `scenario_results`, 실패 알림 webhook, `promote_workspace`, `rollback`
