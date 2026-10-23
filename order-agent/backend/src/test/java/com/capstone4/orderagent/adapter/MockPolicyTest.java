package com.capstone4.orderagent.adapter;
import java.time.*;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;
/**
 * 결제와 배송 Mock 의 핵심 업무 규칙을 보호한다. 결제 Mock 의 거절이 예외가 아니라 업무 결과인지, 배송 Mock 이 결제 없는 배송을 막고
 * 예정일을 고정 Clock 기준으로 결정적으로 계산하는지 확인한다. 세부 경계값은 같은 폴더의 MockAdapterContractTest 가 맡는다.
 * 대상은 order-agent/backend/src/main/java/com/capstone4/orderagent/adapter/PaymentMockAdapter.java 와
 * order-agent/backend/src/main/java/com/capstone4/orderagent/adapter/ShippingMockAdapter.java 다.
 */
class MockPolicyTest {
    @Test void paymentDeclineIsAnExplicitBusinessResult() {
        var mock = new PaymentMockAdapter(); UUID id = UUID.randomUUID();
        assertThat(mock.pay(id, "c_declined", 30000, "card", true).paid()).isFalse();
        assertThat(mock.pay(id, "c_declined", 30000, "prepaid", true).paid()).isTrue();
        assertThatThrownBy(() -> mock.pay(id, "c_1001", 30000, "card", false)).isInstanceOf(IllegalStateException.class);
    }
    @Test void shippingGuardsPaymentAndCalculatesDeterministicEta() {
        var mock = new ShippingMockAdapter(Clock.fixed(Instant.parse("2026-10-20T00:00:00Z"), ZoneOffset.UTC));
        UUID id = UUID.randomUUID();
        assertThat(mock.reserve(id, "KR", false, true, true).estimatedDeliveryDate()).isEqualTo(LocalDate.of(2026, 10, 22));
        assertThat(mock.reserve(id, "US", true, true, true).estimatedDeliveryDate()).isEqualTo(LocalDate.of(2026, 10, 26));
        assertThatThrownBy(() -> mock.reserve(id, "KR", false, false, true)).isInstanceOf(IllegalStateException.class);
    }
}
