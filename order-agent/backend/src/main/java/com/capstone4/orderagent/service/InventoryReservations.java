package com.capstone4.orderagent.service;

import java.util.*;
import com.capstone4.orderagent.adapter.InventoryMockAdapter;
import com.capstone4.orderagent.entity.Order;
import com.capstone4.orderagent.repository.ProductRepository;
import org.springframework.stereotype.Component;

/**
 * 주문 항목 전체의 재고를 SKU 순서로 잠근 뒤 예약하거나 되돌린다. 비관적 잠금이라 호출자의 트랜잭션 안에서만 쓴다.
 * 모든 호출자가 같은 SKU 순서로 잠그므로 여러 주문이 같은 상품들을 동시에 잠가도 상품 잠금끼리 deadlock 이 생기지 않는다.
 * OrderToolService(예약, 결제 거절과 실패 보상의 해제), DryRunService(모의 실행 정리), InventoryRecovery(재시작 복구)가
 * 같은 규칙을 쓰도록 한곳에 모았다. 실제 수량 변경은
 * order-agent/backend/src/main/java/com/capstone4/orderagent/adapter/InventoryMockAdapter.java 가 한다.
 */
@Component
public class InventoryReservations {
    private final ProductRepository products;
    private final InventoryMockAdapter inventory;
    /** 상품 잠금 저장소와 재고 Mock 으로 만든다. */
    public InventoryReservations(ProductRepository products,InventoryMockAdapter inventory){this.products=products;this.inventory=inventory;}
    /** 전량 예약 여부와 잠근 직후의 최소 재고(예약 가능 수량)다. */
    public record Reservation(boolean reserved, int reservableQuantity) {}
    /**
     * 주문 항목 전체를 잠그고 잠근 직후 최소 재고를 읽은 뒤 전량 예약하거나 전량 거절한다.
     * 최소 재고는 trace 의 inventory.reservable_quantity 로 남는다.
     */
    public Reservation reserveAll(Order order){
        var quantities=quantities(order);var locked=products.lockBySkus(quantities.keySet());
        int available=locked.stream().mapToInt(p->p.getStockQuantity()).min().orElse(0);
        boolean reserved=inventory.reserve(locked,quantities);
        return new Reservation(reserved,available);
    }
    /** 주문 항목 전체의 예약 수량을 재고로 되돌리고 주문의 예약 표시를 지운다. 해제 조건(미결제 여부 등)은 호출자가 정한다. */
    public void releaseAll(Order order){
        var quantities=quantities(order);
        inventory.release(products.lockBySkus(quantities.keySet()),quantities);order.released();
    }
    /** 주문 항목을 SKU 순서의 수량 맵으로 바꾼다. */
    private static SortedMap<String,Integer> quantities(Order order){
        var q=new TreeMap<String,Integer>();for(var i:order.getItems())q.put(i.getSku(),i.getQuantity());return q;
    }
}
