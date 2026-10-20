package com.capstone4.orderagent.adapter;
import java.util.*;
import com.capstone4.orderagent.entity.Product;
import org.springframework.stereotype.Component;
@Component
public class InventoryMockAdapter {
    /** The caller owns row locks and the transaction. Reserve all or none. */
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
    public void release(List<Product> products, Map<String, Integer> quantities) {
        for (var p : products) p.release(quantities.get(p.getSku()));
    }
}
