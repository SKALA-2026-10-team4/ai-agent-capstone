package com.capstone4.orderagent.controller;
import java.util.*;
import com.capstone4.orderagent.dto.product.ProductResponse;
import com.capstone4.orderagent.service.ProductService;
import org.springframework.web.bind.annotation.*;
/**
 * GET /api/products 로 판매 중인 상품 목록을 SKU 순으로 {items:[...]} 에 담아 돌려준다. 항목마다 지금 재고 수량이 들어 있다.
 * order-agent/backend/src/main/java/com/capstone4/orderagent/service/ProductService.java 의 getProducts 에 맡긴다.
 * frontend/src/api/orderAgent.js 의 listProducts(frontend/src/views/OrderView.vue), order-agent/frontend/src/api/products.js 의 listProducts,
 * order-agent/scripts/smoke.py, skillops-agent/scripts/b_c_contract_smoke.py, skillops-agent/scripts/c2_real_judge.py 가 부른다.
 * smoke.py 는 이 응답의 재고로 성공한 주문만 재고를 줄였는지 보고, 뒤의 두 스크립트는 모의 실행 전후로 재고가 그대로인지 본다.
 */
@RestController
@RequestMapping("/api/products")
public class ProductController {
    private final ProductService service;
    /** Spring 이 상품 서비스를 주입해 빈으로 만든다. */
    public ProductController(ProductService service) { this.service = service; }
    /** 활성 상품 목록을 items 키 하나로 감싸 돌려준다. */
    @GetMapping Map<String, List<ProductResponse>> list() { return Map.of("items", service.getProducts()); }
}
