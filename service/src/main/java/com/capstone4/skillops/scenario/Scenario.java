package com.capstone4.skillops.scenario;

import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import tools.jackson.databind.PropertyNamingStrategies;
import tools.jackson.databind.annotation.JsonNaming;

/**
 * 시험 주문 파일 한 건이다. 계약 3(docs/contracts/scenario.schema.json)과 칸 이름이 같고, JSON 의 snake_case 칸을 읽으며 모르는 칸은 무시한다.
 * service/src/main/java/com/capstone4/skillops/scenario/ScenarioCatalog.java 가 서비스가 켜질 때 파일마다 읽고,
 * service/src/main/java/com/capstone4/skillops/scenario/ScenarioRunner.java 가 id, order, expect 를 쓴다.
 * name, source_run_id, k 는 읽어 두기만 하고 코드에서 쓰지 않는다.
 *
 * @param id          시험 주문 번호다. 예를 들어 g-01 이다
 * @param order       모의 실행에 넘길 주문(customer, items, payment_method, shipping)이다
 * @param sourceRunId 재현 주문이면 원래 실패한 실행 번호, 기준 주문이면 null 이다. 지금은 쓰지 않는다
 * @param k           계약 3 의 반복 횟수 칸이다. 코드에서 쓰지 않는다. 돌릴 횟수는 run_scenarios 의 repeat 가 정하고,
 *                    넣지 않으면 ScenarioRunner.DEFAULT_REPEAT 다
 */
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
@JsonIgnoreProperties(ignoreUnknown = true)
public record Scenario(String id, String name, String sourceRunId, Map<String, Object> order, Expect expect,
        Integer k) {

    /**
     * 기대 결과다. 비어 있는 칸은 채점하지 않는다. Expectation.of 가 채점 기준으로 바꾼다.
     *
     * @param skillsCalled 성공해야 하는 skill 의 순서다
     * @param finalStatus  기대하는 최종 상태다
     * @param shipments    기대하는 배송 건수다
     * @param assertions   확인 조건 목록이다. 문법은 Check 를 따른다
     */
    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Expect(List<String> skillsCalled, String finalStatus, Integer shipments, List<String> assertions) {
    }
}
