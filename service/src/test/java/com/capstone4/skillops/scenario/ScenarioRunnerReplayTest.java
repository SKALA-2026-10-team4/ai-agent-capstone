package com.capstone4.skillops.scenario;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.capstone4.skillops.workspace.WorkspaceService;

/**
 * service/src/main/java/com/capstone4/skillops/scenario/ScenarioRunner.java 의 재현 주문 처리를 보호한다.
 * 재현 주문은 주문 Agent 에서 실패했던 실제 주문을 연습 공간으로 다시 돌리는 것이다.
 * scenario_ids 의 f- 번호와 replay_run_ids 해석, 결과의 scenario_id 와 replay_of_run_id, 재현 합격 기준,
 * 원래 주문을 돌리기 전에 모두 한 번씩만 꺼내는 동작, contract-only 실행기 거부, 검사 순서를 고정한다.
 * 주문 Agent 는 Mockito 로, 실행기는 finalStatus 로 끝난 것처럼 답하는 가짜로 대신한다.
 */
class ScenarioRunnerReplayTest {

    static final String EXECUTION = "5bc11dca-57c1-4517-950c-a0aec8ed67e2";
    static final String RUN_ALIAS = "r_5bc11dca57c14517950ca0aec8ed67e2";
    static final Map<String, Object> ORIGINAL = Map.of(
            "customer", "c_1001",
            "items", List.of(Map.of("sku", "CAMP-LANTERN-LED", "qty", 1)),
            "payment_method", "card",
            "shipping", Map.of("country", "KR", "express", false));

    WorkspaceService workspaces = mock(WorkspaceService.class);
    OrderAgentRunClient orderAgent = mock(OrderAgentRunClient.class);
    ScenarioCatalog catalog = new ScenarioCatalog("../scenarios", TestFixtures.JSON);
    /** 가짜 실행기가 받은 요청을 차례로 모은다. */
    List<FlowRunner.DryRunRequest> requests = new ArrayList<>();
    /** 가짜 실행기가 돌려줄 최종 상태다. 테스트가 바꿔 가며 쓴다. */
    String finalStatus = "SHIPPED";
    /**
     * 재현을 지원하는 가짜 실행기다. 받은 요청을 requests 에 모으고, r_1 부터 차례로 실행 번호를 매겨
     * 단계 없이 finalStatus 로 끝난 것처럼 답한다.
     */
    FlowRunner harness = new FlowRunner() {
        @Override
        public RunOutcome runDryRun(DryRunRequest request) {
            requests.add(request);
            return new RunOutcome("r_" + requests.size(), finalStatus, List.of());
        }

        @Override
        public boolean supportsReplay() {
            return true;
        }
    };
    ScenarioRunner runner = new ScenarioRunner(workspaces, catalog, harness, new ScenarioGrader(), orderAgent,
            new TestFixtures.InMemoryRunLedger());

    /** 연습 공간 w_snap 은 운영 skill 을 갖고, 주문 Agent 는 EXECUTION 의 원래 주문으로 ORIGINAL 을 돌려준다. */
    @BeforeEach
    void setUp() {
        when(workspaces.skillsOf("w_snap")).thenReturn(TestFixtures.liveSkills());
        when(orderAgent.originalOrder(EXECUTION)).thenReturn(ORIGINAL);
    }

    @Test
    void replaysFailedExecutionAsF() {
        var report = runner.run("w_snap", List.of("f-" + EXECUTION), null, null, 2);

        assertThat(report.allPassed()).isTrue();
        assertThat(report.results()).singleElement().satisfies(r -> {
            assertThat(r.scenarioId()).isEqualTo("f-" + EXECUTION);
            assertThat(r.replayOfRunId()).isEqualTo(EXECUTION);
            assertThat(r.passed()).isTrue();
            assertThat(r.runIds()).containsExactly("r_1", "r_2");
        });
        assertThat(requests).hasSize(2).allSatisfy(req -> {
            assertThat(req.scenarioId()).isEqualTo("f-" + EXECUTION);
            assertThat(req.order()).isEqualTo(ORIGINAL);
            assertThat(req.workspaceId()).isEqualTo("w_snap");
        });
    }

    @Test
    void replayRunIdAliasAccepted() {
        var report = runner.run("w_snap", null, List.of(RUN_ALIAS), null, 1);

        assertThat(report.results()).singleElement().satisfies(r -> {
            assertThat(r.scenarioId()).isEqualTo("f-" + EXECUTION);
            assertThat(r.replayOfRunId()).isEqualTo(RUN_ALIAS);
        });
        verify(orderAgent).originalOrder(EXECUTION);
        assertThat(requests).singleElement().satisfies(req -> assertThat(req.order()).isEqualTo(ORIGINAL));
    }

    @Test
    void replayRunIdsAcceptExecutionUuid() {
        var report = runner.run("w_snap", List.of(), List.of(EXECUTION), null, 1);

        assertThat(report.results()).singleElement().satisfies(r -> {
            assertThat(r.scenarioId()).isEqualTo("f-" + EXECUTION);
            assertThat(r.replayOfRunId()).isEqualTo(EXECUTION);
        });
    }

    @Test
    void replayPassesUnlessFailed() {
        for (String status : List.of("SHIPPED", "REJECTED", "HOLD")) {
            finalStatus = status;
            assertThat(runner.run("w_snap", List.of("f-" + EXECUTION), null, null, 1).allPassed())
                    .as(status).isTrue();
        }

        finalStatus = "FAILED";
        var report = runner.run("w_snap", List.of("f-" + EXECUTION), null, null, 1);

        assertThat(report.allPassed()).isFalse();
        assertThat(report.results().getFirst().failures()).singleElement()
                .satisfies(f -> assertThat(f.reason()).isEqualTo("오류로 멈추지 않아야 하는데 최종 상태가 FAILED"));
    }

    @Test
    void unknownReplayExecutionRejectedBeforeRunning() {
        String unknown = "00000000-0000-0000-0000-000000000000";
        when(orderAgent.originalOrder(unknown)).thenThrow(
                new IllegalArgumentException("재현할 실행 " + unknown + " 을 주문 Agent 에서 찾을 수 없습니다"));

        // 기준 주문 g-01 이 앞에 있어도 아무것도 돌리지 않는다.
        assertThatThrownBy(() -> runner.run("w_snap", List.of("g-01", "f-" + unknown), null, null, 1))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("재현할 실행 " + unknown + " 을 주문 Agent 에서 찾을 수 없습니다");
        assertThat(requests).isEmpty();
    }

    @Test
    void replayNeedsHarnessRunner() {
        var contractOnly = new ScenarioRunner(workspaces, catalog, new ContractOnlyFlowRunner(), new ScenarioGrader(),
                orderAgent, new TestFixtures.InMemoryRunLedger());

        assertThatThrownBy(() -> contractOnly.run("w_snap", List.of("f-" + EXECUTION), null, null, 1))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("재현 주문은 skillops.flow-runner=harness 에서만 돌릴 수 있습니다");
        assertThatThrownBy(() -> contractOnly.run("w_snap", null, List.of(RUN_ALIAS), null, 1))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("재현 주문은 skillops.flow-runner=harness 에서만 돌릴 수 있습니다");
        verifyNoInteractions(orderAgent);
    }

    /** f- 뒤가 하이픈 있는 UUID 일 때만 재현 주문이다. 나머지는 시험 주문 목록에서 찾고, 없으면 목록의 오류가 난다. */
    @Test
    void otherFIdsGoToTheCatalog() {
        for (String id : List.of("f-01", "f-repro", "f-" + EXECUTION.replace("-", ""))) {
            assertThatThrownBy(() -> runner.run("w_snap", List.of(id), null, null, 1))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageStartingWith("시험 주문 " + id + " 이 없습니다");
        }
        verifyNoInteractions(orderAgent);
        assertThat(requests).isEmpty();
    }

    @Test
    void badReplayRunIdRejected() {
        for (String id : List.of("5bc11dca", "r_5bc11dca", "f-" + EXECUTION, "r_" + EXECUTION, "")) {
            assertThatThrownBy(() -> runner.run("w_snap", null, List.of(id), null, 1))
                    .as(id)
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("replay_run_ids");
        }
        verifyNoInteractions(orderAgent);
        assertThat(requests).isEmpty();
    }

    @Test
    void needsScenarioIdsOrReplayRunIds() {
        assertThatThrownBy(() -> runner.run("w_snap", null, null, null, null))
                .hasMessage("scenario_ids 나 replay_run_ids 가운데 하나는 넣으세요");
        assertThatThrownBy(() -> runner.run("w_snap", List.of(), List.of(), null, null))
                .hasMessage("scenario_ids 나 replay_run_ids 가운데 하나는 넣으세요");
        assertThat(requests).isEmpty();
    }

    /** 같은 실행을 두 번 넣어도 원래 주문은 한 번만 꺼낸다. */
    @Test
    void sameExecutionIsFetchedOnce() {
        runner.run("w_snap", List.of("f-" + EXECUTION), List.of(RUN_ALIAS), null, 1);

        verify(orderAgent, times(1)).originalOrder(EXECUTION);
        assertThat(requests).hasSize(2);
    }

    @Test
    void extraChecksApplyToReplays() {
        var report = runner.run("w_snap", List.of("f-" + EXECUTION), null, List.of("payment.status == 'PAID'"), 1);

        assertThat(report.allPassed()).isFalse();
        assertThat(report.results().getFirst().failures()).singleElement().satisfies(f -> {
            assertThat(f.runId()).isEqualTo("r_1");
            assertThat(f.skill()).isNull();
            assertThat(f.reason()).isEqualTo("확인 조건 실패: payment.status == 'PAID' (실제 null)");
        });
    }

    /**
     * scenario_ids 의 f-&lt;대문자 UUID&gt; 는 scenario_id 에 받은 번호를, replay_of_run_id 에 f- 뒤 글자를 대소문자 그대로 쓴다.
     * replay_run_ids 의 대문자 UUID 는 scenario_id 만 소문자로 바꾼다. 원래 주문은 늘 소문자 번호로 꺼낸다.
     */
    @Test
    void uppercaseReplayKeepsGivenCase() {
        String upper = EXECUTION.toUpperCase(Locale.ROOT);

        var fromScenarioIds = runner.run("w_snap", List.of("f-" + upper), null, null, 1);
        var fromReplayRunIds = runner.run("w_snap", null, List.of(upper), null, 1);

        assertThat(fromScenarioIds.results()).singleElement().satisfies(r -> {
            assertThat(r.scenarioId()).isEqualTo("f-" + upper);
            assertThat(r.replayOfRunId()).isEqualTo(upper);
        });
        assertThat(fromReplayRunIds.results()).singleElement().satisfies(r -> {
            assertThat(r.scenarioId()).isEqualTo("f-" + EXECUTION);
            assertThat(r.replayOfRunId()).isEqualTo(upper);
        });
        verify(orderAgent, times(2)).originalOrder(EXECUTION);
        verify(orderAgent, never()).originalOrder(upper);
    }

    @Test
    void scenarioIdErrorComesBeforeReplayRunIdError() {
        assertThatThrownBy(() -> runner.run("w_snap", List.of("g-99"), List.of("bad"), null, 1))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageStartingWith("시험 주문 g-99 이 없습니다");
    }

    /** 잘못된 replay_run_ids 는 실행기가 재현을 못 하는지보다 먼저 알린다. */
    @Test
    void badReplayRunIdIsReportedBeforeRunnerSupport() {
        var contractOnly = new ScenarioRunner(workspaces, catalog, new ContractOnlyFlowRunner(), new ScenarioGrader(),
                orderAgent, new TestFixtures.InMemoryRunLedger());

        assertThatThrownBy(() -> contractOnly.run("w_snap", null, List.of("bad"), null, 1))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("replay_run_ids 의 bad 를 읽을 수 없습니다. 실행 번호(UUID)나 run_id(r_ 와 32자리 16진수)를 넣으세요");
        verifyNoInteractions(orderAgent);
    }

    @Test
    void goldenAndReplayResultsKeepTheGivenOrder() {
        var report = runner.run("w_snap", List.of("f-" + EXECUTION, "g-01"), List.of(RUN_ALIAS), null, 1);

        assertThat(report.results()).extracting(ScenarioRunReport.ScenarioResult::scenarioId)
                .containsExactly("f-" + EXECUTION, "g-01", "f-" + EXECUTION);
        assertThat(report.results()).extracting(ScenarioRunReport.ScenarioResult::replayOfRunId)
                .containsExactly(EXECUTION, null, RUN_ALIAS);
        assertThat(requests).extracting(FlowRunner.DryRunRequest::order)
                .containsExactly(ORIGINAL, TestFixtures.g01Order(), ORIGINAL);
    }
}
