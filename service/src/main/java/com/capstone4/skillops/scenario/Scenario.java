package com.capstone4.skillops.scenario;

import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import tools.jackson.databind.PropertyNamingStrategies;
import tools.jackson.databind.annotation.JsonNaming;

/**
 * 시험 주문 한 건. 계약 3(docs/contracts/scenario.schema.json)과 칸 이름이 같다.
 *
 * @param sourceRunId 재현 주문이면 원래 실패한 실행 번호, 기준 주문이면 null
 * @param k           같은 주문을 돌릴 횟수. run_scenarios 의 repeat 가 우선한다
 */
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
@JsonIgnoreProperties(ignoreUnknown = true)
public record Scenario(String id, String name, String sourceRunId, Map<String, Object> order, Expect expect,
        Integer k) {

    /** 기대 결과. 비어 있는 칸은 채점하지 않는다. */
    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Expect(List<String> skillsCalled, String finalStatus, Integer shipments, List<String> assertions) {
    }
}
