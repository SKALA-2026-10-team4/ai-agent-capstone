package com.capstone4.skillops.scenario;

import java.util.Map;

import com.capstone4.skillops.workspace.WorkspaceSkill;

/**
 * 주문 한 건을 연습 공간의 skill 로 모의 실행한다. 실제 결제·배송은 일어나지 않는다.
 * {@code skillops.flow-runner} 로 하나만 고른다. harness 는 {@link HarnessFlowRunner}(주문 Agent 에서 후보 snapshot 으로 모의 실행),
 * contract-only(기본)는 {@link ContractOnlyFlowRunner}(SKILL.md 맨 위 칸만 보고 흉내)다.
 */
public interface FlowRunner {

    RunOutcome runDryRun(DryRunRequest request);

    /**
     * 재현 주문(주문 Agent 에서 실패했던 실제 주문)을 돌릴 수 있으면 true. 주문 Agent 에서 실제로 돌리는 실행기만 true 다.
     * 흉내 내는 실행기로 실제 주문을 돌리면 고쳐졌다는 근거가 되지 않는다.
     */
    default boolean supportsReplay() {
        return false;
    }

    /**
     * @param order  시험 주문의 order 칸(customer, items, payment_method, shipping)
     * @param skills 연습 공간의 skill 이름 → 원문
     */
    record DryRunRequest(String scenarioId, Map<String, Object> order, String workspaceId,
            Map<String, WorkspaceSkill> skills) {
    }
}
