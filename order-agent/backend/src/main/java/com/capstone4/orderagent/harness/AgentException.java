package com.capstone4.orderagent.harness;

import java.util.Map;

/**
 * 주문 Agent 실행과 진단 조회에서 쓰는 오류다. category 는 예외 메시지이자 trace 의 EXECUTION_ERROR data.category,
 * 실행 기록의 error_category 로 남는 기계용 이름이다. details 는 실패 경계를 설명하는 값으로, 예를 들어 CONTRACT_VIOLATION 이면
 * skill_id, phase, missing_inputs, missing_dependencies 를 담는다. category 와 details 는 같은 이름의 접근자로 읽는다.
 * HTTP 요청에서 이 예외가 나가면 order-agent/backend/src/main/java/com/capstone4/orderagent/exception/GlobalExceptionHandler.java 가
 * category 가 NOT_FOUND 로 끝나면 404, 아니면 400 으로 바꾼다.
 * category 이름은 모듈 밖에서도 실패 원인으로 읽으므로 이름을 바꾸기 전에 소비자를 찾아본다. 예를 들어
 * service/src/main/java/com/capstone4/skillops/proposal/ProposalDocumentWriter.java 는 CONTRACT_VIOLATION 을 따로 설명한다.
 */
public class AgentException extends RuntimeException {
    private final String category;
    private final Map<String,Object> details;
    /** details 없이 category 만으로 만든다. */
    public AgentException(String category) { this(category, Map.of()); }
    /** details 를 복사해 바꿀 수 없게 담는다. Map.copyOf 라 null 키나 null 값을 넣으면 NullPointerException 이다. */
    public AgentException(String category, Map<String,Object> details) {
        super(category); this.category=category; this.details=Map.copyOf(details);
    }
    public String category() { return category; }
    public Map<String,Object> details() { return details; }
}
