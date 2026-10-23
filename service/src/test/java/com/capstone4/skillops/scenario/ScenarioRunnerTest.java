package com.capstone4.skillops.scenario;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.capstone4.skillops.workspace.WorkspaceService;

/**
 * service/src/main/java/com/capstone4/skillops/scenario/ScenarioRunner.java 의 기준 주문 실행을 보호한다.
 * 기본 반복 횟수와 결과 모양, 떨어진 이유를 "; " 로 잇는 모양, 실행마다 장부에 남기는 동작을 본다.
 * 잘못된 입력을 아무것도 돌리기 전에 거부하는지와 검사 순서(곧 오류 우선순위)도 고정한다.
 * 실행기는 실제 ContractOnlyFlowRunner 를 감싸 부른 횟수를 세고, 연습 공간은 Mockito 로 대신한다.
 */
class ScenarioRunnerTest {

    WorkspaceService workspaces = mock(WorkspaceService.class);
    OrderAgentRunClient orderAgent = mock(OrderAgentRunClient.class);
    AtomicInteger calls = new AtomicInteger();
    TestFixtures.InMemoryRunLedger ledger = new TestFixtures.InMemoryRunLedger();
    ScenarioRunner runner;

    /** 연습 공간 w_ok 는 운영 skill, w_f1 은 장애 F1 skill 을 갖는다. 실행기는 ContractOnlyFlowRunner 를 감싸 부른 횟수를 calls 에 센다. */
    @BeforeEach
    void setUp() {
        when(workspaces.skillsOf("w_ok")).thenReturn(TestFixtures.liveSkills());
        when(workspaces.skillsOf("w_f1")).thenReturn(TestFixtures.f1Skills());
        FlowRunner real = new ContractOnlyFlowRunner();
        FlowRunner counting = req -> {
            calls.incrementAndGet();
            return real.runDryRun(req);
        };
        runner = new ScenarioRunner(workspaces, new ScenarioCatalog("../scenarios", TestFixtures.JSON),
                counting, new ScenarioGrader(), orderAgent, ledger);
    }

    @Test
    void everyRunIsRecordedForItsWorkspace() {
        var passed = runner.run("w_ok", List.of("g-01"), null, null, 2);
        var failed = runner.run("w_f1", List.of("g-01"), null, null, 1);
        List<String> okRuns = passed.results().get(0).runIds();
        List<String> f1Runs = failed.results().get(0).runIds();

        assertThat(ledger.runIdsOf("w_ok")).containsExactlyInAnyOrderElementsOf(okRuns);
        assertThat(ledger.runIdsOf("w_f1")).containsExactlyInAnyOrderElementsOf(f1Runs);
        assertThat(ledger.runIdsOf("w_other")).isEmpty();
        assertThat(ledger.known(okRuns.get(0))).isTrue();
        assertThat(ledger.known(f1Runs.get(0))).isTrue();
        assertThat(ledger.known("r_" + "0".repeat(32))).isFalse();
    }

    @Test
    void nothingIsRecordedWhenInputIsRejectedBeforeRunning() {
        assertThatThrownBy(() -> runner.run("w_ok", List.of("g-99"), null, null, 1))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(ledger.runIdsOf("w_ok")).isEmpty();
    }

    @Test
    void runsG01TwiceAndPasses() {
        var report = runner.run("w_ok", List.of("g-01"), null, null, null);
        assertThat(report.workspaceId()).isEqualTo("w_ok");
        assertThat(report.allPassed()).isTrue();
        assertThat(report.results()).singleElement().satisfies(r -> {
            assertThat(r.scenarioId()).isEqualTo("g-01");
            assertThat(r.replayOfRunId()).isNull();
            assertThat(r.runIds()).hasSize(2);
            assertThat(r.failures()).isEmpty();
        });
    }

    @Test
    void f1WorkspaceFailsWithJoinedReasons() {
        var report = runner.run("w_f1", List.of("g-01"), null, List.of("inventory.예약가능수량 != null"), 2);
        assertThat(report.allPassed()).isFalse();
        var f = report.results().get(0).failures();
        assertThat(f).hasSize(2);
        assertThat(f.get(0).skill()).isEqualTo("payment");
        assertThat(f.get(0).reason()).contains("최종 상태 기대 SHIPPED, 실제 FAILED").contains("; ");
    }

    @Test
    void rejectsBadCheckBeforeRunning() {
        assertThatThrownBy(() -> runner.run("w_ok", List.of("g-01"), null, List.of("payment.status = PAID"), 2))
                .hasMessageStartingWith("확인 조건을 읽을 수 없습니다");
        assertThat(calls).hasValue(0);
    }

    @Test
    void rejectsUnknownScenario() {
        assertThatThrownBy(() -> runner.run("w_ok", List.of("g-99"), null, null, null)).hasMessageContaining("g-01");
        assertThat(calls).hasValue(0);
    }

    @Test
    void rejectsUnknownWorkspaceBeforeRunning() {
        when(workspaces.skillsOf("w_x")).thenThrow(
                new IllegalArgumentException("workspace_id w_x 이 없습니다. create_workspace 로 먼저 만드세요"));
        assertThatThrownBy(() -> runner.run("w_x", List.of("g-01"), null, null, null)).hasMessageContaining("w_x");
        assertThat(calls).hasValue(0);
    }

    @Test
    void rejectsEmptyScenarioIdsAndBadRepeat() {
        assertThatThrownBy(() -> runner.run("w_ok", List.of(), null, null, null))
                .hasMessage("scenario_ids 나 replay_run_ids 가운데 하나는 넣으세요");
        assertThatThrownBy(() -> runner.run("w_ok", List.of("g-01"), null, null, 0))
                .hasMessage("repeat 는 1~5 사이여야 합니다 (받은 값 0)");
        assertThatThrownBy(() -> runner.run("w_ok", List.of("g-01"), null, null, 6))
                .hasMessage("repeat 는 1~5 사이여야 합니다 (받은 값 6)");
        assertThat(calls).hasValue(0);
    }

    /** 장부는 실행마다 채점 직후 남긴다. 뒤 실행에서 예외가 나도 앞서 돌린 실행은 장부에 남아 있다. */
    @Test
    void ledgerKeepsEarlierTrialsWhenALaterTrialFails() {
        FlowRunner real = new ContractOnlyFlowRunner();
        FlowRunner failsOnSecond = req -> {
            if (calls.incrementAndGet() == 2) {
                throw new IllegalStateException("주문 Agent 에 연결할 수 없습니다");
            }
            return real.runDryRun(req);
        };
        var flaky = new ScenarioRunner(workspaces, new ScenarioCatalog("../scenarios", TestFixtures.JSON),
                failsOnSecond, new ScenarioGrader(), orderAgent, ledger);

        assertThatThrownBy(() -> flaky.run("w_ok", List.of("g-01"), null, null, 2))
                .isInstanceOf(IllegalStateException.class);
        assertThat(ledger.runIdsOf("w_ok")).hasSize(1);
    }

    // 아래 네 테스트는 검사 순서를 고정한다. 검사 순서가 곧 오류 우선순위이며,
    // repeat, 빈 입력, extra_checks, 연습 공간, 시험 주문 번호 순서로 검사한다.

    @Test
    void repeatIsCheckedBeforeEmptyIds() {
        assertThatThrownBy(() -> runner.run("w_ok", List.of(), null, null, 0))
                .hasMessage("repeat 는 1~5 사이여야 합니다 (받은 값 0)");
    }

    @Test
    void emptyIdsAreCheckedBeforeChecks() {
        assertThatThrownBy(() -> runner.run("w_ok", List.of(), null, List.of("bad"), null))
                .hasMessage("scenario_ids 나 replay_run_ids 가운데 하나는 넣으세요");
    }

    @Test
    void checksAreParsedBeforeWorkspaceLookup() {
        when(workspaces.skillsOf("w_x")).thenThrow(
                new IllegalArgumentException("workspace_id w_x 이 없습니다. create_workspace 로 먼저 만드세요"));

        assertThatThrownBy(() -> runner.run("w_x", List.of("g-01"), null, List.of("bad"), null))
                .hasMessageStartingWith("확인 조건을 읽을 수 없습니다");
        verify(workspaces, never()).skillsOf("w_x");
    }

    @Test
    void workspaceIsCheckedBeforeScenarioIds() {
        when(workspaces.skillsOf("w_x")).thenThrow(
                new IllegalArgumentException("workspace_id w_x 이 없습니다. create_workspace 로 먼저 만드세요"));

        assertThatThrownBy(() -> runner.run("w_x", List.of("g-99"), null, null, null))
                .hasMessage("workspace_id w_x 이 없습니다. create_workspace 로 먼저 만드세요");
    }

    /** 떨어진 이유는 첫 오류 단계 이유가 맨 앞이고 "; " 로 잇는다. */
    @Test
    void f1ReasonStartsWithErrorStepAndSeparator() {
        var report = runner.run("w_f1", List.of("g-01"), null, null, 1);

        assertThat(report.results().getFirst().failures()).singleElement().satisfies(f -> {
            assertThat(f.skill()).isEqualTo("payment");
            assertThat(f.reason()).startsWith(
                    "payment 단계 오류: required input inventory.reserved not found; 거친 skill 순서 기대");
        });
    }

    /** scenario_ids 안의 null 은 시험 주문 목록에서 찾다가 NullPointerException 이 난다. 지금 동작을 고정한 것이다. */
    @Test
    void nullScenarioIdFailsInTheCatalog() {
        assertThatThrownBy(() -> runner.run("w_ok", Collections.singletonList(null), null, null, 1))
                .isInstanceOf(NullPointerException.class);
        assertThat(calls).hasValue(0);
    }

    @Test
    void rejectsBadScenarioFileCheckBeforeRunningAnyScenario(@TempDir Path dir) throws Exception {
        Path golden = Files.createDirectories(dir.resolve("golden"));
        String g01 = Files.readString(Path.of("../scenarios/golden/g-01.json"));
        Files.writeString(golden.resolve("g-01.json"), g01);
        Files.writeString(golden.resolve("g-02.json"), g01.replace("\"g-01\"", "\"g-02\"")
                .replace("payment.status == 'PAID'", "payment.status = 'PAID'"));
        FlowRunner counting = req -> {
            calls.incrementAndGet();
            return new ContractOnlyFlowRunner().runDryRun(req);
        };
        var withBadFile = new ScenarioRunner(workspaces, new ScenarioCatalog(dir.toString(), TestFixtures.JSON),
                counting, new ScenarioGrader(), orderAgent, ledger);

        assertThatThrownBy(() -> withBadFile.run("w_ok", List.of("g-01", "g-02"), null, null, 2))
                .hasMessageStartingWith("시험 주문 g-02 파일의 확인 조건을 읽을 수 없습니다: payment.status = 'PAID'");
        assertThat(calls).hasValue(0);
    }
}
