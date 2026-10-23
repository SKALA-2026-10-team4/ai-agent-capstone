package com.capstone4.orderagent.service;

import java.util.*;
import com.capstone4.orderagent.entity.enums.ProcessingStepType;
import com.capstone4.orderagent.exception.*;
import com.capstone4.orderagent.harness.OrderHarness;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Service;

/**
 * 주문 생성과 모의 실행이 함께 쓰는 Harness 실행 진입점이다. 모델 요청을 감싸는 트랜잭션을 일부러 두지 않는다.
 * 모델 응답을 기다리는 동안 DB 잠금과 연결을 잡지 않기 위해서다.
 * OrderService.createOrder 와 DryRunService.run 이 processClaimed 를 부르고, 처리 실패를 HTTP 오류로 분류하는 규칙은 여기 한곳에 있다.
 * 실제 실행 루프는 order-agent/backend/src/main/java/com/capstone4/orderagent/harness/OrderHarness.java 다.
 */
@Service
public class OrderProcessingService {
    private final OrderHarness harness;
    private final OrderLifecycleService lifecycle;
    /** Harness 와 수명 주기 서비스를 주입받는다. */
    public OrderProcessingService(OrderHarness harness,OrderLifecycleService lifecycle){this.harness=harness;this.lifecycle=lifecycle;}
    /** 처리 실패를 HTTP 오류 코드와 주문 실패 단계로 분류한 결과와 원래 예외다. */
    public record Failure(ErrorCode code, ProcessingStepType stage, RuntimeException cause) {}
    /**
     * Harness 로 주문 하나를 실행한다. 실패하면 Harness 가 기록을 마친 뒤 던진 예외가 그대로 나온다.
     * order-agent/backend/src/test/java/com/capstone4/orderagent/integration/TraceContractIntegrationTest.java 도 직접 부른다.
     */
    public void process(UUID orderId){harness.execute(orderId);}
    /**
     * claim 한 주문을 실행하고, 실패하면 주문을 FAILED 로 기록한 뒤 분류 결과를 돌려준다. 로그와 응답은 호출자가 정한다.
     * 원인 사슬에 DataAccessException 이 있으면 PROCESSING_UNAVAILABLE(503), 아니면 INTERNAL_ERROR(500)다.
     * 실패 단계는 예외가 ProcessingFailure(order-agent/backend/src/main/java/com/capstone4/orderagent/exception/ProcessingFailure.java)면
     * 그 단계, 아니면 RECOVERY 이며, 지금 코드에서 ProcessingFailure 를 만드는 곳은 없다.
     * Harness 가 이미 실패를 기록했다면 OrderLifecycleService.fail 은 아무것도 하지 않는다.
     */
    public Optional<Failure> processClaimed(UUID orderId){
        try{process(orderId);return Optional.empty();}
        catch(RuntimeException e){
            var stage=e instanceof ProcessingFailure f?f.getStep():ProcessingStepType.RECOVERY;
            ErrorCode code=hasDatabaseCause(e)?ErrorCode.PROCESSING_UNAVAILABLE:ErrorCode.INTERNAL_ERROR;
            lifecycle.fail(orderId,stage,code.name());
            return Optional.of(new Failure(code,stage,e));
        }
    }
    /** 예외의 cause 사슬에 DataAccessException 이 있는지 본다. DB 장애를 일시적 처리 불가(503)로 구분하려고 쓴다. */
    static boolean hasDatabaseCause(Throwable error) {
        for (Throwable e = error; e != null; e = e.getCause()) if (e instanceof DataAccessException) return true;
        return false;
    }
}
