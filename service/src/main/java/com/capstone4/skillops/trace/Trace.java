package com.capstone4.skillops.trace;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import tools.jackson.databind.PropertyNamingStrategies;
import tools.jackson.databind.annotation.JsonNaming;

/**
 * 주문 한 건의 실행 트레이스. 계약 2(docs/contracts/trace.schema.json)와 칸 이름·값이 같아야 한다.
 * B 의 Agent 가 get_trace 로 이 모양을 그대로 읽는다.
 *
 * @param workspace     dry-run 이면 ws_id, live 면 null
 * @param scenarioId    시나리오 러너가 돌린 실행이면 그 id, 아니면 null
 * @param skillVersions 이 실행에 로드된 skill → version. diff_skill 의 기준점
 */
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public record Trace(
        String runId,
        String orderId,
        RunMode mode,
        String workspace,
        String scenarioId,
        Instant startedAt,
        Map<String, Integer> skillVersions,
        List<TraceStep> steps,
        FinalStatus finalStatus) {
}
