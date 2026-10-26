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
 * 주문 Agent(order-agent/)의 snapshot 조회 API 를 HTTP 로 부르는 읽기 전용 클라이언트다. 저장이나 변경은 하지 않는다.
 * GET /api/snapshots/{id} 로 manifest 를, GET /api/snapshots/{id}/files?path= 로 파일 하나를 읽는다. 두 경로는
 * order-agent/backend/src/main/java/com/capstone4/orderagent/controller/EvidenceController.java 의 snapshot 과 file 이 받는다.
 * 실패는 빈 결과로 바꾸지 않고 OrderAgentException 으로 올린다.
 *
 * 재시도는 주문 Agent 명세(order-agent/docs/mcp-order-api-draft.md 5장)대로 연결 오류, 5xx, 429 에만 한다. 명세가 권하는
 * Retry-After 헤더는 보지 않고 정해진 간격만 기다린다. 튜닝 값은 MAX_ATTEMPTS, RETRY_BACKOFF, CONNECT_TIMEOUT, READ_TIMEOUT
 * 상수와 재시도할 상태를 정하는 isRetryable 에 모여 있다. 모의 실행을 맡는
 * service/src/main/java/com/capstone4/skillops/scenario/OrderAgentRunClient.java 는 재고를 잡는 요청이라 재시도하지 않고 응답을
 * 60초 기다리므로, 두 클라이언트의 튜닝 값은 서로 다르다. 진단 Agent 의 skillops-agent/skillops_agent/order_agent.py 는 주문
 * Agent 의 다른 조회 API 를 같은 재시도 규칙(3번 시도, 0.3초 단위 대기, 연결 오류와 5xx, 429)으로 부른다.
 *
 * 주소는 service/src/main/resources/application.yml 의 skillops.order-agent.base-url(환경 변수 ORDER_AGENT_BASE_URL)이다.
 * 같은 패키지(service/src/main/java/com/capstone4/skillops/snapshot/)의 SkillDiffService 와 DependencyGraphService, 그리고
 * service/src/main/java/com/capstone4/skillops/workspace/WorkspaceService.java 의 createFromSnapshot(create_workspace 를
 * snapshot_id 로 시작할 때)이 이 클래스를 부른다. save_proposal 재판정
 * (service/src/main/java/com/capstone4/skillops/proposal/ProposalJudge.java)도 getSnapshot 으로 진단한 snapshot 의 metadata
 * (담당자 의도 intent, baseline_snapshot_id)와 기준본 manifest 를 읽는다.
 */
@Component
public class OrderAgentClient {

    /** 한 요청을 보내는 최대 횟수다. 첫 시도를 포함하므로 재시도는 최대 두 번이다. */
    static final int MAX_ATTEMPTS = 3;
    /** 재시도 간격의 단위다. n 번째 실패 뒤 RETRY_BACKOFF 의 n 배만큼 기다린다. */
    static final Duration RETRY_BACKOFF = Duration.ofMillis(300);
    /** 주문 Agent 에 연결될 때까지 기다리는 시간이다. 넘으면 연결 오류로 보고 재시도한다. */
    static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(2);
    /** 주문 Agent 의 응답을 기다리는 시간이다. 넘으면 연결 오류와 같이 ORDER_AGENT_UNAVAILABLE 로 보고 재시도한다. */
    static final Duration READ_TIMEOUT = Duration.ofSeconds(5);

    private final RestClient http;
    /** 재시도 사이에 기다리는 단위 시간이다. 운영은 RETRY_BACKOFF 를, 테스트는 Duration.ZERO 를 넣는다. */
    private final Duration backoff;
    /** 오류 응답 본문의 code 와 message 를 읽는 매퍼다. */
    private final JsonMapper json = JsonMapper.builder().build();

    /**
     * Spring 이 쓰는 생성자다. 설정 skillops.order-agent.base-url 을 주소로 삼고, CONNECT_TIMEOUT 과 READ_TIMEOUT 을 건 JDK
     * HttpClient 로 RestClient 를 만든다. 생성자가 둘이라 Autowired 로 이쪽을 고른다.
     */
    @Autowired
    public OrderAgentClient(@Value("${skillops.order-agent.base-url}") String baseUrl) {
        this(RestClient.builder().baseUrl(baseUrl).requestFactory(requestFactory()), RETRY_BACKOFF);
    }

    /**
     * builder 로 RestClient 를 만들고 재시도 간격을 받는 생성자다. Spring 용 생성자가 RETRY_BACKOFF 를 넣어 이 생성자로
     * 넘기고, service/src/test/java/com/capstone4/skillops/snapshot/OrderAgentClientTest.java 는 MockRestServiceServer 를
     * 묶은 builder 와 기다리지 않는 간격(Duration.ZERO)을 넣는다.
     */
    OrderAgentClient(RestClient.Builder builder, Duration backoff) {
        this.http = builder.build();
        this.backoff = backoff;
    }

    /**
     * snapshot 의 manifest(snapshot_id, hash, created_at, metadata, files)를 읽는다. snapshotId 가 UUID 가 아니면 HTTP 를
     * 보내지 않고 INVALID_INPUT 을 던진다. 주문 Agent 가 snapshot 을 찾지 못하거나(SNAPSHOT_NOT_FOUND) 저장 해시가 맞지
     * 않으면(SNAPSHOT_TAMPERED) 그 code 를 담은 OrderAgentException 이 된다.
     */
    public SnapshotManifest getSnapshot(String snapshotId) {
        requireUuid(snapshotId);
        return call(snapshotId, () -> http.get()
                .uri("/api/snapshots/{id}", snapshotId)
                .retrieve()
                .body(SnapshotManifest.class));
    }

    /**
     * snapshot 안의 파일 하나를 읽는다. path 는 query 값으로 인코딩해서 보낸다. path 는 반드시 같은 snapshot 의 manifest 에
     * 있는 값을 넘기고 사용자 입력으로 경로를 만들지 않는다. 파일이 없으면 주문 Agent 의 SNAPSHOT_FILE_NOT_FOUND 가 그대로
     * 올라간다.
     */
    public SnapshotFile readFile(String snapshotId, String path) {
        requireUuid(snapshotId);
        return call(snapshotId, () -> http.get()
                .uri(b -> b.path("/api/snapshots/{id}/files").queryParam("path", "{path}").build(snapshotId, path))
                .retrieve()
                .body(SnapshotFile.class));
    }

    /**
     * SKILL.md 처럼 글이어야 하는 파일의 내용을 읽는다. encoding 이 utf-8(대소문자 무시)이 아니면, 즉 주문 Agent 가 base64 로
     * 보낸 바이너리이거나 encoding 이 비어 있으면 SKILL_FILE_NOT_TEXT 를 던진다.
     */
    public String readText(String snapshotId, String path) {
        SnapshotFile file = readFile(snapshotId, path);
        if (!"utf-8".equalsIgnoreCase(file.encoding())) {
            throw new OrderAgentException("SKILL_FILE_NOT_TEXT", path + " 가 UTF-8 글이 아닙니다(" + file.encoding() + ").",
                    false, null, snapshotId);
        }
        return file.content();
    }

    /**
     * request 를 보내고, 실패하면 OrderAgentException 으로 바꾼 뒤 다시 보낼지 정한다. 응답 오류는 fromResponse 가, 연결 오류와
     * 시간 초과는 unreachable 이 예외로 바꾼다. retryable 이 아니거나 MAX_ATTEMPTS 번째 시도까지 실패하면 마지막 실패를 던지고,
     * 그 전에는 backoff 의 시도 횟수 배만큼 기다린 뒤 다시 보낸다.
     */
    private <T> T call(String snapshotId, Supplier<T> request) {
        for (int attempt = 1; ; attempt++) {
            OrderAgentException failure;
            try {
                return request.get();
            } catch (RestClientResponseException e) {
                failure = fromResponse(e, snapshotId);
            } catch (ResourceAccessException e) {
                failure = unreachable(e, snapshotId);
            }
            if (!failure.retryable() || attempt >= MAX_ATTEMPTS) {
                throw failure;
            }
            sleep(backoff.multipliedBy(attempt));
        }
    }

    /** 다시 보내면 나아질 수 있는 HTTP 상태(5xx, 429)인지 판단한다. 재시도할 상태를 바꾸려면 여기를 고친다. */
    static boolean isRetryable(int status) {
        return status >= 500 || status == 429;
    }

    /**
     * 주문 Agent 의 HTTP 오류 응답을 OrderAgentException 으로 바꾼다. 본문이 JSON 이고 code 나 message 가 있으면 그 값을
     * 보존하고, 없으면 ORDER_AGENT_HTTP_ 뒤에 상태 코드를 붙인 code 와 기본 문구를 쓴다. upstream_status 에는 HTTP 상태를
     * 싣고 retryable 은 isRetryable 로 정한다.
     */
    private OrderAgentException fromResponse(RestClientResponseException e, String snapshotId) {
        int status = e.getStatusCode().value();
        boolean retryable = isRetryable(status);
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
            // 본문이 JSON 이 아니거나 칸을 글로 읽지 못하면, 그 앞까지 읽은 값만 쓰고 나머지는 상태 코드 기본값으로 알린다.
        }
        return new OrderAgentException(code, message, retryable, status, snapshotId);
    }

    /** 주문 Agent 에 닿지 못한 실패(연결 오류, 시간 초과)를 ORDER_AGENT_UNAVAILABLE 로 바꾼다. 늘 다시 시도할 수 있다. */
    private static OrderAgentException unreachable(ResourceAccessException e, String snapshotId) {
        return new OrderAgentException("ORDER_AGENT_UNAVAILABLE",
                "주문 Agent 에 연결하지 못했습니다: " + e.getMessage(), true, null, snapshotId);
    }

    /**
     * snapshotId 가 UUID.fromString 으로 읽히지 않으면 HTTP 를 보내지 않고 INVALID_INPUT 을 던진다. 주소에 들어갈 값을
     * 보내기 전에 막아서 잘못된 입력이 주문 Agent 응답 오류와 섞이지 않는다. UUID.fromString 은 1-1-1-1-1 같은 느슨한 모양도
     * 받는다.
     */
    private static void requireUuid(String snapshotId) {
        try {
            UUID.fromString(snapshotId);
        } catch (IllegalArgumentException | NullPointerException e) {
            throw new OrderAgentException("INVALID_INPUT", "snapshot_id 는 UUID 여야 합니다: " + snapshotId,
                    false, null, snapshotId);
        }
    }

    /** CONNECT_TIMEOUT 을 건 JDK HttpClient 에 READ_TIMEOUT 을 더한 요청 팩토리를 만든다. Spring 용 생성자가 쓴다. */
    private static JdkClientHttpRequestFactory requestFactory() {
        HttpClient client = HttpClient.newBuilder().connectTimeout(CONNECT_TIMEOUT).build();
        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(client);
        factory.setReadTimeout(READ_TIMEOUT);
        return factory;
    }

    /**
     * 재시도 전에 d 만큼 기다린다. 기다리는 중 인터럽트되면 인터럽트 표시를 되살리고, snapshot_id 없이
     * ORDER_AGENT_UNAVAILABLE 을 던져 조회를 멈춘다.
     */
    private static void sleep(Duration d) {
        try {
            Thread.sleep(d);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new OrderAgentException("ORDER_AGENT_UNAVAILABLE", "조회가 중단되었습니다.", true, null, null);
        }
    }
}
