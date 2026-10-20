package com.capstone4.orderagent.dto.order;

import java.time.*;
import java.util.*;
import com.capstone4.orderagent.entity.Order;
import com.capstone4.orderagent.entity.enums.*;

public record OrderResponse(UUID orderId, UUID requestId, String customer, List<Item> items,
    long totalAmount, String currency, String paymentMethod, Shipping shipping,
    OrderStatus status, String resultCode, String message, Payment payment, Shipment shipment,
    Notification notification, List<Step> steps, Instant createdAt, Instant updatedAt, UUID executionId, String traceUrl) {

    public record Item(String sku, String name, int qty, long unitPrice, long lineTotal) {}
    public record Shipping(String country, boolean express) {}
    public record Payment(PaymentStatus status, String reference) {}
    public record Shipment(String reference, LocalDate estimatedDeliveryDate) {}
    public record Notification(NotificationStatus status) {}
    public record Step(int sequence, ProcessingStepType step, ProcessingStepStatus status,
        String resultCode, String message, Instant startedAt, Instant completedAt) {}

    public static OrderResponse from(Order o) {
        String message = switch (o.getStatus()) {
            case CREATED -> "주문이 접수되었습니다.";
            case PROCESSING -> "주문을 처리하고 있습니다.";
            case SHIPPED -> "주문 처리가 완료되었습니다. Mock 배송이 예약되었습니다.";
            case REJECTED -> "재고가 부족하여 주문을 거절했습니다.";
            case HOLD -> "결제가 거절되어 주문을 보류했습니다. 예약 재고는 복원되었습니다.";
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
