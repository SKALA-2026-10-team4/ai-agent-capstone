package com.capstone4.orderagent.repository;

import java.util.*;
import com.capstone4.orderagent.entity.Product;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.*;
import org.springframework.data.repository.query.Param;

public interface ProductRepository extends JpaRepository<Product, Long> {
    List<Product> findByActiveTrueOrderBySkuAsc();
    List<Product> findBySkuInOrderBySkuAsc(Collection<String> skus);
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select p from Product p where p.sku in :skus order by p.sku")
    List<Product> lockBySkus(@Param("skus") Collection<String> skus);
}
