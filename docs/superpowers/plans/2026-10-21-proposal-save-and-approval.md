# 제안서 저장과 담당자 승인 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** B가 검증을 마친 수정안을 service에 제안서로 저장하고, 운영자가 화면에서 그 제안서를 읽고 후보 하나를 승인하거나 거절할 수 있게 한다. 실제 서버를 띄운 프론트엔드 실험에서 처음부터 끝까지 동작해야 한다.

**Architecture:** service가 제안서의 주인이다. B는 `save_proposal` MCP 도구로 진단 결과를 보내고, service는 근거 실행 번호를 자기 시험 기록과 대조한 뒤 저장한다. 저장할 때 service가 정해진 목차와 문장 틀로 제안서 본문(`document`)을 한 번 만들어 함께 보관한다. 화면은 service의 조회 API로 본문을 그대로 보여 주고, 승인과 거절도 service가 기록한다. B의 `/approve`는 더 이상 부르지 않는다.

**Tech Stack:** Spring Boot 4.1 · Java 21 · Spring AI 2.0 `@McpTool` · JPA(PostgreSQL 17, `ddl-auto: update`) · Jackson 3(`tools.jackson`) / Python 3.11 · LangGraph · mcp 2.3 / Vue 3.5 · Vite 8 · Playwright 1.64

**Spec:** 별도 spec 문서가 없다. brainstorming을 거치지 않고 바로 계획을 세웠기 때문이다. 아래 "설계 결정" 절이 spec 역할을 한다. 실행 전에 사용자가 이 절을 확인해야 한다.

## 설계 결정

| 번호 | 결정 | 근거 |
|---|---|---|
| D1 | `save_proposal`은 #60 위에서 service에 직접 만든다(`mcp/ProposalTools`, `proposal/Proposal·ProposalRepository·ProposalService`). #63은 합치지 않는다. | 사용자 결정(2026-10-21): #63은 다른 버전으로 따로 개발 중이다. |
| D2 | 돌려주는 값은 계약 §10대로 `{proposal_id, status, valid_candidate_nos}`다. | B와 계약 문서가 이 이름을 쓴다. |
| D3 | 합격 조건은 B(#60)와 같은 네 가지다. `fixes_failure`가 참이고, `keeps_old_orders.passed == total`이고 `total > 0`이고, `keeps_intent`가 참이고, `no_broken_links`가 참이다. service가 다시 계산하고, B가 보낸 `valid`와 다르면 저장을 거부한다. | 지금 B 코드가 이 네 조건을 쓴다. 계약 §10 본문은 세 조건이라 Task 9에서 고친다. |
| D4 | service는 `run_scenarios`로 돌린 모든 실행을 `scenario_runs` 표에 남긴다. 저장할 때 후보의 `evidence_run_ids`가 그 후보 연습 공간의 기록에 모두 있어야 하고, `proof_run_ids`도 기록에 있어야 한다. | 계약 §10의 거부 조건이다. 가짜 도구(stub)로 만든 제안서가 운영자 화면에 올라가는 일도 막는다. |
| D5 | 제안서 본문은 service의 `ProposalDocumentWriter`가 저장 순간에 한 번 만든다. 목차와 문장 틀은 Task 2에 적힌 그대로 쓴다. 운영자가 본 문서가 나중에 바뀌지 않도록 본문을 함께 보관한다. | 문체를 한곳에서 지키고 테스트로 고정할 수 있다. LLM은 후보 설명 한 칸만 쓴다. |
| D6 | LLM이 쓴 글(후보 `explanation`)은 `ToneCheck`로 검사하고, 걸린 표현을 `document.tone_warnings`에 남긴다. 화면에는 보여 주지 않는다. B 프롬프트에는 문체 규칙을 넣는다. | LLM 출력은 고정할 수 없다. 검사 결과는 실험 때 확인한다. |
| D7 | 승인 요청은 `{candidate_no, intent_confirmed}`다. `candidate_no`는 1부터 센다. 합격한 후보면 추천 후보가 아니어도 승인할 수 있다. | 운영자가 고른 후보가 그대로 기록된다. 0부터 세는 인덱스와 섞이지 않는다. |
| D8 | service는 승인·거절을 기록하고 제안서 상태를 `approved`/`rejected`로 바꾼다. B `/approve`는 부르지 않는다. 같은 제안서는 한 번만 결정할 수 있다. 운영 반영(⑦ promote)은 이 계획에 넣지 않는다. | #60의 B 그래프는 저장 뒤 바로 끝나서 승인을 기다리지 않는다. ⑦은 다음 계획에서 다룬다. |
| D9 | 같은 진단(`thread_id`)으로 다시 저장하면 새로 만들지 않고 처음 저장한 제안서를 돌려준다. | B가 재시도해도 제안서가 둘이 되지 않는다. |
| D10 | 화면은 `/service/api/proposals`로 목록과 상세를 읽는다. 로그인한 사람은 누구나 읽고, 결정은 OPERATOR만 한다. | 기존 승인 규칙과 같다. 브라우저는 B를 직접 부르지 않는다. |
| D11 | 중간 화면 시험(mock 화면 Playwright, 실서버 화면 Playwright)은 만들지 않는다. service와 B는 단위 테스트로, 화면은 빌드와 lint로 확인한다. 성공 여부는 마지막 Task 10의 실제 화면 실험으로 판정한다. | 사용자 지시(2026-10-21). |
| D12 | 기준은 최신 #60(`85c3bf7`, #66 포함)이다. #63·#62는 합치지 않는다. | 사용자 결정(2026-10-21). |

## 제안서 목차 (D5)

| 순서 | 제목 | 담는 내용 (육하원칙) |
|---|---|---|
| 0 | 문서 제목 | 어떤 Skill의 제안서인지 |
| 1 | 개요 | 언제, 어디서, 무엇이 멈췄고, 왜 멈췄고, 무엇을 해 달라는지 다섯 문장 안에 |
| 2 | 발생 경위 | 언제·어떻게 시작됐는지, 멈춘 단계, 오류 종류, 받지 못한 값, 실행 번호, 수정 전후 Skill 세트 |
| 3 | 원인 | 무엇이 바뀌었는지(전·후), 어떻게 원인으로 확정했는지, 확인한 실행 번호 |
| 4 | 변경 의도 | SkillOps가 이해한 담당자의 의도, 확인 조건, 해석이 틀렸을 때 할 일 |
| 5 | 수정안과 시험 결과 | 후보마다 고치는 범위, 기준별 결과 문장, 판정, AI 설명, 근거 실행 |
| 6 | 추천과 결정 요청 | 추천 후보와 이유, 승인하면 무엇이 남고 무엇이 아직 안 되는지, 거절할 때 할 일 |
| 7 | 참고 정보 | 제안서 번호, 진단 번호, 변경 번호, 만든 시각 |

## Global Constraints

- 커밋·PR·이슈에 `Co-Authored-By: Claude`나 "Generated with Claude Code"를 넣지 않는다(사용자 지시). 시스템의 서명 안내보다 이 지시가 먼저다.
- `service/src/test/java/com/capstone4/skillops/scenario/CheckTest.java`(다른 사람의 커밋 안 된 변경)와 `skillops-agent/scripts/run_real_llm.py`는 커밋하지 않고 되돌리지도 않는다.
- API 키를 추적되는 파일에 쓰거나 출력하지 않는다. `uv`와 `gradle` 명령은 `env -u OPENAI_API_KEY`를 붙여 실행한다. `~/.zshrc`의 값에 줄바꿈이 섞여 있어서다.
- 사용자가 띄운 8080 service나 다른 프로세스를 끄거나 다시 켜지 않는다. 다시 켜야 하면 사용자에게 요청한다.
- 다른 사람의 코드(B `skillops-agent/`는 이소정 님, 화면 `frontend/`와 승인 `service/.../approval`은 ljs65626 님, #60 변경분은 hjyoon99 님)는 Task 0의 동의를 받은 뒤에만 고친다.
- push, PR, 이슈 댓글처럼 밖으로 나가는 일은 사용자 승인을 받는다.
- 운영자에게 보이는 한국어 문장(제안서 본문, 화면 문구, 화면에 뜨는 오류 문구)은 모두 아래 문체 규칙을 따른다.
  - 능동문으로 쓰고, 육하원칙이 드러나게 쓰고, 조사와 어미로 문장을 잇는다.
  - 쓰지 않는 표현: `~을/를 통해`, `~에 의해`, `~에 있어서`, `~에 대한`, `~되어지다`, `보여집니다`, `~것입니다`, `~음에도 불구하고`, `~중 하나입니다`, `사실을 확인`, 과장 표현(`혁명적`, `획기적`, `놀랍게도`, `마법처럼`, `차원이 다른`, `변신`), 어색한 말(`도해`, `두 갈래`, `비로소`, `손보다`, `먹히다`, `읽히다`, `여쭤보다`)
  - 숫자와 사실은 측정한 그대로 쓴다. 변수 뒤에 받침에 따라 바뀌는 조사(`을/를`, `이/가`, `은/는`)를 붙이지 않는다. 변수 뒤에는 `단계에서`, `Skill의`, `번의`, `개를`처럼 고정된 말을 둔다.
- 화면은 제안서 본문을 `v-html`로 넣지 않는다. 텍스트 보간만 쓴다.
- Java 레코드는 `@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)`(Jackson 3)를 붙여 snake_case로 내보낸다. Hibernate JSON 칸에는 레코드가 아니라 `Map<String, Object>`를 넣는다. Hibernate는 Jackson 2를 써서 Jackson 3 이름 규칙을 모른다.

## Review Focus

1. B가 같은 진단(`thread_id`)으로 `save_proposal`을 두 번 부르면 제안서는 하나만 생기고, 두 번째 호출도 같은 `proposal_id`를 받아야 한다. → Task 3 `sameThreadReturnsTheFirstProposal`
2. 운영자 두 사람이 같은 제안서를 차례로 승인하면 두 번째는 409 `PROPOSAL_ALREADY_DECIDED`를 받고 승인 기록이 하나만 남아야 한다. 동시 요청은 `@Version`이 막는다. → Task 4 `secondDecisionIsRejected`, `optimisticLockFailureBecomesConflict`
3. 떨어진 후보, 없는 번호(0, 99), `no_valid_fix` 제안서를 승인하면 400이나 409를 받고 승인 기록이 남지 않아야 한다. → Task 4 `rejectsCandidateThatDidNotPass`, `noValidFixCannotBeApproved`
4. 가짜 도구(stub)로 만든 근거 실행 번호로 저장하면 거부되어야 한다. → Task 3 `unknownEvidenceRunIsRejected`
5. LLM 설명에 금지 표현이나 `<script>` 같은 문자열이 섞여도 본문은 깨지지 않고, 금지 표현은 `tone_warnings`에 남고, 화면은 글자 그대로 보여 줘야 한다. → Task 2 `explanationPhrasesAreRecordedNotRewritten`. 화면 쪽은 `v-html` 금지(Global Constraints)로 막고, 최종 리뷰에서 `grep -rn "v-html" frontend/src`가 비었는지 확인한다.

---

## 파일 구조

| 파일 | 할 일 |
|---|---|
| `service/.../scenario/RunLedger.java` (새) | 시험 실행 기록 장부 인터페이스 |
| `service/.../scenario/JpaRunLedger.java`, `ScenarioRunRecord.java`, `ScenarioRunRecordRepository.java` (새) | `scenario_runs` 표에 남기고 읽는다 |
| `service/.../scenario/ScenarioRunner.java` (고침) | 실행마다 장부에 남긴다 |
| `service/.../proposal/ToneCheck.java` (새) | 금지 표현 찾기 |
| `service/.../proposal/ProposalDocument.java` (새) | 본문 레코드(`title, summary, sections, tone_warnings`) |
| `service/.../proposal/ProposalDocumentWriter.java` (새) | 목차와 문장 틀로 본문 만들기 |
| `service/.../proposal/Proposal.java`, `ProposalService.java`, `ProposalRepository.java` (새) | 검사, 합격 계산, 저장, 결정 표시 |
| `service/.../proposal/ProposalException.java` (새) | 화면에 돌려줄 `code`·`message`·HTTP 상태 |
| `service/.../mcp/ProposalTools.java` (새) | `save_proposal` 도구 |
| `service/.../approval/ApprovalService.java`, `Approval.java`, `ApprovalRepository.java` (고침) | 제안서에 결정 기록, Agent 전달 제거 |
| `service/.../approval/AgentClient.java`, `AgentException.java`, 테스트 `AgentClientTest.java` (삭제) | 더 쓰지 않는다 |
| `service/.../api/ApprovalController.java` (고침), `ProposalController.java` (새) | 승인 요청 모양, 조회 API |
| `service/.../auth/SecurityConfig.java` (고침) | 조회 API는 로그인 필요 |
| `skillops-agent/skillops_agent/{tools,stub_tools,nodes}.py`, `prompts/{style,rank_and_explain,extract_intent}.md` | 실제 저장 호출, 문체 규칙 |
| `skillops-agent/scripts/b_c_contract_smoke.py` | 저장한 제안서 번호를 보고에 넣는다 |
| `frontend/src/api/proposals.js`, `utils/proposalView.js`, `mocks/*`, `views/{ProposalListView,ProposalView,DiagnosisView}.vue`, `components/ProposalDocument.vue`(새), `stores/workspace.js` | 실제 조회·승인 화면 |
| `docs/reports/2026-10-21-proposal-approval-experiment.md`(새) | 최종 실험 기록 |
| `docs/contracts/*`, `README.md`, `docs/skill-save-auto-diagnosis.md`, `skillops-agent/docs/c-contract-integration.md` | 계약과 안내 갱신 |

경로의 `service/...`는 `service/src/main/java/com/capstone4/skillops`, 테스트는 `service/src/test/java/com/capstone4/skillops`다.

---

### Task 0: 담당자 동의와 기반 정리 (사용자와 함께, 코드 없음)

이 Task는 사람의 결정이 필요하다. 실행하는 쪽은 아래를 사용자에게 묻고 답을 받은 뒤에만 Task 1로 넘어간다.

- [ ] **Step 1: 동의 받기.** 사용자가 다음 세 분께 이 계획을 알리고 동의를 받는다.
  - 이소정 님(B): #60 위의 `nodes.py`·`tools.py`·`stub_tools.py`·프롬프트를 고치는 것. #63에도 같은 이름의 service 파일(`mcp/ProposalTools.java`, `proposal/Proposal·ProposalRepository·ProposalService.java`)과 같은 MCP 도구 이름 `save_proposal`이 있다는 점도 알린다.
  - ljs65626 님(A, 화면·승인): 승인 API 요청 모양 변경(`candidate` → `candidate_no`), Agent 전달 제거, 제안서 화면 변경.
  - hjyoon99 님(#60): #60 위에서 작업하므로 #60이 develop에 먼저 합쳐져야 한다. 화면 `DiagnosisView`와 B 스모크 스크립트를 고치는 것.
- [ ] **Step 2: 이슈 만들기.** 사용자 승인을 받아 "[FEAT] 제안서 저장과 담당자 승인 연결" 이슈를 만든다. 이후 커밋 메시지 끝의 `(#NN)`에는 이 이슈 번호를 쓴다. 브랜치 이름은 `feat/#NN_proposal-save-and-approval`로 바꾼다(`git branch -m`).
- [ ] **Step 3: 기준 확인.** 작업 브랜치가 최신 #60과 같은지 확인한다.

```bash
git fetch origin
git merge --ff-only origin/feat/#60_skillops-pipeline-integration
git log --oneline -1
cd service && env -u OPENAI_API_KEY ./gradlew test
cd ../skillops-agent && env -u OPENAI_API_KEY uv run pytest -q
```
Expected: `85c3bf7` 이후의 #60 커밋이 맨 위에 있고, service·B 테스트가 모두 통과한다. 이 결과를 출발점으로 기록한다.

---

### Task 1: 시험 실행 기록 장부 (C-2)

**Files:**
- Create: `service/.../scenario/RunLedger.java`, `JpaRunLedger.java`, `ScenarioRunRecord.java`, `ScenarioRunRecordRepository.java`
- Modify: `service/.../scenario/ScenarioRunner.java` (생성자, `run`의 실행 반복문 75-87줄)
- Test: `service/.../scenario/ScenarioRunnerTest.java`, `ScenarioRunnerReplayTest.java`, `TestFixtures.java`에 `InMemoryRunLedger`

**Interfaces:**
- Produces:
  - `public interface RunLedger { void record(String workspaceId, String scenarioId, String runId, boolean passed); Set<String> runIdsOf(String workspaceId); boolean known(String runId); }`
  - `ScenarioRunRecord` 엔티티: 표 `scenario_runs`, 칸 `run_id`(PK, 길이 64), `workspace_id`(길이 64, 색인), `scenario_id`(길이 64), `passed`, `recorded_at`(Instant)
  - `ScenarioRunner` 생성자 마지막 인자로 `RunLedger ledger`를 받는다.

- [ ] **Step 1: 실패하는 테스트 쓰기.** `ScenarioRunnerTest`에 추가한다.

```java
@Test
void everyRunIsRecordedForItsWorkspace() {
    // 기존 테스트처럼 g-01 을 repeat=2 로 돌린다(한 번은 합격, 한 번은 불합격하도록 FlowRunner 대역 구성)
    ScenarioRunReport report = runner.run("w_a", List.of("g-01"), null, null, 2);
    List<String> runIds = report.results().get(0).runIds();
    assertThat(ledger.runIdsOf("w_a")).containsExactlyInAnyOrderElementsOf(runIds);
    assertThat(ledger.runIdsOf("w_other")).isEmpty();
    assertThat(ledger.known(runIds.get(0))).isTrue();
    assertThat(ledger.known("r_" + "0".repeat(32))).isFalse();
}

@Test
void nothingIsRecordedWhenInputIsRejectedBeforeRunning() {
    assertThatThrownBy(() -> runner.run("w_a", List.of("g-99"), null, null, 1))
            .isInstanceOf(IllegalArgumentException.class);
    assertThat(ledger.runIdsOf("w_a")).isEmpty();
}
```

- [ ] **Step 2: 실패를 확인한다.**

Run: `cd service && env -u OPENAI_API_KEY ./gradlew test --tests '*ScenarioRunnerTest'`
Expected: 컴파일 실패(`ledger`, `InMemoryRunLedger` 없음)

- [ ] **Step 3: 구현한다.** `TestFixtures`에 `HashMap` 기반 `InMemoryRunLedger implements RunLedger`를 둔다. `ScenarioRunner.run`은 `runIds.add(outcome.runId())` 바로 뒤, 채점한 다음에 `ledger.record(workspaceId, scenario.scenarioId(), outcome.runId(), grade.passed())`를 부른다. `JpaRunLedger`는 `@Component`이고 `ScenarioRunRecordRepository`(`JpaRepository<ScenarioRunRecord, String>`, `List<ScenarioRunRecord> findByWorkspaceId(String)`)를 쓴다. `ScenarioRunnerReplayTest`와 이 생성자를 쓰는 다른 테스트에도 `new InMemoryRunLedger()`를 넘긴다.

- [ ] **Step 4: 통과를 확인한다.**

Run: `cd service && env -u OPENAI_API_KEY ./gradlew test`
Expected: 모두 통과

- [ ] **Step 5: 커밋**

```bash
git add service/src/main/java/com/capstone4/skillops/scenario service/src/test/java/com/capstone4/skillops/scenario/{ScenarioRunnerTest,ScenarioRunnerReplayTest,TestFixtures}.java
git commit -m "[FEAT] run_scenarios 실행을 scenario_runs 장부에 기록 (#NN)"
```

---

### Task 2: 제안서 본문 작성기와 문체 검사 (C-2)

**Files:**
- Create: `service/.../proposal/ToneCheck.java`, `ProposalDocument.java`, `ProposalDocumentWriter.java`
- Create: `service/src/test/resources/proposal/f1-proposed.json`, `no-root-cause.json`
- Test: `service/.../proposal/ToneCheckTest.java`, `ProposalDocumentWriterTest.java`

**Interfaces:**
- Produces:
  - `public final class ToneCheck { public static List<String> findIn(String text); }`: 찾은 금지 표현을 나온 순서대로 돌려준다.
  - `public record ProposalDocument(String title, String summary, List<Section> sections, List<ToneWarning> toneWarnings)`
  - `public record Section(String key, String heading, List<String> paragraphs, String quote, List<String> items, List<Fact> facts, List<Section> parts, Integer candidateNo)`. 빈 목록은 `List.of()`, 없는 값은 `null`이다.
  - `public record Fact(String label, String value)`, `public record ToneWarning(String where, String phrase)`
  - `public final class ProposalDocumentWriter { public static ProposalDocument write(String proposalId, Map<String, Object> proposal, Instant createdAt); }`
  - 섹션 `key` 순서: `overview`, `what_happened`, `cause`, `intent`(의도가 없으면 뺀다), `candidates`, `decision`, `reference`

`ToneCheck`가 찾는 표현(이 순서로 검사한다): `을 통해`, `를 통해`, `에 의해`, `에 있어서`, `에 대한`, `되어지`, `되어진`, `보여집니다`, `것입니다`, `음에도 불구하고`, `중 하나입니다`, `사실을 확인`, `혁명적`, `획기적`, `놀랍게도`, `마법처럼`, `차원이 다른`, `변신`, `도해`, `두 갈래`, `비로소`, `손보`, `먹혔`, `먹힌`, `읽힙니다`, `여쭤`

시각 형식은 `yyyy년 M월 d일 H시 m분`이고 `Asia/Seoul` 기준이다. 아래 `{when}`은 `failure.at`이 있으면 그 시각, 없으면 `createdAt`이다. `{n}`은 후보 수, `{v}`는 합격 후보 수, `{rec}`은 `recommended_candidate_no`, `{total}`·`{passed}`는 그 후보의 `keeps_old_orders` 값이다.

**문장 틀 (그대로 쓴다)**

| 자리 | 조건 | 문장 |
|---|---|---|
| 제목 | `status=proposed` | `{root.skill} 수정 제안서` |
| 제목 | `no_valid_fix`, 원인 있음 | `{root.skill} 진단 결과` |
| 제목 | 원인 없음 | `Skill 변경 진단 결과` |
| 개요 1 | `trigger.type=skill_saved` | `{when}에 Skill 수정본으로 기준 주문을 시험했더니 {failure.skill} 단계에서 주문이 멈췄습니다.` |
| 개요 1 | `trigger.type=failure` | `{when}에 들어온 주문이 {failure.skill} 단계에서 멈췄습니다.` |
| 개요 1 | `failure` 없음 | `실패한 실행 기록 없이 진단이 끝났습니다.` |
| 개요 2 | 원인 있음 | `원인은 {root.skill} Skill의 변경에 있습니다. 이 변경만 되돌려 다시 돌렸더니 같은 주문이 끝까지 처리됐습니다.` |
| 개요 2 | 원인 없음 | `바뀐 부분을 하나씩 되돌려 시험했지만 원인을 하나로 확정하지 못했습니다.` |
| 개요 3 | `proposed` | `SkillOps가 수정안 {n}개를 실제 주문 흐름에서 시험했고, 그중 {v}개가 모든 확인을 통과했습니다.` |
| 개요 3 | `no_valid_fix`, 후보 있음 | `SkillOps가 수정안 {n}개를 시험했지만 모든 확인을 통과한 안은 없습니다.` |
| 개요 3 | 후보 없음 | `시험할 수정안은 만들지 못했습니다.` |
| 개요 4 | `proposed` | `아래 내용을 확인한 뒤 후보 {rec}번의 승인 여부를 정해 주세요.` |
| 개요 4 | `no_valid_fix` | `담당자가 Skill을 직접 고쳐야 합니다.` |
| 발생 경위 | `skill_saved` | `담당자가 Skill 편집 화면에서 수정본을 저장했습니다. 저장 직후 SkillOps가 이 수정본으로 기준 주문을 모의 실행했고, {when}에 {failure.skill} 단계에서 주문이 멈췄습니다.` |
| 발생 경위 | `failure` | `{when}에 들어온 주문이 {failure.skill} 단계에서 멈췄습니다. 주문 Agent가 이 실패를 기록했고, SkillOps가 그 기록으로 진단을 시작했습니다.` |
| 발생 경위 | `failure` 없음 | `실패한 실행 기록을 찾지 못했습니다.` |
| 발생 경위 | `missing_inputs`가 있음 | `이 단계가 받아야 할 값이 앞 단계에서 넘어오지 않았습니다.` |
| 원인 | 원인 있음, 멈춘 단계와 원인 Skill이 다름 | `주문은 {failure.skill} 단계에서 멈췄지만 원인은 {root.skill} Skill에 있습니다.` |
| 원인 | 원인 있음 | `{root.skill} Skill에서 아래 부분이 바뀌었습니다.` |
| 원인 | 원인 있음 | `SkillOps는 이 변경만 되돌린 연습 공간에서 실패한 주문을 다시 돌렸습니다. 그러자 주문이 끝까지 처리됐고, 그래서 이 변경을 원인으로 확정했습니다.` |
| 원인 | 원인 없음 | `SkillOps가 바뀐 부분을 하나씩 되돌려 실패한 주문을 다시 돌렸지만, 주문이 끝까지 처리된 경우가 없었습니다. 그래서 원인을 하나로 확정하지 못했습니다.` |
| 변경 의도 | 항상 | `SkillOps는 이번 변경의 의도를 아래와 같이 이해했습니다.` (`quote`=`intent.text`) |
| 변경 의도 | 조건 있음 | `수정안을 시험할 때 아래 조건이 지켜지는지도 함께 확인했습니다.` (`items`=`intent.checks`) |
| 변경 의도 | 조건 없음 | `의도를 확인할 조건은 만들지 못했습니다.` |
| 변경 의도 | 항상 | `이 해석이 실제 의도와 다르면 승인하지 말고 거절해 주세요.` |
| 수정안 | 후보 있음 | `SkillOps가 수정안 {n}개를 만들어 각각 따로 마련한 연습 공간에서 시험했습니다.` |
| 수정안 | 시험한 후보 있음 | `실패했던 주문과 기준 주문 {total}건을 돌렸고, 의도 확인 조건도 함께 확인했습니다.` (`total`은 처음 시험한 후보의 값) |
| 수정안 | 후보 없음 | `시험할 수정안을 만들지 못했습니다.` |
| 후보 제목 | 항상 | `후보 {no}번 · {kind 이름}` (`same_skill`=`바뀐 Skill만 고치기`, `linked_skills`=`주고받는 값까지 함께 고치기`, `run_order`=`실행 순서 다시 짜기`) |
| 후보 | `fixes_failure` 참/거짓 | `실패했던 주문을 다시 돌렸더니 끝까지 처리됐습니다.` / `실패했던 주문을 다시 돌렸지만 여전히 멈췄습니다.` |
| 후보 | `passed == total` / 아님 | `기준 주문 {total}건도 모두 이전과 같게 처리됐습니다.` / `기준 주문 {total}건 가운데 {total-passed}건이 이전과 다르게 처리됐습니다.` |
| 후보 | `keeps_intent` 참/거짓 | `의도 확인 조건도 모두 지켜졌습니다.` / `의도 확인 조건 가운데 지켜지지 않은 것이 있습니다.` |
| 후보 | `no_broken_links` 참/거짓 | `고친 뒤에도 Skill 사이의 입력과 출력이 모두 이어집니다.` / `고친 뒤에 입력을 받지 못하는 Skill이 남습니다.` |
| 후보 | `validation_error` 있음 | `이 수정안은 지금 Skill 원문과 맞지 않아 적용하지 못했고, 그래서 시험하지 않았습니다.` |
| 추천 | `proposed` | `추천 후보는 {rec}번입니다.` |
| 추천 | 합격 1개 / 2개 이상 | `모든 확인을 통과한 수정안이 이 후보 하나뿐입니다.` / `모든 확인을 통과한 수정안 가운데 Skill 사이 연결이 맞고 고치는 Skill 수가 가장 적은 안입니다.` |
| 추천 | `proposed` | `운영자가 의도 해석을 확인하고 승인하면 승인 기록과 승인 번호가 남습니다. 다만 운영 Skill에 반영하는 단계는 아직 연결되지 않았습니다. 그래서 승인한 뒤에도 운영 주문은 지금 Skill로 처리됩니다.` |
| 추천 | `proposed` | `의도 해석이 틀렸거나 수정안이 맞지 않으면 거절해 주세요. 거절한 뒤에도 후보 내용을 Skill 편집 화면의 초안으로 가져와 직접 고칠 수 있습니다.` |
| 추천 | `no_valid_fix` | `모든 확인을 통과한 수정안이 없어 승인할 안이 없습니다.` · `담당자가 Skill 편집 화면에서 직접 고친 뒤 다시 저장해 주세요. 저장하면 SkillOps가 같은 검증을 다시 시작합니다.` · `이 제안서는 확인한 뒤 거절해서 닫을 수 있습니다.` |

섹션 제목: `개요`, `발생 경위`, `원인`, `변경 의도`, `수정안과 시험 결과`, `추천과 결정 요청`, `참고 정보`

`facts`(값이 `null`이거나 빈 목록이면 그 줄을 뺀다):
- 발생 경위: `시작 계기`(`Skill 수정본 저장` 또는 `주문 실패`), `멈춘 단계`, `오류 종류`(`CONTRACT_VIOLATION`이면 `CONTRACT_VIOLATION (Skill 사이 입력값 불일치)`, 그 밖에는 값 그대로), `받지 못한 값`(`, `로 잇는다), `오류 메시지`, `실패한 실행`, `수정 전 Skill 세트`, `수정 후 Skill 세트`
- 원인: `바뀌기 전`, `바뀐 뒤`, `원인 확인 실행`
- 후보 part: `판정`(`적용 가능 · {rank}순위` 또는 `적용 불가`), `고치는 Skill`, `적용하지 못한 이유`, `AI 설명`, `근거 실행`
- 참고 정보: `제안서 번호`, `진단 번호`, `변경 번호`, `제안서를 만든 시각`

`tone_warnings`: 후보 `explanation`마다 `ToneCheck.findIn`을 돌려 `where`=`candidates[{no}].explanation`, `phrase`=찾은 표현으로 남긴다. 글은 고치지 않는다.

- [ ] **Step 1: 테스트 자료 만들기.** `f1-proposed.json`은 B가 보낼 제안서 그대로다. 값은 다음과 같다.
  - `trigger.type=skill_saved`, `trigger.change_id=c_20261021_01`
  - `failure={execution_id: 33333333-3333-4333-8333-333333333333, skill: payment, category: CONTRACT_VIOLATION, message: "required input inventory.reserved not found", missing_inputs: [inventory.reserved], at: 2026-10-21T05:03:00Z}`
  - `root_cause={skill: inventory-check, change_no: 1, before: "outputs: [inventory.available, inventory.reserved]", after: "outputs: [inventory.available]", proof_run_ids: [r_a…, r_b…]}`
  - `intent={text: "예약 가능 수량을 계속 내놓고 싶다.", checks: ["inventory.reservable_quantity != null"]}`
  - 후보 1: `same_skill`, `w_c1`, 전부 합격, `rank 1`, explanation `"재고 확인 Skill이 예약 여부를 다시 내놓게 했습니다."`
  - 후보 2: `linked_skills`, `w_c2`, `keeps_old_orders {passed: 2, total: 3}`, `no_broken_links false`, `valid false`, `rank null`, explanation `"이 기능을 통해 연결을 맞췄습니다."`(금지 표현 시험용)
  - `recommended_candidate_no=1`, `status=proposed`, `thread_id=5b0c2f3e-8f0a-4a57-9a3c-1c2d3e4f5a6b`, `baseline_snapshot_id`, `snapshot_id`
  
  `no-root-cause.json`은 `trigger.type=failure`이고 `root_cause=null`, `intent=null`, `candidates=[]`, `status=no_valid_fix`다.

- [ ] **Step 2: 실패하는 테스트 쓰기.**

```java
class ToneCheckTest {
    @Test void findsBannedPhrasesInOrder() {
        assertThat(ToneCheck.findIn("이 기능을 통해 모으고, 알고리즘에 의해 정합니다"))
                .containsExactly("을 통해", "에 의해");
    }
    @Test void plainSentenceHasNone() {
        assertThat(ToneCheck.findIn("이 기능으로 데이터를 모을 수 있습니다")).isEmpty();
    }
}

class ProposalDocumentWriterTest {
    static final Instant CREATED = Instant.parse("2026-10-21T05:10:00Z");

    @Test void summaryFollowsTheFixedSentences() {
        ProposalDocument doc = ProposalDocumentWriter.write("p_1", fixture("f1-proposed.json"), CREATED);
        assertThat(doc.title()).isEqualTo("inventory-check 수정 제안서");
        assertThat(doc.summary()).isEqualTo(
                "2026년 10월 21일 14시 3분에 Skill 수정본으로 기준 주문을 시험했더니 payment 단계에서 주문이 멈췄습니다. "
                + "원인은 inventory-check Skill의 변경에 있습니다. 이 변경만 되돌려 다시 돌렸더니 같은 주문이 끝까지 처리됐습니다. "
                + "SkillOps가 수정안 2개를 실제 주문 흐름에서 시험했고, 그중 1개가 모든 확인을 통과했습니다. "
                + "아래 내용을 확인한 뒤 후보 1번의 승인 여부를 정해 주세요.");
    }
    @Test void sectionsFollowTheTableOfContents() {
        ProposalDocument doc = ProposalDocumentWriter.write("p_1", fixture("f1-proposed.json"), CREATED);
        assertThat(doc.sections()).extracting(Section::heading)
                .containsExactly("개요", "발생 경위", "원인", "변경 의도", "수정안과 시험 결과", "추천과 결정 요청", "참고 정보");
    }
    @Test void failedCandidateExplainsEachCriterion() {
        Section second = candidates(write()).parts().get(1);
        assertThat(second.heading()).isEqualTo("후보 2번 · 주고받는 값까지 함께 고치기");
        assertThat(second.candidateNo()).isEqualTo(2);
        assertThat(second.paragraphs()).containsExactly(
                "실패했던 주문을 다시 돌렸더니 끝까지 처리됐습니다.",
                "기준 주문 3건 가운데 1건이 이전과 다르게 처리됐습니다.",
                "의도 확인 조건도 모두 지켜졌습니다.",
                "고친 뒤에 입력을 받지 못하는 Skill이 남습니다.");
        assertThat(second.facts()).contains(new Fact("판정", "적용 불가"));
    }
    @Test void noRootCauseStillProducesAReadableDocument() {
        ProposalDocument doc = ProposalDocumentWriter.write("p_2", fixture("no-root-cause.json"), CREATED);
        assertThat(doc.title()).isEqualTo("Skill 변경 진단 결과");
        assertThat(doc.sections()).extracting(Section::key).doesNotContain("intent");
    }
    @Test void templatesNeverUseBannedPhrases() {
        for (String name : List.of("f1-proposed.json", "no-root-cause.json")) {
            ProposalDocument doc = ProposalDocumentWriter.write("p_1", withoutExplanations(fixture(name)), CREATED);
            allTexts(doc).forEach(text -> assertThat(ToneCheck.findIn(text)).as(text).isEmpty());
        }
    }
    @Test void explanationPhrasesAreRecordedNotRewritten() {
        ProposalDocument doc = write();
        assertThat(doc.toneWarnings()).containsExactly(new ToneWarning("candidates[2].explanation", "을 통해"));
        assertThat(candidates(doc).parts().get(1).facts()).contains(new Fact("AI 설명", "이 기능을 통해 연결을 맞췄습니다."));
    }
}
```
`fixture`, `write`, `candidates`, `withoutExplanations`(후보 `explanation`을 `null`로 바꾼 사본), `allTexts`(제목·요약·문단·quote·items·facts 값 전부)는 테스트 안의 작은 도우미다.

- [ ] **Step 3: 실패를 확인한다.**

Run: `cd service && env -u OPENAI_API_KEY ./gradlew test --tests '*ToneCheckTest' --tests '*ProposalDocumentWriterTest'`
Expected: 컴파일 실패

- [ ] **Step 4: 구현한다.** 위 문장 틀과 섹션 순서를 그대로 쓴다. 입력은 `Map<String, Object>`라서 작은 읽기 도우미(`str`, `map`, `list`, `bool`, `integer`)를 클래스 안에 둔다.

- [ ] **Step 5: 통과를 확인한다.**

Run: `cd service && env -u OPENAI_API_KEY ./gradlew test`
Expected: 모두 통과

- [ ] **Step 6: 커밋**

```bash
git add service/src/main/java/com/capstone4/skillops/proposal/{ToneCheck,ProposalDocument,ProposalDocumentWriter}.java service/src/test/java/com/capstone4/skillops/proposal service/src/test/resources/proposal
git commit -m "[FEAT] 제안서 본문을 정해진 목차와 문장 틀로 작성 (#NN)"
```

---

### Task 3: `save_proposal` 검사와 저장 (C-2)

**Files:**
- Create: `service/.../proposal/Proposal.java`, `ProposalService.java`, `ProposalRepository.java`, `service/.../mcp/ProposalTools.java`
- Test: `service/.../proposal/ProposalServiceTest.java`, `service/.../mcp/ProposalToolsTest.java`

**Interfaces:**
- Consumes: `RunLedger.runIdsOf(String)`, `RunLedger.known(String)` (Task 1). `ProposalDocumentWriter.write(String, Map, Instant)` (Task 2)
- Produces:
  - `Proposal` 엔티티(표 `proposals`, `@Id proposal_id` = `p_` + UUID 32자리 16진수) 칸: `proposalId`, `threadId`(unique, nullable, 길이 64), `status`(`proposed|no_valid_fix|approved|rejected`), `payload`(JSON Map), `document`(JSON Map), `validCandidateNos`(JSON `List<Integer>`), `createdAt`, `decidedAt`(nullable), `@Version long version`
  - `ProposalService.save(Map<String, Object> proposal) -> SavedProposal`
  - `public record SavedProposal(String proposalId, String status, List<Integer> validCandidateNos)` (snake_case로 `{proposal_id, status, valid_candidate_nos}`)
  - `ProposalService.find(String proposalId) -> Optional<Proposal>`, `ProposalService.recent() -> List<Proposal>` (만든 시각 내림차순 100개)
  - `ProposalRepository extends JpaRepository<Proposal, String>`: `Optional<Proposal> findByThreadId(String)`, `List<Proposal> findTop100ByOrderByCreatedAtDesc()`
  - `ProposalTools`(`@Component`, `mcp` 패키지): `@McpTool(name = "save_proposal")`, 인자 `@McpToolParam Map<String, Object> proposal`, 결과는 `WorkspaceToolResults.run(() -> proposals.save(proposal))`

**검사 규칙** (어기면 `IllegalArgumentException`이고, MCP 결과는 `INVALID_INPUT`이다. 메시지는 개발자용이다):
1. `status`는 `proposed`나 `no_valid_fix`다.
2. `candidates`의 `candidate_no`는 1부터 빠짐없이 커진다.
3. `validation_error`가 없는 후보는 `workspace_id`와 `results`가 있고 `evidence_run_ids`가 비어 있지 않다. 그 번호들은 모두 `ledger.runIdsOf(workspace_id)`에 있다.
4. service가 D3의 네 조건으로 합격 후보를 다시 계산한다. 후보의 `valid`가 이 계산과 다르면 거부한다.
5. 합격 후보가 있으면 `status`는 `proposed`이고, 없으면 `no_valid_fix`다.
6. `proposed`이면 `root_cause.proof_run_ids`가 비어 있지 않고 모두 `ledger.known`이다. `recommended_candidate_no`도 합격 후보 번호다.
7. `thread_id`가 이미 저장돼 있으면 검사하지 않고 그 제안서의 `SavedProposal`을 돌려준다.

- [ ] **Step 1: 실패하는 테스트 쓰기.** `ProposalServiceTest`는 `ProposalRepository`를 Mockito로 대신하고(`save`는 인자를 그대로 돌려준다), `InMemoryRunLedger`에 `f1-proposed.json`의 근거 실행을 미리 넣어 둔다.

```java
@Test void savesAndReturnsContractShape() {
    SavedProposal saved = service.save(f1());
    assertThat(saved.proposalId()).matches("p_[0-9a-f]{32}");
    assertThat(saved.status()).isEqualTo("proposed");
    assertThat(saved.validCandidateNos()).containsExactly(1);
    Proposal stored = captured();
    assertThat(stored.getDocument().get("title")).isEqualTo("inventory-check 수정 제안서");
    assertThat(stored.getThreadId()).isEqualTo("5b0c2f3e-8f0a-4a57-9a3c-1c2d3e4f5a6b");
}
@Test void sameThreadReturnsTheFirstProposal() { /* findByThreadId 가 기존 제안서를 돌려주면 save 를 부르지 않고 같은 id */ }
@Test void unknownEvidenceRunIsRejected() {
    ledger.clear();
    assertThatThrownBy(() -> service.save(f1())).isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("후보 1").hasMessageContaining("근거 실행");
}
@Test void agentValidMustMatchRecomputedResult() { /* 후보 2 의 valid 를 true 로 바꾸면 거부 */ }
@Test void proposedWithoutPassingCandidateIsRejected() { /* 후보 1 의 fixes_failure=false, valid=false 인데 status=proposed */ }
@Test void recommendedMustBePassingCandidate() { /* recommended_candidate_no=2 */ }
@Test void candidateNumbersMustStartAtOneWithoutGaps() { /* 1, 3 */ }
@Test void noValidFixWithoutRootCauseIsAccepted() { /* no-root-cause.json 저장, valid_candidate_nos 빈 목록 */ }
@Test void rejectedCandidateWithValidationErrorNeedsNoEvidence() { /* 후보 2 를 validation_error 있고 results·evidence 없는 후보로 바꿔도 저장 */ }
```

`ProposalToolsTest`(`WorkspaceToolsTest`와 같은 방식):
```java
@Test void returnsProposalIdStatusAndValidNumbers() {
    when(service.save(any())).thenReturn(new SavedProposal("p_abc", "proposed", List.of(1)));
    Map<String, Object> body = mapOf(textOf(tools.saveProposal(Map.of("status", "proposed"))));
    assertThat(body.keySet()).containsExactly("proposal_id", "status", "valid_candidate_nos");
}
@Test void invalidProposalBecomesInvalidInputError() {
    when(service.save(any())).thenThrow(new IllegalArgumentException("후보 1 의 근거 실행 …"));
    CallToolResult result = tools.saveProposal(Map.of());
    assertThat(result.isError()).isTrue();
    assertThat(mapOf(textOf(result))).containsEntry("code", "INVALID_INPUT").containsEntry("retryable", false);
}
```

- [ ] **Step 2: 실패를 확인한다.**

Run: `cd service && env -u OPENAI_API_KEY ./gradlew test --tests '*ProposalServiceTest' --tests '*ProposalToolsTest'`
Expected: 컴파일 실패 또는 FAIL

- [ ] **Step 3: 구현한다.** `ProposalService`는 `ApprovalService`와 같은 방식으로 만든다. public 생성자는 `ProposalRepository`, `RunLedger`를 받고 `Clock.systemUTC()`를 쓴다. 테스트용 package-private 생성자는 `Clock`도 받는다. 본문은 `ProposalDocumentWriter.write`로 만든 뒤 Jackson 3 `JsonMapper.convertValue(doc, Map.class)`로 바꿔 넣는다. `ProposalTools`는 `WorkspaceTools`와 같은 방식으로 만든다(같은 패키지라 `WorkspaceToolResults`를 쓸 수 있다). 도구 설명 문자열에는 보내는 값, 돌려주는 값, 위 거부 조건을 적는다.

- [ ] **Step 4: 통과를 확인한다.**

Run: `cd service && env -u OPENAI_API_KEY ./gradlew test`
Expected: 모두 통과

- [ ] **Step 5: 커밋**

```bash
git add service/src/main/java/com/capstone4/skillops/{proposal,mcp/ProposalTools.java} service/src/test/java/com/capstone4/skillops/{proposal,mcp/ProposalToolsTest.java}
git commit -m "[FEAT] save_proposal MCP 도구: 근거 실행과 합격 조건을 검사하고 본문과 함께 저장 (#NN)"
```

---

### Task 4: 승인·거절을 제안서에 기록 (A 승인 코드, 동의 필요)

**Files:**
- Create: `service/.../proposal/ProposalException.java`
- Modify: `service/.../proposal/ProposalService.java`(결정 표시 추가), `service/.../approval/{ApprovalService,Approval,ApprovalRepository}.java`, `service/.../api/ApprovalController.java`
- Delete: `service/.../approval/AgentClient.java`, `AgentException.java`, `service/src/test/.../approval/AgentClientTest.java`
- Test: `service/.../approval/ApprovalServiceTest.java`, `service/.../api/ApprovalControllerTest.java`

**Interfaces:**
- Consumes: `ProposalService.find` (Task 3)
- Produces:
  - `public class ProposalException extends RuntimeException { ProposalException(String code, String message, int httpStatus); String code(); int httpStatus(); }`
  - `ProposalService.markDecided(String proposalId, String decision, Integer candidateNo) -> Proposal`. `decision`은 `approved|rejected`다. 승인이면 `payload.intent.confirmed=true`도 넣는다.
  - `ApprovalService.approve(String proposalId, int candidateNo, boolean intentConfirmed, AuthUser user) -> Approval`, `reject(String proposalId, AuthUser user) -> Approval`, `latestFor(String proposalId) -> Optional<Approval>`. 두 결정 메서드는 `@Transactional`이다.
  - `ApprovalRepository.findTopByProposalIdOrderByDecidedAtDesc(String) -> Optional<Approval>`
  - 승인 요청 JSON: `{candidate_no: 정수(1 이상), intent_confirmed: 참·거짓}`
  - `ApprovalView` JSON: `{proposal_id, decision, candidate_no, intent_confirmed, approval_token, approved_by, approver_name, decided_at, used_at}`
  - `Approval`의 `agentResult`는 생성자에서 `"NOT_SENT"`로 둔다. 표의 칸은 그대로 두고 화면 응답에서만 뺀다. `candidateNo` 주석은 "1부터 세는 후보 번호"로 고친다.

**오류 (화면용 문구는 문체 규칙을 따른다)**

| 경우 | HTTP | code | message |
|---|---|---|---|
| 제안서 없음 | 404 | `PROPOSAL_NOT_FOUND` | `제안서를 찾을 수 없습니다.` |
| 이미 결정됨 | 409 | `PROPOSAL_ALREADY_DECIDED` | `이미 결정한 제안서입니다.` |
| `no_valid_fix` 승인 | 409 | `NO_VALID_FIX` | `승인할 수 있는 수정안이 없는 제안서입니다.` |
| 합격하지 않은 후보 | 400 | `CANDIDATE_NOT_VALID` | `모든 확인을 통과한 후보만 승인할 수 있습니다.` |
| 의도 미확인 | 400 | `INTENT_NOT_CONFIRMED` | `의도 확인 없이 승인할 수 없습니다.` (지금 문구 그대로) |
| 동시 결정(`ObjectOptimisticLockingFailureException`) | 409 | `PROPOSAL_ALREADY_DECIDED` | `이미 결정한 제안서입니다.` |

`no_valid_fix` 제안서의 거절은 허용한다. 제안서를 닫는 용도다.

- [ ] **Step 1: 실패하는 테스트 쓰기.** `ApprovalServiceTest`는 `AgentClient` 대신 `ProposalService`를 Mockito로 대신한다.

```java
@Test void approvalRecordsCandidateNumberAndMarksProposal() {
    given(proposals.markDecided("p_1", "approved", 1)).willReturn(decided("approved"));
    Approval a = service.approve("p_1", 1, true, operator);
    assertThat(a.getCandidateNo()).isEqualTo(1);
    assertThat(a.getApprovalToken()).matches("a_[A-Z2-9]{10}");
    assertThat(a.getAgentResult()).isEqualTo("NOT_SENT");
}
@Test void rejectsCandidateThatDidNotPass() {
    given(proposals.markDecided("p_1", "approved", 2))
            .willThrow(new ProposalException("CANDIDATE_NOT_VALID", "모든 확인을 통과한 후보만 승인할 수 있습니다.", 400));
    assertThatThrownBy(() -> service.approve("p_1", 2, true, operator)).isInstanceOf(ProposalException.class);
    then(repository).should(never()).save(any());
}
```
`ProposalServiceTest`에 추가한다:
```java
@Test void secondDecisionIsRejected()      { /* approved 인 제안서에 markDecided → 409 PROPOSAL_ALREADY_DECIDED */ }
@Test void noValidFixCannotBeApproved()    { /* no_valid_fix 에 approved → 409 NO_VALID_FIX, rejected 는 성공 */ }
@Test void approvingFillsIntentConfirmed() { /* payload.intent.confirmed == true, status approved, decidedAt 채움 */ }
@Test void unknownCandidateNumberIsRejected() { /* 0, 99 → 400 CANDIDATE_NOT_VALID */ }
```
`ApprovalControllerTest`의 요청 본문을 `{"candidate_no":1,"intent_confirmed":true}`로 바꾸고 다음을 더한다:
```java
@Test void candidateNoBelowOneIsBadRequest()        { /* candidate_no 0 → 400 */ }
@Test void proposalErrorKeepsCodeAndStatus()        { /* approvals.approve 가 409 ProposalException → 409, $.code */ }
@Test void optimisticLockFailureBecomesConflict()   { /* ObjectOptimisticLockingFailureException → 409 PROPOSAL_ALREADY_DECIDED */ }
```

- [ ] **Step 2: 실패를 확인한다.**

Run: `cd service && env -u OPENAI_API_KEY ./gradlew test --tests '*Approval*' --tests '*ProposalServiceTest'`
Expected: 컴파일 실패 또는 FAIL

- [ ] **Step 3: 구현한다.** `ApprovalService.decide`는 먼저 `proposals.markDecided`를 부르고, 그다음 승인 기록을 저장한다. 둘은 한 트랜잭션이다. `ApprovalController`의 `@ExceptionHandler(AgentException)`는 `ProposalException`과 `ObjectOptimisticLockingFailureException` 처리로 바꾼다. 컨트롤러 Javadoc에서 "SkillOps Agent POST /approve 로 넘긴다"는 문장을 지운다. `application.yml`의 `skillops.agent.*`는 `DiagnosisController`가 계속 쓰므로 두고, 주석만 "진단 요청 중계와 기록 조회에 쓴다"로 고친다.

- [ ] **Step 4: 통과를 확인한다.**

Run: `cd service && env -u OPENAI_API_KEY ./gradlew test`
Expected: 모두 통과. `AgentClientTest`는 지워져서 없다.

- [ ] **Step 5: 커밋**

```bash
git add -A service/src/main/java/com/capstone4/skillops/{approval,api/ApprovalController.java,proposal} service/src/test/java/com/capstone4/skillops/{approval,api/ApprovalControllerTest.java,proposal} service/src/main/resources/application.yml
git commit -m "[FEAT] 승인·거절을 저장된 제안서에 기록하고 후보 번호로 승인 (#NN)"
```

---

### Task 5: 제안서 조회 API

**Files:**
- Create: `service/.../api/ProposalController.java`
- Modify: `service/.../auth/SecurityConfig.java:55-58`
- Test: `service/.../api/ProposalControllerTest.java`

**Interfaces:**
- Consumes: `ProposalService.find/recent` (Task 3), `ApprovalService.latestFor` (Task 4)
- Produces (화면이 쓰는 모양):
  - `GET /api/proposals` → `{items: [{proposal_id, status, created_at, title, summary, root_cause_skill, recommended_candidate_no, valid_candidate_nos, candidate_count}]}`
  - `GET /api/proposals/{id}` → `{proposal_id, status, created_at, decided_at, thread_id, valid_candidate_nos, recommended_candidate_no, document, proposal, decision}`. 여기서 `document`는 Task 2 모양 그대로이고 `proposal`은 저장한 payload다. `decision`은 `ApprovalView` 또는 `null`이다.
  - 없는 번호는 404 `{code: PROPOSAL_NOT_FOUND, message: "제안서를 찾을 수 없습니다."}`

- [ ] **Step 1: 실패하는 테스트 쓰기.** `ApprovalControllerTest`처럼 `@WebMvcTest(ProposalController.class)`와 `@Import({SecurityConfig.class, TokenService.class})`를 쓴다.

```java
@Test void listRequiresLogin()            { /* 토큰 없이 GET /api/proposals → 401 */ }
@Test void ownerCanReadList()             { /* owner 토큰 → 200, $.items[0].proposal_id, $.items[0].title */ }
@Test void detailCarriesDocumentAndDecision() {
    // $.document.sections[0].heading == "개요", $.proposal.candidates[0].candidate_no == 1,
    // $.decision.candidate_no == 1, $.decision.approval_token 존재
}
@Test void unknownProposalIs404()         { /* $.code == "PROPOSAL_NOT_FOUND" */ }
```

- [ ] **Step 2: 실패를 확인한다.**

Run: `cd service && env -u OPENAI_API_KEY ./gradlew test --tests '*ProposalControllerTest'`
Expected: 컴파일 실패

- [ ] **Step 3: 구현한다.** 경로 변수는 `ApprovalController`와 같은 `p_[A-Za-z0-9_]{1,62}` 검사를 쓴다. `SecurityConfig`에는 `.requestMatchers(HttpMethod.GET, "/api/proposals", "/api/proposals/*").authenticated()`를 OPERATOR 규칙 다음 줄에 넣는다.

- [ ] **Step 4: 통과를 확인한다.**

Run: `cd service && env -u OPENAI_API_KEY ./gradlew test`
Expected: 모두 통과

- [ ] **Step 5: 커밋**

```bash
git add service/src/main/java/com/capstone4/skillops/{api/ProposalController.java,auth/SecurityConfig.java} service/src/test/java/com/capstone4/skillops/api/ProposalControllerTest.java
git commit -m "[FEAT] 로그인한 사용자가 제안서 목록과 본문을 조회 (#NN)"
```

---

### Task 6: B가 실제로 제안서를 저장 (B 코드, 동의 필요)

**Files:**
- Modify: `skillops-agent/skillops_agent/tools.py:161-162`, `stub_tools.py`(끝에 메서드 추가), `nodes.py:728-766`(save_proposal), `nodes.py`의 `rank_and_explain`·`extract_intent` 프롬프트 호출
- Create: `skillops-agent/prompts/style.md`
- Modify: `skillops-agent/prompts/rank_and_explain.md`, `extract_intent.md`, `skillops-agent/scripts/b_c_contract_smoke.py`
- Test: `skillops-agent/tests/test_graph.py`, `tests/test_foundation.py`

**Interfaces:**
- Consumes: MCP `save_proposal(proposal) -> {proposal_id, status, valid_candidate_nos}` (Task 3)
- Produces:
  - `nodes.proposal_payload(state: AgentState) -> dict`: 순수 함수다. 키는 `thread_id, trigger, baseline_snapshot_id, snapshot_id, failure, root_cause, intent, candidates, recommended_candidate_no, status`다.
    - `failure`는 `state["trace"]`의 `EXECUTION_ERROR`에서 만든다: `{execution_id, skill(data.skill_id 또는 event.skill_id), category, message, missing_inputs, at(event.at)}`. 없으면 `None`이다.
    - `root_cause`는 상태 값에 `changes`에서 같은 `change_no`의 `before`, `after`를 더한 것이다. 없으면 `None`이다.
    - 후보마다 `workspace` 값을 `workspace_id`로 옮겨 보낸다.
    - `status`는 `valid`인 후보가 있으면 `proposed`, 없으면 `no_valid_fix`다.
  - `save_proposal` 노드는 저장에 성공하면 `proposal = {**payload, "id": proposal_id, "storage_status": "SAVED", "valid_candidate_nos": [...]}`와 `diagnosis_status = "EVALUATED"`를 돌려준다. 실패하면 예외를 그대로 올린다(`guarded`가 `ERROR`로 기록한다). 지금 있는 "evidence_run_ids 없음(400)" 자체 검사는 지운다. 검사는 service가 한다.
  - `StubMCPTools.save_proposal(proposal)`은 `{"proposal_id": "p_stub" + uuid4().hex[:12], "status": proposal["status"], "valid_candidate_nos": [c["candidate_no"] for c in proposal["candidates"] if c.get("valid")]}`를 돌려주고, 받은 값을 `self.saved`에 남긴다.
  - `prompts/style.md`: 문체 규칙. Global Constraints의 금지 표현 목록과, 사용자가 준 고치기 예시 25쌍(이 계획 맨 아래 "문체 예시" 절)을 담는다. 중괄호 `{}`를 쓰지 않는다(`.format`에 들어간다). `rank_and_explain.md`와 `extract_intent.md` 끝에 `{style}` 자리를 두고, `nodes.py`가 `style=load_prompt("style")`을 넘긴다. `rank_and_explain.md`의 "예: 시나리오 g-01(r_ws_c01_g01)에서 통과함"은 `예: 실패했던 주문(r_…)과 기준 주문 3건이 모두 통과했습니다.`로 바꾼다.

- [ ] **Step 1: 실패하는 테스트 쓰기.**

```python
async def test_proposal_is_saved_with_contract_payload(tools):
    result = await run(initial())  # test_graph.py 의 기존 도우미(initial, run)를 쓴다
    saved = tools.saved[-1]
    assert saved["status"] == "proposed"
    assert saved["failure"]["skill"] == "payment"
    assert saved["failure"]["missing_inputs"] == ["inventory.reserved"]
    assert saved["root_cause"]["before"] and saved["root_cause"]["after"]
    assert all(c["workspace_id"].startswith("w_") for c in saved["candidates"])
    assert "workspace" not in saved["candidates"][0]
    assert result["proposal"]["storage_status"] == "SAVED"
    assert result["proposal"]["id"].startswith("p_stub")
    assert result["diagnosis_status"] == "EVALUATED"

async def test_rejected_save_ends_in_error(tools, monkeypatch):
    async def reject(proposal):
        raise MCPToolError("save_proposal", {"code": "INVALID_INPUT", "message": "후보 1 의 근거 실행"})
    monkeypatch.setattr(tools, "save_proposal", reject)
    result = await run(initial())
    assert result["diagnosis_status"] == "ERROR"
    assert "save_proposal" in result["error"]

def test_style_rules_reach_explanation_prompt():
    text = load_prompt("rank_and_explain").format(root_cause={}, intent={}, candidates=[], style=load_prompt("style"))
    assert "을 통해" in text and "이 기능으로 데이터를 모을 수 있습니다" in text
```
기존 `test_real_event_shape_and_changed_skill_are_used`의 `NOT_SAVED` 단언은 `SAVED`로 바꾼다. `test_foundation.py`의 프롬프트 목록에 `"style"`을 더한다.

- [ ] **Step 2: 실패를 확인한다.**

Run: `cd skillops-agent && env -u OPENAI_API_KEY uv run pytest tests/test_graph.py tests/test_foundation.py -q`
Expected: FAIL (`tools.saved` 없음, `NOT_SAVED`)

- [ ] **Step 3: 구현한다.** `MCPTools.save_proposal`은 `return await self._call("save_proposal", {"proposal": proposal})`다. 나머지는 Interfaces 블록대로 한다. `b_c_contract_smoke.py`의 보고에 `"proposal_id": (result.get("proposal") or {}).get("id")`를 더하고, `storage_status != "SAVED"`이면 실패로 끝낸다. 대역 LLM이 내는 문장도 바꾼다.
  - intent `text`: `"예약 가능 수량(inventory.reservable_quantity)을 계속 내놓고 싶다."`
  - explanation: `"재고 확인 Skill이 예약 여부(inventory.reserved)를 다시 내놓게 했습니다. 실패했던 주문과 기준 주문 3건이 모두 통과했습니다."`

- [ ] **Step 4: 통과를 확인한다.**

Run: `cd skillops-agent && env -u OPENAI_API_KEY uv run pytest -q && env -u OPENAI_API_KEY uv run ruff check skillops_agent tests scripts/b_c_contract_smoke.py`
Expected: 모두 통과, Ruff 문제 없음

- [ ] **Step 5: 커밋**

```bash
git add skillops-agent/skillops_agent/{tools,stub_tools,nodes}.py skillops-agent/prompts skillops-agent/tests/{test_graph,test_foundation}.py skillops-agent/scripts/b_c_contract_smoke.py
git commit -m "[FEAT] B 가 검증한 수정안을 save_proposal 로 저장하고 문체 규칙을 프롬프트에 적용 (#NN)"
```

---

### Task 7: 화면의 제안서 목록 (A 화면, 동의 필요)

**Files:**
- Modify: `frontend/src/api/proposals.js`, `frontend/src/utils/proposalView.js`, `frontend/src/mocks/index.js`(510-561줄 제안서·승인 경로), `frontend/src/mocks/proposal-f1.json`, `frontend/src/views/ProposalListView.vue`

**Interfaces:**
- Consumes: Task 5의 응답 모양, Task 4의 승인 요청·응답 모양
- Produces:
  - `api/proposals.js`: `listProposals() -> Promise<{items}>`(`GET /service/api/proposals`), `getProposal(id)`(`GET /service/api/proposals/{id}`), `approveProposal(proposalId, candidateNo, intentConfirmed)`(본문 `{candidate_no, intent_confirmed}`), `rejectProposal(proposalId)`, `listApprovals()`. `PROPOSALS_AVAILABLE`과 `/agent` 경로는 지운다.
  - `utils/proposalView.js`: `KIND`의 키를 `same_skill`(`바뀐 Skill만 고치기`), `linked_skills`(`주고받는 값까지 함께 고치기`), `run_order`(`실행 순서 다시 짜기`)로 바꾼다. 이름은 Task 2와 같다. `APPROVAL` 대신 `STATUS = { proposed: {label: '검토 대기', tone: 'info'}, no_valid_fix: {label: '쓸 수 있는 안 없음', tone: ''}, approved: {label: '승인됨', tone: 'ok'}, rejected: {label: '거절됨', tone: 'err'} }`를 둔다.
  - `mocks/proposal-f1.json`: Task 5 상세 응답 모양 그대로다. `proposal_id: "p_mockf1"`, `status: "proposed"`다. `document`는 Task 2의 F1 테스트 기대값과 같은 문장으로 손으로 채운다. `proposal.candidates[].patches[].patch`가 있다. `npm run dev:mock`으로 화면을 만들 때 쓴다.
  - mock 경로: `GET /service/api/proposals`, `GET /service/api/proposals/:id`, `POST …/approve|reject`. 규칙은 Task 4 표와 같다(404, 409, 400 code와 문구 포함).

**ProposalListView 문구:** 제목 `받은 제안서`, 설명 `SkillOps가 실제로 시험한 수정 제안서예요. 내용을 읽고 승인하거나 거절해 주세요.`, 빈 상태 `검토할 제안서가 없어요`, 탭 `검토 대기 {n}` / `전체 {n}`. 카드에는 `title`, `summary`, 상태 배지, `추천 후보 {n}번`(있을 때), `합격 {v} / 후보 {c}`가 나온다. `PROPOSALS_AVAILABLE` 분기는 지운다.

- [ ] **Step 1: 구현한다.** Interfaces 블록대로 고친다.
- [ ] **Step 2: 빌드와 lint로 확인한다.**

Run: `cd frontend && npm run build && npm run lint`
Expected: 빌드 성공, lint 문제 없음

- [ ] **Step 3: 커밋**

```bash
git add frontend/src/api/proposals.js frontend/src/utils/proposalView.js frontend/src/mocks frontend/src/views/ProposalListView.vue
git commit -m "[FEAT] 제안서 목록을 service 조회 API 로 읽음 (#NN)"
```

---

### Task 8: 제안서 본문 화면과 승인·거절 (A 화면, 동의 필요)

**Files:**
- Create: `frontend/src/components/ProposalDocument.vue`
- Modify: `frontend/src/views/ProposalView.vue`(전체), `frontend/src/views/DiagnosisView.vue`(86줄 근처), `frontend/src/stores/workspace.js:172`(`p.diff` → `p.patch`)
- Delete: `frontend/src/components/CandidateCard.vue`(다른 곳에서 쓰지 않는다)

**Interfaces:**
- Consumes: Task 7의 API·mock·`STATUS`·`KIND`
- Produces:
  - `ProposalDocument.vue` props: `document`(Task 2 모양), `selectedNo`(Number), `canSelect`(Boolean). emits: `select(candidateNo)`. slot: `intent`(변경 의도 섹션 끝에 들어간다). 문서 제목(`document.title`)은 그리지 않는다. `ProposalView`가 `PageHeader`의 `title`로 넘기고, `PageHeader`가 `h1`로 그린다. 섹션 제목은 `h2`, 후보 part 제목은 `h3`로 그린다. 결정 패널 같은 다른 영역에서는 `h2`를 쓰지 않는다. `paragraphs`는 `p`, `quote`는 `blockquote`, `items`는 `ul`, `facts`는 `dl`이다. `parts`는 누를 수 있는 카드이고, `candidateNo`가 `selectedNo`와 같으면 강조한다. 모든 글은 `{{ }}` 보간으로만 넣는다.
  - `ProposalView.vue` 동작:
    - `getProposal(route.params.id)`로 읽는다. 처음 선택은 `recommended_candidate_no`이고, 없으면 첫 후보다.
    - 선택한 후보의 `patches`를 `DiffView`(`:patch="p.patch"`)로 보여 준다.
    - 결정 패널은 `status === 'proposed'`일 때 승인 버튼(`후보 {n}번 승인`)과 거절 버튼(`거절`)을, `no_valid_fix`일 때 거절 버튼(`확인하고 닫기`)만 보여 준다.
    - 승인 버튼은 OPERATOR이고, 의도를 확인했고, 선택한 후보가 `valid_candidate_nos`에 있을 때만 켜진다.
    - 결정한 뒤에는 `decision`(결정, 결정한 사람, 승인 번호)과 `운영 반영은 아직 연결되지 않았어요. 승인 기록과 승인 번호만 남아요.`를 보여 준다.
    - `초안으로 가져와 고치기` 버튼은 지금처럼 둔다.
    - 기존의 `proposal.recommended`, `approval_status`, `candidate.evidence`, `p.diff`, `root_cause.confirmed_by`, `intent.assertions` 참조는 모두 없앤다.
  - 화면 문구(이대로 쓴다):
    - `PageHeader` 설명: `SkillOps가 실제 주문 흐름에서 시험한 결과예요. 개요부터 차례로 읽고 결정해 주세요.`
    - 의도 확인 체크: `네, SkillOps가 이해한 의도가 맞아요`
    - 확인 안 함 안내: `변경 의도를 확인해야 승인할 수 있어요.`
    - 떨어진 후보를 골랐을 때: `이 후보는 모든 확인을 통과하지 못해 승인할 수 없어요.`
    - OWNER 안내: `승인과 거절은 운영자만 할 수 있어요.`
    - 승인 성공 알림: `후보 {n}번을 승인했어요.`
    - 거절 성공 알림: `제안서를 거절했어요.`
  - `DiagnosisView.vue`: `run.proposal?.id`가 있으면 `제안서 보기` 링크(`/proposals/{id}`)를 보여 준다. `storage_status === 'SAVED'`이면 `제안서를 저장했어요. 운영자가 검토하면 승인 여부가 정해져요.`라고 쓴다. `NOT_SAVED` 문구는 예전 기록을 위해 남긴다.

- [ ] **Step 1: 구현한다.** Interfaces 블록대로 한다.
- [ ] **Step 2: 빌드와 lint로 확인한다.**

Run: `cd frontend && npm run build && npm run lint && grep -rn "v-html" src || echo "v-html 없음"`
Expected: 빌드 성공, lint 문제 없음, `v-html 없음`

- [ ] **Step 3: 커밋**

```bash
git add -A frontend/src/components frontend/src/views/ProposalView.vue frontend/src/views/DiagnosisView.vue frontend/src/stores/workspace.js
git commit -m "[FEAT] 제안서 본문 화면에서 후보를 골라 승인·거절 (#NN)"
```

---

### Task 9: 계약 문서 갱신

**Files:**
- Modify:
  - `docs/contracts/mcp-tools-input-output.md` §10
    - 보내는 값에 `thread_id`, `failure`, `baseline_snapshot_id`, `snapshot_id`, `root_cause.before/after`, `candidates[].valid/validation_error`를 더한다.
    - 돌려받는 값, 합격 조건 네 가지, 거부 조건을 Task 3대로 고친다.
    - "읽고 쓰는 저장 공간"을 `scenario_runs`·`proposals`로 고친다.
  - `docs/contracts/proposal.schema.json`: B가 보내는 v1 모양으로 고친다.
  - `docs/contracts/mcp-tools-and-rest.md`와 `README.md`의 승인 API 행: `{candidate_no, intent_confirmed}`로 고치고, `GET /api/proposals`와 `GET /api/proposals/{id}`를 더한다.
  - `docs/skill-save-auto-diagnosis.md`: "아직 포함하지 않는 것"의 첫 줄을 고친다.
  - `skillops-agent/docs/c-contract-integration.md`: "현재 C에 없는 기능" 절을 고친다.

- [ ] **Step 1: 문서를 고친다.** 문서 문장도 Global Constraints의 문체 규칙을 따른다.
- [ ] **Step 2: 전체 단위 테스트를 돌린다.**

Run:
```bash
cd service && env -u OPENAI_API_KEY ./gradlew test
cd ../skillops-agent && env -u OPENAI_API_KEY uv run pytest -q
cd ../frontend && npm run build && npm run lint
```
Expected: 모두 통과

- [ ] **Step 3: 커밋**

```bash
git add docs/contracts README.md docs/skill-save-auto-diagnosis.md skillops-agent/docs/c-contract-integration.md
git commit -m "[DOCS] 제안서 저장과 승인 계약 문서 갱신 (#NN)"
```

---

### Task 10: 최종 실험 (실제 화면, 실제 LLM, 사용자 승인 뒤)

모든 기능을 구현한 뒤 한 번 돌린다. 이 실험이 이 계획의 성공 기준이다. 유료 OpenAI 호출이 생기므로, 실행하는 쪽은 시작 전에 사용자에게 비용과 순서를 알리고 승인을 받는다. 사용자가 띄운 서버는 다시 켜지 않는다. 새 코드로 다시 켜야 하면 사용자에게 요청한다.

- [ ] **Step 1: 서버를 띄운다(사용자).** 네 서버를 띄운다. 세 서버(order-agent, service, B)의 `SKILLOPS_SERVICE_TOKEN`은 같아야 한다.
  - order-agent(8081, `fixed` 모드, 데모 기능 켬)
  - service(8080, 새 코드, `SKILLOPS_FLOW_RUNNER=harness`, `SKILLOPS_SCENARIOS_DIR=../scenarios/order-agent`)
  - B(8000, 새 코드, `SKILLOPS_USE_STUB_MCP=false`): `env -u OPENAI_API_KEY uv run uvicorn skillops_agent.main:app --port 8000`으로 띄운다. 이렇게 해야 키를 `.env`에서 읽는다.
  - frontend(5173, `npm run dev`)
- [ ] **Step 2: 돈이 들지 않는 사전 점검.** 유료 모델 대신 고정된 결정으로 B를 실제 C와 주문 Agent에 돌린다.

Run: `cd skillops-agent && env -u OPENAI_API_KEY SKILLOPS_USE_STUB_MCP=false uv run scripts/b_c_contract_smoke.py`
Expected: 보고에 `"storage_status": "SAVED"`와 `"proposal_id": "p_…"`가 있고, `stock_and_live_failures_unchanged`가 `true`다. 실패하면 Step 3으로 가지 않고 원인을 고친다.

- [ ] **Step 3: 결함을 만든다.** 담당자(`owner`)로 로그인한다. Skill 편집에서 `정상본 고정`을 누르고, `inventory-check`의 `outputs`에서 `inventory.reserved`를 지운 뒤 `전체 수정본 저장`을 누른다.
- [ ] **Step 4: 진단을 지켜본다.** `자동 검증·진단 진행 확인`을 누른다.
  - 확인할 것: 상태가 `진단·후보 평가 완료`가 되고, `제안서를 저장했어요.` 문구와 `제안서 보기` 링크가 나온다.
  - `오류`로 끝나면 진행 로그와 `error`를 기록하고 멈춘다.
- [ ] **Step 5: 제안서를 읽는다(담당자).** `제안서 보기`를 누른다.
  - 목차: 섹션 제목이 `개요`, `발생 경위`, `원인`, `변경 의도`, `수정안과 시험 결과`, `추천과 결정 요청`, `참고 정보` 순서로 나온다.
  - 내용: 개요의 시각, 멈춘 단계(`payment`), 원인 Skill(`inventory-check`)이 진단 기록과 같다.
  - 근거 번호: `원인 확인 실행`과 `근거 실행` 번호가 실행 화면에서 열린다.
  - 권한: 담당자 화면에서는 승인 버튼이 꺼져 있고 `승인과 거절은 운영자만 할 수 있어요.`가 나온다.
  - 문체 검사 결과: `GET /service/api/proposals/{id}` 응답의 `document.tone_warnings`를 확인한다.
- [ ] **Step 6: 승인한다(운영자).** 운영자(`operator`)로 다시 로그인한다.
  - 승인 전 목록: `받은 제안서`의 `검토 대기` 수를 적어 둔다.
  - 승인 조건: 제안서에서 의도를 확인하기 전에는 승인 버튼이 꺼져 있다. 떨어진 후보를 고르면 `이 후보는 모든 확인을 통과하지 못해 승인할 수 없어요.`가 나온다.
  - 승인: 추천 후보를 승인하면 `후보 {n}번을 승인했어요.`와 `a_`로 시작하는 승인 번호가 나온다.
  - 승인 뒤: 새로고침해도 `승인됨`이고 승인 버튼이 다시 나오지 않는다. 목록의 `검토 대기` 수가 하나 줄었다.
- [ ] **Step 7: 기록한다.** 결과를 `docs/reports/2026-10-21-proposal-approval-experiment.md`에 적는다.
  - 결과: 진단 번호, 제안서 번호, 걸린 시간, 후보 수와 합격 수, 승인 번호, `tone_warnings`, 각 확인 항목의 통과 여부
  - 확인한 것과 확인하지 못한 것을 나눠 적는다.
  - API 키나 토큰 값은 적지 않는다.
  
  커밋은 사용자에게 물은 뒤 한다.

---

## 문체 예시 (Task 6 `prompts/style.md`에 그대로 넣는다)

| 고치기 전 | 고친 뒤 |
|---|---|
| 이 기능을 통해 데이터를 모을 수 있습니다 | 이 기능으로 데이터를 모을 수 있습니다 |
| 검색 의도에 대한 분석이 먼저입니다 | 검색 의도를 먼저 분석합니다 |
| 결과는 대시보드에서 보여집니다 | 결과는 대시보드에 나옵니다 |
| 순위는 알고리즘에 의해 결정됩니다 | 알고리즘이 순위를 정합니다 |
| 이것은 가장 흔한 실수 중 하나입니다 | 흔한 실수입니다 |
| 여러 번 고쳤음에도 불구하고 유입은 늘지 않았습니다 | 여러 번 고쳤는데도 유입은 늘지 않았습니다 |
| 색인이 지연된다는 사실을 확인했습니다 | 색인이 지연된다는 점을 확인했습니다 |
| 설정에 있어서 주의가 필요합니다 | 설정할 때 주의해야 합니다 |
| 방문자가 이해하는 것을 돕습니다 | 방문자가 이해하기 쉬워집니다 |
| 이 기능은 업무 방식을 바꾸는 혁명적 변화입니다 | 이 기능은 주간 보고서 초안을 만듭니다 |
| 놀랍게도 3초 만에 완성됩니다 | 측정한 조건에서 생성에 3초가 걸렸습니다 |
| AI가 마법처럼 코드를 짜 줍니다 | AI가 코드를 대신 씁니다 |
| 노트북을 웹 서버로 변신시킵니다 | 노트북에서 작은 서버 프로그램이 실행됩니다 |
| 획기적인 성능 개선을 이뤄냈습니다 | 응답 시간이 1.2초에서 0.4초로 줄었습니다 |
| 차원이 다른 결과물을 만들어 냅니다 | 표 형식까지 맞춘 결과물이 나옵니다 |
| 두 갈래로 나뉩니다 | 두 가지로 나뉩니다 |
| 아래 도해를 보면 | 아래 그림을 보면 |
| 설정을 손보면 | 설정을 고치면 |
| 비로소 확인됩니다 | 그제야 확인됩니다 |
| 의견이 갈립니다 | 의견이 달라집니다 |
| 어떤 전략이 먹혔는지 | 어떤 전략이 통했는지 |
| 긍정적인 신호로 읽힙니다 | 긍정적인 신호로 보입니다 |
| 담당자께 여쭤보겠습니다 | 담당자에게 물어보겠습니다 |
| 세 지식이 필요합니다 | 세 가지 지식이 필요합니다 |
| 첫 자리에 놓습니다 | 첫 화면에 놓습니다 |
