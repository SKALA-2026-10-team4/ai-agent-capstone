package com.capstone4.skillops.scenario;

import java.net.http.HttpClient;
import java.net.http.HttpConnectTimeoutException;
import java.net.http.HttpTimeoutException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Supplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * C-2 가 주문 Agent(order-agent/)에서 시험 주문을 돌릴 때 쓰는 HTTP. C-1 의 조회 전용 OrderAgentClient 와 따로 둔다.
 * 후보 snapshot 올리기(POST /api/snapshots/candidates), 모의 실행(POST /api/dry-runs), 실행 기록 읽기, 실패했던 주문 꺼내기.
 * 재시도하지 않는다. 모의 실행은 도는 동안 재고를 잡으므로, 응답이 늦었다고 같은 주문을 또 보내지 않으려는 것이다.
 * 주문 Agent 가 4xx 로 거절하면 {@link Rejected}(IllegalArgumentException, 오류 본문의 code 포함),
 * 연결 실패·5xx 면 "연결할 수 없습니다", 응답 시간 초과면 "응답이 늦습니다" 로 알리는 IllegalStateException 이다.
 */
@Component
public class OrderAgentRunClient {

    /** 하이픈 있는 UUID 모양의 실행 번호(execution_id) */
    static final Pattern EXECUTION_ID = Pattern.compile(
            "[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}");
    /** run_id 별칭. r_ + 하이픈을 뺀 execution_id(주문 Agent EvidenceController 와 같은 규칙) */
    private static final Pattern RUN_ID = Pattern.compile("r_([0-9a-fA-F]{32})");
    private static final ParameterizedTypeReference<Map<String, Object>> MAP = new ParameterizedTypeReference<>() {
    };

    /** 연결을 맺는 데 기다리는 시간 */
    static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(2);
    /**
     * 응답을 기다리는 시간. 주문 Agent 의 실행 한도 order-agent.execution-timeout-ms(45000, order-agent application.yml)보다 길어야 한다.
     * 짧으면 느리지만 멀쩡한 모의 실행을 여기서 먼저 끊고, 주문 Agent 는 계속 돌며 재고를 잡고 있다.
     */
    static final Duration READ_TIMEOUT = Duration.ofSeconds(60);

    /** 모의 실행 응답 가운데 쓰는 칸. FAILED 로 끝난 실행도 HTTP 200 이다. */
    public record DryRunStarted(String executionId, String runId, String finalStatus) {
    }

    /** 주문 Agent 가 요청을 4xx 로 거절했다. code 는 오류 본문의 code, 없으면 null. */
    public static final class Rejected extends IllegalArgumentException {

        private final int status;
        private final String code;

        Rejected(String message, int status, String code) {
            super(message);
            this.status = status;
            this.code = code;
        }

        public int status() {
            return status;
        }

        public String code() {
            return code;
        }

        /** 주문 Agent 에 그 snapshot 이 없다(주문 Agent DB 를 비우고 다시 켰을 때). */
        boolean snapshotNotFound() {
            return status == 404 && "SNAPSHOT_NOT_FOUND".equals(code);
        }
    }

    private final RestClient http;
    private final String baseUrl;
    private final JsonMapper json = JsonMapper.builder().build();

    @Autowired
    public OrderAgentRunClient(@Value("${skillops.order-agent.base-url}") String baseUrl) {
        this(RestClient.builder().baseUrl(baseUrl).requestFactory(requestFactory()), baseUrl);
    }

    /** 시험에서는 MockRestServiceServer 를 묶은 builder 를 넘긴다. baseUrl 은 오류 문구에만 쓴다. */
    OrderAgentRunClient(RestClient.Builder builder, String baseUrl) {
        this.http = builder.build();
        this.baseUrl = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
    }

    /**
     * 파일 묶음을 후보 snapshot 으로 올린다. 경로는 skills/ 로 시작해야 한다(검사는 주문 Agent 가 한다).
     *
     * @return 새 snapshot_id
     */
    public String publishCandidate(Map<String, String> files, Map<String, Object> metadata) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("files", files);
        body.put("metadata", metadata);
        String path = "/api/snapshots/candidates";
        Map<String, Object> manifest = call("후보 snapshot 올리기", path, () -> http.post().uri(path)
                .contentType(MediaType.APPLICATION_JSON).body(body).retrieve().body(MAP));
        return required(manifest, "snapshot_id", path);
    }

    /** 주문 하나를 snapshot 으로 모의 실행한다. 주문 Agent 가 끝나면 재고를 되돌리고, 실행은 live 실패 목록에 나오지 않는다. */
    public DryRunStarted dryRun(String snapshotId, Map<String, Object> order) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("snapshot_id", snapshotId);
        body.put("order", order);
        String path = "/api/dry-runs";
        Map<String, Object> started = call("모의 실행", path, () -> http.post().uri(path)
                .contentType(MediaType.APPLICATION_JSON).body(body).retrieve().body(MAP));
        return new DryRunStarted(required(started, "execution_id", path), required(started, "run_id", path),
                (String) started.get("final_status"));
    }

    /** 실행 기록 전체(GET /api/executions/{id}/trace). 없는 실행이면 {@link Rejected}(404). */
    public Map<String, Object> trace(String executionId) {
        return call("실행 기록 읽기", "/api/executions/" + executionId + "/trace",
                () -> http.get().uri("/api/executions/{id}/trace", executionId).retrieve().body(MAP));
    }

    /**
     * 실패했던 실행의 원래 주문. 실행 기록의 order_id 로 주문을 읽어 모의 실행에 넣을 칸만 돌려준다:
     * {customer, items[{sku, qty}], payment_method, shipping{country, express}}.
     *
     * @param executionIdOrRunId execution_id(UUID) 또는 run_id(r_ + 32자리 16진수)
     * @throws IllegalArgumentException 번호 모양이 틀렸거나 주문 Agent 에 그 실행·주문이 없을 때
     */
    public Map<String, Object> originalOrder(String executionIdOrRunId) {
        String executionId = executionIdOf(executionIdOrRunId);
        if (executionId == null) {
            throw new IllegalArgumentException("재현할 실행 번호는 UUID 나 r_ 와 32자리 16진수(run_id) 여야 합니다: "
                    + executionIdOrRunId);
        }
        Map<String, Object> order;
        try {
            Object orderId = trace(executionId).get("order_id");
            if (!(orderId instanceof String id)) {
                throw notFound(executionIdOrRunId, null);
            }
            order = call("주문 읽기", "/api/orders/" + id, () -> http.get().uri("/api/orders/{id}", id).retrieve().body(MAP));
        } catch (Rejected e) {
            if (e.status() == 404) {
                throw notFound(executionIdOrRunId, e);
            }
            throw e;
        }

        Map<String, Object> original = new LinkedHashMap<>();
        original.put("customer", order.get("customer"));
        original.put("items", listOfMaps(order.get("items")).stream().map(item -> {
            Map<String, Object> line = new LinkedHashMap<>();
            line.put("sku", item.get("sku"));
            line.put("qty", item.get("qty"));
            return line;
        }).toList());
        original.put("payment_method", order.get("payment_method"));
        Map<String, Object> shipping = order.get("shipping") instanceof Map<?, ?> s ? toStringKeys(s) : Map.of();
        Map<String, Object> destination = new LinkedHashMap<>();
        destination.put("country", shipping.get("country"));
        destination.put("express", shipping.get("express"));
        original.put("shipping", destination);
        return original;
    }

    /** execution_id(UUID) 나 run_id(r_ + 32자리 16진수)를 하이픈 있는 소문자 UUID 로 바꾼다. 둘 다 아니면 null. */
    static String executionIdOf(String executionIdOrRunId) {
        if (executionIdOrRunId == null) {
            return null;
        }
        if (EXECUTION_ID.matcher(executionIdOrRunId).matches()) {
            return executionIdOrRunId.toLowerCase(Locale.ROOT);
        }
        Matcher run = RUN_ID.matcher(executionIdOrRunId);
        if (!run.matches()) {
            return null;
        }
        String hex = run.group(1).toLowerCase(Locale.ROOT);
        return hex.substring(0, 8) + "-" + hex.substring(8, 12) + "-" + hex.substring(12, 16) + "-"
                + hex.substring(16, 20) + "-" + hex.substring(20);
    }

    private static IllegalArgumentException notFound(String executionIdOrRunId, Rejected cause) {
        IllegalArgumentException e = new IllegalArgumentException(
                "재현할 실행 " + executionIdOrRunId + " 을 주문 Agent 에서 찾을 수 없습니다");
        if (cause != null) {
            e.initCause(cause);
        }
        return e;
    }

    /** 4xx 는 {@link Rejected}, 5xx·연결 실패·시간 초과는 IllegalStateException 으로 바꾼다. */
    private <T> T call(String action, String path, Supplier<T> request) {
        String url = baseUrl + path;
        try {
            T body = request.get();
            if (body == null) {
                throw new IllegalStateException("주문 Agent 의 " + action + " 응답이 비어 있습니다: " + url);
            }
            return body;
        } catch (RestClientResponseException e) {
            int status = e.getStatusCode().value();
            JsonNode error = errorBody(e);
            String code = text(error, "code");
            if (status >= 400 && status < 500) {
                throw new Rejected(rejectedMessage(action, status, code, error), status, code);
            }
            throw new IllegalStateException(unreachable(url, "HTTP " + status + (code == null ? "" : " " + code)), e);
        } catch (ResourceAccessException e) {
            if (isReadTimeout(e)) {
                throw new IllegalStateException(slow(url), e);
            }
            throw new IllegalStateException(unreachable(url, causeOf(e)), e);
        }
    }

    /**
     * 연결은 됐는데 응답이 시간 안에 오지 않았다. JDK HttpClient 는 이때 HttpTimeoutException 을 내고,
     * 연결 시간 초과는 그 하위 HttpConnectTimeoutException 이다. 연결 시간 초과는 닿지 않은 것으로 본다.
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

    private static String slow(String url) {
        return "주문 Agent 의 응답이 늦습니다: " + url + " (" + READ_TIMEOUT.toSeconds() + "초 안에 답이 없음)";
    }

    private static String unreachable(String url, String cause) {
        return "주문 Agent 에 연결할 수 없습니다: " + url + " (" + cause + ")";
    }

    /** 예: 주문 Agent 가 모의 실행을 거절했습니다: PRODUCT_NOT_FOUND (HTTP 404) 선택한 상품을 찾을 수 없습니다. */
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

    /** 가장 안쪽 원인. 예: ConnectException: Connection refused, HttpTimeoutException: request timed out */
    private static String causeOf(Throwable e) {
        Throwable root = e;
        while (root.getCause() != null && root.getCause() != root) {
            root = root.getCause();
        }
        String name = root.getClass().getSimpleName();
        return root.getMessage() == null || root.getMessage().isBlank() ? name : name + ": " + root.getMessage();
    }

    private JsonNode errorBody(RestClientResponseException e) {
        try {
            return json.readTree(e.getResponseBodyAsString());
        } catch (RuntimeException ignored) {
            // 본문이 JSON 이 아니면 상태 코드로만 알린다.
            return null;
        }
    }

    private static String text(JsonNode node, String field) {
        return node != null && node.hasNonNull(field) ? node.get(field).asString() : null;
    }

    private static String required(Map<String, Object> body, String field, String path) {
        if (body.get(field) instanceof String value && !value.isBlank()) {
            return value;
        }
        throw new IllegalStateException("주문 Agent 의 " + path + " 응답에 " + field + " 가 없습니다");
    }

    private static List<Map<String, Object>> listOfMaps(Object value) {
        if (!(value instanceof List<?> list)) {
            return List.of();
        }
        return list.stream().filter(Map.class::isInstance).map(m -> toStringKeys((Map<?, ?>) m)).toList();
    }

    /** 값이 null 인 칸도 그대로 둔다(Collectors.toMap 은 null 값을 받지 않는다). */
    private static Map<String, Object> toStringKeys(Map<?, ?> map) {
        Map<String, Object> copy = new LinkedHashMap<>();
        map.forEach((key, value) -> copy.put(String.valueOf(key), value));
        return copy;
    }

    private static JdkClientHttpRequestFactory requestFactory() {
        HttpClient client = HttpClient.newBuilder().connectTimeout(CONNECT_TIMEOUT).build();
        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(client);
        factory.setReadTimeout(READ_TIMEOUT);
        return factory;
    }
}
