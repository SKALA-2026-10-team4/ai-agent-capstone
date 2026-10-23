package com.capstone4.orderagent.exception;

import java.util.UUID;

/**
 * 주문 API 의 업무 오류를 HTTP 응답으로 전하는 예외다. ErrorCode 가 HTTP 상태와 한국어 문구를 정하고, 예외 메시지도 그 문구다.
 * orderId 는 이미 접수된 주문에 걸린 오류일 때 담아, 클라이언트가 새 주문을 넣지 않고 그 주문을 다시 읽게 한다.
 * order-agent/backend/src/main/java/com/capstone4/orderagent/exception/GlobalExceptionHandler.java 의 business 가 ErrorResponse 로 바꾼다.
 * 던지는 곳은 order-agent/backend/src/main/java/com/capstone4/orderagent/controller/OrderController.java 의 uuid 와
 * order-agent/backend/src/main/java/com/capstone4/orderagent/service/ 의 NormalizedOrder, OrderLifecycleService, OrderService, DryRunService 다.
 * 실행과 조회의 기계용 오류는 이 예외가 아니라 order-agent/backend/src/main/java/com/capstone4/orderagent/harness/AgentException.java 를 쓴다.
 * code 와 orderId 는 이름이 같은 get 접근자로 읽는다.
 */
public class BusinessException extends RuntimeException {
    private final ErrorCode code;
    private final UUID orderId;
    /** 주문과 무관한 오류(입력 형식, 없는 상품이나 주문)를 만든다. */
    public BusinessException(ErrorCode code) { this(code, null); }
    /** 이미 접수된 주문 id 를 함께 담는 오류를 만든다. orderId 가 null 이면 응답의 order_id 도 null 이다. */
    public BusinessException(ErrorCode code, UUID orderId) {
        super(code.message); this.code = code; this.orderId = orderId;
    }
    public ErrorCode getCode() { return code; }
    public UUID getOrderId() { return orderId; }
}
