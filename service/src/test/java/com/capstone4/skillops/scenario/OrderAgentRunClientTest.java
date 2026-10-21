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
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

/** 재현 주문의 원래 주문 꺼내기와 오류 모양. 응답은 주문 Agent 에서 저장한 실측 파일이다(F1 live 실패). */
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

    @Test
    void timeoutIsReportedAsUnreachable() {
        server.expect(requestTo(BASE + "/api/dry-runs"))
                .andRespond(withException(new HttpTimeoutException("request timed out")));

        assertThatThrownBy(() -> client.dryRun("aaaaaaaa-1111-2222-3333-444444444444", Map.of()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("주문 Agent 에 연결할 수 없습니다: " + BASE + "/api/dry-runs (HttpTimeoutException: request timed out)");
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

    private void expectTraceAndOrder() {
        server.expect(requestTo(BASE + "/api/executions/" + EXECUTION + "/trace"))
                .andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess(resource("trace-contract-violation.json"), MediaType.APPLICATION_JSON));
        server.expect(requestTo(BASE + "/api/orders/" + ORDER))
                .andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess(resource("order-contract-violation.json"), MediaType.APPLICATION_JSON));
    }

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
