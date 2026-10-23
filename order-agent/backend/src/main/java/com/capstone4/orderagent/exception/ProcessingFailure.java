package com.capstone4.orderagent.exception;

import com.capstone4.orderagent.entity.enums.ProcessingStepType;

/**
 * 처리 실패를 실패한 주문 처리 단계와 함께 전하는 예외다. 롤백 경계를 넘길 때 관리 중인 엔티티가 아니라 단계 종류만 넘기려고 만들었다.
 * order-agent/backend/src/main/java/com/capstone4/orderagent/service/OrderProcessingService.java 의 processClaimed 는 예외가 이 타입이면
 * 그 단계를, 아니면 RECOVERY 를 FAILED 로 남긴다. 지금 코드에서 이 예외를 만드는 곳은 없어 그 분기는 쓰이지 않는다.
 * Harness 실행의 실패 단계는 order-agent/backend/src/main/java/com/capstone4/orderagent/harness/OrderHarness.java 의 recordFailure 가 먼저 기록한다.
 */
public class ProcessingFailure extends RuntimeException {
    private final ProcessingStepType step;
    /** 실패한 단계와 원래 예외로 만든다. 원래 예외는 cause 로 남는다. */
    public ProcessingFailure(ProcessingStepType step, RuntimeException cause) { super(cause); this.step = step; }
    /** 실패한 주문 처리 단계다. */
    public ProcessingStepType getStep() { return step; }
}
