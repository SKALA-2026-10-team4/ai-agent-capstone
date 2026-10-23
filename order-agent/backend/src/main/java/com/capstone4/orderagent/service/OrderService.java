package com.capstone4.orderagent.service;
import java.util.UUID;
import com.capstone4.orderagent.dto.order.*;
import com.capstone4.orderagent.exception.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.*;
import org.springframework.stereotype.Service;
/**
 * 주문 생성 API 를 조율한다. 요청 정규화, 멱등 키 확인, snapshot 사전 확인, 접수, 처리 소유권 획득, Harness 실행, 실패 분류를 차례로 하고
 * 조회는 OrderLifecycleService 에 맡긴다.
 * order-agent/backend/src/main/java/com/capstone4/orderagent/controller/OrderController.java 가 POST /api/orders,
 * GET /api/orders/{id}, GET /api/orders/by-request/{key} 를 이 클래스로 보낸다.
 * 프론트엔드에서는 frontend/src/api/orderAgent.js 와 order-agent/frontend/src/api/orders.js 가 이 경로를 부른다.
 */
@Service
public class OrderService {
    private static final Logger log = LoggerFactory.getLogger(OrderService.class);
    private final OrderLifecycleService lifecycle;
    private final OrderProcessingService processor;
    private final com.capstone4.orderagent.workspace.SnapshotService snapshots;
    /** 수명 주기 서비스, 처리 진입점, snapshot 서비스를 주입받는다. */
    public OrderService(OrderLifecycleService lifecycle, OrderProcessingService processor, com.capstone4.orderagent.workspace.SnapshotService snapshots) {
        this.lifecycle = lifecycle; this.processor = processor; this.snapshots=snapshots;
    }
    /** 생성 결과다. created 가 true 면 새 주문이라 컨트롤러가 201 과 Location 을, false 면 같은 키의 기존 주문이라 200 을 돌려준다. */
    public record Creation(boolean created, OrderResponse order) {}
    /**
     * 주문을 만들고 처리한다. 부모 트랜잭션이 없어 접수, claim, 처리, 실패 기록이 각자 커밋되므로, 처리 중 실패해도 접수된 주문과
     * 그때까지의 결과가 남는다. 같은 키의 주문이 있으면 처리하지 않고 그 주문을 돌려준다.
     * 요청에 snapshot_id 가 있으면 접수 전에 order-agent/backend/src/main/java/com/capstone4/orderagent/workspace/SnapshotService.java 의
     * get 으로 존재와 해시를 확인한다.
     * 같은 키로 동시에 접수해 UNIQUE 제약에 걸리면 기존 주문을 다시 읽고, claim 을 얻지 못하면 처리하지 않고 현재 주문을 돌려준다.
     * 처리에 실패하면 order_id 를 담은 BusinessException(INTERNAL_ERROR 500 또는 PROCESSING_UNAVAILABLE 503)을 던진다. 이 예외는
     * order-agent/backend/src/main/java/com/capstone4/orderagent/exception/BusinessException.java 에 있다.
     * 재고 부족이나 결제 거절 같은 업무 거절은 실패가 아니라 정상 응답이다.
     */
    public Creation createOrder(UUID key, CreateOrderRequest request) {
        var input = NormalizedOrder.from(request);
        var existing = lifecycle.existing(key, input.hash());
        if (existing.isPresent()) return new Creation(false, lifecycle.get(existing.get().orderId()));
        if(request.snapshotId()!=null)snapshots.get(request.snapshotId());
        OrderLifecycleService.Accepted accepted;
        try { accepted = lifecycle.accept(key, input); }
        catch (DataIntegrityViolationException conflict) {
            accepted = lifecycle.existing(key, input.hash()).orElseThrow(() -> conflict);
        }
        UUID id = accepted.orderId();
        if (accepted.created() && lifecycle.claim(id)) {
            var failure = processor.processClaimed(id);
            if (failure.isPresent()) {
                // TODO(C-2 webhook 연동): trace 저장이 확정된 뒤 실패 이벤트(run_id, execution_id)를 전달한다.
                // 전송이 실패해도 주문 결과를 덮지 않는다. 업무 거절과 평가 실행의 실패는 자동 진단이 되풀이되지 않도록 전달하지 않는다.
                log.error("Order {} failed at {} ({})", id, failure.get().stage(), failure.get().cause().getClass().getSimpleName());
                throw new BusinessException(failure.get().code(), id);
            }
        }
        return new Creation(accepted.created(), lifecycle.get(id));
    }
    /**
     * GET /api/orders/{id} 응답을 만든다. service/src/main/java/com/capstone4/skillops/scenario/OrderAgentRunClient.java 의
     * originalOrder 도 실패했던 실행의 원래 주문을 이 경로로 읽는다.
     */
    public OrderResponse getOrder(UUID id) { return lifecycle.get(id); }
    /** GET /api/orders/by-request/{key} 응답을 만든다. 응답을 잃은 클라이언트가 같은 요청 키로 결과를 다시 찾을 때 쓴다. */
    public OrderResponse getByRequestId(UUID key) { return lifecycle.byRequest(key); }
}
