package com.capstone4.skillops.scenario;

import java.util.Map;

import com.capstone4.skillops.workspace.WorkspaceSkill;

/**
 * 주문 한 건을 연습 공간의 skill 로 모의 실행하는 실행기의 접점이다. 실제 결제와 배송은 일어나지 않는다.
 * 구현은 service/src/main/resources/application.yml 의 skillops.flow-runner(환경 변수 SKILLOPS_FLOW_RUNNER)로 하나만 고른다.
 * harness 면 {@link HarnessFlowRunner} 가 주문 Agent 에서 후보 snapshot 으로 모의 실행하고,
 * contract-only(기본)면 {@link ContractOnlyFlowRunner} 가 SKILL.md 맨 위 칸만 보고 흉내 낸다.
 * 두 구현에 붙은 @ConditionalOnProperty 가 이 선택을 맡는다. 다른 값을 넣으면 실행기가 하나도 뜨지 않아 서비스가 켜지지 않는다.
 * 선택 규칙은 service/src/test/java/com/capstone4/skillops/scenario/FlowRunnerSelectionTest.java 가 고정한다.
 * service/src/main/java/com/capstone4/skillops/scenario/ScenarioRunner.java 가 이 접점만 알고 runDryRun 과 supportsReplay 를 부른다.
 * 새 실행기를 더하려면 이 접점을 구현하고 skillops.flow-runner 의 새 값으로 @ConditionalOnProperty 를 단다.
 */
public interface FlowRunner {

    /** 주문 한 건을 한 번 모의 실행하고 채점에 필요한 만큼의 결과를 돌려준다. 던진 예외는 ScenarioRunner 를 거쳐 run_scenarios 오류가 된다. */
    RunOutcome runDryRun(DryRunRequest request);

    /**
     * 재현 주문(주문 Agent 에서 실패했던 실제 주문)을 돌릴 수 있으면 true 를 돌려준다. 주문 Agent 에서 실제로 돌리는 실행기만 true 다.
     * 흉내 내는 실행기로 실제 주문을 돌리면 고쳐졌다는 근거가 되지 않기 때문이다.
     * ScenarioRunner 의 requireReplaySupport() 가 이 값을 본다.
     */
    default boolean supportsReplay() {
        return false;
    }

    /**
     * 모의 실행 한 번의 요청이다. ScenarioRunner 의 runScenario() 가 실행마다 만든다.
     *
     * @param scenarioId  시험 주문 번호다. 오류 문구에 쓴다
     * @param order       시험 주문의 order 칸(customer, items, payment_method, shipping)이다. 재현 주문이면 주문 Agent 에서 꺼낸 원래 주문이다
     * @param workspaceId 연습 공간 번호다. HarnessFlowRunner 는 이 번호로 연습 공간 파일을 다시 읽는다
     * @param skills      연습 공간의 skill 이름별 원문이다. ContractOnlyFlowRunner 만 쓴다
     */
    record DryRunRequest(String scenarioId, Map<String, Object> order, String workspaceId,
            Map<String, WorkspaceSkill> skills) {
    }
}
