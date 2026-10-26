package com.capstone4.skillops.scenario;

import java.util.List;
import java.util.Map;

/**
 * 채점에 필요한 만큼만 담은 실행 결과다. C-1 의 Trace 에 기대지 않으려고 C-2 가 따로 둔 모양이다.
 * service/src/main/java/com/capstone4/skillops/scenario/ContractOnlyFlowRunner.java 는 이 모양을 직접 만들고, 주문 Agent 의 실행 기록은
 * service/src/main/java/com/capstone4/skillops/scenario/OrderAgentTraceConverter.java 가 이 모양으로 바꾼다.
 * service/src/main/java/com/capstone4/skillops/scenario/ScenarioGrader.java 가 읽어 채점한다.
 * 상태 글자는 상수로 모아 두었다. SHIPPED 와 FAILED 는 주문 Agent 의
 * order-agent/backend/src/main/java/com/capstone4/orderagent/entity/enums/OrderStatus.java 값과, ok 와 error 는 주문 Agent 가
 * SKILL_FINISHED 에 남기는 status 와 같은 글자다. skipped 는 ContractOnlyFlowRunner 만 쓴다.
 *
 * @param runId       모의 실행 번호다. run_scenarios 결과의 run_ids 에 들어간다
 * @param finalStatus SHIPPED, REJECTED, HOLD, FAILED 가운데 하나다. 실행 기록에 없으면 null 이다
 * @param steps       거친 단계를 순서대로 담는다
 * @param error       어느 단계의 것인지 모르는 실행 오류의 한 줄이다(예: skill 없는 EXECUTION_ERROR 의 INVALID_FLOW). 없으면 null 이다.
 *                    단계에 붙은 오류는 Step.error 에 있다. ScenarioGrader 가 불합격 이유 맨 앞에 "실행 오류:" 로 붙인다
 */
public record RunOutcome(String runId, String finalStatus, List<Step> steps, String error) {

    /** 단계 밖 실행 오류가 없는 결과를 만든다. ContractOnlyFlowRunner 와 테스트가 쓴다. */
    public RunOutcome(String runId, String finalStatus, List<Step> steps) {
        this(runId, finalStatus, steps, null);
    }

    /** 끝까지 처리돼 배송이 잡힌 최종 상태다. */
    public static final String SHIPPED = "SHIPPED";
    /** 오류로 멈춘 최종 상태다. 재현 주문은 이 상태가 아니어야 합격이다. */
    public static final String FAILED = "FAILED";

    /**
     * 실행의 단계 하나다.
     *
     * @param skill  이 단계의 skill 이름이다
     * @param status ok, error, skipped 가운데 하나다
     * @param input  이 단계가 받은 값이다
     * @param output 이 단계가 내놓은 값이다
     * @param error  status 가 error 일 때 사람이 읽을 한 줄이고, 아니면 null 이다
     */
    public record Step(String skill, String status, Map<String, Object> input, Map<String, Object> output,
            String error) {

        /** 성공한 단계다. */
        public static final String OK = "ok";
        /** 오류가 난 단계다. */
        public static final String ERROR = "error";
        /** 앞 단계 오류로 돌리지 않은 단계다. */
        public static final String SKIPPED = "skipped";
    }
}
