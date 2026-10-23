package com.capstone4.orderagent.controller;
import java.net.URI;
import java.util.UUID;
import com.capstone4.orderagent.dto.order.*;
import com.capstone4.orderagent.exception.*;
import com.capstone4.orderagent.service.OrderService;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
/**
 * 주문 생성과 조회 HTTP API 다. POST /api/orders 는 Idempotency-Key 헤더로 같은 요청의 재전송을 주문 하나로 묶고,
 * GET /api/orders/{id} 와 GET /api/orders/by-request/{key} 는 저장된 주문을 돌려준다. 처리는 모두
 * order-agent/backend/src/main/java/com/capstone4/orderagent/service/OrderService.java 에 맡긴다.
 * 호출처는 frontend/src/api/orderAgent.js 의 createOrder, getOrder, getOrderByRequest 와
 * order-agent/frontend/src/api/orders.js 의 createOrder, getOrder, getByRequest,
 * service/src/main/java/com/capstone4/skillops/scenario/OrderAgentRunClient.java 의 originalOrder(GET /api/orders/{id}),
 * order-agent/scripts/smoke.py, skillops-agent/scripts/c2_real_judge.py 다. 명세는 order-agent/docs/api.md 다.
 */
@RestController
@RequestMapping("/api/orders")
public class OrderController {
    private final OrderService service;
    /** Spring 이 주문 서비스를 주입해 빈으로 만든다. */
    public OrderController(OrderService service) { this.service = service; }
    /**
     * POST /api/orders: 주문을 접수하고 Harness(order-agent/backend/src/main/java/com/capstone4/orderagent/harness/OrderHarness.java)로
     * 끝까지 처리한 뒤 결과 주문을 돌려주는 동기 호출이다. 새 주문이면 201 과 Location 을,
     * 같은 키로 이미 접수된 주문이면 다시 처리하지 않고 200 으로 그 주문을 돌려준다. 같은 키에 다른 입력이면 IDEMPOTENCY_CONFLICT(409)다.
     * 재고 부족과 결제 거절은 오류가 아니라 정상 응답(status REJECTED, HOLD)이다. 처리 중 실패는 order_id 를 담은 500 이나 503 이며,
     * 클라이언트는 그 order_id 나 같은 키로 FAILED 주문을 다시 읽는다(frontend/src/api/orderAgent.js 의 createOrder).
     * 헤더가 없거나 UUID 가 아니면 VALIDATION_ERROR(400)다.
     */
    @PostMapping
    ResponseEntity<OrderResponse> create(@RequestHeader("Idempotency-Key") String key, @Valid @RequestBody CreateOrderRequest request) {
        var result = service.createOrder(uuid(key), request);
        return result.created() ? ResponseEntity.created(URI.create("/api/orders/" + result.order().orderId())).body(result.order())
            : ResponseEntity.ok(result.order());
    }
    /** GET /api/orders/{id}: 주문 id 로 주문을 읽는다. 없으면 ORDER_NOT_FOUND(404)다. */
    @GetMapping("/{id}") OrderResponse get(@PathVariable String id) { return service.getOrder(uuid(id)); }
    /** GET /api/orders/by-request/{key}: 응답을 받지 못한 클라이언트가 같은 Idempotency-Key 로 주문 결과를 다시 찾는다. 없으면 ORDER_NOT_FOUND(404)다. */
    @GetMapping("/by-request/{key}") OrderResponse byRequest(@PathVariable String key) { return service.getByRequestId(uuid(key)); }
    /**
     * 경로와 헤더의 UUID 를 엄격하게 읽는다. UUID.fromString 은 1-1-1-1-1 같은 줄인 표기도 받으므로, 다시 문자열로 바꾼 값이
     * 원문과 대소문자를 빼고 같을 때만 받는다. 그 밖의 표기는 VALIDATION_ERROR(400)로 거절한다.
     */
    private UUID uuid(String raw) {
        try {
            var id = UUID.fromString(raw);
            if (!id.toString().equalsIgnoreCase(raw)) throw new IllegalArgumentException();
            return id;
        } catch (IllegalArgumentException e) { throw new BusinessException(ErrorCode.VALIDATION_ERROR); }
    }
}
