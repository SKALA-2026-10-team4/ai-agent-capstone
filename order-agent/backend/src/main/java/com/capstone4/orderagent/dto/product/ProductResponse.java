package com.capstone4.orderagent.dto.product;

public record ProductResponse(String sku, String name, long unitPrice, int stockQuantity) {}
