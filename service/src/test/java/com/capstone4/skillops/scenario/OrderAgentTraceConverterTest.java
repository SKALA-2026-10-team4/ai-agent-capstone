package com.capstone4.skillops.scenario;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

/** 주문 Agent 에서 저장한 실측 trace(src/test/resources/order-agent/)를 RunOutcome 으로 바꾼다. */
class OrderAgentTraceConverterTest {

    @Test
    void shippedTraceBecomesFiveOkSteps() {
        RunOutcome outcome = OrderAgentTraceConverter.toOutcome("r_374d6c7e6c314f659ed151dda24608cd",
                trace("trace-shipped.json"));

        assertThat(outcome.runId()).isEqualTo("r_374d6c7e6c314f659ed151dda24608cd");
        assertThat(outcome.finalStatus()).isEqualTo("SHIPPED");
        assertThat(outcome.steps()).extracting(RunOutcome.Step::skill)
                .containsExactly("order-intake", "inventory-check", "payment", "shipping-reserve", "notify");
        assertThat(outcome.steps()).allSatisfy(step -> {
            assertThat(step.status()).isEqualTo("ok");
            assertThat(step.error()).isNull();
        });
        RunOutcome.Step payment = outcome.steps().get(2);
        assertThat(payment.input()).containsEntry("inventory.reserved", true);
        assertThat(payment.output()).containsEntry("payment.status", "PAID");
    }

    @Test
    void contractViolationBecomesErrorStep() {
        RunOutcome outcome = OrderAgentTraceConverter.toOutcome("r_5bc11dca57c14517950ca0aec8ed67e2",
                trace("trace-contract-violation.json"));

        assertThat(outcome.finalStatus()).isEqualTo("FAILED");
        // 정적 검사에서 막혀 SKILL_STARTED 가 없다. 그래서 payment 오류 단계 하나뿐이다.
        assertThat(outcome.steps()).singleElement().satisfies(step -> {
            assertThat(step.skill()).isEqualTo("payment");
            assertThat(step.status()).isEqualTo("error");
            assertThat(step.input()).isEmpty();
            assertThat(step.output()).isEmpty();
            assertThat(step.error()).isEqualTo(
                    "CONTRACT_VIOLATION: Skill에 필요한 입력 또는 선행 단계가 충족되지 않았습니다. (빠진 값 inventory.reserved)");
        });
    }

    /** 실행 중 오류: 주문 Agent 는 그 skill 의 SKILL_FINISHED(status error)를 먼저 남긴다. 단계를 둘로 만들지 않는다. */
    @Test
    void runtimeErrorFillsTheFinishedErrorStep() {
        Map<String, Object> trace = Map.of("final_status", "FAILED", "events", List.of(
                event(1, "SKILL_STARTED", "order-intake", Map.of("step_no", 1, "input", Map.of("order.id", "o1"))),
                event(2, "SKILL_FINISHED", "order-intake",
                        Map.of("step_no", 1, "status", "ok", "output", Map.of("order.total", 30000))),
                event(3, "SKILL_STARTED", "inventory-check", Map.of("step_no", 2, "input", Map.of("order.id", "o1"))),
                event(4, "SKILL_FINISHED", "inventory-check", Map.of("step_no", 2, "status", "error", "output", Map.of())),
                event(5, "EXECUTION_ERROR", "inventory-check", Map.of("category", "MISSING_SKILL_OUTPUT",
                        "skill_id", "inventory-check", "missing_inputs", List.of(), "phase", "runtime")),
                event(6, "EXECUTION_FINISHED", null, Map.of("final_status", "FAILED"))));

        RunOutcome outcome = OrderAgentTraceConverter.toOutcome("r_x", trace);

        assertThat(outcome.steps()).extracting(RunOutcome.Step::skill).containsExactly("order-intake", "inventory-check");
        RunOutcome.Step failed = outcome.steps().get(1);
        assertThat(failed.status()).isEqualTo("error");
        assertThat(failed.input()).containsEntry("order.id", "o1");
        assertThat(failed.error()).isEqualTo("MISSING_SKILL_OUTPUT");
    }

    /** 바꾼 결과를 C-2 채점기가 그대로 채점할 수 있어야 한다. 실측 SHIPPED 는 g-01 의 기대값을 모두 만족한다. */
    @Test
    void shippedTraceMeetsG01Expectation() {
        RunOutcome outcome = OrderAgentTraceConverter.toOutcome("r_1", trace("trace-shipped.json"));

        GradeResult grade = new ScenarioGrader().grade(Expectation.of(TestFixtures.g01().expect()), List.of(), outcome);

        assertThat(grade.reasons()).isEmpty();
        assertThat(grade.passed()).isTrue();
    }

    @Test
    void contractViolationFailsG01AtPayment() {
        RunOutcome outcome = OrderAgentTraceConverter.toOutcome("r_1", trace("trace-contract-violation.json"));

        GradeResult grade = new ScenarioGrader().grade(Expectation.of(TestFixtures.g01().expect()), List.of(), outcome);

        assertThat(grade.passed()).isFalse();
        assertThat(grade.skill()).isEqualTo("payment");
        assertThat(grade.reasons().getFirst()).startsWith("payment 단계 오류: CONTRACT_VIOLATION");
    }

    static Map<String, Object> trace(String name) {
        try (InputStream in = OrderAgentTraceConverterTest.class.getResourceAsStream("/order-agent/" + name)) {
            if (in == null) {
                throw new IllegalStateException("시험 자료가 없습니다: order-agent/" + name);
            }
            @SuppressWarnings("unchecked")
            Map<String, Object> trace = TestFixtures.JSON.readValue(in, Map.class);
            return trace;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static Map<String, Object> event(int sequence, String kind, String skill, Map<String, Object> data) {
        Map<String, Object> event = new HashMap<>();
        event.put("sequence", sequence);
        event.put("kind", kind);
        event.put("skill_id", skill);
        event.put("data", data);
        return event;
    }
}
