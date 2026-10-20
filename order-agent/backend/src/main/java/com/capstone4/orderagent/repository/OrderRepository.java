package com.capstone4.orderagent.repository;

import java.time.Instant;
import java.util.*;
import com.capstone4.orderagent.entity.Order;
import com.capstone4.orderagent.entity.enums.OrderStatus;
import org.springframework.data.jpa.repository.*;
import org.springframework.data.repository.query.Param;

public interface OrderRepository extends JpaRepository<Order, UUID> {
    @Lock(jakarta.persistence.LockModeType.PESSIMISTIC_WRITE)
    @Query("select o from Order o where o.id=:id")
    Optional<Order> lockById(@Param("id") UUID id);
    Optional<Order> findByRequestId(UUID requestId);
    List<Order> findByStatusIn(Collection<OrderStatus> statuses);
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("update Order o set o.status = com.capstone4.orderagent.entity.enums.OrderStatus.PROCESSING, "
        + "o.updatedAt = :now, o.version = o.version + 1 where o.id = :id "
        + "and o.status = com.capstone4.orderagent.entity.enums.OrderStatus.CREATED")
    int claim(@Param("id") UUID id, @Param("now") Instant now);
}
