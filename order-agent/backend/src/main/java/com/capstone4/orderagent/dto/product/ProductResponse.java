package com.capstone4.orderagent.dto.product;

/**
 * GET /api/products 응답의 상품 한 줄이다. JSON 키는 sku, name, unit_price, stock_quantity 이고 stock_quantity 는 조회 시점의 재고다.
 * order-agent/backend/src/main/java/com/capstone4/orderagent/service/ProductService.java 의 getProducts 가 만든다.
 * order-agent/scripts/smoke.py 는 stock_quantity 로 성공한 주문만 재고를 줄였는지 확인하고, skillops-agent/scripts/b_c_contract_smoke.py 와
 * skillops-agent/scripts/c2_real_judge.py 는 모의 실행 전후로 재고가 그대로인지 확인한다.
 */
public record ProductResponse(String sku, String name, long unitPrice, int stockQuantity) {}
