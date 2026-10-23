package com.capstone4.orderagent.entity;

import jakarta.persistence.*;

/**
 * 주문 항목 한 줄(order_items)이다. 접수할 때 상품의 SKU, 이름, 단가를 복사해 두므로 나중에 상품 정보가 바뀌어도 주문 금액과 응답은 바뀌지 않는다.
 * lineTotal 은 단가와 수량의 곱이며 넘침 검사(Math.multiplyExact)를 한다. 수량 범위(1~99)와 주문 하나에 같은 상품이 두 줄 들어가지 않는 규칙은
 * order-agent/backend/src/main/resources/schema.sql 의 CHECK 와 UNIQUE 가 막는다.
 * order-agent/backend/src/main/java/com/capstone4/orderagent/entity/Order.java 의 addItem 만 만든다. 읽는 쪽은
 * order-agent/backend/src/main/java/com/capstone4/orderagent/service/ 의 InventoryReservations(예약 수량), OrderToolService(도구 컨텍스트의 order.items)와
 * order-agent/backend/src/main/java/com/capstone4/orderagent/dto/order/OrderResponse.java 의 from 이다. 접근자는 칼럼 값을 그대로 돌려준다.
 */
@Entity
@Table(name = "order_items")
public class OrderItem {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) private Long id;
    @ManyToOne(fetch = FetchType.LAZY, optional = false) @JoinColumn(name = "order_id") private Order order;
    @ManyToOne(fetch = FetchType.LAZY, optional = false) @JoinColumn(name = "product_id") private Product product;
    @Column(nullable = false, length = 64) private String sku;
    @Column(nullable = false, length = 120) private String productName;
    @Column(nullable = false) private long unitPrice;
    @Column(nullable = false) private int quantity;
    @Column(nullable = false) private long lineTotal;

    /** JPA 가 행을 읽을 때 쓰는 기본 생성자다. */
    protected OrderItem() {}
    /** 주문과 상품, 수량으로 항목을 만들고 그 시점의 상품 정보를 복사한다. Order.addItem 이 부른다. */
    public OrderItem(Order order, Product product, int quantity) {
        this.order = order; this.product = product;
        this.sku = product.getSku(); this.productName = product.getName();
        this.unitPrice = product.getUnitPrice(); this.quantity = quantity;
        this.lineTotal = Math.multiplyExact(unitPrice, quantity);
    }
    public String getSku() { return sku; }
    public String getProductName() { return productName; }
    public int getQuantity() { return quantity; }
    public long getUnitPrice() { return unitPrice; }
    public long getLineTotal() { return lineTotal; }
}
