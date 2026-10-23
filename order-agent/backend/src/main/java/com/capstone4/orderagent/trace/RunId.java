package com.capstone4.orderagent.trace;

import java.util.UUID;
import com.capstone4.orderagent.harness.AgentException;

/**
 * 실행 id(UUID)와 외부 별칭 run_id 사이의 변환 규칙이다. run_id 는 r_ 뒤에 하이픈을 뺀 32자리 16진수를 붙인 문자열이며,
 * 기존 계약용 별칭이라 진단 thread id 나 진단 run ID 와는 다르다.
 * order-agent/backend/src/main/java/com/capstone4/orderagent/trace/ExecutionRepository.java 의 summary 가 trace 응답의 run_id 를 of 로 만들고,
 * order-agent/backend/src/main/java/com/capstone4/orderagent/controller/EvidenceController.java 의 legacyTrace(GET /api/traces/{runId})가 parse 로 되읽는다.
 * 같은 규칙을 다른 모듈이 복제한다. service/src/main/java/com/capstone4/skillops/scenario/OrderAgentRunClient.java 의 executionIdOf,
 * skillops-agent/skillops_agent/contracts.py 의 execution_uuid, frontend/src/utils/traceRun.js 의 executionPath 가 r_ 별칭을 UUID 로 바꾸므로
 * 형식을 바꾸면 함께 고친다. order-agent/backend/src/test/java/com/capstone4/orderagent/trace/RunIdTest.java 가 왕복 규칙을 고정한다.
 */
public final class RunId {
    /** 정적 변환 메서드만 있는 클래스라 객체를 만들지 않는다. */
    private RunId() {}
    /** 실행 id 를 run_id 별칭으로 바꾼다. 16진수는 UUID 문자열 그대로 소문자다. */
    public static String of(UUID id) { return "r_" + id.toString().replace("-", ""); }
    /**
     * run_id 별칭을 실행 id 로 바꾼다. 대문자 16진수도 받는다. 접두어, 길이, 문자 규칙이 다르면 400 이 아니라
     * 없는 실행과 같은 EXECUTION_NOT_FOUND 를 던져 GET /api/traces/{runId} 가 404 로 답하게 한다.
     */
    public static UUID parse(String runId) {
        if(!runId.matches("r_[a-fA-F0-9]{32}"))throw new AgentException("EXECUTION_NOT_FOUND");
        String raw=runId.substring(2);String id=raw.substring(0,8)+"-"+raw.substring(8,12)+"-"+raw.substring(12,16)+"-"+raw.substring(16,20)+"-"+raw.substring(20);
        return UUID.fromString(id);
    }
}
