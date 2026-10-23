package com.capstone4.skillops.snapshot;

import static com.capstone4.skillops.snapshot.SnapshotTestSupport.assertFailure;
import static com.capstone4.skillops.snapshot.SnapshotTestSupport.failure;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.client.ExpectedCount.once;
import static org.springframework.test.web.client.ExpectedCount.times;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withException;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import java.net.ConnectException;
import java.time.Duration;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

/**
 * service/src/main/java/com/capstone4/skillops/snapshot/OrderAgentClient.java 의 주문 Agent snapshot 조회를 보호한다.
 * 요청 주소와 path query 인코딩, UUID 사전 검사, 재시도 횟수(MAX_ATTEMPTS)와 재시도할 실패(5xx, 429, 연결 오류), 오류 본문의
 * code 와 message 보존, utf-8 이 아닌 파일 거절, 기다리는 중 인터럽트 처리를 고정한다. HTTP 는 MockRestServiceServer 로
 * 대신하고, 재시도 사이 대기는 Duration.ZERO 로 없앤다.
 */
class OrderAgentClientTest {

    static final String BASE = "http://order-agent";
    static final String SNAPSHOT = "11111111-2222-3333-4444-555555555555";
    static final String MANIFEST_URL = BASE + "/api/snapshots/" + SNAPSHOT;
    static final String SKILL_MD = "skills/payment/SKILL.md";
    /** SKILL_MD 를 path query 로 인코딩한 파일 조회 주소다. */
    static final String FILE_URL = MANIFEST_URL + "/files?path=skills%2Fpayment%2FSKILL.md";
    /** 주문 Agent manifest 응답 예시다. 파일 두 개를 이 순서로 담는다. */
    static final String MANIFEST_JSON = """
            {"snapshot_id":"11111111-2222-3333-4444-555555555555","hash":"sha256:snap",
             "created_at":"2026-10-20T01:02:03Z","metadata":{"label":"baseline"},
             "files":[{"path":"skills/payment/SKILL.md","hash":"sha256:pay","size":120},
                      {"path":"skills/payment/resources/policy.md","hash":"sha256:policy","size":7}]}
            """;

    RestClient.Builder builder = RestClient.builder().baseUrl(BASE);
    MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
    OrderAgentClient client = new OrderAgentClient(builder, Duration.ZERO);

    @Test
    void getSnapshotReadsSnakeCaseManifest() {
        server.expect(once(), requestTo(MANIFEST_URL)).andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess(MANIFEST_JSON, MediaType.APPLICATION_JSON));

        SnapshotManifest manifest = client.getSnapshot(SNAPSHOT);

        assertThat(manifest.snapshotId()).isEqualTo(SNAPSHOT);
        assertThat(manifest.hash()).isEqualTo("sha256:snap");
        assertThat(manifest.createdAt()).isEqualTo("2026-10-20T01:02:03Z");
        assertThat(manifest.metadata()).isEqualTo(Map.of("label", "baseline"));
        assertThat(manifest.files()).containsExactly(
                new SnapshotManifest.FileEntry("skills/payment/SKILL.md", "sha256:pay", 120),
                new SnapshotManifest.FileEntry("skills/payment/resources/policy.md", "sha256:policy", 7));
        server.verify();
    }

    @Test
    void nonUuidSnapshotIdIsRejectedWithoutCalling() {
        assertFailure(failure(() -> client.getSnapshot("not-a-uuid")),
                "INVALID_INPUT", "snapshot_id 는 UUID 여야 합니다: not-a-uuid", false, null, "not-a-uuid");
        assertFailure(failure(() -> client.getSnapshot(null)),
                "INVALID_INPUT", "snapshot_id 는 UUID 여야 합니다: null", false, null, null);
        assertFailure(failure(() -> client.readFile("../etc", SKILL_MD)),
                "INVALID_INPUT", "snapshot_id 는 UUID 여야 합니다: ../etc", false, null, "../etc");
        assertFailure(failure(() -> client.readText("x", SKILL_MD)),
                "INVALID_INPUT", "snapshot_id 는 UUID 여야 합니다: x", false, null, "x");
        server.verify();
    }

    /** UUID.fromString 은 느슨해서 1-1-1-1-1 도 통과한다. 엄격한 정규식으로 바꾸지 않는다. */
    @Test
    void lenientUuidIsSentAsIs() {
        server.expect(once(), requestTo(BASE + "/api/snapshots/1-1-1-1-1"))
                .andRespond(withSuccess(MANIFEST_JSON, MediaType.APPLICATION_JSON));

        assertThat(client.getSnapshot("1-1-1-1-1").hash()).isEqualTo("sha256:snap");
        server.verify();
    }

    @Test
    void notFoundKeepsOrderAgentCodeAndMessageWithoutRetry() {
        server.expect(once(), requestTo(MANIFEST_URL))
                .andRespond(withStatus(HttpStatus.NOT_FOUND).contentType(MediaType.APPLICATION_JSON)
                        .body("{\"code\":\"SNAPSHOT_NOT_FOUND\",\"message\":\"snapshot 을 찾을 수 없습니다.\"}"));

        assertFailure(failure(() -> client.getSnapshot(SNAPSHOT)),
                "SNAPSHOT_NOT_FOUND", "snapshot 을 찾을 수 없습니다.", false, 404, SNAPSHOT);
        server.verify();
    }

    @Test
    void clientErrorWithoutBodyUsesStatusCode() {
        server.expect(once(), requestTo(MANIFEST_URL)).andRespond(withStatus(HttpStatus.CONFLICT));

        assertFailure(failure(() -> client.getSnapshot(SNAPSHOT)),
                "ORDER_AGENT_HTTP_409", "주문 Agent 가 409 를 돌려주었습니다.", false, 409, SNAPSHOT);
        server.verify();
    }

    @Test
    void messageOnlyBodyKeepsStatusCode() {
        server.expect(once(), requestTo(MANIFEST_URL))
                .andRespond(withStatus(HttpStatus.BAD_REQUEST).contentType(MediaType.APPLICATION_JSON)
                        .body("{\"message\":\"path 가 manifest 에 없습니다.\"}"));

        assertFailure(failure(() -> client.getSnapshot(SNAPSHOT)),
                "ORDER_AGENT_HTTP_400", "path 가 manifest 에 없습니다.", false, 400, SNAPSHOT);
        server.verify();
    }

    @Test
    void serverErrorIsRetriedThreeTimes() {
        server.expect(times(3), requestTo(MANIFEST_URL))
                .andRespond(withStatus(HttpStatus.SERVICE_UNAVAILABLE).contentType(MediaType.TEXT_PLAIN)
                        .body("Service Unavailable"));

        assertFailure(failure(() -> client.getSnapshot(SNAPSHOT)),
                "ORDER_AGENT_HTTP_503", "주문 Agent 가 503 를 돌려주었습니다.", true, 503, SNAPSHOT);
        server.verify();
    }

    @Test
    void serverErrorThenSuccessReturnsManifest() {
        server.expect(once(), requestTo(MANIFEST_URL)).andRespond(withStatus(HttpStatus.SERVICE_UNAVAILABLE));
        server.expect(once(), requestTo(MANIFEST_URL)).andRespond(withSuccess(MANIFEST_JSON, MediaType.APPLICATION_JSON));

        assertThat(client.getSnapshot(SNAPSHOT).snapshotId()).isEqualTo(SNAPSHOT);
        server.verify();
    }

    @Test
    void tooManyRequestsIsRetriedThreeTimes() {
        server.expect(times(3), requestTo(MANIFEST_URL))
                .andRespond(withStatus(HttpStatus.TOO_MANY_REQUESTS).contentType(MediaType.APPLICATION_JSON)
                        .body("{\"code\":\"RATE_LIMITED\",\"message\":\"잠시 뒤 다시 부르세요.\"}"));

        assertFailure(failure(() -> client.getSnapshot(SNAPSHOT)),
                "RATE_LIMITED", "잠시 뒤 다시 부르세요.", true, 429, SNAPSHOT);
        server.verify();
    }

    /** code 가 객체면 asString() 이 실패해 본문을 버리고 상태 코드로만 알린다. */
    @Test
    void containerCodeFallsBackToStatusCode() {
        server.expect(times(3), requestTo(MANIFEST_URL))
                .andRespond(withStatus(HttpStatus.INTERNAL_SERVER_ERROR).contentType(MediaType.APPLICATION_JSON)
                        .body("{\"code\":{\"x\":1},\"message\":\"무시되는 글\"}"));

        assertFailure(failure(() -> client.getSnapshot(SNAPSHOT)),
                "ORDER_AGENT_HTTP_500", "주문 Agent 가 500 를 돌려주었습니다.", true, 500, SNAPSHOT);
        server.verify();
    }

    @Test
    void connectFailureIsRetriedThreeTimesThenUnavailable() {
        server.expect(times(3), requestTo(MANIFEST_URL)).andRespond(withException(new ConnectException("Connection refused")));

        OrderAgentException e = failure(() -> client.getSnapshot(SNAPSHOT));

        assertThat(e.code()).isEqualTo("ORDER_AGENT_UNAVAILABLE");
        assertThat(e.getMessage()).startsWith("주문 Agent 에 연결하지 못했습니다: ").contains("Connection refused");
        assertThat(e.retryable()).isTrue();
        assertThat(e.upstreamStatus()).isNull();
        assertThat(e.snapshotId()).isEqualTo(SNAPSHOT);
        server.verify();
    }

    @Test
    void connectFailureThenSuccessReturnsManifest() {
        server.expect(once(), requestTo(MANIFEST_URL)).andRespond(withException(new ConnectException("Connection refused")));
        server.expect(once(), requestTo(MANIFEST_URL)).andRespond(withSuccess(MANIFEST_JSON, MediaType.APPLICATION_JSON));

        assertThat(client.getSnapshot(SNAPSHOT).hash()).isEqualTo("sha256:snap");
        server.verify();
    }

    @Test
    void connectFailureThenServerErrorStopsAtThirdAttempt() {
        server.expect(once(), requestTo(MANIFEST_URL)).andRespond(withException(new ConnectException("Connection refused")));
        server.expect(times(2), requestTo(MANIFEST_URL)).andRespond(withStatus(HttpStatus.BAD_GATEWAY));

        assertFailure(failure(() -> client.getSnapshot(SNAPSHOT)),
                "ORDER_AGENT_HTTP_502", "주문 Agent 가 502 를 돌려주었습니다.", true, 502, SNAPSHOT);
        server.verify();
    }

    @Test
    void serverErrorThenConnectFailureStopsAtThirdAttempt() {
        server.expect(times(2), requestTo(MANIFEST_URL)).andRespond(withStatus(HttpStatus.SERVICE_UNAVAILABLE));
        server.expect(once(), requestTo(MANIFEST_URL)).andRespond(withException(new ConnectException("Connection refused")));

        OrderAgentException e = failure(() -> client.getSnapshot(SNAPSHOT));

        assertThat(e.code()).isEqualTo("ORDER_AGENT_UNAVAILABLE");
        assertThat(e.retryable()).isTrue();
        assertThat(e.upstreamStatus()).isNull();
        assertThat(e.snapshotId()).isEqualTo(SNAPSHOT);
        server.verify();
    }

    /** 기다리는 중 인터럽트되면 snapshot_id 없이 중단 오류를 올리고 인터럽트 표시를 되살린다. */
    @Test
    void interruptDuringBackoffStopsWithUnavailable() {
        server.expect(once(), requestTo(MANIFEST_URL)).andRespond(withStatus(HttpStatus.SERVICE_UNAVAILABLE));

        Thread.currentThread().interrupt();
        try {
            assertFailure(failure(() -> client.getSnapshot(SNAPSHOT)),
                    "ORDER_AGENT_UNAVAILABLE", "조회가 중단되었습니다.", true, null, null);
            assertThat(Thread.currentThread().isInterrupted()).isTrue();
        } finally {
            Thread.interrupted();
        }
        server.verify();
    }

    @Test
    void readFileSendsPathAsEncodedQuery() {
        server.expect(once(), requestTo(FILE_URL)).andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess(fileJson(SKILL_MD, "utf-8", "---\\nname: payment\\n---\\n"), MediaType.APPLICATION_JSON));

        SnapshotFile file = client.readFile(SNAPSHOT, SKILL_MD);

        assertThat(file).isEqualTo(new SnapshotFile(SNAPSHOT, SKILL_MD, "sha256:pay", 12, "utf-8", "---\nname: payment\n---\n"));
        server.verify();
    }

    @Test
    void readFileEncodesSpacesAndAmpersands() {
        server.expect(once(), requestTo(MANIFEST_URL + "/files?path=skills%2Fx%2Fresources%2Fa%20b%26c.md"))
                .andRespond(withSuccess(fileJson("skills/x/resources/a b&c.md", "utf-8", "x"), MediaType.APPLICATION_JSON));

        assertThat(client.readFile(SNAPSHOT, "skills/x/resources/a b&c.md").content()).isEqualTo("x");
        server.verify();
    }

    @Test
    void readFileRetriesLikeGetSnapshot() {
        server.expect(times(3), requestTo(FILE_URL)).andRespond(withStatus(HttpStatus.SERVICE_UNAVAILABLE));

        assertFailure(failure(() -> client.readFile(SNAPSHOT, SKILL_MD)),
                "ORDER_AGENT_HTTP_503", "주문 Agent 가 503 를 돌려주었습니다.", true, 503, SNAPSHOT);
        server.verify();
    }

    @Test
    void readTextReturnsUtf8ContentIgnoringCase() {
        server.expect(once(), requestTo(FILE_URL))
                .andRespond(withSuccess(fileJson(SKILL_MD, "utf-8", "본문"), MediaType.APPLICATION_JSON));
        server.expect(once(), requestTo(FILE_URL))
                .andRespond(withSuccess(fileJson(SKILL_MD, "UTF-8", "대문자"), MediaType.APPLICATION_JSON));

        assertThat(client.readText(SNAPSHOT, SKILL_MD)).isEqualTo("본문");
        assertThat(client.readText(SNAPSHOT, SKILL_MD)).isEqualTo("대문자");
        server.verify();
    }

    @Test
    void readTextRejectsBase64() {
        server.expect(once(), requestTo(FILE_URL))
                .andRespond(withSuccess(fileJson(SKILL_MD, "base64", "AAEC"), MediaType.APPLICATION_JSON));

        assertFailure(failure(() -> client.readText(SNAPSHOT, SKILL_MD)),
                "SKILL_FILE_NOT_TEXT", SKILL_MD + " 가 UTF-8 글이 아닙니다(base64).", false, null, SNAPSHOT);
        server.verify();
    }

    @Test
    void readTextRejectsMissingEncoding() {
        server.expect(once(), requestTo(FILE_URL)).andRespond(withSuccess(
                "{\"snapshot_id\":\"" + SNAPSHOT + "\",\"path\":\"" + SKILL_MD + "\",\"hash\":\"h\",\"size\":1,\"content\":\"x\"}",
                MediaType.APPLICATION_JSON));

        assertFailure(failure(() -> client.readText(SNAPSHOT, SKILL_MD)),
                "SKILL_FILE_NOT_TEXT", SKILL_MD + " 가 UTF-8 글이 아닙니다(null).", false, null, SNAPSHOT);
        server.verify();
    }

    @Test
    void readTextPassesFileErrorsThrough() {
        server.expect(once(), requestTo(FILE_URL))
                .andRespond(withStatus(HttpStatus.NOT_FOUND).contentType(MediaType.APPLICATION_JSON)
                        .body("{\"code\":\"FILE_NOT_FOUND\",\"message\":\"파일이 없습니다.\"}"));

        assertFailure(failure(() -> client.readText(SNAPSHOT, SKILL_MD)),
                "FILE_NOT_FOUND", "파일이 없습니다.", false, 404, SNAPSHOT);
        server.verify();
    }

    @Test
    void fileEntriesKeepManifestOrder() {
        server.expect(once(), requestTo(MANIFEST_URL)).andRespond(withSuccess(MANIFEST_JSON, MediaType.APPLICATION_JSON));

        List<String> paths = client.getSnapshot(SNAPSHOT).files().stream().map(SnapshotManifest.FileEntry::path).toList();

        assertThat(paths).containsExactly("skills/payment/SKILL.md", "skills/payment/resources/policy.md");
    }

    /** 주문 Agent 파일 조회 응답 JSON 을 만든다. content 는 JSON 글 안에 그대로 넣으므로 줄바꿈은 이스케이프한 글로 넘긴다. */
    private static String fileJson(String path, String encoding, String content) {
        return "{\"snapshot_id\":\"" + SNAPSHOT + "\",\"path\":\"" + path + "\",\"hash\":\"sha256:pay\",\"size\":12,"
                + "\"encoding\":\"" + encoding + "\",\"content\":\"" + content + "\"}";
    }
}
