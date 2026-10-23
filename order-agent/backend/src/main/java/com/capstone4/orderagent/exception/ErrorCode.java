package com.capstone4.orderagent.exception;

/**
 * 주문 API 의 오류 코드와 HTTP 상태, 사용자에게 보여 줄 한국어 문구다. 이름은 응답의 code 로, message 는 응답의 message 로 그대로 나간다.
 * 처리 중 실패는 원인 사슬에 DB 예외가 있으면 PROCESSING_UNAVAILABLE(503), 아니면 INTERNAL_ERROR(500)로 나눈다
 * (order-agent/backend/src/main/java/com/capstone4/orderagent/service/OrderProcessingService.java 의 processClaimed).
 * 두 문구가 결과를 다시 확인하라고 안내하는 까닭은 주문 처리에 부모 트랜잭션이 없어, 오류가 나도 접수와 앞 단계 결과가 이미 커밋돼 있을 수 있기 때문이다.
 * 실행과 조회의 AgentException category 는 이 enum 에 없고,
 * order-agent/backend/src/main/java/com/capstone4/orderagent/exception/GlobalExceptionHandler.java 의 agent 가 따로 응답을 만든다.
 * 코드와 상태의 표는 order-agent/docs/api.md 6장에도 있으므로 바꾸면 함께 고친다. status 는 HTTP 상태 숫자, message 는 응답 문구다.
 */
public enum ErrorCode {
    VALIDATION_ERROR(400, "주문 입력을 확인해주세요."),
    MALFORMED_JSON(400, "JSON 형식과 필드 타입을 확인해주세요."),
    PRODUCT_NOT_FOUND(404, "선택한 상품을 찾을 수 없습니다."),
    ORDER_NOT_FOUND(404, "주문을 찾을 수 없습니다."),
    IDEMPOTENCY_CONFLICT(409, "같은 요청 키에 다른 주문 입력이 사용되었습니다."),
    PROCESSING_UNAVAILABLE(503, "일시적으로 처리할 수 없습니다. 같은 요청 키로 결과를 확인해주세요."),
    INTERNAL_ERROR(500, "주문 처리 중 오류가 발생했습니다. 주문 결과를 확인해주세요.");
    public final int status;
    public final String message;
    ErrorCode(int status, String message) { this.status = status; this.message = message; }
}
