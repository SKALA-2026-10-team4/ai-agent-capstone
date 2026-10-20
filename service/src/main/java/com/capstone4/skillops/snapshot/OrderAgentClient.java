package com.capstone4.skillops.snapshot;

import java.net.http.HttpClient;
import java.time.Duration;
import java.util.UUID;
import java.util.function.Supplier;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * 주문 Agent(order-agent/)의 snapshot 조회 API 를 HTTP 로 부른다. 저장·변경은 하지 않는다.
 * 재시도는 주문 Agent 명세(docs/mcp-order-api-draft.md 5장)대로 연결 오류·5xx·429 에만 한다.
 */
@Component
public class OrderAgentClient {

    private static final int MAX_ATTEMPTS = 3;

    private final RestClient http;
    private final Duration backoff;
    private final JsonMapper json = JsonMapper.builder().build();

    @Autowired
    public OrderAgentClient(@Value("${skillops.order-agent.base-url}") String baseUrl) {
        this(RestClient.builder().baseUrl(baseUrl).requestFactory(requestFactory()), Duration.ofMillis(300));
    }

    OrderAgentClient(RestClient.Builder builder, Duration backoff) {
        this.http = builder.build();
        this.backoff = backoff;
    }

    public SnapshotManifest getSnapshot(String snapshotId) {
        requireUuid(snapshotId);
        return call(snapshotId, () -> http.get()
                .uri("/api/snapshots/{id}", snapshotId)
                .retrieve()
                .body(SnapshotManifest.class));
    }

    /** path 는 반드시 같은 snapshot 의 manifest 에 있는 값을 넘긴다. 사용자 입력으로 경로를 만들지 않는다. */
    public SnapshotFile readFile(String snapshotId, String path) {
        requireUuid(snapshotId);
        return call(snapshotId, () -> http.get()
                .uri(b -> b.path("/api/snapshots/{id}/files").queryParam("path", "{path}").build(snapshotId, path))
                .retrieve()
                .body(SnapshotFile.class));
    }

    /** SKILL.md 처럼 글이어야 하는 파일의 내용. base64(바이너리)로 오면 오류로 올린다. */
    public String readText(String snapshotId, String path) {
        SnapshotFile file = readFile(snapshotId, path);
        if (!"utf-8".equalsIgnoreCase(file.encoding())) {
            throw new OrderAgentException("SKILL_FILE_NOT_TEXT", path + " 가 UTF-8 글이 아닙니다(" + file.encoding() + ").",
                    false, null, snapshotId);
        }
        return file.content();
    }

    private <T> T call(String snapshotId, Supplier<T> request) {
        for (int attempt = 1; ; attempt++) {
            try {
                return request.get();
            } catch (RestClientResponseException e) {
                OrderAgentException error = fromResponse(e, snapshotId);
                if (!error.retryable() || attempt >= MAX_ATTEMPTS) {
                    throw error;
                }
            } catch (ResourceAccessException e) {
                if (attempt >= MAX_ATTEMPTS) {
                    throw new OrderAgentException("ORDER_AGENT_UNAVAILABLE",
                            "주문 Agent 에 연결하지 못했습니다: " + e.getMessage(), true, null, snapshotId);
                }
            }
            sleep(backoff.multipliedBy(attempt));
        }
    }

    private OrderAgentException fromResponse(RestClientResponseException e, String snapshotId) {
        int status = e.getStatusCode().value();
        boolean retryable = status >= 500 || status == 429;
        String code = "ORDER_AGENT_HTTP_" + status;
        String message = "주문 Agent 가 " + status + " 를 돌려주었습니다.";
        try {
            JsonNode body = json.readTree(e.getResponseBodyAsString());
            if (body.hasNonNull("code")) {
                code = body.get("code").asString();
            }
            if (body.hasNonNull("message")) {
                message = body.get("message").asString();
            }
        } catch (RuntimeException ignored) {
            // 본문이 JSON 이 아니면 상태 코드로만 알린다.
        }
        return new OrderAgentException(code, message, retryable, status, snapshotId);
    }

    private static void requireUuid(String snapshotId) {
        try {
            UUID.fromString(snapshotId);
        } catch (IllegalArgumentException | NullPointerException e) {
            throw new OrderAgentException("INVALID_INPUT", "snapshot_id 는 UUID 여야 합니다: " + snapshotId,
                    false, null, snapshotId);
        }
    }

    private static JdkClientHttpRequestFactory requestFactory() {
        HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();
        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(client);
        factory.setReadTimeout(Duration.ofSeconds(5));
        return factory;
    }

    private static void sleep(Duration d) {
        try {
            Thread.sleep(d);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new OrderAgentException("ORDER_AGENT_UNAVAILABLE", "조회가 중단되었습니다.", true, null, null);
        }
    }
}
