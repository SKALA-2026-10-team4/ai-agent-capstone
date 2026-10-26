package com.capstone4.skillops.evaluation;

import java.util.Map;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.ResponseEntity;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import com.capstone4.skillops.proposal.VerifiedProposalController;
import com.capstone4.skillops.snapshot.OrderAgentException;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MissingRequestHeaderException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

/** 새 평가 경계의 오류만 구조화하며 기존 API 예외 계약은 바꾸지 않는다. */
@RestControllerAdvice(assignableTypes={EvaluationController.class,VerifiedProposalController.class})
public class EvaluationErrors {
    /** 상위 조회 실패의 코드·재시도 가능 여부를 보존한다. */
    @ExceptionHandler(OrderAgentException.class)
    public ResponseEntity<Map<String,Object>> upstream(OrderAgentException error) {
        int status=error.upstreamStatus()!=null && error.upstreamStatus()==404 ? 404 : error.code().equals("INVALID_INPUT") ? 400 : 503;
        return ResponseEntity.status(status).body(body(error.code(),error.getMessage(),error.retryable()));
    }
    /** JSON 타입·필수 헤더 오류도 문서화한 입력 오류로 반환한다. */
    @ExceptionHandler({HttpMessageNotReadableException.class,MissingRequestHeaderException.class,MethodArgumentTypeMismatchException.class})
    public ResponseEntity<Map<String,Object>> unreadable(Exception error) {
        return ResponseEntity.badRequest().body(body("INVALID_EVALUATION_INPUT","JSON 타입·필수 헤더·조회 인자를 확인하세요",false));
    }
    /** 평가 오류의 원래 코드와 상태를 보존한다. */
    @ExceptionHandler(EvaluationException.class)
    public ResponseEntity<Map<String,Object>> evaluation(EvaluationException error) {
        return ResponseEntity.status(error.status).body(body(error.code,error.getMessage(),error.status==429 || error.status==503));
    }
    /** 저장 경쟁은 성공인 척하지 않고 재조회 가능한 충돌로 반환한다. */
    @ExceptionHandler({DataIntegrityViolationException.class,ObjectOptimisticLockingFailureException.class})
    public ResponseEntity<Map<String,Object>> conflict(RuntimeException error) {
        return ResponseEntity.status(409).body(body("EVALUATION_WRITE_CONFLICT","동시 요청과 충돌했습니다. 같은 키로 재조회하세요",true));
    }
    /** 잘못된 조건 문법도 공통 입력 오류로 변환한다. */
    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String,Object>> invalid(IllegalArgumentException error) {
        return ResponseEntity.badRequest().body(body("INVALID_EVALUATION_INPUT",String.valueOf(error.getMessage()),false));
    }
    /** 오류 JSON의 공통 필드를 만든다. */
    public static Map<String,Object> body(String code,String message,boolean retryable) {
        return Map.of("code",code,"message",message,"retryable",retryable);
    }
}
