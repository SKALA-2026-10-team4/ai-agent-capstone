package com.capstone4.skillops.scenario;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

/**
 * service/src/main/java/com/capstone4/skillops/scenario/OrderAgentTraceConverter.java 의 변환 규칙을 보호한다.
 * 주문 Agent 에서 저장한 실측 trace(service/src/test/resources/order-agent/)가 다섯 ok 단계나 payment 오류 단계가 되는지,
 * 바꾼 결과를 ScenarioGrader 가 그대로 채점할 수 있는지 본다. 실행 중 오류와 정적 검사 오류를 어느 단계에 붙이는지,
 * step_no 순서, 기록이 끊긴 경우, 오류 문구 모양도 고정한다.
 */
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

    /** 실행 중 오류에서는 주문 Agent 가 그 skill 의 SKILL_FINISHED(status error)를 먼저 남긴다. 단계를 둘로 만들지 않는다. */
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

    @Test
    void missingStepNoIsRejected() {
        Map<String, Object> trace = Map.of("final_status", "FAILED", "events", List.of(
                event(1, "SKILL_STARTED", "order-intake", Map.of("input", Map.of()))));

        assertThatThrownBy(() -> OrderAgentTraceConverter.toOutcome("r_x", trace))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageStartingWith("주문 Agent trace 의 단계 기록에 step_no 가 없습니다: ");
    }

    /** 기록이 중간에 끊겨 SKILL_FINISHED 가 없으면 오류 단계로 본다. */
    @Test
    void startedWithoutFinishedBecomesErrorStep() {
        Map<String, Object> trace = Map.of("final_status", "FAILED", "events", List.of(
                event(1, "SKILL_STARTED", "payment", Map.of("step_no", 1, "input", Map.of("order.id", "o1")))));

        assertThat(OrderAgentTraceConverter.toOutcome("r_x", trace).steps()).singleElement().satisfies(step -> {
            assertThat(step.skill()).isEqualTo("payment");
            assertThat(step.status()).isEqualTo("error");
            assertThat(step.input()).containsEntry("order.id", "o1");
            assertThat(step.output()).isEmpty();
            assertThat(step.error()).isEqualTo("payment 단계가 끝났다는 기록이 없습니다");
        });
    }

    @Test
    void finishedErrorWithoutExecutionErrorGetsDefaultText() {
        Map<String, Object> trace = Map.of("final_status", "FAILED", "events", List.of(
                event(1, "SKILL_FINISHED", "payment", Map.of("step_no", 1, "status", "error", "output", Map.of()))));

        assertThat(OrderAgentTraceConverter.toOutcome("r_x", trace).steps()).singleElement().satisfies(step -> {
            assertThat(step.status()).isEqualTo("error");
            assertThat(step.input()).isEmpty();
            assertThat(step.error()).isEqualTo("주문 Agent 가 payment 단계를 오류로 기록했습니다");
        });
    }

    @Test
    void executionErrorWithoutSkillAddsNoStep() {
        Map<String, Object> trace = Map.of("final_status", "FAILED", "events", List.of(
                event(1, "SKILL_STARTED", "order-intake", Map.of("step_no", 1, "input", Map.of())),
                event(2, "SKILL_FINISHED", "order-intake", Map.of("step_no", 1, "status", "ok", "output", Map.of())),
                event(3, "EXECUTION_ERROR", null, Map.of("category", "TIMEOUT"))));

        RunOutcome outcome = OrderAgentTraceConverter.toOutcome("r_x", trace);
        assertThat(outcome.steps()).singleElement().satisfies(step -> {
            assertThat(step.skill()).isEqualTo("order-intake");
            assertThat(step.status()).isEqualTo("ok");
            assertThat(step.error()).isNull();
        });
        assertThat(outcome.error()).isEqualTo("TIMEOUT");
    }

    /**
     * D2: skill 이 붙지 않은 flow 단위 오류(INCOMPLETE_FLOW, UNKNOWN_SKILL, INVALID_FLOW)도 오류 category 가 떨어진 이유 맨 앞에 남는다.
     * 진단 Agent 의 _reproduce_failure 가 reason 에 원래 category 가 있는지 보기 때문이다. failures[].skill 은 모르는 그대로 null 이다.
     */
    @Test
    void skilllessFlowErrorKeepsItsCategoryInTheReason() {
        for (String category : List.of("INCOMPLETE_FLOW", "UNKNOWN_SKILL", "INVALID_FLOW")) {
            Map<String, Object> trace = Map.of("final_status", "FAILED", "events", List.of(
                    event(1, "EXECUTION_ERROR", null, Map.of("category", category, "message", "흐름을 시작할 수 없습니다"))));

            RunOutcome outcome = OrderAgentTraceConverter.toOutcome("r_x", trace);
            GradeResult grade = new ScenarioGrader().grade(Expectation.of(TestFixtures.g01().expect()), List.of(), outcome);

            assertThat(outcome.steps()).as(category).isEmpty();
            assertThat(outcome.error()).as(category).isEqualTo(category + ": 흐름을 시작할 수 없습니다");
            assertThat(grade.skill()).as(category).isNull();
            assertThat(grade.reasons().getFirst()).as(category).isEqualTo("실행 오류: " + category + ": 흐름을 시작할 수 없습니다");
        }
    }

    /** skill 이 붙은 오류는 지금처럼 그 단계에 붙고 RunOutcome.error 는 비어 있다. */
    @Test
    void stepErrorLeavesRunErrorEmpty() {
        RunOutcome outcome = OrderAgentTraceConverter.toOutcome("r_x", Map.of("final_status", "FAILED", "events", List.of(
                event(1, "EXECUTION_ERROR", "payment", Map.of("category", "CONTRACT_VIOLATION")))));

        assertThat(outcome.error()).isNull();
        assertThat(outcome.steps()).singleElement().satisfies(step -> assertThat(step.error()).isEqualTo("CONTRACT_VIOLATION"));
    }

    @Test
    void errorTextListsMissingDependencies() {
        Map<String, Object> error = new HashMap<>();
        error.put("category", "CONTRACT_VIOLATION");
        error.put("missing_inputs", List.of());
        error.put("missing_dependencies", List.of("a", "b"));

        assertThat(OrderAgentTraceConverter.errorText(error)).isEqualTo("CONTRACT_VIOLATION (먼저 끝나야 할 skill a, b)");
        assertThat(OrderAgentTraceConverter.errorText(Map.of("message", " ", "missing_inputs", List.of("x", "y"))))
                .isEqualTo("EXECUTION_ERROR (빠진 값 x, y)");
    }

    /** 같은 skill 이라도 마지막 단계가 ok 로 끝났으면 그 뒤의 실행 오류는 새 오류 단계로 덧붙인다. */
    @Test
    void executionErrorAfterOkStepOfSameSkillAppendsNewStep() {
        Map<String, Object> trace = Map.of("final_status", "FAILED", "events", List.of(
                event(1, "SKILL_STARTED", "payment", Map.of("step_no", 1, "input", Map.of())),
                event(2, "SKILL_FINISHED", "payment",
                        Map.of("step_no", 1, "status", "ok", "output", Map.of("payment.status", "PAID"))),
                event(3, "EXECUTION_ERROR", "payment", Map.of("category", "POST_CHECK_FAILED", "message", "검사 실패"))));

        RunOutcome outcome = OrderAgentTraceConverter.toOutcome("r_x", trace);

        assertThat(outcome.steps()).extracting(RunOutcome.Step::skill, RunOutcome.Step::status)
                .containsExactly(tuple("payment", "ok"), tuple("payment", "error"));
        assertThat(outcome.steps().get(1).input()).isEmpty();
        assertThat(outcome.steps().get(1).error()).isEqualTo("POST_CHECK_FAILED: 검사 실패");
    }

    /** EXECUTION_ERROR 가 여럿이면 마지막 것만 쓴다. */
    @Test
    void lastExecutionErrorWins() {
        Map<String, Object> trace = Map.of("final_status", "FAILED", "events", List.of(
                event(1, "EXECUTION_ERROR", "payment", Map.of("category", "FIRST")),
                event(2, "EXECUTION_ERROR", "notify", Map.of("category", "SECOND"))));

        assertThat(OrderAgentTraceConverter.toOutcome("r_x", trace).steps()).singleElement().satisfies(step -> {
            assertThat(step.skill()).isEqualTo("notify");
            assertThat(step.error()).isEqualTo("SECOND");
        });
    }

    /** 단계는 기록 순서가 아니라 step_no 순서이고, 한 단계의 skill 은 그 step_no 로 처음 들어온 기록의 skill 이다. */
    @Test
    void stepsFollowStepNoAndFirstRecordNamesTheStep() {
        Map<String, Object> trace = Map.of("final_status", "SHIPPED", "events", List.of(
                event(1, "SKILL_STARTED", "payment", Map.of("step_no", 2, "input", Map.of())),
                event(2, "SKILL_FINISHED", "other-name", Map.of("step_no", 2, "status", "ok", "output", Map.of())),
                event(3, "SKILL_STARTED", "order-intake", Map.of("step_no", 1, "input", Map.of())),
                event(4, "SKILL_FINISHED", "order-intake", Map.of("step_no", 1, "status", "ok", "output", Map.of()))));

        assertThat(OrderAgentTraceConverter.toOutcome("r_x", trace).steps()).extracting(RunOutcome.Step::skill)
                .containsExactly("order-intake", "payment");
    }

    @Test
    void nonMapEventsAreIgnored() {
        List<Object> events = new ArrayList<>();
        events.add("잘못된 기록");
        events.add(42);
        events.add(null);
        events.add(event(1, "SKILL_STARTED", "order-intake", Map.of("step_no", 1, "input", Map.of())));
        events.add(event(2, "SKILL_FINISHED", "order-intake", Map.of("step_no", 1, "status", "ok", "output", Map.of())));
        Map<String, Object> trace = new HashMap<>();
        trace.put("final_status", "SHIPPED");
        trace.put("events", events);

        RunOutcome outcome = OrderAgentTraceConverter.toOutcome("r_x", trace);

        assertThat(outcome.finalStatus()).isEqualTo("SHIPPED");
        assertThat(outcome.steps()).extracting(RunOutcome.Step::skill).containsExactly("order-intake");
    }

    /** service/src/test/resources/order-agent/ 의 실측 trace 파일을 Map 으로 읽는다. HarnessFlowRunnerTest 도 이 메서드를 쓴다. */
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

    /** 주문 Agent trace 의 events 한 칸 모양(sequence, kind, skill_id, data)을 만든다. */
    private static Map<String, Object> event(int sequence, String kind, String skill, Map<String, Object> data) {
        Map<String, Object> event = new HashMap<>();
        event.put("sequence", sequence);
        event.put("kind", kind);
        event.put("skill_id", skill);
        event.put("data", data);
        return event;
    }
}
