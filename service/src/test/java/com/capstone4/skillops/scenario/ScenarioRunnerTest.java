package com.capstone4.skillops.scenario;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.capstone4.skillops.workspace.WorkspaceService;

class ScenarioRunnerTest {

    WorkspaceService workspaces = mock(WorkspaceService.class);
    AtomicInteger calls = new AtomicInteger();
    ScenarioRunner runner;

    @BeforeEach
    void setUp() {
        when(workspaces.skillsOf("w_ok")).thenReturn(TestFixtures.liveSkills());
        when(workspaces.skillsOf("w_f1")).thenReturn(TestFixtures.f1Skills());
        FlowRunner real = new ContractOnlyFlowRunner();
        FlowRunner counting = req -> {
            calls.incrementAndGet();
            return real.runDryRun(req);
        };
        runner = new ScenarioRunner(workspaces, new ScenarioCatalog(Path.of("../scenarios"), TestFixtures.JSON),
                counting, new ScenarioGrader());
    }

    @Test
    void runsG01TwiceAndPasses() {
        var report = runner.run("w_ok", List.of("g-01"), null, null);
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
        var report = runner.run("w_f1", List.of("g-01"), List.of("inventory.예약가능수량 != null"), 2);
        assertThat(report.allPassed()).isFalse();
        var f = report.results().get(0).failures();
        assertThat(f).hasSize(2);
        assertThat(f.get(0).skill()).isEqualTo("payment");
        assertThat(f.get(0).reason()).contains("최종 상태 기대 SHIPPED, 실제 FAILED").contains("; ");
    }

    @Test
    void rejectsBadCheckBeforeRunning() {
        assertThatThrownBy(() -> runner.run("w_ok", List.of("g-01"), List.of("payment.status = PAID"), 2))
                .hasMessageStartingWith("확인 조건을 읽을 수 없습니다");
        assertThat(calls).hasValue(0);
    }

    @Test
    void rejectsUnknownScenario() {
        assertThatThrownBy(() -> runner.run("w_ok", List.of("g-99"), null, null)).hasMessageContaining("g-01");
        assertThat(calls).hasValue(0);
    }

    @Test
    void rejectsUnknownWorkspaceBeforeRunning() {
        when(workspaces.skillsOf("w_x")).thenThrow(
                new IllegalArgumentException("workspace_id w_x 이 없습니다. create_workspace 로 먼저 만드세요"));
        assertThatThrownBy(() -> runner.run("w_x", List.of("g-01"), null, null)).hasMessageContaining("w_x");
        assertThat(calls).hasValue(0);
    }

    @Test
    void rejectsEmptyScenarioIdsAndBadRepeat() {
        assertThatThrownBy(() -> runner.run("w_ok", List.of(), null, null))
                .hasMessageStartingWith("scenario_ids 에 시험 주문 번호를 하나 이상 넣으세요");
        assertThatThrownBy(() -> runner.run("w_ok", List.of("g-01"), null, 0))
                .hasMessage("repeat 는 1~5 사이여야 합니다 (받은 값 0)");
        assertThatThrownBy(() -> runner.run("w_ok", List.of("g-01"), null, 6))
                .hasMessage("repeat 는 1~5 사이여야 합니다 (받은 값 6)");
        assertThat(calls).hasValue(0);
    }
}
