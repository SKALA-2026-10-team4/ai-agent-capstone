package com.capstone4.skillops.scenario;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withException;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.net.ConnectException;
import java.net.http.HttpConnectTimeoutException;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

/**
 * service/src/main/java/com/capstone4/skillops/scenario/OrderAgentRunClient.java 와
 * service/src/main/java/com/capstone4/skillops/scenario/OrderAgentRunErrors.java 의 동작을 보호한다.
 * 재현 주문의 원래 주문을 꺼내는 규칙, execution_id 와 run_id 해석, 4xx 와 5xx 와 연결 실패와 시간 초과를 어떤 예외와 문구로
 * 알리는지 글자 단위로 고정한다. 이 문구는 run_scenarios 오류로 Agent 에게 그대로 간다.
 * READ_TIMEOUT 이 주문 Agent 의 실행 한도보다 긴지도 본다.
 * 주문 Agent 는 MockRestServiceServer 로 대신한다. 원래 주문 꺼내기 테스트의 실행 기록과 주문 응답은 주문 Agent 에서 저장한
 * 실측 파일(service/src/test/resources/order-agent/ 의 F1 live 실패 기록)을 쓰고, 오류 응답과 모양을 일부러 바꾼 응답은
 * 테스트 안에서 직접 만든다.
 */
class OrderAgentRunClientTest {

    static final String BASE = "http://order-agent";
    static final String EXECUTION = "5bc11dca-57c1-4517-950c-a0aec8ed67e2";
    static final String RUN_ALIAS = "r_5bc11dca57c14517950ca0aec8ed67e2";
    static final String ORDER = "6a1bd834-ef3c-436f-a0c2-2757552dff09";

    RestClient.Builder builder = RestClient.builder().baseUrl(BASE);
    MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
    OrderAgentRunClient client = new OrderAgentRunClient(builder, BASE);

    @Test
    void originalOrderKeepsOnlyOrderFields() {
        expectTraceAndOrder();

        Map<String, Object> original = client.originalOrder(EXECUTION);

        assertThat(original).isEqualTo(Map.of(
                "customer", "c_1001",
                "items", List.of(Map.of("sku", "CAMP-LANTERN-LED", "qty", 1)),
                "payment_method", "card",
                "shipping", Map.of("country", "KR", "express", false)));
        server.verify();
    }

    @Test
    void originalOrderAcceptsRunIdAlias() {
        expectTraceAndOrder();

        assertThat(client.originalOrder(RUN_ALIAS)).containsEntry("customer", "c_1001");
        server.verify();
    }

    @Test
    void unknownExecutionIsInvalidInput() {
        server.expect(requestTo(BASE + "/api/executions/" + EXECUTION + "/trace"))
                .andRespond(withStatus(HttpStatus.NOT_FOUND).contentType(MediaType.APPLICATION_JSON)
                        .body("{\"code\":\"EXECUTION_NOT_FOUND\",\"message\":\"실행 자료를 확인할 수 없습니다: EXECUTION_NOT_FOUND\"}"));

        assertThatThrownBy(() -> client.originalOrder(RUN_ALIAS))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("재현할 실행 " + RUN_ALIAS + " 을 주문 Agent 에서 찾을 수 없습니다");
    }

    @Test
    void missingOrderIsInvalidInput() {
        server.expect(requestTo(BASE + "/api/executions/" + EXECUTION + "/trace"))
                .andRespond(withSuccess(resource("trace-contract-violation.json"), MediaType.APPLICATION_JSON));
        server.expect(requestTo(BASE + "/api/orders/" + ORDER))
                .andRespond(withStatus(HttpStatus.NOT_FOUND).contentType(MediaType.APPLICATION_JSON)
                        .body("{\"code\":\"ORDER_NOT_FOUND\",\"message\":\"주문을 찾을 수 없습니다.\"}"));

        assertThatThrownBy(() -> client.originalOrder(EXECUTION))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("재현할 실행 " + EXECUTION + " 을 주문 Agent 에서 찾을 수 없습니다");
    }

    @Test
    void badIdIsRejectedWithoutCalling() {
        for (String id : List.of("5bc11dca", "r_" + EXECUTION, "../orders")) {
            assertThatThrownBy(() -> client.originalOrder(id)).isInstanceOf(IllegalArgumentException.class);
        }
        server.verify();
    }

    /** JDK HttpClient 는 읽기 시간 초과를 HttpTimeoutException("Request cancelled") 로 낸다(실측). */
    @Test
    void readTimeoutIsReportedAsSlow() {
        server.expect(requestTo(BASE + "/api/dry-runs"))
                .andRespond(withException(new HttpTimeoutException("Request cancelled")));

        assertThatThrownBy(() -> client.dryRun("aaaaaaaa-1111-2222-3333-444444444444", Map.of()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("주문 Agent 의 응답이 늦습니다: " + BASE + "/api/dry-runs ("
                        + OrderAgentRunClient.READ_TIMEOUT.toSeconds() + "초 안에 답이 없음)");
    }

    /** 연결 시간 초과는 HttpTimeoutException 의 하위 클래스인 HttpConnectTimeoutException 으로 온다(실측). 닿지 않은 것이지 느린 것이 아니다. */
    @Test
    void connectTimeoutIsReportedAsUnreachable() {
        server.expect(requestTo(BASE + "/api/dry-runs"))
                .andRespond(withException(new HttpConnectTimeoutException("HTTP connect timed out")));

        assertThatThrownBy(() -> client.dryRun("aaaaaaaa-1111-2222-3333-444444444444", Map.of()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("주문 Agent 에 연결할 수 없습니다: " + BASE + "/api/dry-runs (HttpConnectTimeoutException: HTTP connect timed out)");
    }

    @Test
    void refusedConnectionIsReportedAsUnreachable() {
        server.expect(requestTo(BASE + "/api/dry-runs"))
                .andRespond(withException(new ConnectException("Connection refused")));

        assertThatThrownBy(() -> client.dryRun("aaaaaaaa-1111-2222-3333-444444444444", Map.of()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("주문 Agent 에 연결할 수 없습니다: " + BASE + "/api/dry-runs (ConnectException: Connection refused)");
    }

    /**
     * 주문 Agent 의 execution-timeout-ms(order-agent/backend/src/main/resources/application.yml, 45000)보다 오래 기다려야
     * 느린 정상 실행을 끊지 않는다. 그 값을 바꾸면 이 테스트의 숫자도 함께 고친다.
     */
    @Test
    void readTimeoutOutlastsOrderAgentRunLimit() {
        Duration orderAgentRunLimit = Duration.ofMillis(45_000);

        assertThat(OrderAgentRunClient.READ_TIMEOUT).isGreaterThan(orderAgentRunLimit);
        assertThat(OrderAgentRunClient.CONNECT_TIMEOUT).isEqualTo(Duration.ofSeconds(2));
    }

    @Test
    void serverErrorIsReportedAsUnreachable() {
        server.expect(requestTo(BASE + "/api/dry-runs")).andRespond(withStatus(HttpStatus.INTERNAL_SERVER_ERROR)
                .contentType(MediaType.APPLICATION_JSON)
                .body("{\"code\":\"INTERNAL_ERROR\",\"message\":\"주문 처리 중 오류가 발생했습니다.\"}"));

        assertThatThrownBy(() -> client.dryRun("aaaaaaaa-1111-2222-3333-444444444444", Map.of()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("주문 Agent 에 연결할 수 없습니다: " + BASE + "/api/dry-runs (HTTP 500 INTERNAL_ERROR)");
    }

    @Test
    void executionIdOfAcceptsUuidAndRunId() {
        assertThat(OrderAgentRunClient.executionIdOf(EXECUTION)).isEqualTo(EXECUTION);
        assertThat(OrderAgentRunClient.executionIdOf(EXECUTION.toUpperCase())).isEqualTo(EXECUTION);
        assertThat(OrderAgentRunClient.executionIdOf(RUN_ALIAS)).isEqualTo(EXECUTION);
        assertThat(OrderAgentRunClient.executionIdOf("r_" + EXECUTION)).isNull();
        assertThat(OrderAgentRunClient.executionIdOf("f-" + EXECUTION)).isNull();
        assertThat(OrderAgentRunClient.executionIdOf(null)).isNull();
    }

    /** 거절 문구는 동작 이름, code 와 HTTP 상태, 오류 본문 message, field_errors 목록 순서로 잇는다. Rejected 는 원인 예외를 달지 않는다. */
    @Test
    void rejectionMessageListsCodeMessageAndFieldErrors() {
        server.expect(requestTo(BASE + "/api/dry-runs")).andRespond(withStatus(HttpStatus.BAD_REQUEST)
                .contentType(MediaType.APPLICATION_JSON)
                .body("{\"code\":\"VALIDATION_ERROR\",\"message\":\"주문 입력을 확인해주세요.\",\"order_id\":null,"
                        + "\"field_errors\":[{\"field\":\"order.customer\",\"message\":\"must match\"}]}"));

        assertThatThrownBy(() -> client.dryRun("aaaaaaaa-1111-2222-3333-444444444444", Map.of()))
                .isInstanceOfSatisfying(OrderAgentRunClient.Rejected.class, e -> {
                    assertThat(e.status()).isEqualTo(400);
                    assertThat(e.code()).isEqualTo("VALIDATION_ERROR");
                })
                .hasMessage("주문 Agent 가 모의 실행 요청을 거절했습니다: VALIDATION_ERROR (HTTP 400) 주문 입력을 확인해주세요. "
                        + "[order.customer: must match]")
                .hasNoCause();
    }

    @Test
    void rejectionWithoutJsonBodyUsesStatusOnly() {
        server.expect(requestTo(BASE + "/api/dry-runs")).andRespond(withStatus(HttpStatus.NOT_FOUND)
                .contentType(MediaType.TEXT_PLAIN).body("Not Found"));

        assertThatThrownBy(() -> client.dryRun("aaaaaaaa-1111-2222-3333-444444444444", Map.of()))
                .isInstanceOfSatisfying(OrderAgentRunClient.Rejected.class, e -> {
                    assertThat(e.status()).isEqualTo(404);
                    assertThat(e.code()).isNull();
                })
                .hasMessage("주문 Agent 가 모의 실행 요청을 거절했습니다: HTTP 404");
    }

    @Test
    void serverErrorWithoutBodyShowsStatusOnly() {
        server.expect(requestTo(BASE + "/api/dry-runs")).andRespond(withStatus(HttpStatus.SERVICE_UNAVAILABLE));

        assertThatThrownBy(() -> client.dryRun("aaaaaaaa-1111-2222-3333-444444444444", Map.of()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("주문 Agent 에 연결할 수 없습니다: " + BASE + "/api/dry-runs (HTTP 503)")
                .hasCauseInstanceOf(RestClientResponseException.class);
    }

    /** 실행 기록 읽기(GET)는 문구에 받은 번호를 이어 붙인 경로를 쓴다. 동작 이름은 "실행 기록 읽기" 다. */
    @Test
    void traceFailuresNameThePathAndTheAction() {
        server.expect(requestTo(BASE + "/api/executions/" + EXECUTION + "/trace"))
                .andRespond(withStatus(HttpStatus.INTERNAL_SERVER_ERROR));
        server.expect(requestTo(BASE + "/api/executions/" + EXECUTION + "/trace"))
                .andRespond(withStatus(HttpStatus.FORBIDDEN).contentType(MediaType.APPLICATION_JSON)
                        .body("{\"code\":\"FORBIDDEN\",\"message\":\"볼 수 없습니다.\"}"));

        assertThatThrownBy(() -> client.trace(EXECUTION))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("주문 Agent 에 연결할 수 없습니다: " + BASE + "/api/executions/" + EXECUTION + "/trace (HTTP 500)");
        assertThatThrownBy(() -> client.trace(EXECUTION))
                .isInstanceOf(OrderAgentRunClient.Rejected.class)
                .hasMessage("주문 Agent 가 실행 기록 읽기 요청을 거절했습니다: FORBIDDEN (HTTP 403) 볼 수 없습니다.");
        server.verify();
    }

    /** 오류 문구의 주소는 baseUrl 끝의 '/' 를 뗀 것이다. */
    @Test
    void trailingSlashOfBaseUrlIsDroppedInMessages() {
        OrderAgentRunClient slashed = new OrderAgentRunClient(builder, BASE + "/");
        server.expect(requestTo(BASE + "/api/dry-runs")).andRespond(withStatus(HttpStatus.BAD_GATEWAY));

        assertThatThrownBy(() -> slashed.dryRun("aaaaaaaa-1111-2222-3333-444444444444", Map.of()))
                .hasMessage("주문 Agent 에 연결할 수 없습니다: " + BASE + "/api/dry-runs (HTTP 502)");
    }

    @Test
    void missingSnapshotIdIsReported() {
        server.expect(requestTo(BASE + "/api/snapshots/candidates"))
                .andExpect(method(HttpMethod.POST))
                .andRespond(withSuccess("{\"hash\":\"h\"}", MediaType.APPLICATION_JSON));

        assertThatThrownBy(() -> client.publishCandidate(Map.of(), Map.of()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("주문 Agent 의 /api/snapshots/candidates 응답에 snapshot_id 가 없습니다");
    }

    @Test
    void emptyBodyIsReported() {
        server.expect(requestTo(BASE + "/api/snapshots/candidates")).andRespond(withSuccess());

        assertThatThrownBy(() -> client.publishCandidate(Map.of(), Map.of()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("주문 Agent 의 후보 snapshot 올리기 응답이 비어 있습니다: " + BASE + "/api/snapshots/candidates");
    }

    @Test
    void traceWithoutOrderIdIsNotFound() {
        server.expect(requestTo(BASE + "/api/executions/" + EXECUTION + "/trace"))
                .andRespond(withSuccess("{\"events\":[]}", MediaType.APPLICATION_JSON));

        assertThatThrownBy(() -> client.originalOrder(EXECUTION))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("재현할 실행 " + EXECUTION + " 을 주문 Agent 에서 찾을 수 없습니다")
                .hasNoCause();
        server.verify();
    }

    @Test
    void orderRejectionOtherThan404PassesThrough() {
        server.expect(requestTo(BASE + "/api/executions/" + EXECUTION + "/trace"))
                .andRespond(withSuccess(resource("trace-contract-violation.json"), MediaType.APPLICATION_JSON));
        server.expect(requestTo(BASE + "/api/orders/" + ORDER))
                .andRespond(withStatus(HttpStatus.BAD_REQUEST).contentType(MediaType.APPLICATION_JSON)
                        .body("{\"code\":\"VALIDATION_ERROR\",\"message\":\"주문 번호를 확인해주세요.\"}"));

        assertThatThrownBy(() -> client.originalOrder(EXECUTION))
                .isInstanceOfSatisfying(OrderAgentRunClient.Rejected.class, e -> assertThat(e.status()).isEqualTo(400))
                .hasMessage("주문 Agent 가 주문 읽기 요청을 거절했습니다: VALIDATION_ERROR (HTTP 400) 주문 번호를 확인해주세요.");
    }

    @Test
    void orderServerErrorNamesTheOrderPath() {
        server.expect(requestTo(BASE + "/api/executions/" + EXECUTION + "/trace"))
                .andRespond(withSuccess(resource("trace-contract-violation.json"), MediaType.APPLICATION_JSON));
        server.expect(requestTo(BASE + "/api/orders/" + ORDER)).andRespond(withStatus(HttpStatus.INTERNAL_SERVER_ERROR));

        assertThatThrownBy(() -> client.originalOrder(EXECUTION))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("주문 Agent 에 연결할 수 없습니다: " + BASE + "/api/orders/" + ORDER + " (HTTP 500)");
    }

    /** 원래 주문에 칸이 없거나 모양이 달라도 키는 [customer, items, payment_method, shipping] 순서로 모두 둔다. */
    @Test
    @SuppressWarnings("unchecked")
    void originalOrderKeepsKeysWhenValuesAreMissing() {
        server.expect(requestTo(BASE + "/api/executions/" + EXECUTION + "/trace"))
                .andRespond(withSuccess(resource("trace-contract-violation.json"), MediaType.APPLICATION_JSON));
        server.expect(requestTo(BASE + "/api/orders/" + ORDER))
                .andRespond(withSuccess("{\"customer\":\"c_1001\",\"items\":\"목록 아님\"}", MediaType.APPLICATION_JSON));

        Map<String, Object> original = client.originalOrder(EXECUTION);

        assertThat(original.keySet()).containsExactly("customer", "items", "payment_method", "shipping");
        assertThat(original.get("customer")).isEqualTo("c_1001");
        assertThat(original.get("items")).isEqualTo(List.of());
        assertThat(original).containsEntry("payment_method", null);
        Map<String, Object> shipping = (Map<String, Object>) original.get("shipping");
        assertThat(shipping.keySet()).containsExactly("country", "express");
        assertThat(shipping.values()).containsOnlyNulls();
    }

    /** items 가운데 Map 이 아닌 칸은 버리고, 각 줄은 sku 와 qty 만 그 순서로 남긴다. */
    @Test
    @SuppressWarnings("unchecked")
    void originalOrderKeepsOnlySkuAndQtyOfMapItems() {
        server.expect(requestTo(BASE + "/api/executions/" + EXECUTION + "/trace"))
                .andRespond(withSuccess(resource("trace-contract-violation.json"), MediaType.APPLICATION_JSON));
        server.expect(requestTo(BASE + "/api/orders/" + ORDER))
                .andRespond(withSuccess("{\"customer\":\"c_1\",\"items\":[{\"qty\":2,\"name\":\"랜턴\",\"sku\":\"A\"},\"x\",{\"sku\":\"B\"}],"
                        + "\"payment_method\":\"card\",\"shipping\":{\"express\":true,\"country\":\"KR\",\"zip\":\"0\"}}",
                        MediaType.APPLICATION_JSON));

        Map<String, Object> original = client.originalOrder(EXECUTION);

        List<Map<String, Object>> items = (List<Map<String, Object>>) original.get("items");
        assertThat(items).hasSize(2);
        assertThat(items.get(0).keySet()).containsExactly("sku", "qty");
        assertThat(items.get(0)).containsEntry("sku", "A").containsEntry("qty", 2);
        assertThat(items.get(1)).containsEntry("sku", "B").containsEntry("qty", null);
        Map<String, Object> shipping = (Map<String, Object>) original.get("shipping");
        assertThat(shipping.keySet()).containsExactly("country", "express");
        assertThat(shipping).containsEntry("country", "KR").containsEntry("express", true);
    }

    /** 실측 trace 와 그 order_id 의 주문 응답을 차례로 돌려줄 것을 기대한다. */
    private void expectTraceAndOrder() {
        server.expect(requestTo(BASE + "/api/executions/" + EXECUTION + "/trace"))
                .andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess(resource("trace-contract-violation.json"), MediaType.APPLICATION_JSON));
        server.expect(requestTo(BASE + "/api/orders/" + ORDER))
                .andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess(resource("order-contract-violation.json"), MediaType.APPLICATION_JSON));
    }

    /** service/src/test/resources/order-agent/ 의 실측 파일을 글로 읽는다. 파일이 없으면 IllegalStateException 을 던진다. */
    private static String resource(String name) {
        try (InputStream in = OrderAgentRunClientTest.class.getResourceAsStream("/order-agent/" + name)) {
            if (in == null) {
                throw new IllegalStateException("시험 자료가 없습니다: order-agent/" + name);
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
