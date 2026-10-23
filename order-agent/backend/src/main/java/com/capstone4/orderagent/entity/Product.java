package com.capstone4.orderagent.entity;

import jakarta.persistence.*;

/**
 * 상품과 재고(products)다. 상품 행은 order-agent/backend/src/main/resources/data.sql 이 시드로 넣고, 애플리케이션 코드는 상품을 만들거나 지우지 않는다.
 * 재고 수량은 reserve 와 release 로만 바뀌며, 둘 다 order-agent/backend/src/main/java/com/capstone4/orderagent/adapter/InventoryMockAdapter.java 가
 * 호출자의 비관적 잠금(ProductRepository.lockBySkus)으로 잡은 행에서 부른다. 음수 재고는 reserve 의 검사와
 * order-agent/backend/src/main/resources/schema.sql 의 CHECK 가 함께 막는다.
 * active 가 false 인 상품은 상품 목록에 나오지 않고 접수 때 PRODUCT_NOT_FOUND 가 된다. 접근자는 칼럼 값을 그대로 돌려준다.
 */
@Entity
@Table(name = "products")
public class Product {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @Column(nullable = false, unique = true, length = 64) private String sku;
    @Column(nullable = false, length = 120) private String name;
    @Column(nullable = false) private long unitPrice;
    @Column(nullable = false) private int stockQuantity;
    @Column(nullable = false) private boolean active;

    /** JPA 가 행을 읽을 때 쓰는 기본 생성자다. */
    protected Product() {}
    public Long getId() { return id; }
    public String getSku() { return sku; }
    public String getName() { return name; }
    public long getUnitPrice() { return unitPrice; }
    public int getStockQuantity() { return stockQuantity; }
    public boolean isActive() { return active; }
    /**
     * 수량만큼 재고를 줄인다. 1 보다 작거나 남은 재고보다 많으면 IllegalStateException 이다. 전량 예약이 가능한지는
     * InventoryMockAdapter.reserve 가 모든 상품을 먼저 확인하므로, 이 검사는 그 뒤에 한 번 더 막는 안전장치다.
     */
    public void reserve(int quantity) {
        if (quantity < 1 || quantity > stockQuantity) throw new IllegalStateException("Invalid reservation");
        stockQuantity -= quantity;
    }
    /** 예약했던 수량을 재고로 되돌린다. 1 보다 작으면 IllegalArgumentException 이고, 더한 값이 int 를 넘으면 ArithmeticException 이다. */
    public void release(int quantity) {
        if (quantity < 1) throw new IllegalArgumentException("Invalid release");
        stockQuantity = Math.addExact(stockQuantity, quantity);
    }
}
