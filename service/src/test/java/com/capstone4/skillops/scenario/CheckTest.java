package com.capstone4.skillops.scenario;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * service/src/main/java/com/capstone4/skillops/scenario/Check.java 의 조건 문법 v2 를 보호한다.
 * 앞의 시험들은 v1 동작(==, !=, in, 값 비교, 오류 문구)이 그대로인지 보고, 뒤의 매개변수 시험은 주문 Agent 와 진단 Agent 가 함께 쓰는
 * docs/contracts/examples/condition-cases.json 의 모든 사례(evaluate 의 holds, unreadable)를 그대로 따르는지 본다.
 */
class CheckTest {

    /** 공용 사례 파일의 모양이다. description, version, notes 칸은 읽지 않는다. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    record Cases(Map<String, Object> context, List<Evaluate> evaluate, List<String> unreadable) {
    }

    /** 평가 사례 하나다. */
    record Evaluate(String text, boolean holds) {
    }

    @Test
    void stringEquality() {
        Check c = Check.parse("payment.status == 'PAID'");
        assertThat(c.holds(Map.of("payment.status", "PAID"))).isTrue();
        assertThat(c.holds(Map.of("payment.status", "DECLINED"))).isFalse();
    }

    @Test
    void booleanAndNullWithKoreanKey() {
        assertThat(Check.parse("notify.sent == true").holds(Map.of("notify.sent", true))).isTrue();
        Check present = Check.parse("inventory.예약가능수량 != null");
        assertThat(present.holds(Map.of())).isFalse();
        assertThat(present.holds(Map.of("inventory.예약가능수량", 12))).isTrue();
    }

    @Test
    void numbersCompareByValue() {
        Check c = Check.parse("order.total == 39000");
        assertThat(c.holds(Map.of("order.total", 39000))).isTrue();
        assertThat(c.holds(Map.of("order.total", 39000.0))).isTrue();
    }

    @Test
    void inChecksMembership() {
        Check c = Check.parse("payment.status in ['PAID', 'HOLD']");
        assertThat(c.holds(Map.of("payment.status", "HOLD"))).isTrue();
        assertThat(c.holds(Map.of("payment.status", "FAILED"))).isFalse();
    }

    @Test
    void rejectsMalformed() {
        assertThatThrownBy(() -> Check.parse("payment.status = PAID"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("확인 조건을 읽을 수 없습니다: payment.status = PAID (예: payment.status == 'PAID')");
        assertThatThrownBy(() -> Check.parse("payment.status in 'PAID'"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void actualText() {
        Check c = Check.parse("payment.status == 'PAID'");
        assertThat(c.actualText(Map.of())).isEqualTo("null");
        assertThat(c.actualText(Map.of("payment.status", "DECLINED"))).isEqualTo("'DECLINED'");
    }

    /** 숫자 비교는 실제 값이 숫자일 때만 참이 될 수 있고, 오른쪽은 늘 BigDecimal 로 읽는다. */
    @Test
    void orderingNeedsANumberOnBothSides() {
        Check atLeast = Check.parse("order.total >= 100000");
        assertThat(atLeast.op()).isEqualTo(Check.Op.GE);
        assertThat(atLeast.holds(Map.of("order.total", 120000L))).isTrue();
        assertThat(atLeast.holds(Map.of("order.total", 99999.99))).isFalse();
        assertThat(atLeast.holds(Map.of("order.total", "120000"))).isFalse();
        assertThat(atLeast.holds(Map.of())).isFalse();
        assertThat(Check.parse("order.total < 0").holds(Map.of())).isFalse();
    }

    /** 경로 찾기는 가장 긴 앞부분 하나만 본다. 그 값이 Map 이 아니면 더 짧은 앞부분으로 다시 찾지 않고 null 이다. */
    @Test
    void lookupUsesOnlyTheLongestPrefix() {
        Map<String, Object> context = Map.of("order", Map.of("shipping", Map.of("country", "KR")), "order.shipping", "flat");

        assertThat(Check.lookup(context, "order.shipping.country")).isNull();
        assertThat(Check.lookup(Map.of("order", Map.of("shipping", Map.of("country", "KR"))), "order.shipping.country"))
                .isEqualTo("KR");
        assertThat(Check.parse("order.shipping.country != 'KR'").actualText(
                Map.of("order.shipping", Map.of("country", "US")))).isEqualTo("'US'");
    }

    /** 공용 사례의 평가 결과를 그대로 따른다. */
    @ParameterizedTest(name = "{index}: {0}")
    @MethodSource("evaluateCases")
    void sharedConditionCasesEvaluate(Evaluate testCase) {
        assertThat(Check.parse(testCase.text()).holds(cases().context())).as(testCase.text()).isEqualTo(testCase.holds());
    }

    /** 공용 사례의 읽을 수 없는 조건은 모두 IllegalArgumentException 으로 거부한다. */
    @ParameterizedTest(name = "{index}: [{0}]")
    @MethodSource("unreadableCases")
    void sharedConditionCasesRejectUnreadable(String text) {
        assertThatThrownBy(() -> Check.parse(text)).as(text).isInstanceOf(IllegalArgumentException.class)
                .hasMessageStartingWith("확인 조건을 읽을 수 없습니다");
    }

    static Stream<Evaluate> evaluateCases() {
        return cases().evaluate().stream();
    }

    static Stream<String> unreadableCases() {
        return cases().unreadable().stream();
    }

    /** 저장소의 공용 조건 사례 파일을 읽는다. 테스트는 service/ 에서 돌므로 ../ 로 저장소 루트를 가리킨다. */
    static Cases cases() {
        return TestFixtures.JSON.readValue(Path.of("../docs/contracts/examples/condition-cases.json").toFile(), Cases.class);
    }
}
