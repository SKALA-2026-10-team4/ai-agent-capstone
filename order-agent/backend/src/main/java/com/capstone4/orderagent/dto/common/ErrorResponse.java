package com.capstone4.orderagent.dto.common;

import java.util.*;

/**
 * 주문 Agent 의 모든 HTTP 오류 본문이다. JSON 은 snake_case 로 code, message, order_id, field_errors 를 쓴다.
 * code 는 ErrorCode 이름이나 AgentException 의 category 이고, message 는 사람이 읽는 한국어 문구다.
 * order_id 는 이미 접수된 주문에 걸린 오류(처리 중 실패, 같은 요청 키의 입력 충돌)에서만 채운다. 클라이언트는 새 주문을 넣지 않고
 * 그 주문을 다시 읽는다(frontend/src/api/orderAgent.js 의 createOrder 가 500 대 오류에서 이렇게 한다).
 * field_errors 는 Bean Validation 실패의 필드별 메시지이고 그 밖의 오류에서는 빈 목록이다.
 * order-agent/backend/src/main/java/com/capstone4/orderagent/exception/GlobalExceptionHandler.java 만 이 record 를 만든다.
 * 이 모양은 frontend/src/api/client.js 의 httpError, order-agent/frontend/src/api/client.js 의 readBody,
 * service/src/main/java/com/capstone4/skillops/snapshot/OrderAgentClient.java 의 fromResponse,
 * service/src/main/java/com/capstone4/skillops/scenario/OrderAgentRunErrors.java 의 fromResponse,
 * skillops-agent/skillops_agent/order_agent.py 의 _from_response 가 읽으므로 키를 바꾸면 함께 고친다. 명세는 order-agent/docs/api.md 6장이다.
 */
public record ErrorResponse(String code, String message, UUID orderId, List<FieldError> fieldErrors) {
    /**
     * 필드 하나의 검증 실패다. field 는 Bean Validation 이 알려 준 Java 필드 경로(예: items[0].qty, paymentMethod)라
     * JSON 키의 snake_case 이름과 다를 수 있다. message 는 검증 어노테이션의 기본 문구다.
     */
    public record FieldError(String field, String message) {}
}
