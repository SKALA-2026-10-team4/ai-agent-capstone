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

/**
 * 모든 컨트롤러의 예외를 ErrorResponse JSON 으로 바꾸는 RestControllerAdvice 다. 예외 종류마다 HTTP 상태와 code 를 정한다.
 * AgentException 은 category 가 NOT_FOUND 로 끝나면 404, 아니면 400 이고 code 는 category 다. BusinessException 은 ErrorCode 의 상태와 문구,
 * order_id 를 쓴다. Bean Validation 실패는 VALIDATION_ERROR 와 field_errors, 빠진 헤더나 파라미터와 경로 값 타입 오류는 VALIDATION_ERROR,
 * 읽을 수 없는 JSON 이나 맞지 않는 값 타입은 MALFORMED_JSON 이다. DB 예외는 PROCESSING_UNAVAILABLE(503), 그 밖의 예외는 INTERNAL_ERROR(500)다.
 * DB 예외와 예상하지 못한 예외는 메시지 대신 예외 클래스 이름만 로그에 남기고 응답에는 정해진 문구만 싣는다.
 * order-agent/docs/api.md 6장이 내부 예외, 스택, SQL 을 message 에 넣지 않도록 정하기 때문이다.
 * 상태와 code 는 frontend/src/api/client.js, order-agent/frontend/src/api/client.js,
 * service/src/main/java/com/capstone4/skillops/snapshot/OrderAgentClient.java 와 service/src/main/java/com/capstone4/skillops/scenario/OrderAgentRunErrors.java,
 * skillops-agent/skillops_agent/order_agent.py 가 읽으므로 규칙을 바꾸면 함께 확인한다.
 */
@RestControllerAdvice
public class GlobalExceptionHandler {
    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);
    /**
     * 실행과 조회 오류(AgentException)를 바꾼다. category 이름의 끝으로 상태를 정하므로 category 를 NOT_FOUND 로 끝나게 바꾸면 404 가 된다.
     * message 는 정해진 머리말 뒤에 category 를 붙이고 order_id 와 field_errors 는 비운다.
     * 주문 처리 중 실패는 OrderService 가 BusinessException 으로 감싸 이곳에 오지 않지만, 접수 전 snapshot 확인 실패(SNAPSHOT_NOT_FOUND,
     * SNAPSHOT_TAMPERED)와 EvidenceController, DemoGuard 의 오류는 그대로 온다.
     */
    @ExceptionHandler(com.capstone4.orderagent.harness.AgentException.class)
    ResponseEntity<ErrorResponse> agent(com.capstone4.orderagent.harness.AgentException e) {
        int status = e.category().endsWith("NOT_FOUND") ? 404 : 400;
        return ResponseEntity.status(status).body(new ErrorResponse(e.category(), "실행 자료를 확인할 수 없습니다: " + e.category(), null, List.of()));
    }
    /** 주문 업무 오류를 ErrorCode 의 상태와 문구로 바꾸고 예외가 담은 order_id 를 싣는다. */
    @ExceptionHandler(BusinessException.class)
    ResponseEntity<ErrorResponse> business(BusinessException e) { return response(e.getCode(), e.getOrderId(), List.of()); }
    /** 요청 본문의 Bean Validation 실패를 VALIDATION_ERROR 로 바꾸고 필드별 실패를 field_errors 에 담는다. */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    ResponseEntity<ErrorResponse> validation(MethodArgumentNotValidException e) {
        var fields = e.getBindingResult().getFieldErrors().stream()
            .map(f -> new ErrorResponse.FieldError(f.getField(), f.getDefaultMessage())).toList();
        return response(ErrorCode.VALIDATION_ERROR, null, fields);
    }
    /** 빠진 Idempotency-Key 헤더, 빠진 쿼리 파라미터(GET /api/snapshots/{id}/files 의 path), UUID 로 읽을 수 없는 경로 값을 VALIDATION_ERROR 로 바꾼다. */
    @ExceptionHandler({MissingRequestHeaderException.class, MissingServletRequestParameterException.class, MethodArgumentTypeMismatchException.class})
    ResponseEntity<ErrorResponse> invalidInput(Exception e) { return response(ErrorCode.VALIDATION_ERROR, null, List.of()); }
    /**
     * 본문을 객체로 읽지 못한 경우를 MALFORMED_JSON 으로 바꾼다. 깨진 JSON, 맞지 않는 값 타입, 정수 자리의 실수, 모르는 키가 여기에 든다.
     * 뒤의 둘은 order-agent/backend/src/main/resources/application.yml 의 spring.jackson.deserialization 설정
     * (accept-float-as-int: false, fail-on-unknown-properties: true) 때문이다.
     */
    @ExceptionHandler(HttpMessageNotReadableException.class)
    ResponseEntity<ErrorResponse> malformed(Exception e) { return response(ErrorCode.MALFORMED_JSON, null, List.of()); }
    /** 요청 처리 중 난 DB 예외를 일시적 처리 불가(PROCESSING_UNAVAILABLE 503)로 바꾼다. 잠금 대기 시간 초과도 여기에 든다. */
    @ExceptionHandler(DataAccessException.class)
    ResponseEntity<ErrorResponse> database(DataAccessException e) {
        log.error("Database request failed ({})", e.getClass().getSimpleName());
        return response(ErrorCode.PROCESSING_UNAVAILABLE, null, List.of());
    }
    /** 위 처리기가 잡지 않은 모든 예외를 INTERNAL_ERROR(500)로 바꾼다. */
    @ExceptionHandler(Exception.class)
    ResponseEntity<ErrorResponse> unexpected(Exception e) {
        log.error("Unexpected request failure ({})", e.getClass().getSimpleName());
        return response(ErrorCode.INTERNAL_ERROR, null, List.of());
    }
    /** ErrorCode 의 상태로 응답을 만들고 본문에 code 이름, 문구, order_id, field_errors 를 담는다. */
    private ResponseEntity<ErrorResponse> response(ErrorCode code, UUID id, List<ErrorResponse.FieldError> fields) {
        return ResponseEntity.status(code.status).body(new ErrorResponse(code.name(), code.message, id, fields));
    }
}
