package com.capstone4.orderagent.exception;

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
