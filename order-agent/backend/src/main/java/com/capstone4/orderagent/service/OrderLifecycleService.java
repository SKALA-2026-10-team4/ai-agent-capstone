package com.capstone4.orderagent.service;

import java.time.Clock;
import java.util.*;
import com.capstone4.orderagent.dto.order.OrderResponse;
import com.capstone4.orderagent.entity.Order;
import com.capstone4.orderagent.entity.enums.*;
import com.capstone4.orderagent.exception.*;
import com.capstone4.orderagent.repository.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class OrderLifecycleService {
    private final OrderRepository orders;
    private final ProductRepository products;
    private final Clock clock;
    private final com.capstone4.orderagent.trace.ExecutionRepository evidence;
    private final InventoryRecovery inventoryRecovery;
    public OrderLifecycleService(OrderRepository orders, ProductRepository products, Clock clock, com.capstone4.orderagent.trace.ExecutionRepository evidence, InventoryRecovery inventoryRecovery) {
        this.orders = orders; this.products = products; this.clock = clock; this.evidence=evidence; this.inventoryRecovery=inventoryRecovery;
    }
    public record Accepted(UUID orderId, boolean created) {}

    @Transactional(readOnly = true)
    public Optional<Accepted> existing(UUID key, String hash) {
        return orders.findByRequestId(key).map(o -> {
            if (!o.getRequestHash().equals(hash)) throw new BusinessException(ErrorCode.IDEMPOTENCY_CONFLICT, o.getId());
            return new Accepted(o.getId(), false);
        });
    }
    @Transactional(timeout = 10)
    public Accepted accept(UUID key, NormalizedOrder input) {
        var existing = existing(key, input.hash());
        if (existing.isPresent()) return existing.get();
        var catalog = products.findBySkuInOrderBySkuAsc(input.items().keySet());
        if (catalog.size() != input.items().size() || catalog.stream().anyMatch(p -> !p.isActive()))
            throw new BusinessException(ErrorCode.PRODUCT_NOT_FOUND);
        var o = new Order(key, input.hash(), input.customer(), input.method(), input.country(), input.express(), clock.instant());
        o.useSnapshot(input.snapshotId());
        for (var p : catalog) o.addItem(p, input.items().get(p.getSku()));
        var now = clock.instant();
        o.addStep(ProcessingStepType.INTAKE, ProcessingStepStatus.SUCCESS, null, "주문 접수 완료", now, now);
        orders.saveAndFlush(o);
        return new Accepted(o.getId(), true);
    }
    @Transactional(timeout = 10)
    public boolean claim(UUID id) { return orders.claim(id, clock.instant()) == 1; }

    @Transactional(readOnly = true)
    public OrderResponse get(UUID id) { return OrderResponse.from(find(id)); }
    @Transactional(readOnly = true)
    public OrderResponse byRequest(UUID key) {
        return OrderResponse.from(orders.findByRequestId(key).orElseThrow(() -> new BusinessException(ErrorCode.ORDER_NOT_FOUND)));
    }
    @Transactional(timeout = 10)
    public void fail(UUID id, ProcessingStepType failedStep, String code) {
        var o = find(id);
        if (o.getStatus() != OrderStatus.CREATED && o.getStatus() != OrderStatus.PROCESSING) return;
        var now = clock.instant();
        o.fail(code, now);
        o.addStep(failedStep, ProcessingStepStatus.FAILED, code, "실행이 중단되었습니다. 이전 단계의 확정 결과는 보존됩니다.", now, now);
        var normal = List.of(ProcessingStepType.INVENTORY, ProcessingStepType.PAYMENT,
            ProcessingStepType.SHIPPING, ProcessingStepType.NOTIFICATION);
        int index = normal.indexOf(failedStep);
        for (var step : normal.subList(Math.max(0, index + 1), normal.size()))
            o.addStep(step, ProcessingStepStatus.SKIPPED, "UPSTREAM_FAILED", "앞 단계 오류로 실행하지 않았습니다.", now, now);
    }
    @Transactional(timeout = 10)
    public void recoverInterrupted() {
        for (var o : orders.findByStatusIn(List.of(OrderStatus.CREATED, OrderStatus.PROCESSING))) {
            var now = clock.instant();
            inventoryRecovery.releaseUnpaid(o);
            for(var execution:evidence.runningForOrder(o.getId())){
                evidence.append(execution,"EXECUTION_INTERRUPTED",null,Map.of("category","PROCESS_INTERRUPTED"),now);
                long elapsed=java.time.Duration.between(java.time.Instant.parse((String)evidence.get(execution).get("started_at")),now).toMillis();
                evidence.finish(execution,"INTERRUPTED","FAILED","PROCESS_INTERRUPTED",Math.max(0,elapsed),now);
            }
            o.fail("PROCESS_INTERRUPTED", now);
            // Earlier tool transactions remain committed; never invent or erase their results.
            o.addStep(ProcessingStepType.RECOVERY, ProcessingStepStatus.FAILED, "PROCESS_INTERRUPTED",
                "이전 실행이 중단되었습니다. 확정된 결과는 보존하고 미결제 예약은 해제했습니다.", now, now);
        }
    }
    private Order find(UUID id) {
        return orders.findById(id).orElseThrow(() -> new BusinessException(ErrorCode.ORDER_NOT_FOUND));
    }
}
