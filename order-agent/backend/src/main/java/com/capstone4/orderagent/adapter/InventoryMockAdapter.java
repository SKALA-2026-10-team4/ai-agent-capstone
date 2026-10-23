package com.capstone4.orderagent.adapter;
import java.util.*;
import com.capstone4.orderagent.entity.Product;
import org.springframework.stereotype.Component;
/**
 * 재고 Mock 이다. 외부 재고 시스템 대신 products 테이블의 재고 수량을 바꾼다. 호출자가 잠근 Product 목록에 전량 예약하거나 전량 거절하고,
 * 해제할 때 수량을 되돌린다. 수량 변경과 음수 재고 방지는 order-agent/backend/src/main/java/com/capstone4/orderagent/entity/Product.java 의
 * reserve, release 가 맡는다. order-agent/backend/src/main/java/com/capstone4/orderagent/service/InventoryReservations.java 가 유일한 호출자다.
 */
@Component
public class InventoryMockAdapter {
    /**
     * 행 잠금과 트랜잭션은 호출자가 가진다. 모두 예약하거나 하나도 예약하지 않는다. 비활성 상품이나 재고 부족이 하나라도 있으면
     * 아무것도 바꾸지 않고 false 를 돌려준다. 상품 수와 수량 맵 크기가 다르면 IllegalStateException, 수량이 없거나 1 보다 작으면
     * IllegalArgumentException 이다.
     */
    public boolean reserve(List<Product> products, Map<String, Integer> quantities) {
        if (products.size() != quantities.size()) throw new IllegalStateException("Missing product");
        for (var p : products) {
            var qty = quantities.get(p.getSku());
            if (qty == null || qty < 1) throw new IllegalArgumentException("Invalid quantity");
            if (!p.isActive() || p.getStockQuantity() < qty) return false;
        }
        for (var p : products) p.reserve(quantities.get(p.getSku()));
        return true;
    }
    /** 예약한 수량을 재고로 되돌린다. 호출자가 잠근 상품만 받는다. */
    public void release(List<Product> products, Map<String, Integer> quantities) {
        for (var p : products) p.release(quantities.get(p.getSku()));
    }
}
