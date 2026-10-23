package com.capstone4.skillops.diagnosis;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withException;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;
import org.springframework.web.server.ResponseStatusException;

import com.sun.net.httpserver.HttpServer;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * 리팩토링 전 진단 중계 동작을 고정하는 특성 테스트다. 대상은
 * service/src/main/java/com/capstone4/skillops/diagnosis/DiagnosisController.java 다.
 * 서비스 토큰과 저장 이벤트 검사의 순서와 예외, Agent 로 넘기는 중계, Agent 오류를 404 나 502 ApiError 로 바꾸는 규칙, 공개 생성자가
 * 만드는 실제 HTTP 요청의 모양(HTTP/1.1, 업그레이드 헤더 없음, 언제나 붙는 서비스 토큰 헤더)을 본다.
 */
class DiagnosisControllerCharacterizationTest {

    static final JsonMapper JSON = JsonMapper.builder().build();
    // 검사를 통과하는 가장 작은 Skill 저장 이벤트다.
    static final Map<String, Object> SAVED = Map.of("type", "skill_saved", "change_id", "change");

    /** 기대하는 {code, message} 본문을 만든다. */
    static JsonNode error(String code, String message) {
        return JSON.createObjectNode().put("code", code).put("message", message);
    }

    /** 값을 JSON 트리로 바꾼다. */
    static JsonNode tree(Object value) {
        return JSON.valueToTree(value);
    }

    /** 처리기가 돌려준 응답의 상태와 ApiError 본문이 기대와 같은지 본다. */
    static void assertError(ResponseEntity<?> response, int status, String code, String message) {
        assertThat(response.getStatusCode().value()).isEqualTo(status);
        assertThat(tree(response.getBody())).isEqualTo(error(code, message));
    }

    /** 던진 예외가 정확히 ResponseStatusException 이고 상태와 reason 이 기대와 같은지 본다. */
    static void assertStatusException(Throwable thrown, int status, String reason) {
        assertThat(thrown).isExactlyInstanceOf(ResponseStatusException.class);
        ResponseStatusException e = (ResponseStatusException) thrown;
        assertThat(e.getStatusCode().value()).isEqualTo(status);
        assertThat(e.getReason()).isEqualTo(reason);
    }

    // ── 들어오는 저장 이벤트 검사 ─────────────────────────────────────────

    @Test
    void blankServiceTokenAcceptsAnyHeader() {
        RestClient.Builder builder = RestClient.builder().baseUrl("http://agent");
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        DiagnosisController controller = new DiagnosisController(builder.build(), " ");
        server.expect(requestTo("http://agent/diagnose")).andExpect(method(HttpMethod.POST))
                .andRespond(withSuccess("{\"thread_id\":\"t1\"}", MediaType.APPLICATION_JSON));

        assertThat(controller.changed("anything", SAVED).get("thread_id")).isEqualTo("t1");
        server.verify();
    }

    @Test
    void tokenIsCheckedBeforeTheEventAndNothingIsForwarded() {
        RestClient.Builder builder = RestClient.builder().baseUrl("http://agent");
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        DiagnosisController controller = new DiagnosisController(builder.build(), "secret");
        Map<String, Object> noChangeId = new HashMap<>();
        noChangeId.put("type", "skill_saved");
        noChangeId.put("change_id", null);

        assertStatusException(catchThrown(() -> controller.changed("wrong", Map.of("type", "other"))), 401, null);
        assertStatusException(catchThrown(() -> controller.changed("", SAVED)), 401, null);
        assertStatusException(catchThrown(() -> controller.changed("secret", Map.of("type", "other", "change_id", "c"))),
                400, "A saved change is required");
        assertStatusException(catchThrown(() -> controller.changed("secret", Map.of("type", "skill_saved"))), 400,
                "A saved change is required");
        assertStatusException(catchThrown(() -> controller.changed("secret", noChangeId)), 400,
                "A saved change is required");
        server.verify();
    }

    @Test
    void runsAreRelayed() {
        RestClient.Builder builder = RestClient.builder().baseUrl("http://agent");
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        DiagnosisController controller = new DiagnosisController(builder.build(), "");
        server.expect(requestTo("http://agent/runs")).andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess("{\"runs\":[{\"thread_id\":\"t1\"}]}", MediaType.APPLICATION_JSON));

        assertThat(tree(controller.list())).isEqualTo(JSON.readTree("{\"runs\":[{\"thread_id\":\"t1\"}]}"));
        server.verify();
    }

    // ── Agent 오류 응답(처리기 직접 호출) ──────────────────────────────────

    @Test
    void upstreamNotFoundStays404AndOtherStatusesBecome502() {
        DiagnosisController controller = new DiagnosisController(RestClient.builder().build(), "");

        assertError(controller.upstream(HttpClientErrorException.create(HttpStatus.NOT_FOUND, "Not Found",
                HttpHeaders.EMPTY, new byte[0], StandardCharsets.UTF_8)), 404, "DIAGNOSIS_UPSTREAM_ERROR",
                "진단 Agent가 요청을 처리하지 못했습니다 (404).");
        assertError(controller.upstream(HttpServerErrorException.create(HttpStatus.INTERNAL_SERVER_ERROR, "Boom",
                HttpHeaders.EMPTY, new byte[0], StandardCharsets.UTF_8)), 502, "DIAGNOSIS_UPSTREAM_ERROR",
                "진단 Agent가 요청을 처리하지 못했습니다 (500).");
        assertError(controller.upstream(HttpClientErrorException.create(HttpStatus.BAD_REQUEST, "Bad",
                HttpHeaders.EMPTY, new byte[0], StandardCharsets.UTF_8)), 502, "DIAGNOSIS_UPSTREAM_ERROR",
                "진단 Agent가 요청을 처리하지 못했습니다 (400).");
        assertError(controller.upstream(new RestClientResponseException("odd", HttpStatusCode.valueOf(599), "",
                HttpHeaders.EMPTY, new byte[0], StandardCharsets.UTF_8)), 502, "DIAGNOSIS_UPSTREAM_ERROR",
                "진단 Agent가 요청을 처리하지 못했습니다 (599).");
    }

    @Test
    void connectionFailureIs502() {
        DiagnosisController controller = new DiagnosisController(RestClient.builder().build(), "");

        assertError(controller.unavailable(new ResourceAccessException("down")), 502, "DIAGNOSIS_UNAVAILABLE",
                "진단 Agent에 연결하지 못했습니다. 다시 조회해 주세요.");
    }

    // ── MVC 를 거친 응답 ─────────────────────────────────────────────────

    /**
     * MVC 를 거쳐도 Agent 오류는 상태와 ApiError 본문을 지키는지 본다. 들어오는 요청의 401 과 400 은 상태를 보고, 400 은 reason
     * 문구도 본다. UUID 가 아닌 threadId 가 400 이 되는지도 본다.
     */
    @Test
    void errorsThroughMvcKeepStatusAndBody() throws Exception {
        RestClient.Builder builder = RestClient.builder().baseUrl("http://agent");
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        MockMvc mvc = MockMvcBuilders.standaloneSetup(new DiagnosisController(builder.build(), "secret")).build();
        UUID id = UUID.fromString("5b0c2f3e-8f0a-4a57-9a3c-1c2d3e4f5a6b");
        server.expect(requestTo("http://agent/runs")).andRespond(withStatus(HttpStatus.INTERNAL_SERVER_ERROR));
        server.expect(requestTo("http://agent/runs/" + id + "/events")).andRespond(withStatus(HttpStatus.NOT_FOUND));
        server.expect(requestTo("http://agent/diagnose")).andRespond(withException(new IOException("down")));

        assertMvcError(mvc.perform(get("/api/diagnoses")).andReturn(), 502, "DIAGNOSIS_UPSTREAM_ERROR",
                "진단 Agent가 요청을 처리하지 못했습니다 (500).");
        assertMvcError(mvc.perform(get("/api/diagnoses/" + id)).andReturn(), 404, "DIAGNOSIS_UPSTREAM_ERROR",
                "진단 Agent가 요청을 처리하지 못했습니다 (404).");
        assertMvcError(mvc.perform(post("/internal/skill-changes").header("X-Service-Token", "secret")
                .contentType(MediaType.APPLICATION_JSON).content("{\"type\":\"skill_saved\",\"change_id\":\"c1\"}"))
                .andReturn(), 502, "DIAGNOSIS_UNAVAILABLE", "진단 Agent에 연결하지 못했습니다. 다시 조회해 주세요.");
        server.verify();

        MvcResult wrongToken = mvc.perform(post("/internal/skill-changes").header("X-Service-Token", "nope")
                .contentType(MediaType.APPLICATION_JSON).content("{\"type\":\"skill_saved\",\"change_id\":\"c1\"}"))
                .andReturn();
        assertThat(wrongToken.getResponse().getStatus()).isEqualTo(401);
        MvcResult missingHeader = mvc.perform(post("/internal/skill-changes").contentType(MediaType.APPLICATION_JSON)
                .content("{\"type\":\"skill_saved\",\"change_id\":\"c1\"}")).andReturn();
        assertThat(missingHeader.getResponse().getStatus()).isEqualTo(401);
        MvcResult badEvent = mvc.perform(post("/internal/skill-changes").header("X-Service-Token", "secret")
                .contentType(MediaType.APPLICATION_JSON).content("{\"type\":\"other\"}")).andReturn();
        assertThat(badEvent.getResponse().getStatus()).isEqualTo(400);
        assertThat(badEvent.getResponse().getErrorMessage()).isEqualTo("A saved change is required");
        assertThat(mvc.perform(get("/api/diagnoses/not-a-uuid")).andReturn().getResponse().getStatus()).isEqualTo(400);
    }

    /** MVC 를 거친 응답의 상태, JSON 형식, code 와 message 두 칸뿐인 본문을 본다. */
    static void assertMvcError(MvcResult result, int status, String code, String message) throws Exception {
        assertThat(result.getResponse().getStatus()).isEqualTo(status);
        assertThat(MediaType.parseMediaType(result.getResponse().getContentType()))
                .matches(type -> type.isCompatibleWith(MediaType.APPLICATION_JSON));
        JsonNode body = JSON.readTree(result.getResponse().getContentAsString(StandardCharsets.UTF_8));
        assertThat(body.propertyNames()).containsExactlyInAnyOrder("code", "message");
        assertThat(body).isEqualTo(error(code, message));
    }

    // ── 공개 생성자가 만드는 HTTP 클라이언트 ─────────────────────────────────

    /** 시험용 HTTP 서버가 받은 요청 한 건의 모양이다. serviceToken 은 X-Service-Token 헤더 값 목록이다. */
    record Captured(String method, String path, String protocol, List<String> serviceToken, String upgrade,
            String contentType, String body) {
    }

    @Test
    void publicConstructorTalksHttp11AndAlwaysSendsTheServiceTokenHeader() throws Exception {
        List<Captured> requests = new ArrayList<>();
        HttpServer server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        server.createContext("/", exchange -> {
            String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            synchronized (requests) {
                requests.add(new Captured(exchange.getRequestMethod(), exchange.getRequestURI().getPath(),
                        exchange.getProtocol(), exchange.getRequestHeaders().get("X-Service-Token"),
                        exchange.getRequestHeaders().getFirst("Upgrade"),
                        exchange.getRequestHeaders().getFirst("Content-Type"), body));
            }
            byte[] reply = "{\"thread_id\":\"t-http\"}".getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, reply.length);
            exchange.getResponseBody().write(reply);
            exchange.close();
        });
        server.start();
        try {
            String base = "http://" + InetAddress.getLoopbackAddress().getHostAddress() + ":"
                    + server.getAddress().getPort();
            UUID id = UUID.fromString("5b0c2f3e-8f0a-4a57-9a3c-1c2d3e4f5a6b");

            DiagnosisController secured = new DiagnosisController(base, "server-secret");
            assertThat(secured.changed("server-secret", SAVED).get("thread_id")).isEqualTo("t-http");
            DiagnosisController open = new DiagnosisController(base, "");
            assertThat(open.list().get("thread_id")).isEqualTo("t-http");
            assertThat(open.status(id).get("thread_id")).isEqualTo("t-http");

            assertThat(requests).hasSize(3);
            Captured diagnose = requests.get(0);
            assertThat(diagnose.method()).isEqualTo("POST");
            assertThat(diagnose.path()).isEqualTo("/diagnose");
            assertThat(diagnose.protocol()).isEqualTo("HTTP/1.1");
            assertThat(diagnose.serviceToken()).containsExactly("server-secret");
            assertThat(diagnose.upgrade()).isNull();
            assertThat(MediaType.parseMediaType(diagnose.contentType())).isEqualTo(MediaType.APPLICATION_JSON);
            assertThat(JSON.readTree(diagnose.body())).isEqualTo(tree(SAVED));

            Captured runs = requests.get(1);
            assertThat(runs.method()).isEqualTo("GET");
            assertThat(runs.path()).isEqualTo("/runs");
            assertThat(runs.protocol()).isEqualTo("HTTP/1.1");
            assertThat(runs.serviceToken()).containsExactly("");
            assertThat(runs.upgrade()).isNull();

            Captured events = requests.get(2);
            assertThat(events.method()).isEqualTo("GET");
            assertThat(events.path()).isEqualTo("/runs/" + id + "/events");
            assertThat(events.serviceToken()).containsExactly("");
        } finally {
            server.stop(0);
        }
    }

    /** 호출이 던진 예외를 돌려주고, 던지지 않으면 null 을 돌려준다. */
    static Throwable catchThrown(Runnable call) {
        try {
            call.run();
        } catch (Throwable t) {
            return t;
        }
        return null;
    }

    @Test
    void wrongTokenIsResponseStatusExceptionWithoutReason() {
        DiagnosisController controller = new DiagnosisController(RestClient.builder().build(), "secret");
        assertThatThrownBy(() -> controller.changed("wrong", SAVED)).isExactlyInstanceOf(ResponseStatusException.class)
                .hasMessage("401 UNAUTHORIZED");
    }
}
