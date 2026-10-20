package com.capstone4.orderagent.controller;
import java.util.*;
import com.capstone4.orderagent.dto.product.ProductResponse;
import com.capstone4.orderagent.service.ProductService;
import org.springframework.web.bind.annotation.*;
@RestController
@RequestMapping("/api/products")
public class ProductController {
    private final ProductService service;
    public ProductController(ProductService service) { this.service = service; }
    @GetMapping Map<String, List<ProductResponse>> list() { return Map.of("items", service.getProducts()); }
}
