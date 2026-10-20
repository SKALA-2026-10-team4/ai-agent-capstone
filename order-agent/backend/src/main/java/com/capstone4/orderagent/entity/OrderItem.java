package com.capstone4.orderagent.entity;

import jakarta.persistence.*;

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

    protected OrderItem() {}
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
