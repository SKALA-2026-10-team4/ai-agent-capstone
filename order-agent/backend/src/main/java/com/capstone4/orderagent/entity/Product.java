package com.capstone4.orderagent.entity;

import jakarta.persistence.*;

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

    protected Product() {}
    public Long getId() { return id; }
    public String getSku() { return sku; }
    public String getName() { return name; }
    public long getUnitPrice() { return unitPrice; }
    public int getStockQuantity() { return stockQuantity; }
    public boolean isActive() { return active; }
    public void reserve(int quantity) {
        if (quantity < 1 || quantity > stockQuantity) throw new IllegalStateException("Invalid reservation");
        stockQuantity -= quantity;
    }
    public void release(int quantity) {
        if (quantity < 1) throw new IllegalArgumentException("Invalid release");
        stockQuantity = Math.addExact(stockQuantity, quantity);
    }
}
