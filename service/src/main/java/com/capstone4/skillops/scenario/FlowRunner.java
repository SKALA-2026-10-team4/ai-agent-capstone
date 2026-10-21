package com.capstone4.skillops.scenario;

import java.util.Map;

import com.capstone4.skillops.workspace.WorkspaceSkill;

/**
 * 주문 한 건을 연습 공간의 skill 로 모의 실행한다. 실제 결제·배송은 일어나지 않는다.
 * A 의 실행기가 구현한다({@code skillops.flow-runner=harness}). 모의 실행은 C-1 TraceRecorder 로 DRY_RUN 기록을 남기고,
 * 그 Trace 를 {@link RunOutcome} 으로 바꿔 돌려준다. A 실행기가 붙기 전에는 {@link ContractOnlyFlowRunner} 를 쓴다.
 */
public interface FlowRunner {

    RunOutcome runDryRun(DryRunRequest request);

    /**
     * @param order  시험 주문의 order 칸(customer, items, payment_method, shipping)
     * @param skills 연습 공간의 skill 이름 → 원문
     */
    record DryRunRequest(String scenarioId, Map<String, Object> order, String workspaceId,
            Map<String, WorkspaceSkill> skills) {
    }
}
