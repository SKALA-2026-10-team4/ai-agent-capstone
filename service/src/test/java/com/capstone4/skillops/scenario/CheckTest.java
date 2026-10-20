package com.capstone4.skillops.scenario;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Map;

import org.junit.jupiter.api.Test;

class CheckTest {

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
}
