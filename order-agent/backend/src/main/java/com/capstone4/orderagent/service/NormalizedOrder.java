package com.capstone4.orderagent.service;

import java.io.*;
import java.security.*;
import java.util.*;
import com.capstone4.orderagent.dto.order.CreateOrderRequest;
import com.capstone4.orderagent.exception.*;

/**
 * 주문 요청을 정규화한 값이다. 같은 SKU 줄을 합치고, 고객 id 앞뒤 공백을 지우고, 고정된 바이트 순서로 SHA-256 요청 해시를 만든다.
 * 해시는 같은 Idempotency-Key 로 다른 내용을 보냈는지 OrderLifecycleService.existing 이 가려낼 때 쓴다.
 * items 는 SKU 순서의 바꿀 수 없는 맵이다. OrderService.createOrder 와 DryRunService.run 이 만들고 OrderLifecycleService.accept 가 받는다.
 */
record NormalizedOrder(String customer, SortedMap<String, Integer> items,
                       String method, String country, boolean express, String hash, UUID snapshotId) {
    /**
     * 요청을 정규화하고 요청 해시를 계산한다. 같은 SKU 수량 합계가 99 를 넘으면 VALIDATION_ERROR 이며, 이 한도는
     * order-agent/backend/src/main/resources/schema.sql 의 order_items.quantity CHECK 범위와 같다.
     * 해시 입력 순서는 고객, 결제 방식, 배송 국가, 빠른 배송, snapshot_id(있을 때만), SKU 순 항목이다.
     * 순서나 항목을 바꾸면 기존 주문의 해시와 달라져 같은 키 재요청이 IDEMPOTENCY_CONFLICT 가 된다.
     */
    static NormalizedOrder from(CreateOrderRequest r) {
        SortedMap<String, Integer> items = new TreeMap<>();
        for (var item : r.items()) {
            int total = items.merge(item.sku(), item.qty(), Integer::sum);
            if (total > 99) throw new BusinessException(ErrorCode.VALIDATION_ERROR);
        }
        String customer = r.customer().strip();
        try {
            var bytes = new ByteArrayOutputStream();
            try (var out = new DataOutputStream(bytes)) {
                out.writeUTF(customer); out.writeUTF(r.paymentMethod());
                out.writeUTF(r.shipping().country()); out.writeBoolean(r.shipping().express());
                if(r.snapshotId()!=null)out.writeUTF(r.snapshotId().toString());
                for (var item : items.entrySet()) { out.writeUTF(item.getKey()); out.writeInt(item.getValue()); }
            }
            String hash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes.toByteArray()));
            return new NormalizedOrder(customer, Collections.unmodifiableSortedMap(items), r.paymentMethod(),
                r.shipping().country(), r.shipping().express(), hash, r.snapshotId());
        } catch (IOException | NoSuchAlgorithmException e) { throw new IllegalStateException(e); }
    }
}
