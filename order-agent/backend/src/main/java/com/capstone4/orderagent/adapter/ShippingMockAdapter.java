package com.capstone4.orderagent.adapter;
import java.time.*;
import java.util.*;
import org.springframework.stereotype.Component;
@Component
public class ShippingMockAdapter {
    public record Result(String reference, LocalDate estimatedDeliveryDate) {}
    private final Clock clock;
    public ShippingMockAdapter(Clock clock) { this.clock = clock; }
    public Result reserve(UUID orderId, String country, boolean express, boolean paid, boolean reserved) {
        if (!paid || !reserved || !Set.of("KR", "US", "JP").contains(country))
            throw new IllegalStateException("Paid and reserved order required");
        int days = ("KR".equals(country) ? 2 : 7) - (express ? 1 : 0);
        return new Result("ship_mock_" + orderId, LocalDate.now(clock).plusDays(days));
    }
}
