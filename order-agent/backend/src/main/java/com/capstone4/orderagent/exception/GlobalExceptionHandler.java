package com.capstone4.orderagent.exception;

import java.util.*;
import com.capstone4.orderagent.dto.common.ErrorResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.http.*;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingRequestHeaderException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

@RestControllerAdvice
public class GlobalExceptionHandler {
    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);
    @ExceptionHandler(com.capstone4.orderagent.harness.AgentException.class)
    ResponseEntity<ErrorResponse> agent(com.capstone4.orderagent.harness.AgentException e) {
        int status = e.category().endsWith("NOT_FOUND") ? 404 : 400;
        return ResponseEntity.status(status).body(new ErrorResponse(e.category(), "실행 자료를 확인할 수 없습니다: " + e.category(), null, List.of()));
    }
    @ExceptionHandler(BusinessException.class)
    ResponseEntity<ErrorResponse> business(BusinessException e) { return response(e.getCode(), e.getOrderId(), List.of()); }
    @ExceptionHandler(MethodArgumentNotValidException.class)
    ResponseEntity<ErrorResponse> validation(MethodArgumentNotValidException e) {
        var fields = e.getBindingResult().getFieldErrors().stream()
            .map(f -> new ErrorResponse.FieldError(f.getField(), f.getDefaultMessage())).toList();
        return response(ErrorCode.VALIDATION_ERROR, null, fields);
    }
    @ExceptionHandler({MissingRequestHeaderException.class, MissingServletRequestParameterException.class, MethodArgumentTypeMismatchException.class})
    ResponseEntity<ErrorResponse> invalidInput(Exception e) { return response(ErrorCode.VALIDATION_ERROR, null, List.of()); }
    @ExceptionHandler(HttpMessageNotReadableException.class)
    ResponseEntity<ErrorResponse> malformed(Exception e) { return response(ErrorCode.MALFORMED_JSON, null, List.of()); }
    @ExceptionHandler(DataAccessException.class)
    ResponseEntity<ErrorResponse> database(DataAccessException e) {
        log.error("Database request failed ({})", e.getClass().getSimpleName());
        return response(ErrorCode.PROCESSING_UNAVAILABLE, null, List.of());
    }
    @ExceptionHandler(Exception.class)
    ResponseEntity<ErrorResponse> unexpected(Exception e) {
        log.error("Unexpected request failure ({})", e.getClass().getSimpleName());
        return response(ErrorCode.INTERNAL_ERROR, null, List.of());
    }
    private ResponseEntity<ErrorResponse> response(ErrorCode code, UUID id, List<ErrorResponse.FieldError> fields) {
        return ResponseEntity.status(code.status).body(new ErrorResponse(code.name(), code.message, id, fields));
    }
}
