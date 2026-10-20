package com.capstone4.orderagent.service;

import java.io.*;
import java.security.*;
import java.util.*;
import com.capstone4.orderagent.dto.order.CreateOrderRequest;
import com.capstone4.orderagent.exception.*;

record NormalizedOrder(String customer, SortedMap<String, Integer> items,
                       String method, String country, boolean express, String hash, UUID snapshotId) {
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
