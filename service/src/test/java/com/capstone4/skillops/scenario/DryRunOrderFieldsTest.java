package com.capstone4.skillops.scenario;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

/**
 * service/src/main/java/com/capstone4/skillops/scenario/OrderAgentRunClient.java 의 originalOrder() 가 돌려주는 원래 주문이
 * harness 가 모의 실행에 보내는 주문 칸(DRY_RUN_ORDER_FIELDS)과 같은 키를 같은 순서로 갖는지 보호한다.
 * toDryRunOrder 는 키를 직접 쓰므로 한쪽만 고치면 이 테스트가 깨진다. 주문 Agent 는 MockRestServiceServer 로 대신한다.
 */
class DryRunOrderFieldsTest {

    static final String BASE = "http://order-agent";
    static final String EXECUTION = "5bc11dca-57c1-4517-950c-a0aec8ed67e2";

    @Test
    void originalOrderKeysAreTheDryRunOrderFields() {
        RestClient.Builder builder = RestClient.builder().baseUrl(BASE);
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        OrderAgentRunClient client = new OrderAgentRunClient(builder, BASE);
        server.expect(requestTo(BASE + "/api/executions/" + EXECUTION + "/trace"))
                .andRespond(withSuccess("{\"order_id\":\"o-1\"}", MediaType.APPLICATION_JSON));
        server.expect(requestTo(BASE + "/api/orders/o-1")).andRespond(withSuccess("{}", MediaType.APPLICATION_JSON));

        assertThat(client.originalOrder(EXECUTION).keySet())
                .containsExactlyElementsOf(OrderAgentRunClient.DRY_RUN_ORDER_FIELDS);
        server.verify();
    }
}
