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
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import com.capstone4.skillops.workspace.WorkspaceSkill;

/**
 * service/src/main/java/com/capstone4/skillops/scenario/ContractOnlyFlowRunner.java 의 흉내 규칙을 보호한다.
 * 운영 skill 로는 다섯 단계가 모두 ok 이고 g-01 을 통과하는지, F1 변경이면 payment 에서 멈추고 뒤 단계가 skipped 인지 본다.
 * 연습 공간에 entry flow 나 단계 skill 이 없을 때의 오류 문구, 실행 번호 모양, 주문 칸 앞머리, CANNED 값,
 * order 가 null 일 때의 지금 동작도 고정한다. skill 원문은 TestFixtures 가 저장소의 skills/ 폴더에서 읽는다.
 */
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

    /** 앞 단계가 실패하면 뒤 단계는 맨 위 칸을 읽지 않고 건너뛴다. 그래서 뒤 skill 의 맨 위 칸이 깨져 있어도 오류가 나지 않는다. */
    @Test
    void brokenHeaderAfterFailureIsSkipped() {
        var skills = new HashMap<>(f1Skills());
        skills.put("notify", new WorkspaceSkill(1, "# 맨 위 칸이 없는 글\n"));

        RunOutcome out = runner.runDryRun(new FlowRunner.DryRunRequest("g-01", g01Order(), "w_t", skills));

        assertThat(out.finalStatus()).isEqualTo("FAILED");
        assertThat(out.steps()).extracting(RunOutcome.Step::status)
                .containsExactly("ok", "ok", "error", "skipped", "skipped");
        RunOutcome.Step notify = out.steps().get(4);
        assertThat(notify.skill()).isEqualTo("notify");
        assertThat(notify.input()).isEmpty();
        assertThat(notify.output()).isEmpty();
        assertThat(notify.error()).isNull();
        assertThat(out.steps().get(2).input()).isEmpty();
        assertThat(out.steps().get(2).output()).isEmpty();
    }

    @Test
    void runIdShape() {
        RunOutcome out = runner.runDryRun(new FlowRunner.DryRunRequest("g-01", g01Order(), "w_t", liveSkills()));
        assertThat(out.runId()).matches("r_fake[A-Za-z0-9]{8}");
    }

    /** 주문 칸은 order. 을 붙인 이름으로 문맥에 들어가고, 단계의 받은 값은 맨 위 칸 inputs 순서다. */
    @Test
    void orderFieldsArePrefixed() {
        RunOutcome out = runner.runDryRun(new FlowRunner.DryRunRequest("g-01", g01Order(), "w_t", liveSkills()));

        RunOutcome.Step intake = out.steps().getFirst();
        assertThat(intake.skill()).isEqualTo("order-intake");
        assertThat(intake.input()).containsEntry("order.customer", "c_1001");
        assertThat(intake.input().keySet())
                .containsExactly("order.customer", "order.items", "order.payment_method", "order.shipping");
    }

    /** 내놓는 값은 CANNED 에 있으면 그 값, 없으면 "fake:값이름" 이다. */
    @Test
    void outputsAreCannedOrFake() {
        RunOutcome out = runner.runDryRun(new FlowRunner.DryRunRequest("g-01", g01Order(), "w_t", liveSkills()));

        RunOutcome.Step inventory = out.steps().get(1);
        assertThat(inventory.skill()).isEqualTo("inventory-check");
        assertThat(inventory.output().keySet()).containsExactly("inventory.available", "inventory.reserved");
        assertThat(inventory.output()).containsEntry("inventory.available", "fake:inventory.available")
                .containsEntry("inventory.reserved", true);
        assertThat(out.steps().get(2).input()).containsEntry("inventory.reserved", true);
    }

    /** order 가 null 이면 NullPointerException 이 난다. 지금 동작을 고정한 것이며, order-flow 검사는 그보다 먼저 한다. */
    @Test
    void nullOrderFailsAfterFlowChecks() {
        // 예외 문구는 MCP run_scenarios 의 INTERNAL_ERROR message 로 그대로 나가므로 DryRunRequest.order() 가 null 이라는 문구까지 고정한다.
        assertThatThrownBy(() -> runner.runDryRun(new FlowRunner.DryRunRequest("g-01", null, "w_t", liveSkills())))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("FlowRunner$DryRunRequest.order()");
        var noFlow = new HashMap<>(liveSkills());
        noFlow.remove("order-flow");
        assertThatThrownBy(() -> runner.runDryRun(new FlowRunner.DryRunRequest("g-01", null, "w_t", noFlow)))
                .hasMessage("연습 공간에 order-flow 가 없습니다");
    }

    /** 여러 줄 steps와 outputs를 사용해도 실제 다섯 단계를 실행하고 같은 결과를 낸다. */
    @Test
    void blockListsRunAllStepsAndPreserveOutputKeys() {
        var skills = new HashMap<>(liveSkills());
        WorkspaceSkill flow = skills.get("order-flow");
        skills.put("order-flow", new WorkspaceSkill(flow.baseVersion(), flow.fullText().replace(
                "steps: [order-intake, inventory-check, payment, shipping-reserve, notify]",
                "steps:\n  - order-intake\n  - inventory-check\n  - payment\n  - shipping-reserve\n  - notify")));
        WorkspaceSkill inventory = skills.get("inventory-check");
        skills.put("inventory-check", new WorkspaceSkill(inventory.baseVersion(), inventory.fullText().replace(
                "outputs:    [inventory.available, inventory.reserved]",
                "outputs:\n  - inventory.available\n  - 'inventory.reserved'")));

        RunOutcome result = runner.runDryRun(new FlowRunner.DryRunRequest("g-01", g01Order(), "w_t", skills));

        assertThat(result.finalStatus()).isEqualTo("SHIPPED");
        assertThat(result.steps()).extracting(RunOutcome.Step::skill)
                .containsExactly("order-intake", "inventory-check", "payment", "shipping-reserve", "notify");
        assertThat(result.steps().get(2).input()).containsEntry("inventory.reserved", true);
        assertThat(new ScenarioGrader().grade(Expectation.of(g01().expect()), List.of(), result).passed()).isTrue();
    }

    /** 누락·null·빈 steps는 주문이나 업무 단계를 실행하기 전에 입력 오류로 거부한다. */
    @Test
    void emptyFlowNeverReturnsShipped() {
        for (String declaration : List.of("", "steps: []\n", "steps: null\n", "steps: ~\n", "steps:\n")) {
            var skills = new HashMap<>(liveSkills());
            skills.put("order-flow", new WorkspaceSkill(1,
                    "---\nname: order-flow\nversion: 1\n" + declaration + "---\nbody\n"));

            assertThatThrownBy(() -> runner.runDryRun(new FlowRunner.DryRunRequest("g-01", null, "w_t", skills)))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("steps");
        }
    }

    /** 정상 다섯 단계의 null steps와 flow 선택 목록을 읽고 g-01 결과를 유지한다. */
    @ParameterizedTest
    @ValueSource(strings = {"null", "~", ""})
    void allowedNullListsKeepAllFiveStepsAndGoldenResult(String value) {
        var skills = new HashMap<>(liveSkills());
        skills.replaceAll((id, skill) -> {
            String fields = id.equals("order-flow")
                    ? "inputs: " + value + "\noutputs: " + value + "\ndepends_on: " + value
                    : "steps: " + value;
            return new WorkspaceSkill(skill.baseVersion(), skill.fullText().replace("\n---\n", "\n" + fields + "\n---\n"));
        });

        RunOutcome outcome = runner.runDryRun(new FlowRunner.DryRunRequest("g-01", g01Order(), "w_t", skills));

        assertThat(outcome.finalStatus()).isEqualTo(RunOutcome.SHIPPED);
        assertThat(outcome.steps()).extracting(RunOutcome.Step::skill)
                .containsExactly("order-intake", "inventory-check", "payment", "shipping-reserve", "notify");
        assertThat(outcome.steps()).extracting(RunOutcome.Step::status).containsOnly(RunOutcome.Step.OK);
        assertThat(new ScenarioGrader().grade(Expectation.of(g01().expect()), List.of(), outcome).passed()).isTrue();
    }

    /** steps null을 허용하더라도 일반 단계의 명시적 null 입출력은 실행 오류로 남는다. */
    @ParameterizedTest
    @ValueSource(strings = {"inputs", "outputs"})
    void nullStepInputsAndOutputsRemainRejected(String field) {
        var skills = new HashMap<>(liveSkills());
        WorkspaceSkill payment = skills.get("payment");
        String text = payment.fullText().replaceFirst(field + ":[^\n]*", field + ": null")
                .replace("\n---\n", "\nsteps: null\n---\n");
        skills.put("payment", new WorkspaceSkill(payment.baseVersion(), text));

        assertThatThrownBy(() -> runner.runDryRun(new FlowRunner.DryRunRequest("g-01", g01Order(), "w_t", skills)))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining(field);
    }
}
