package com.capstone4.orderagent.adapter;
import java.util.UUID;
import org.springframework.stereotype.Component;
/**
 * 결제 Mock 이다. 재고가 예약된 주문만 결제하고, 결정적인 거절 규칙과 참조 번호 형식을 가진다.
 * 결제 거절 분기를 재현하는 튜닝 지점은 DECLINED_CUSTOMER 와 DECLINED_METHOD 상수다.
 * order-agent/backend/src/main/java/com/capstone4/orderagent/service/OrderToolService.java 의 payment.charge 처리가 부르고,
 * order-agent/backend/src/test/java/com/capstone4/orderagent/adapter 의 MockAdapterContractTest 와 MockPolicyTest 가 정책을 고정한다.
 */
@Component
public class PaymentMockAdapter {
    // 이 고객이 이 결제 방식으로 결제하면 늘 거절한다. 시연과 시험에서 결제 거절(HOLD) 분기를 재현하려고 둔 값이다.
    private static final String DECLINED_CUSTOMER = "c_declined";
    private static final String DECLINED_METHOD = "card";
    // 승인 참조 번호 접두사다. 참조는 이 접두사 뒤에 주문 id 를 붙인다.
    private static final String REFERENCE_PREFIX = "pay_mock_";
    /** 결제 결과다. 승인되면 paid 가 true 이고 reference 가 있으며, 거절되면 false 와 null 이다. */
    public record Result(boolean paid, String reference) {}
    /**
     * 결제를 요청한다. 재고 예약이 없거나 금액이 음수이거나 결제 방식이 card, prepaid 가 아니면 IllegalStateException 이다.
     * 거절은 예외가 아니라 paid 가 false 인 Result 로 돌려준다. 업무 거절을 시스템 오류와 구분해 HOLD 로 기록하게 하려는 것이다.
     */
    public Result pay(UUID orderId, String customer, long amount, String method, boolean reserved) {
        if (!reserved || amount < 0 || !("card".equals(method) || "prepaid".equals(method)))
            throw new IllegalStateException("Invalid payment request");
        if (DECLINED_CUSTOMER.equals(customer) && DECLINED_METHOD.equals(method)) return new Result(false, null);
        return new Result(true, REFERENCE_PREFIX + orderId);
    }
}
