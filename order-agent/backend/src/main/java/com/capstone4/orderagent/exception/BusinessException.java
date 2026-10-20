package com.capstone4.orderagent.exception;

import java.util.UUID;

public class BusinessException extends RuntimeException {
    private final ErrorCode code;
    private final UUID orderId;
    public BusinessException(ErrorCode code) { this(code, null); }
    public BusinessException(ErrorCode code, UUID orderId) {
        super(code.message); this.code = code; this.orderId = orderId;
    }
    public ErrorCode getCode() { return code; }
    public UUID getOrderId() { return orderId; }
}
