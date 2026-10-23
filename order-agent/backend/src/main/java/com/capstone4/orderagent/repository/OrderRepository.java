package com.capstone4.orderagent.repository;

import java.time.Instant;
import java.util.*;
import com.capstone4.orderagent.entity.Order;
import com.capstone4.orderagent.entity.enums.OrderStatus;
import org.springframework.data.jpa.repository.*;
import org.springframework.data.repository.query.Param;

/**
 * 주문(orders) JPA 저장소다. 주문 행 잠금, 요청 키 조회, 상태별 조회, 처리 소유권 획득(claim)을 제공한다.
 * order-agent/backend/src/main/java/com/capstone4/orderagent/service/ 의 OrderLifecycleService, OrderToolService, DryRunService 가 쓴다.
 * 잠금을 기다리는 시간은 order-agent/backend/src/main/resources/application.yml 의 hikari connection-init-sql(lock_timeout 3s)로 제한되고,
 * 넘기면 DB 예외가 된다.
 */
public interface OrderRepository extends JpaRepository<Order, UUID> {
    /**
     * 주문 행을 비관적 쓰기 잠금(select for update)으로 읽는다. 같은 주문의 도구 실행, 완료 확정, 실패 보상, 미선택 처리, 모의 실행 정리가
     * 서로 겹치지 않게 한다. OrderToolService 와 DryRunService 의 cleanup 이 쓴다.
     */
    @Lock(jakarta.persistence.LockModeType.PESSIMISTIC_WRITE)
    @Query("select o from Order o where o.id=:id")
    Optional<Order> lockById(@Param("id") UUID id);
    /** Idempotency-Key 로 주문을 찾는다. request_id 는 UNIQUE 라 많아야 하나다. OrderLifecycleService 의 existing 과 byRequest 가 쓴다. */
    Optional<Order> findByRequestId(UUID requestId);
    /** 주어진 상태의 주문을 모두 읽는다. 재시작 복구(OrderLifecycleService.recoverInterrupted)가 CREATED 와 PROCESSING 주문을 찾을 때 쓴다. */
    List<Order> findByStatusIn(Collection<OrderStatus> statuses);
    /**
     * CREATED 주문만 PROCESSING 으로 바꾸고 바뀐 행 수를 돌려준다. 조건부 UPDATE 라 같은 주문을 두 요청이 함께 claim 해도 한쪽만 1 을 받아,
     * 한 주문을 두 번 처리하지 않는다. version 을 직접 올려 낙관적 잠금과 맞추고, 실행 전 flush 와 실행 뒤 clear 로
     * 영속성 컨텍스트에 남은 옛 상태를 읽지 않게 한다. OrderLifecycleService.claim 이 부른다.
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("update Order o set o.status = com.capstone4.orderagent.entity.enums.OrderStatus.PROCESSING, "
        + "o.updatedAt = :now, o.version = o.version + 1 where o.id = :id "
        + "and o.status = com.capstone4.orderagent.entity.enums.OrderStatus.CREATED")
    int claim(@Param("id") UUID id, @Param("now") Instant now);
}
