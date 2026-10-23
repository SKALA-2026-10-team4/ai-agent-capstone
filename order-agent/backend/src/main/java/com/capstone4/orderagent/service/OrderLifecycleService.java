package com.capstone4.orderagent.service;

import java.time.*;
import java.util.*;
import com.capstone4.orderagent.dto.order.OrderResponse;
import com.capstone4.orderagent.entity.Order;
import com.capstone4.orderagent.entity.enums.*;
import com.capstone4.orderagent.exception.*;
import com.capstone4.orderagent.repository.*;
import com.capstone4.orderagent.trace.ExecutionRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 주문의 수명 주기(접수, 처리 소유권, 조회, 실패 기록, 재시작 복구)를 각각 짧은 독립 트랜잭션으로 수행한다.
 * OrderService.createOrder 와 DryRunService.run 은 처리 전체를 감싸는 부모 트랜잭션 없이 이 메서드들을 차례로 불러,
 * 접수가 처리 결과와 무관하게 먼저 커밋되게 한다. DryRunService.run 은 accept 만 dry_run 표시와 같은 짧은 트랜잭션 안에서 부른다.
 * order-agent/backend/src/main/java/com/capstone4/orderagent/harness/OrderHarness.java 의 recordFailure 와
 * OrderProcessingService.processClaimed 가 fail 을 부르고,
 * order-agent/backend/src/main/java/com/capstone4/orderagent/config/InterruptedOrderRecovery.java 가 기동할 때 recoverInterrupted 를 부른다.
 * 실패 뒤 건너뛸 단계는 order-agent/backend/src/main/java/com/capstone4/orderagent/entity/enums/ProcessingStepType.java 의
 * BUSINESS_FLOW 순서를 따른다.
 */
@Service
public class OrderLifecycleService {
    private final OrderRepository orders;
    private final ProductRepository products;
    private final Clock clock;
    private final ExecutionRepository evidence;
    private final InventoryRecovery inventoryRecovery;
    /** 주문과 상품 저장소, UTC Clock, 실행 기록 저장소, 재고 복구 도구를 주입받는다. */
    public OrderLifecycleService(OrderRepository orders, ProductRepository products, Clock clock, ExecutionRepository evidence, InventoryRecovery inventoryRecovery) {
        this.orders = orders; this.products = products; this.clock = clock; this.evidence=evidence; this.inventoryRecovery=inventoryRecovery;
    }
    /** 접수 결과다. created 가 false 면 같은 요청 키로 이미 접수된 주문을 돌려준 것이다. */
    public record Accepted(UUID orderId, boolean created) {}

    /**
     * 같은 요청 키(Idempotency-Key)로 접수된 주문을 찾는다. 요청 해시가 다르면 같은 키를 다른 주문 입력에 쓴 것이라
     * IDEMPOTENCY_CONFLICT(409)를 던진다.
     */
    @Transactional(readOnly = true)
    public Optional<Accepted> existing(UUID key, String hash) {
        return orders.findByRequestId(key).map(o -> {
            if (!o.getRequestHash().equals(hash)) throw new BusinessException(ErrorCode.IDEMPOTENCY_CONFLICT, o.getId());
            return new Accepted(o.getId(), false);
        });
    }
    /**
     * 주문을 접수해 Order 와 OrderItem 엔티티, INTAKE 성공 단계를 저장한다. 엔티티는
     * order-agent/backend/src/main/java/com/capstone4/orderagent/entity 에 있다. 같은 키의 주문이 이미 있으면 그 주문을 돌려준다.
     * 상품이 하나라도 없거나 비활성이면 PRODUCT_NOT_FOUND 다. 요청에 snapshot_id 가 있으면 주문에 기록해 Harness 가 그 snapshot 으로
     * 실행한다. 같은 키로 동시에 들어와 UNIQUE 제약에 걸리면 saveAndFlush 가 예외를 내고, OrderService.createOrder 가 existing 으로
     * 다시 읽는다.
     */
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
    /**
     * CREATED 주문을 PROCESSING 으로 조건부 갱신해 처리 소유권을 얻는다. 갱신한 행이 하나일 때만 true 이므로
     * 한 주문을 두 요청이 함께 처리하지 않는다.
     */
    @Transactional(timeout = 10)
    public boolean claim(UUID id) { return orders.claim(id, clock.instant()) == 1; }

    /** 주문 id 로 주문 응답을 만든다. 없으면 ORDER_NOT_FOUND 다. */
    @Transactional(readOnly = true)
    public OrderResponse get(UUID id) { return OrderResponse.from(find(id)); }
    /**
     * 요청 키로 주문 응답을 만든다. 응답을 잃은 클라이언트가 같은 키로 결과를 다시 찾을 때 쓰며,
     * 없으면 ORDER_NOT_FOUND 다.
     */
    @Transactional(readOnly = true)
    public OrderResponse byRequest(UUID key) {
        return OrderResponse.from(orders.findByRequestId(key).orElseThrow(() -> new BusinessException(ErrorCode.ORDER_NOT_FOUND)));
    }
    /**
     * 주문을 FAILED 로 바꾸고 실패 단계를 FAILED 로, 업무 순서에서 그 뒤에 오는 단계를 SKIPPED(UPSTREAM_FAILED)로 남긴다.
     * 실패 단계가 업무 단계가 아니면(INTAKE, RELEASE, RECOVERY) 업무 단계 전체를 SKIPPED 로 남긴다.
     * 주문이 CREATED 나 PROCESSING 상태가 아니면 아무것도 하지 않으므로, Harness 가 먼저 기록한 실패를
     * OrderProcessingService.processClaimed 가 다시 불러도 덮지 않는다. 이미 커밋된 앞 단계 결과는 지우지 않는다.
     */
    @Transactional(timeout = 10)
    public void fail(UUID id, ProcessingStepType failedStep, String code) {
        var o = find(id);
        if (o.getStatus() != OrderStatus.CREATED && o.getStatus() != OrderStatus.PROCESSING) return;
        var now = clock.instant();
        o.fail(code, now);
        o.addStep(failedStep, ProcessingStepStatus.FAILED, code, "실행이 중단되었습니다. 이전 단계의 확정 결과는 보존됩니다.", now, now);
        for (var step : ProcessingStepType.after(failedStep))
            o.addStep(step, ProcessingStepStatus.SKIPPED, "UPSTREAM_FAILED", "앞 단계 오류로 실행하지 않았습니다.", now, now);
    }
    /**
     * 기동할 때 CREATED 나 PROCESSING 으로 남은 주문을 모두 정리한다. 이전 프로세스가 처리 도중 멈췄다는 뜻이므로 주문을 다시 실행하지 않고
     * 실패로 닫는다. 한 트랜잭션 안에서 주문마다 recover 를 부른다. InterruptedOrderRecovery 가 트래픽을 받기 전에 부른다.
     */
    @Transactional(timeout = 10)
    public void recoverInterrupted() {
        for (var o : orders.findByStatusIn(List.of(OrderStatus.CREATED, OrderStatus.PROCESSING))) recover(o);
    }
    /**
     * 중단된 주문 하나를 세 단계로 정리한다. 먼저 InventoryRecovery 가 결제 전 예약 재고를 되돌리고, 이어서 이 주문의 RUNNING 실행마다
     * 중단을 기록하고, 마지막으로 주문을 FAILED(PROCESS_INTERRUPTED)로 바꾼 뒤 RECOVERY 실패 단계를 남긴다.
     */
    private void recover(Order o) {
        var now = clock.instant();
        inventoryRecovery.releaseUnpaid(o);
        interruptRunningExecutions(o.getId(), now);
        o.fail("PROCESS_INTERRUPTED", now);
        // 앞서 커밋된 도구 트랜잭션의 결과는 그대로 둔다. 없던 결과를 만들거나 있던 결과를 지우지 않는다.
        o.addStep(ProcessingStepType.RECOVERY, ProcessingStepStatus.FAILED, "PROCESS_INTERRUPTED",
            "이전 실행이 중단되었습니다. 확정된 결과는 보존하고 미결제 예약은 해제했습니다.", now, now);
    }
    /**
     * 주문의 RUNNING 실행마다 EXECUTION_INTERRUPTED 를 남기고, 시작 시각부터 지난 시간으로 실행을 INTERRUPTED 로 닫는다.
     * 실패 목록 조회는 completion 이 FAILED 나 INTERRUPTED 인 live 실행을 고르므로 중단된 실행도 진단 대상이 된다.
     * EXECUTION_INTERRUPTED 이름은 frontend/src/utils/eventView.js 와 order-agent/frontend/src/utils/evidenceFormat.js 가 읽는
     * trace 계약이다.
     */
    private void interruptRunningExecutions(UUID orderId, Instant now) {
        for(var execution:evidence.runningForOrder(orderId)){
            evidence.append(execution,"EXECUTION_INTERRUPTED",null,Map.of("category","PROCESS_INTERRUPTED"),now);
            long elapsed=Duration.between(evidence.startedAt(execution),now).toMillis();
            evidence.finish(execution,"INTERRUPTED","FAILED","PROCESS_INTERRUPTED",Math.max(0,elapsed),now);
        }
    }
    /** 주문을 id 로 읽는다. 없으면 ORDER_NOT_FOUND 다. */
    private Order find(UUID id) {
        return orders.findById(id).orElseThrow(() -> new BusinessException(ErrorCode.ORDER_NOT_FOUND));
    }
}
