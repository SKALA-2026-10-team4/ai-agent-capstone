package com.capstone4.orderagent.adapter;
import java.time.*;
import java.util.*;
import org.springframework.stereotype.Component;
/**
 * 배송 Mock 이다. 결제와 재고 예약이 끝난 주문만 배송을 예약하고 도착 예정일을 결정적으로 계산한다.
 * 지원 국가, 국내 국가, 국내와 해외 배송 일수, 빠른 배송 단축 일수가 튜닝 지점이며 모두 이 클래스의 상수다.
 * 날짜는 주입한 Clock(order-agent/backend/src/main/java/com/capstone4/orderagent/config/ClockConfig.java 의 UTC)으로 계산한다.
 * order-agent/backend/src/main/java/com/capstone4/orderagent/service/OrderToolService.java 의 shipping.reserve 처리가 부르고,
 * order-agent/backend/src/test/java/com/capstone4/orderagent/adapter 의 MockAdapterContractTest 와 MockPolicyTest 가 정책을 고정한다.
 */
@Component
public class ShippingMockAdapter {
    // 배송할 수 있는 국가다. order-agent/backend/src/main/java/com/capstone4/orderagent/dto/order/CreateOrderRequest.java 의 검증과
    // order-agent/backend/src/main/resources/schema.sql 의 shipping_country CHECK 도 같은 세 나라만 받으므로 함께 바꾼다.
    private static final Set<String> SUPPORTED_COUNTRIES = Set.of("KR", "US", "JP");
    private static final String DOMESTIC_COUNTRY = "KR";
    // 국내와 해외의 기본 배송 일수, 빠른 배송이면 빼는 일수다.
    private static final int DOMESTIC_DAYS = 2;
    private static final int INTERNATIONAL_DAYS = 7;
    private static final int EXPRESS_SAVED_DAYS = 1;
    // 배송 참조 번호 접두사다. 참조는 이 접두사 뒤에 주문 id 를 붙인다.
    private static final String REFERENCE_PREFIX = "ship_mock_";
    /** 배송 예약 결과다. 참조 번호와 도착 예정일을 담는다. */
    public record Result(String reference, LocalDate estimatedDeliveryDate) {}
    private final Clock clock;
    /** Spring 이 ClockConfig 의 UTC Clock 을 주입한다. 테스트는 고정 Clock 을 넣는다. */
    public ShippingMockAdapter(Clock clock) { this.clock = clock; }
    /**
     * 배송을 예약한다. 결제가 끝나지 않았거나 재고 예약이 없거나 지원하지 않는 국가면 IllegalStateException 이다.
     * 예정일은 오늘(UTC)에 국내 2일이나 해외 7일을 더하고, 빠른 배송이면 1일을 뺀다.
     */
    public Result reserve(UUID orderId, String country, boolean express, boolean paid, boolean reserved) {
        if (!paid || !reserved || !SUPPORTED_COUNTRIES.contains(country))
            throw new IllegalStateException("Paid and reserved order required");
        int days = (DOMESTIC_COUNTRY.equals(country) ? DOMESTIC_DAYS : INTERNATIONAL_DAYS) - (express ? EXPRESS_SAVED_DAYS : 0);
        return new Result(REFERENCE_PREFIX + orderId, LocalDate.now(clock).plusDays(days));
    }
}
