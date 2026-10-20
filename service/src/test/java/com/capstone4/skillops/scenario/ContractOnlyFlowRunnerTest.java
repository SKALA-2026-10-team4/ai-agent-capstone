package com.capstone4.skillops.scenario;

import static com.capstone4.skillops.scenario.TestFixtures.f1Skills;
import static com.capstone4.skillops.scenario.TestFixtures.g01;
import static com.capstone4.skillops.scenario.TestFixtures.g01Order;
import static com.capstone4.skillops.scenario.TestFixtures.liveSkills;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.HashMap;
import java.util.List;

import org.junit.jupiter.api.Test;

class ContractOnlyFlowRunnerTest {

    FlowRunner runner = new ContractOnlyFlowRunner();

    @Test
    void liveSkillsShipAndPassG01() {
        RunOutcome out = runner.runDryRun(new FlowRunner.DryRunRequest("g-01", g01Order(), "w_t", liveSkills()));
        assertThat(out.runId()).startsWith("r_fake");
        assertThat(out.finalStatus()).isEqualTo("SHIPPED");
        assertThat(out.steps()).extracting(RunOutcome.Step::status).containsOnly("ok").hasSize(5);
        assertThat(new ScenarioGrader().grade(Expectation.of(g01().expect()), List.of(), out).passed()).isTrue();
    }

    @Test
    void f1ChangeBreaksPayment() {
        RunOutcome out = runner.runDryRun(new FlowRunner.DryRunRequest("g-01", g01Order(), "w_t", f1Skills()));
        assertThat(out.finalStatus()).isEqualTo("FAILED");
        assertThat(out.steps().get(2).skill()).isEqualTo("payment");
        assertThat(out.steps().get(2).error()).isEqualTo("required input inventory.reserved not found");
        assertThat(out.steps()).extracting(RunOutcome.Step::status)
                .containsExactly("ok", "ok", "error", "skipped", "skipped");
    }

    @Test
    void workspaceWithoutOrderFlowIsRejected() {
        var skills = new HashMap<>(liveSkills());
        skills.remove("order-flow");
        assertThatThrownBy(() -> runner.runDryRun(new FlowRunner.DryRunRequest("g-01", g01Order(), "w_t", skills)))
                .hasMessage("연습 공간에 order-flow 가 없습니다");
    }

    @Test
    void stepSkillMissingFromWorkspaceIsRejected() {
        var skills = new HashMap<>(liveSkills());
        skills.remove("notify");
        assertThatThrownBy(() -> runner.runDryRun(new FlowRunner.DryRunRequest("g-01", g01Order(), "w_t", skills)))
                .hasMessage("order-flow 의 실행 순서에 있는 notify 가 연습 공간에 없습니다");
    }
}
