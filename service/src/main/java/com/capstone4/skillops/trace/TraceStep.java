package com.capstone4.skillops.trace;

import java.util.List;
import java.util.Map;

import tools.jackson.databind.PropertyNamingStrategies;
import tools.jackson.databind.annotation.JsonNaming;

/**
 * skill 한 번 실행의 기록. 계약 2 의 step.
 *
 * @param input       이 skill 이 읽은 컨텍스트 키들 (inputs 선언 기준)
 * @param output      이 skill 이 쓴 컨텍스트 키들
 * @param toolsCalled 어댑터 tool 이름. 시나리오 기대값이 여기에 걸린다
 * @param error       status=error 일 때 사람이 읽을 한 줄, 아니면 null
 * @param ms          걸린 시간(밀리초)
 */
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public record TraceStep(
        String skill,
        int version,
        Map<String, Object> input,
        Map<String, Object> output,
        List<String> toolsCalled,
        StepStatus status,
        String error,
        long ms) {
}
