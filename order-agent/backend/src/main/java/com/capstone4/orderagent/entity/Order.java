package com.capstone4.orderagent.entity;

import java.time.*;
import java.util.*;
import com.capstone4.orderagent.entity.enums.*;
import jakarta.persistence.*;

@Entity
@Table(name = "orders")
public class Order {
    @Id private UUID id;
    private UUID executionId;
    private UUID requestedSnapshotId;
    @Column(nullable=false) private boolean inventoryReserved;
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

    protected Order() {}
    public Order(UUID requestId, String requestHash, String customer, String method,
                 String country, boolean express, Instant now) {
        this.id = UUID.randomUUID(); this.requestId = requestId; this.requestHash = requestHash;
        this.customer = customer; this.paymentMethod = method; this.shippingCountry = country;
        this.shippingExpress = express; this.createdAt = now; this.updatedAt = now;
        this.status = OrderStatus.CREATED; this.paymentStatus = PaymentStatus.NOT_ATTEMPTED;
        this.notificationStatus = NotificationStatus.NOT_ATTEMPTED;
    }
    public void addItem(Product product, int quantity) {
        if (status != OrderStatus.CREATED) throw new IllegalStateException("Order already accepted");
        var item = new OrderItem(this, product, quantity); items.add(item);
        totalAmount = Math.addExact(totalAmount, item.getLineTotal());
    }
    public void addStep(ProcessingStepType type, ProcessingStepStatus state, String code,
                        String message, Instant started, Instant completed) {
        steps.add(new OrderProcessingStep(this, steps.size() + 1, type, state, code, message, started, completed));
        updatedAt = completed;
    }
    private void requireProcessing() {
        if (status != OrderStatus.PROCESSING) throw new IllegalStateException("Order is not processing");
    }
    public void reject() { requireProcessing(); status = OrderStatus.REJECTED; resultCode = "OUT_OF_STOCK"; }
    public void hold() {
        requireProcessing(); paymentStatus = PaymentStatus.DECLINED;
        status = OrderStatus.HOLD; resultCode = "PAYMENT_DECLINED";
    }
    public void paid(String reference) { requireProcessing(); paymentStatus = PaymentStatus.PAID; paymentReference = reference; }
    public void shipped(String reference, LocalDate eta) {
        requireProcessing();
        if (paymentStatus != PaymentStatus.PAID) throw new IllegalStateException("Payment required");
        shipmentReference = reference; estimatedDeliveryDate = eta;
    }
    public void notified() {
        requireProcessing();
        if(shipmentReference==null)throw new IllegalStateException("Shipment required");
        notificationStatus=NotificationStatus.SENT;
    }
    public void complete() {
        requireProcessing();
        if (shipmentReference == null || notificationStatus != NotificationStatus.SENT) throw new IllegalStateException("Confirmed shipment and notification required");
        status = OrderStatus.SHIPPED;
    }
    public void fail(String code, Instant now) {
        if (status != OrderStatus.CREATED && status != OrderStatus.PROCESSING) return;
        status = OrderStatus.FAILED; resultCode = code; updatedAt = now;
    }
    public void useSnapshot(UUID id) { if(status!=OrderStatus.CREATED)throw new IllegalStateException(); requestedSnapshotId=id; }
    public UUID getRequestedSnapshotId() { return requestedSnapshotId; }
    public UUID getExecutionId() { return executionId; }
    public boolean isInventoryReserved() { return inventoryReserved; }
    public void reserved() { requireProcessing(); inventoryReserved=true; }
    public void released() { inventoryReserved=false; }
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
