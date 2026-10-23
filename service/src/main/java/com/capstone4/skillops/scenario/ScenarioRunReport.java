package com.capstone4.skillops.scenario;

import java.util.List;

import tools.jackson.databind.PropertyNamingStrategies;
import tools.jackson.databind.annotation.JsonNaming;

/**
 * run_scenarios 의 결과 묶음이다. 계약 5 v1 모양이며 JSON 칸 이름은 snake_case 다.
 * service/src/main/java/com/capstone4/skillops/scenario/ScenarioRunner.java 의 report() 가 만들고,
 * service/src/main/java/com/capstone4/skillops/mcp/WorkspaceToolResults.java 의 run() 이 JSON 글 하나로 바꿔 도구 결과로 돌려준다.
 * 진단 Agent 는 skillops-agent/skillops_agent/contracts.py 의 validate_report 로 이 모양을 검사한다.
 * workspace_id 가 같은지, results 가 요청한 순서와 같은지, run_ids 가 서로 다른 repeat 개이고 모두 UUID 로 읽히는지,
 * failures 의 run_id 가 run_ids 안에 있는지, passed 가 failures 와 맞고 all_passed 가 결과마다의 passed 와 맞는지 본다.
 * 칸 이름이나 뜻을 바꾸면 그 검사도 함께 고친다.
 *
 * @param workspaceId 시험을 돌린 연습 공간 번호다
 * @param allPassed   돌린 시험 주문이 전부 합격이면 true 다
 * @param results     시험 주문별 결과다. scenario_ids 다음 replay_run_ids 순서다
 */
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public record ScenarioRunReport(String workspaceId, boolean allPassed, List<ScenarioResult> results) {

    /**
     * 시험 주문 하나의 결과다. repeat 번 돌린 실행이 모두 합격해야 passed 가 true 다.
     *
     * @param scenarioId    기준 주문이면 g-NN, 재현 주문이면 f-&lt;execution_id&gt; 다
     * @param replayOfRunId 재현 주문이면 넣은 실행 번호(scenario_ids 의 f- 뒤, 또는 replay_run_ids 의 값 그대로)이고, 기준 주문이면 null 이다
     * @param passed        모든 실행이 합격이면 true 다
     * @param runIds        반복할 때마다 생긴 모의 실행 번호다. 제안서의 근거로 쓴다
     * @param failures      떨어진 실행마다 한 칸이다. 비어 있으면 합격이다
     */
    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record ScenarioResult(String scenarioId, String replayOfRunId, boolean passed, List<String> runIds,
            List<Failure> failures) {
    }

    /**
     * 떨어진 실행 한 번이다.
     *
     * @param runId  떨어진 모의 실행 번호다. 같은 결과의 run_ids 안에 있다
     * @param skill  문제가 처음 드러난 skill, 곧 처음 오류가 난 단계의 skill 이다. 오류 없이 결과만 달라 특정할 수 없으면 null 이다
     * @param reason 떨어진 이유다. 여러 개면 "; " 로 잇고, 순서와 문구는 ScenarioGrader 가 정한다
     */
    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record Failure(String runId, String skill, String reason) {
    }
}
