package com.capstone4.skillops.scenario;

import java.util.List;
import java.util.Map;

/**
 * 채점에 필요한 만큼만 담은 실행 결과. C-1 의 Trace 에 기대지 않으려고 C-2 가 따로 둔 모양이다.
 * A 의 실행기가 붙으면 Trace → RunOutcome 변환만 더한다.
 *
 * @param finalStatus SHIPPED · REJECTED · HOLD · FAILED
 */
public record RunOutcome(String runId, String finalStatus, List<Step> steps) {

    /**
     * @param status ok · error · skipped
     * @param error  status 가 error 일 때 사람이 읽을 한 줄, 아니면 null
     */
    public record Step(String skill, String status, Map<String, Object> input, Map<String, Object> output,
            String error) {
    }
}
