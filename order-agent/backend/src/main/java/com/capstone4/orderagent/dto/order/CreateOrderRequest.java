package com.capstone4.orderagent.dto.order;

import java.util.List;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;

/**
 * POST /api/orders 의 요청 본문이며 POST /api/dry-runs 의 order 로도 쓴다. JSON 키는 customer, items, payment_method, shipping, snapshot_id 다.
 * Bean Validation 이 형식을 먼저 막는다. customer 는 앞뒤 공백을 허용한 c_ 접두 식별자(64자 이하), items 는 1~20개이고 항목마다
 * SKU(64자 이하)와 수량 1~99, payment_method 는 card 나 prepaid, shipping.country 는 KR, US, JP 가운데 하나다.
 * 어기면 order-agent/backend/src/main/java/com/capstone4/orderagent/exception/GlobalExceptionHandler.java 가 VALIDATION_ERROR(400)와 field_errors 로 답한다.
 * 같은 SKU 를 여러 줄에 나눠 적은 수량의 합계 99 한도는 order-agent/backend/src/main/java/com/capstone4/orderagent/service/NormalizedOrder.java 가,
 * 허용값은 order-agent/backend/src/main/resources/schema.sql 의 CHECK 가 한 번 더 막는다.
 * 허용값을 바꾸면 schema.sql 의 CHECK, order-agent/backend/src/main/java/com/capstone4/orderagent/adapter/ShippingMockAdapter.java 의 SUPPORTED_COUNTRIES,
 * order-agent/backend/src/main/java/com/capstone4/orderagent/adapter/PaymentMockAdapter.java 의 결제 방식 검사도 함께 바꾼다.
 * snapshot_id 는 선택이다. 주면 그 snapshot 으로 실행하고 멱등 해시에도 들어가며, 없으면 실행할 때 게시 번들을 새 snapshot 으로 고정한다.
 * order-agent/backend/src/main/java/com/capstone4/orderagent/controller/ 의 OrderController.create 와 DryRunController.run 이 받고,
 * order-agent/backend/src/main/java/com/capstone4/orderagent/service/ 의 OrderService.createOrder 와 DryRunService.run 이 NormalizedOrder.from 으로 정규화한다.
 */
public record CreateOrderRequest(
    @NotBlank @Size(max = 64) @Pattern(regexp = "\\s*c_[A-Za-z0-9_]+\\s*") String customer,
    @NotNull @Size(min = 1, max = 20) List<@NotNull @Valid Item> items,
    @NotNull @Pattern(regexp = "card|prepaid") String paymentMethod,
    @NotNull @Valid Shipping shipping,
    java.util.UUID snapshotId
) {
    /** snapshot_id 없이 만드는 편의 생성자다. 지금은 OrderApiIntegrationTest 만 쓴다. */
    public CreateOrderRequest(String customer, List<Item> items, String method, Shipping shipping) {
        this(customer, items, method, shipping, null);
    }
    /**
     * 주문 항목 한 줄이다. qty 는 정수 1~99 이며, 1.5 같은 실수는 order-agent/backend/src/main/resources/application.yml 의
     * accept-float-as-int: false 설정 때문에 본문을 읽는 단계에서 거절된다(MALFORMED_JSON).
     */
    public record Item(@NotBlank @Size(max = 64) String sku, @NotNull @Min(1) @Max(99) Integer qty) {}
    /** 배송 국가(KR, US, JP)와 빠른 배송 여부다. 배송 예정일은 ShippingMockAdapter 가 이 두 값으로 계산한다. */
    public record Shipping(@NotNull @Pattern(regexp = "KR|US|JP") String country, boolean express) {}
}
