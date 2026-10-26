package com.capstone4.orderagent.dto.order;

import java.time.*;
import java.util.*;
import com.capstone4.orderagent.entity.Order;
import com.capstone4.orderagent.entity.enums.*;

/**
 * 주문 API 의 응답 본문이다. POST /api/orders, GET /api/orders/{id}, GET /api/orders/by-request/{key} 가 같은 모양을 돌려주며 JSON 키는 snake_case 다.
 * 저장된 주문의 항목과 금액, 상태와 결과 코드, 결제와 배송과 알림 결과, 처리 단계 목록, 실행 기록 연결(execution_id, trace_url)을 담는다.
 * order-agent/backend/src/main/java/com/capstone4/orderagent/service/OrderLifecycleService.java 의 get 과 byRequest 가 from 으로 만든다.
 * 모듈 밖에서는 frontend/src/api/orderAgent.js 와 order-agent/frontend/src/api/orders.js 가 받아 화면에 보여 주고,
 * service/src/main/java/com/capstone4/skillops/scenario/OrderAgentRunClient.java 의 originalOrder 가 customer, items, payment_method, shipping 을 꺼내
 * 실패했던 주문을 모의 실행으로 다시 돌린다. 키를 바꾸면 이 소비자들도 함께 고친다. 명세는 order-agent/docs/api.md 다.
 */
public record OrderResponse(UUID orderId, UUID requestId, String customer, List<Item> items,
    long totalAmount, String currency, String paymentMethod, Shipping shipping,
    OrderStatus status, String resultCode, String message, Payment payment, Shipment shipment,
    Notification notification, List<Step> steps, Instant createdAt, Instant updatedAt, UUID executionId, String traceUrl) {

    /** 접수할 때 고정한 항목 한 줄이다. 상품명과 단가는 주문 뒤 상품 정보가 바뀌어도 그대로다. */
    public record Item(String sku, String name, int qty, long unitPrice, long lineTotal) {}
    /** 배송 국가와 빠른 배송 여부다. */
    public record Shipping(String country, boolean express) {}
    /** 결제 상태와 Mock 결제 참조다. 결제가 승인되기 전에는 reference 가 null 이다. */
    public record Payment(PaymentStatus status, String reference) {}
    /** Mock 배송 참조와 배송 예정일이다. 배송을 예약하기 전에는 응답의 shipment 자체가 null 이다. */
    public record Shipment(String reference, LocalDate estimatedDeliveryDate) {}
    /** 성공 알림 기록 상태다. */
    public record Notification(NotificationStatus status) {}
    /** 주문 처리 단계 기록 한 줄이다. sequence 는 1 부터 늘어나는 기록 순서이며, 결제 거절 뒤 RELEASE 처럼 업무 순서에 없는 단계도 끼어든다. */
    public record Step(int sequence, ProcessingStepType step, ProcessingStepStatus status,
        String resultCode, String message, Instant startedAt, Instant completedAt) {}

    /**
     * 도구나 Harness 가 정하는 업무 종료 결과 코드다(Order.reject, Order.hold, Order.noSelection). HOLD 나 REJECTED 주문의 결과 코드가
     * 이 밖의 값이면 단계 Skill 실행 규칙의 reason 이다(Order.stopByRule). order-agent/frontend/src/utils/format.js 의 orderStatusLabel 도 같은 구분을 쓴다.
     */
    private static final Set<String> NON_RULE_CODES = Set.of("OUT_OF_STOCK", "PAYMENT_DECLINED", "NO_SELECTION");

    /**
     * 주문 엔티티를 응답으로 바꾼다. message 는 주문 상태와 결과 코드로 정한 한국어 안내 문구이고 currency 는 늘 KRW 다.
     * REJECTED 는 재고 부족(OUT_OF_STOCK)과 엔트리 미선택(NO_SELECTION), HOLD 는 결제 거절(PAYMENT_DECLINED)을 기준으로 적고, 결과 코드가 실행 규칙의
     * reason 이면 "업무 규칙(reason)에 따라 주문을 거절/보류했습니다." 로 적는다. 규칙 보류는 결제 뒤 단계에서도 걸릴 수 있어 재고 복원을 말하지 않는다.
     * 결과 코드가 없으면(과거 주문) 예전 문구 그대로다.
     * 배송 참조가 없으면 shipment 를 null 로, 실행 기록이 없으면(과거 MVP 주문) trace_url 을 null 로 둔다.
     * trace_url 은 order-agent/backend/src/main/java/com/capstone4/orderagent/controller/EvidenceController.java 의
     * GET /api/executions/{id}/trace 경로다. 지연 로딩하는 항목과 단계를 읽으므로 OrderLifecycleService 의 읽기 트랜잭션 안에서 불러야 한다.
     */
    public static OrderResponse from(Order o) {
        String code = o.getResultCode();
        boolean rule = code != null && !NON_RULE_CODES.contains(code);
        String message = switch (o.getStatus()) {
            case CREATED -> "주문이 접수되었습니다.";
            case PROCESSING -> "주문을 처리하고 있습니다.";
            case SHIPPED -> "주문 처리가 완료되었습니다. Mock 배송이 예약되었습니다.";
            case REJECTED -> rule ? "업무 규칙(" + code + ")에 따라 주문을 거절했습니다."
                : "NO_SELECTION".equals(code) ? "처리할 Skill 을 고르지 못해 주문을 거절했습니다." : "재고가 부족하여 주문을 거절했습니다.";
            case HOLD -> rule ? "업무 규칙(" + code + ")에 따라 주문을 보류했습니다."
                : "결제가 거절되어 주문을 보류했습니다. 예약 재고는 복원되었습니다.";
            case FAILED -> "주문 처리 중 오류가 발생했습니다. 새 주문 전에 결과를 확인해주세요.";
        };
        return new OrderResponse(o.getId(), o.getRequestId(), o.getCustomer(),
            o.getItems().stream().map(i -> new Item(i.getSku(), i.getProductName(), i.getQuantity(),
                i.getUnitPrice(), i.getLineTotal())).toList(),
            o.getTotalAmount(), "KRW", o.getPaymentMethod(),
            new Shipping(o.getShippingCountry(), o.isShippingExpress()), o.getStatus(), o.getResultCode(),
            message, new Payment(o.getPaymentStatus(), o.getPaymentReference()),
            o.getShipmentReference() == null ? null : new Shipment(o.getShipmentReference(), o.getEstimatedDeliveryDate()),
            new Notification(o.getNotificationStatus()),
            o.getSteps().stream().map(s -> new Step(s.getSequence(), s.getStep(), s.getStatus(),
                s.getResultCode(), s.getMessage(), s.getStartedAt(), s.getCompletedAt())).toList(),
            o.getCreatedAt(), o.getUpdatedAt(), o.getExecutionId(),
            o.getExecutionId()==null?null:"/api/executions/"+o.getExecutionId()+"/trace");
    }
}
