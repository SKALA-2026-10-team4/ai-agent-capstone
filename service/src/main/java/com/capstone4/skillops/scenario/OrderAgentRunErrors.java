package com.capstone4.skillops.scenario;

import java.net.http.HttpConnectTimeoutException;
import java.net.http.HttpTimeoutException;
import java.util.ArrayList;
import java.util.List;

import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClientResponseException;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * {@link OrderAgentRunClient} 의 HTTP 실패를 C-2 예외와 문구로 바꾸는 규칙이다.
 * 4xx 는 OrderAgentRunClient.Rejected(IllegalArgumentException)로, 5xx 와 연결 실패와 응답 시간 초과는 IllegalStateException 으로 바꾼다.
 * 이 문구는 run_scenarios 오류의 message 로 Agent 에게 그대로 가며,
 * service/src/test/java/com/capstone4/skillops/scenario/OrderAgentRunClientTest.java 가 글자 단위로 고정한다.
 * service/src/main/java/com/capstone4/skillops/scenario/OrderAgentRunClient.java 의 call() 만 이 클래스를 부른다.
 */
final class OrderAgentRunErrors {

    /** 오류 응답 본문을 읽는 JSON 파서다. */
    private static final JsonMapper JSON = JsonMapper.builder().build();

    /** 정적 메서드만 있는 규칙 모음이라 객체를 만들지 않는다. */
    private OrderAgentRunErrors() {
    }

    /**
     * 주문 Agent 가 오류 상태로 답했을 때의 예외를 만든다. 4xx 는 오류 본문의 code 와 문구를 담은 Rejected 이고,
     * 그 밖의 상태는 원인 예외를 단 "연결할 수 없습니다" IllegalStateException 이다.
     */
    static RuntimeException fromResponse(String action, String url, RestClientResponseException e) {
        int status = e.getStatusCode().value();
        JsonNode error = errorBody(e);
        String code = text(error, "code");
        if (status >= 400 && status < 500) {
            return new OrderAgentRunClient.Rejected(rejectedMessage(action, status, code, error), status, code);
        }
        return new IllegalStateException(unreachable(url, "HTTP " + status + (code == null ? "" : " " + code)), e);
    }

    /**
     * 응답을 받지 못했을 때의 예외를 만든다. 응답 시간 초과면 "응답이 늦습니다" 이고,
     * 연결 거부나 연결 시간 초과 같은 그 밖의 경우는 "연결할 수 없습니다" 다.
     */
    static IllegalStateException fromAccess(String url, ResourceAccessException e) {
        if (isReadTimeout(e)) {
            return new IllegalStateException(slow(url), e);
        }
        return new IllegalStateException(unreachable(url, causeOf(e)), e);
    }

    /** 성공 상태인데 본문이 비어 있을 때의 예외를 만든다. */
    static IllegalStateException emptyBody(String action, String url) {
        return new IllegalStateException("주문 Agent 의 " + action + " 응답이 비어 있습니다: " + url);
    }

    /**
     * 연결은 됐는데 응답이 시간 안에 오지 않았으면 true 를 돌려준다. 원인 예외를 안쪽까지 따라가며 본다.
     * JDK HttpClient 는 이때 HttpTimeoutException 을 내고, 연결 시간 초과는 그 하위 클래스인 HttpConnectTimeoutException 이다.
     * 연결 시간 초과는 닿지 않은 것으로 보아 false 다.
     */
    private static boolean isReadTimeout(Throwable e) {
        for (Throwable t = e; t != null; t = t.getCause() == t ? null : t.getCause()) {
            if (t instanceof HttpConnectTimeoutException) {
                return false;
            }
            if (t instanceof HttpTimeoutException) {
                return true;
            }
        }
        return false;
    }

    /** 응답 시간 초과 문구다. OrderAgentRunClient.READ_TIMEOUT 의 초를 함께 적는다. */
    private static String slow(String url) {
        return "주문 Agent 의 응답이 늦습니다: " + url + " (" + OrderAgentRunClient.READ_TIMEOUT.toSeconds() + "초 안에 답이 없음)";
    }

    /** 연결할 수 없다는 문구다. 괄호 안에 HTTP 상태나 가장 안쪽 원인을 적는다. */
    private static String unreachable(String url, String cause) {
        return "주문 Agent 에 연결할 수 없습니다: " + url + " (" + cause + ")";
    }

    /**
     * 거절 문구를 만든다. 동작 이름, code 와 HTTP 상태, 오류 본문의 message, field_errors 목록 순서로 잇는다.
     * 예를 들면 "주문 Agent 가 모의 실행 요청을 거절했습니다: PRODUCT_NOT_FOUND (HTTP 404) 선택한 상품을 찾을 수 없습니다." 가 된다.
     */
    private static String rejectedMessage(String action, int status, String code, JsonNode error) {
        StringBuilder message = new StringBuilder("주문 Agent 가 ").append(action).append(" 요청을 거절했습니다: ")
                .append(code == null ? "HTTP " + status : code + " (HTTP " + status + ")");
        String detail = text(error, "message");
        if (detail != null) {
            message.append(' ').append(detail);
        }
        JsonNode fields = error == null ? null : error.get("field_errors");
        if (fields != null && fields.isArray() && !fields.isEmpty()) {
            List<String> parts = new ArrayList<>();
            fields.forEach(f -> parts.add(text(f, "field") + ": " + text(f, "message")));
            message.append(" [").append(String.join(", ", parts)).append(']');
        }
        return message.toString();
    }

    /**
     * 가장 안쪽 원인을 "클래스 이름: 메시지" 모양으로 돌려준다. 메시지가 비어 있으면 클래스 이름만 쓴다.
     * 예를 들면 ConnectException: Connection refused 나 HttpTimeoutException: request timed out 이다.
     */
    private static String causeOf(Throwable e) {
        Throwable root = e;
        while (root.getCause() != null && root.getCause() != root) {
            root = root.getCause();
        }
        String name = root.getClass().getSimpleName();
        return root.getMessage() == null || root.getMessage().isBlank() ? name : name + ": " + root.getMessage();
    }

    /** 오류 응답 본문을 JSON 으로 읽는다. JSON 이 아니면 null 을 돌려준다. */
    private static JsonNode errorBody(RestClientResponseException e) {
        try {
            return JSON.readTree(e.getResponseBodyAsString());
        } catch (RuntimeException ignored) {
            // 본문이 JSON 이 아니면 상태 코드로만 알린다.
            return null;
        }
    }

    /** node 의 field 칸이 null 이 아닌 값이면 글자로 돌려주고, 아니면 null 을 돌려준다. */
    private static String text(JsonNode node, String field) {
        return node != null && node.hasNonNull(field) ? node.get(field).asString() : null;
    }
}
