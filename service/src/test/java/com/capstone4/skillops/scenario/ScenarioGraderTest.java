package com.capstone4.skillops.scenario;

import static com.capstone4.skillops.scenario.TestFixtures.g01;
import static org.assertj.core.api.Assertions.assertThat;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

/**
 * service/src/main/java/com/capstone4/skillops/scenario/ScenarioGrader.java 의 채점 규칙과 떨어진 이유의 순서와 문구를 보호한다.
 * 이유 문구는 계약 5 의 failures[].reason 으로 Agent 에게 가므로 f1RunFailsWithAllReasonsInOrder 가 글자 단위로 고정한다.
 * 확인 조건과 extra_checks 의 순서, 재현 기준, 비어 있는 기대 칸, 합격해도 오류 단계 skill 이 남는 동작, 배송 건수 세는 법도 본다.
 */
class ScenarioGraderTest {

    ScenarioGrader grader = new ScenarioGrader();

    @Test
    void passesG01() {
        GradeResult r = grader.grade(Expectation.of(g01().expect()), List.of(), shippedRun());
        assertThat(r.passed()).isTrue();
        assertThat(r.reasons()).isEmpty();
        assertThat(r.skill()).isNull();
    }

    @Test
    void f1RunFailsWithAllReasonsInOrder() {
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

    @Test
    void failsOnWrongValueOnly() {
        GradeResult r = grader.grade(Expectation.of(g01().expect()), List.of(),
                shippedRunWith("payment.status", "DECLINED"));
        assertThat(r.reasons()).containsExactly("확인 조건 실패: payment.status == 'PAID' (실제 'DECLINED')");
        assertThat(r.skill()).isNull();
    }

    @Test
    void extraChecksApplyAfterScenarioChecks() {
        GradeResult r = grader.grade(Expectation.of(g01().expect()),
                List.of(Check.parse("inventory.예약가능수량 != null")), shippedRun());
        assertThat(r.reasons()).containsExactly("확인 조건 실패: inventory.예약가능수량 != null (실제 null)");
    }

    @Test
    void replayPassesUnlessFailed() {
        assertThat(grader.grade(Expectation.replay(), List.of(), runWithFinalStatus("REJECTED")).passed()).isTrue();
        assertThat(grader.grade(Expectation.replay(), List.of(), f1Run()).reasons())
                .contains("오류로 멈추지 않아야 하는데 최종 상태가 FAILED");
    }

    @Test
    void skipsMissingExpectFields() {
        var expect = new Scenario.Expect(
                List.of("order-intake", "inventory-check", "payment", "shipping-reserve", "notify"), "SHIPPED", null, null);
        assertThat(grader.grade(Expectation.of(expect), List.of(), shippedRun()).passed()).isTrue();
    }

    /** 합격이어도 GradeResult.skill 에는 처음 오류가 난 단계의 skill 이 들어간다(이유는 비어 있다). */
    @Test
    void errorStepSkillIsReportedEvenWhenPassed() {
        RunOutcome rejected = new RunOutcome("r_rej", "REJECTED", List.of(
                new RunOutcome.Step("payment", "error", Map.of(), Map.of(), "결제가 거절됐습니다")));

        GradeResult r = grader.grade(Expectation.replay(), List.of(), rejected);

        assertThat(r.passed()).isTrue();
        assertThat(r.skill()).isEqualTo("payment");
        assertThat(r.reasons()).isEmpty();
    }

    /** 배송 건수는 성공한 shipping-reserve 단계 가운데 shipping.id 를 내놓은 단계만 센다. */
    @Test
    void shipmentsCountOnlyOkStepsWithShippingId() {
        GradeResult r = grader.grade(Expectation.of(g01().expect()), List.of(), shippedRunWith("shipping.id", null));

        assertThat(r.passed()).isFalse();
        assertThat(r.reasons()).containsExactly("배송 건수 기대 1, 실제 0");
        assertThat(r.skill()).isNull();
    }

    /** g-01 의 기대값을 모두 만족하는 SHIPPED 실행이다. */
    private static RunOutcome shippedRun() {
        return new RunOutcome("r_test", "SHIPPED", List.of(
                ok("order-intake", Map.of("order.id", "o_1", "order.total", 39000)),
                ok("inventory-check", Map.of("inventory.available", 10, "inventory.reserved", true)),
                ok("payment", Map.of("payment.id", "pay_1", "payment.status", "PAID")),
                ok("shipping-reserve", Map.of("shipping.id", "sh_1", "shipping.eta", "2026-10-23")),
                ok("notify", Map.of("notify.sent", true, "notify.channel", "sms"))));
    }

    /** shippedRun 에서 key 를 내놓은 단계의 그 값만 value 로 바꾼 실행이다. */
    private static RunOutcome shippedRunWith(String key, Object value) {
        List<RunOutcome.Step> steps = shippedRun().steps().stream().map(s -> {
            if (!s.output().containsKey(key)) {
                return s;
            }
            Map<String, Object> output = new HashMap<>(s.output());
            output.put(key, value);
            return new RunOutcome.Step(s.skill(), s.status(), s.input(), output, s.error());
        }).toList();
        return new RunOutcome("r_test", "SHIPPED", steps);
    }

    /** 단계는 shippedRun 과 같고 최종 상태만 다른 실행이다. */
    private static RunOutcome runWithFinalStatus(String finalStatus) {
        return new RunOutcome("r_test", finalStatus, shippedRun().steps());
    }

    /** 장애 F1 실행이다. inventory-check 가 inventory.reserved 대신 inventory.예약가능수량 을 내놓아 payment 에서 멈춘다. */
    private static RunOutcome f1Run() {
        return new RunOutcome("r_f1", "FAILED", List.of(
                ok("order-intake", Map.of("order.id", "o_1", "order.total", 39000)),
                ok("inventory-check", Map.of("inventory.예약가능수량", 12)),
                new RunOutcome.Step("payment", "error", Map.of(), Map.of(),
                        "required input inventory.reserved not found"),
                new RunOutcome.Step("shipping-reserve", "skipped", Map.of(), Map.of(), null),
                new RunOutcome.Step("notify", "skipped", Map.of(), Map.of(), null)));
    }

    /** 받은 값은 비어 있고 output 을 내놓은 ok 단계를 만든다. */
    private static RunOutcome.Step ok(String skill, Map<String, Object> output) {
        return new RunOutcome.Step(skill, "ok", Map.of(), output, null);
    }
}
