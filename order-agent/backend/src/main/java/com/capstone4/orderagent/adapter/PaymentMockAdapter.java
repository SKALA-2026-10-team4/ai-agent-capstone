package com.capstone4.orderagent.adapter;
import java.util.UUID;
import org.springframework.stereotype.Component;
@Component
public class PaymentMockAdapter {
    public record Result(boolean paid, String reference) {}
    public Result pay(UUID orderId, String customer, long amount, String method, boolean reserved) {
        if (!reserved || amount < 0 || !("card".equals(method) || "prepaid".equals(method)))
            throw new IllegalStateException("Invalid payment request");
        if ("c_declined".equals(customer) && "card".equals(method)) return new Result(false, null);
        return new Result(true, "pay_mock_" + orderId);
    }
}
