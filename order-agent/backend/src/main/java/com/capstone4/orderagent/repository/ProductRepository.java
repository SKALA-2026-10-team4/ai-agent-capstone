package com.capstone4.orderagent.repository;

import java.util.*;
import com.capstone4.orderagent.entity.Product;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.*;
import org.springframework.data.repository.query.Param;

/**
 * 상품(products) JPA 저장소다. 판매 중인 상품 목록, SKU 집합 조회, 재고 변경 전의 SKU 순 비관적 잠금을 제공한다.
 * order-agent/backend/src/main/java/com/capstone4/orderagent/service/ 의 ProductService, OrderLifecycleService, InventoryReservations 가 쓴다.
 */
public interface ProductRepository extends JpaRepository<Product, Long> {
    /** 판매 중인 상품을 SKU 순으로 읽는다. ProductService.getProducts(GET /api/products)가 쓴다. */
    List<Product> findByActiveTrueOrderBySkuAsc();
    /** SKU 집합의 상품을 잠금 없이 읽는다. 접수(OrderLifecycleService.accept)가 상품의 존재와 활성 여부, 단가를 확인할 때 쓴다. */
    List<Product> findBySkuInOrderBySkuAsc(Collection<String> skus);
    /**
     * SKU 집합의 상품 행을 SKU 순으로 비관적 쓰기 잠금해 읽는다. 여러 주문이 같은 상품들을 잡아도 늘 같은 순서로 잠가 교착을 피한다.
     * InventoryReservations 의 reserveAll 과 releaseAll 이 재고를 바꾸기 직전에 부른다.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select p from Product p where p.sku in :skus order by p.sku")
    List<Product> lockBySkus(@Param("skus") Collection<String> skus);
}
