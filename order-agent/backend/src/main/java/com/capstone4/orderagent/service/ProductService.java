package com.capstone4.orderagent.service;

import java.util.List;
import com.capstone4.orderagent.dto.product.ProductResponse;
import com.capstone4.orderagent.repository.ProductRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 판매 중인 상품 목록을 응답 DTO 로 돌려준다.
 * order-agent/backend/src/main/java/com/capstone4/orderagent/controller/ProductController.java 가 GET /api/products 를 이 클래스로 보내고,
 * 프론트엔드에서는 frontend/src/api/orderAgent.js 와 order-agent/frontend/src/api/products.js 의 listProducts 가 이 경로를 부른다.
 */
@Service
public class ProductService {
    private final ProductRepository products;
    /** 상품 저장소를 주입받는다. */
    public ProductService(ProductRepository products) { this.products = products; }
    /** 활성 상품만 SKU 순서로 읽어 sku, 이름, 단가, 재고 수량을 담은 DTO 로 바꾼다. */
    @Transactional(readOnly = true)
    public List<ProductResponse> getProducts() {
        return products.findByActiveTrueOrderBySkuAsc().stream().map(p ->
            new ProductResponse(p.getSku(), p.getName(), p.getUnitPrice(), p.getStockQuantity())).toList();
    }
}
