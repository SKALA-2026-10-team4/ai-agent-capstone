package com.capstone4.orderagent.dto.common;

import java.util.*;

public record ErrorResponse(String code, String message, UUID orderId, List<FieldError> fieldErrors) {
    public record FieldError(String field, String message) {}
}
