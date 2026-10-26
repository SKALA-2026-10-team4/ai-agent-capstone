package com.capstone4.orderagent.entity;

import java.time.*;
import java.util.*;
import com.capstone4.orderagent.entity.enums.*;
import jakarta.persistence.*;

/**
 * 주문 집합체다. orders 테이블 한 행과 그 항목(order_items), 처리 단계 기록(order_processing_steps)을 함께 다루고 상태 전이 규칙을 메서드 안의 가드로 지킨다.
 * 주문은 CREATED 로 접수되고 PROCESSING 을 거쳐 SHIPPED(정상 완료), REJECTED(재고 부족, 엔트리 미선택, 실행 규칙 reject),
 * HOLD(결제 거절, 실행 규칙 hold), FAILED(실행 오류나 재시작 중단) 가운데 하나로 끝난다. CREATED 에서 PROCESSING 으로 가는 전이만은 엔티티가 아니라
 * order-agent/backend/src/main/java/com/capstone4/orderagent/repository/OrderRepository.java 의 claim 이 조건부 UPDATE 로 한다.
 * 업무 결과를 바꾸는 메서드는 대부분 PROCESSING 일 때만 허용하고 어기면 IllegalStateException 을 던져, 그 도구 트랜잭션 전체를 되돌리게 한다.
 * version 칼럼은 JPA 낙관적 잠금에 쓰고, claim 은 직접 1 을 올린다.
 * 상태를 바꾸는 쪽은 order-agent/backend/src/main/java/com/capstone4/orderagent/service/ 의 OrderLifecycleService(접수, 실패, 재시작 복구),
 * OrderToolService(도구 효과, 완료 확정, 보상, 미선택), DryRunService(모의 실행 표시, 정리), InventoryReservations, InventoryRecovery 이고,
 * order-agent/backend/src/main/java/com/capstone4/orderagent/dto/order/OrderResponse.java 의 from 이 응답으로 읽는다.
 * 칼럼 제약은 order-agent/backend/src/main/resources/schema.sql 에 있으며, ddl-auto validate 라 엔티티와 스키마가 다르면 기동이 실패한다.
 * 이름이 get 이나 is 로 시작하는 메서드는 칼럼 값을 그대로 돌려주는 접근자이고, 항목과 단계 목록은 바꿀 수 없는 보기로 돌려준다.
 */
@Entity
@Table(name = "orders")
public class Order {
    @Id private UUID id;
    private UUID executionId;
    private UUID requestedSnapshotId;
    @Column(nullable=false) private boolean inventoryReserved;
    @Column(nullable=false) private boolean dryRun;
    @Column(nullable = false, unique = true) private UUID requestId;
    @org.hibernate.annotations.JdbcTypeCode(org.hibernate.type.SqlTypes.CHAR)
    @Column(nullable = false, length = 64) private String requestHash;
    @Column(nullable = false, length = 64) private String customer;
    @Column(nullable = false, length = 16) private String paymentMethod;
    @Column(nullable = false, length = 2) private String shippingCountry;
    @Column(nullable = false) private boolean shippingExpress;
    @Column(nullable = false) private long totalAmount;
    @Enumerated(EnumType.STRING) @Column(nullable = false, length = 20) private OrderStatus status;
    @Column(length = 64) private String resultCode;
    @Enumerated(EnumType.STRING) @Column(nullable = false, length = 20) private PaymentStatus paymentStatus;
    @Column(length = 80) private String paymentReference;
    @Column(length = 80) private String shipmentReference;
    private LocalDate estimatedDeliveryDate;
    @Enumerated(EnumType.STRING) @Column(nullable = false, length = 20) private NotificationStatus notificationStatus;
    @Column(nullable = false) private Instant createdAt;
    @Column(nullable = false) private Instant updatedAt;
    @Version private Long version;
    @OneToMany(mappedBy = "order", cascade = CascadeType.ALL) @OrderBy("sku ASC")
    private List<OrderItem> items = new ArrayList<>();
    @OneToMany(mappedBy = "order", cascade = CascadeType.ALL) @OrderBy("sequence ASC")
    private List<OrderProcessingStep> steps = new ArrayList<>();

    /** JPA 가 행을 읽을 때 쓰는 기본 생성자다. */
    protected Order() {}
    /**
     * 새 주문을 CREATED, 결제와 알림 NOT_ATTEMPTED 상태로 만든다. 주문 id 는 여기서 무작위 UUID 로 정하고,
     * requestId 는 Idempotency-Key, requestHash 는 NormalizedOrder 의 정규화 해시다. OrderLifecycleService.accept 가 부른다.
     */
    public Order(UUID requestId, String requestHash, String customer, String method,
                 String country, boolean express, Instant now) {
        this.id = UUID.randomUUID(); this.requestId = requestId; this.requestHash = requestHash;
        this.customer = customer; this.paymentMethod = method; this.shippingCountry = country;
        this.shippingExpress = express; this.createdAt = now; this.updatedAt = now;
        this.status = OrderStatus.CREATED; this.paymentStatus = PaymentStatus.NOT_ATTEMPTED;
        this.notificationStatus = NotificationStatus.NOT_ATTEMPTED;
    }
    /**
     * 항목 한 줄을 더하고 합계를 넘침 검사(Math.addExact)와 함께 늘린다. 접수 중(CREATED)에만 허용해 처리를 시작한 뒤 금액이 바뀌지 않게 한다.
     * OrderLifecycleService.accept 가 상품마다 부른다.
     */
    public void addItem(Product product, int quantity) {
        if (status != OrderStatus.CREATED) throw new IllegalStateException("Order already accepted");
        var item = new OrderItem(this, product, quantity); items.add(item);
        totalAmount = Math.addExact(totalAmount, item.getLineTotal());
    }
    /**
     * 처리 단계 기록 한 줄을 다음 순번으로 더하고 updatedAt 을 그 단계가 끝난 시각으로 옮긴다. 상태 검사를 하지 않으므로
     * 거절, 실패, 재시작 복구, 모의 실행 정리처럼 주문이 끝난 뒤에도 기록을 더할 수 있다.
     */
    public void addStep(ProcessingStepType type, ProcessingStepStatus state, String code,
                        String message, Instant started, Instant completed) {
        steps.add(new OrderProcessingStep(this, steps.size() + 1, type, state, code, message, started, completed));
        updatedAt = completed;
    }
    /** 업무 효과 메서드의 공통 가드다. PROCESSING 이 아니면 IllegalStateException 을 던진다. */
    private void requireProcessing() {
        if (status != OrderStatus.PROCESSING) throw new IllegalStateException("Order is not processing");
    }
    /** 재고 부족으로 주문을 REJECTED(OUT_OF_STOCK)로 끝낸다. OrderToolService 의 inventory.reserve 처리가 부른다. */
    public void reject() { requireProcessing(); status = OrderStatus.REJECTED; resultCode = "OUT_OF_STOCK"; }
    /** 결제 거절로 결제 상태를 DECLINED, 주문을 HOLD(PAYMENT_DECLINED)로 끝낸다. OrderToolService 의 payment.charge 처리가 재고를 되돌린 뒤 부른다. */
    public void hold() {
        requireProcessing(); paymentStatus = PaymentStatus.DECLINED;
        status = OrderStatus.HOLD; resultCode = "PAYMENT_DECLINED";
    }
    /**
     * 단계 Skill 의 실행 규칙(rules)이 매치된 주문을 HOLD 나 REJECTED 로 끝내고 결과 코드를 규칙의 reason 으로 남긴다.
     * hold 와 달리 결제 상태는 건드리지 않으므로, 결제 전 규칙이면 NOT_ATTEMPTED 로, 결제 뒤 단계의 규칙이면 PAID 로 남는다.
     * 다른 상태를 주면 IllegalArgumentException 이다. OrderToolService.stopByRule 이 결제 전 예약 재고를 되돌린 뒤 부른다.
     */
    public void stopByRule(OrderStatus status, String reason) {
        requireProcessing();
        if (status != OrderStatus.HOLD && status != OrderStatus.REJECTED) throw new IllegalArgumentException("Rule stop must be HOLD or REJECTED");
        this.status = status; resultCode = reason;
    }
    /** Mock 결제 승인과 그 참조를 남긴다. 주문은 PROCESSING 그대로이며, PAID 는 이후 실패해도 자동으로 되돌리지 않는 확정 결과다. */
    public void paid(String reference) { requireProcessing(); paymentStatus = PaymentStatus.PAID; paymentReference = reference; }
    /** 결제가 끝난 주문에만 Mock 배송 참조와 예정일을 남긴다. 주문은 아직 PROCESSING 이고 SHIPPED 확정은 complete 가 한다. */
    public void shipped(String reference, LocalDate eta) {
        requireProcessing();
        if (paymentStatus != PaymentStatus.PAID) throw new IllegalStateException("Payment required");
        shipmentReference = reference; estimatedDeliveryDate = eta;
    }
    /** 배송 참조가 있는 주문의 알림 상태를 SENT 로 바꾼다. OrderToolService 의 notify.send 처리가 부른다. */
    public void notified() {
        requireProcessing();
        if(shipmentReference==null)throw new IllegalStateException("Shipment required");
        notificationStatus=NotificationStatus.SENT;
    }
    /**
     * 배송 참조와 알림 SENT 가 모두 있을 때만 주문을 SHIPPED 로 확정한다. 그래서 알림 단계를 뺀 flow 는 SHIPPED 가 될 수 없다.
     * OrderToolService.complete 가 실행 종료 기록과 같은 트랜잭션에서 부른다.
     */
    public void complete() {
        requireProcessing();
        if (shipmentReference == null || notificationStatus != NotificationStatus.SENT) throw new IllegalStateException("Confirmed shipment and notification required");
        status = OrderStatus.SHIPPED;
    }
    /**
     * CREATED 나 PROCESSING 인 주문만 FAILED 로 바꾸고 결과 코드를 남긴다. 이미 끝난 주문은 그대로 둬, 실패 기록이 두 번 불려도
     * 먼저 남긴 결과를 덮지 않는다. OrderLifecycleService 의 fail 과 재시작 복구가 부른다.
     */
    public void fail(String code, Instant now) {
        if (status != OrderStatus.CREATED && status != OrderStatus.PROCESSING) return;
        status = OrderStatus.FAILED; resultCode = code; updatedAt = now;
    }
    /** 접수 중인 주문에 실행할 snapshot 을 지정한다. null 이면 실행할 때 게시 번들을 새 snapshot 으로 고정한다. OrderLifecycleService.accept 가 부른다. */
    public void useSnapshot(UUID id) { if(status!=OrderStatus.CREATED)throw new IllegalStateException(); requestedSnapshotId=id; }
    public UUID getRequestedSnapshotId() { return requestedSnapshotId; }
    /** 접수 중인 주문을 모의 실행 주문으로 표시한다. DryRunService.run 이 접수와 같은 트랜잭션에서 부르고, Harness 는 이 표시로 run mode 를 dry_run 으로 남긴다. */
    public void markDryRun() { if(status!=OrderStatus.CREATED)throw new IllegalStateException(); dryRun=true; }
    public boolean isDryRun() { return dryRun; }
    public UUID getExecutionId() { return executionId; }
    public boolean isInventoryReserved() { return inventoryReserved; }
    /** 재고를 예약했다고 표시한다. 실패 보상, 재시작 복구, 모의 실행 정리가 이 표시를 보고 재고를 되돌린다. */
    public void reserved() { requireProcessing(); inventoryReserved=true; }
    /** 재고 예약 표시를 지운다. 상태 검사가 없어 주문이 끝난 뒤의 보상과 정리에서도 부를 수 있다. InventoryReservations.releaseAll 이 부른다. */
    public void released() { inventoryReserved=false; }
    /** 모델이 엔트리 Skill 을 고르지 않은 주문을 REJECTED(NO_SELECTION)로 끝낸다. OrderToolService.noSelection 이 부른다. */
    public void noSelection() { requireProcessing(); status=OrderStatus.REJECTED; resultCode="NO_SELECTION"; }
    public UUID getId() { return id; }
    public UUID getRequestId() { return requestId; }
    public String getRequestHash() { return requestHash; }
    public String getCustomer() { return customer; }
    public String getPaymentMethod() { return paymentMethod; }
    public String getShippingCountry() { return shippingCountry; }
    public boolean isShippingExpress() { return shippingExpress; }
    public long getTotalAmount() { return totalAmount; }
    public OrderStatus getStatus() { return status; }
    public String getResultCode() { return resultCode; }
    public PaymentStatus getPaymentStatus() { return paymentStatus; }
    public String getPaymentReference() { return paymentReference; }
    public String getShipmentReference() { return shipmentReference; }
    public LocalDate getEstimatedDeliveryDate() { return estimatedDeliveryDate; }
    public NotificationStatus getNotificationStatus() { return notificationStatus; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
    public List<OrderItem> getItems() { return Collections.unmodifiableList(items); }
    public List<OrderProcessingStep> getSteps() { return Collections.unmodifiableList(steps); }
}
