package com.capstone4.orderagent.adapter;
import java.time.*;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;
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
