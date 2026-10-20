package com.capstone4.orderagent.service;

import java.util.List;
import com.capstone4.orderagent.dto.product.ProductResponse;
import com.capstone4.orderagent.repository.ProductRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ProductService {
    private final ProductRepository products;
    public ProductService(ProductRepository products) { this.products = products; }
    @Transactional(readOnly = true)
    public List<ProductResponse> getProducts() {
        return products.findByActiveTrueOrderBySkuAsc().stream().map(p ->
            new ProductResponse(p.getSku(), p.getName(), p.getUnitPrice(), p.getStockQuantity())).toList();
    }
}
