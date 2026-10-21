package com.capstone4.skillops.scenario;

import java.util.List;

import tools.jackson.databind.PropertyNamingStrategies;
import tools.jackson.databind.annotation.JsonNaming;

/**
 * 시험 주문 돌리기의 결과. 도구는 {@link #results()} 목록만 돌려준다(계약 5 v0 모양).
 *
 * @param allPassed 돌린 시험 주문이 전부 합격이면 true
 */
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public record ScenarioRunReport(String workspaceId, boolean allPassed, List<ScenarioResult> results) {

    /**
     * @param runs 반복할 때마다 생긴 모의 실행 번호. 제안서의 근거로 쓴다
     */
    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record ScenarioResult(String scenarioId, boolean passed, List<String> runs, List<Failure> failures) {
    }

    /**
     * @param step  문제가 처음 드러난 skill. 특정할 수 없으면 null
     * @param error 떨어진 이유. 여러 개면 "; " 로 잇는다
     */
    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record Failure(String runId, String step, String error) {
    }
}
