package com.capstone4.skillops.scenario;

import java.util.List;

import tools.jackson.databind.PropertyNamingStrategies;
import tools.jackson.databind.annotation.JsonNaming;

/**
 * run_scenarios 도구의 결과. 계약 5 v1 의 9번과 같은 모양이다.
 *
 * @param allPassed 돌린 시험 주문이 전부 합격이면 true
 */
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public record ScenarioRunReport(String workspaceId, boolean allPassed, List<ScenarioResult> results) {

    /**
     * @param replayOfRunId 재현 주문이면 원래 실패한 실행 번호. 이번 범위에서는 항상 null
     * @param runIds        반복할 때마다 생긴 모의 실행 번호. 제안서의 근거로 쓴다
     */
    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record ScenarioResult(String scenarioId, String replayOfRunId, boolean passed, List<String> runIds,
            List<Failure> failures) {
    }

    /**
     * @param skill  문제가 처음 드러난 skill. 특정할 수 없으면 null
     * @param reason 떨어진 이유. 여러 개면 "; " 로 잇는다
     */
    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record Failure(String runId, String skill, String reason) {
    }
}
