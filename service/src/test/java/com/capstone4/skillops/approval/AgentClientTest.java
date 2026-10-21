package com.capstone4.skillops.approval;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

class AgentClientTest {

    private final RestClient.Builder builder = RestClient.builder().baseUrl("http://agent")
            .defaultHeader("X-Service-Token", "svc-token");
    private final MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
    private final AgentClient client = new AgentClient(builder);

    @Test
    void forwards_decision_with_service_token() {
        server.expect(requestTo("http://agent/approve"))
                .andExpect(header("X-Service-Token", "svc-token"))
                .andExpect(header("Content-Type", "application/json"))
                .andExpect(content().json("{\"proposal_id\":\"p_1\",\"status\":\"approved\",\"token\":\"a_X\"}"))
                .andRespond(withSuccess("{\"accepted\":true,\"proposal_id\":\"p_1\",\"status\":\"approved\"}",
                        MediaType.APPLICATION_JSON));

        assertThat(client.approve("p_1", "approved", "a_X")).isEqualTo(new AgentClient.Reply(true, "approved"));
    }

    @Test
    void unknown_proposal_is_404() {
        server.expect(requestTo("http://agent/approve"))
                .andRespond(withStatus(HttpStatus.NOT_FOUND).contentType(MediaType.APPLICATION_JSON)
                        .body("{\"detail\":\"Proposal 'p_1' not found or already processed\"}"));

        assertThatThrownBy(() -> client.approve("p_1", "approved", "a_X"))
                .isInstanceOfSatisfying(AgentException.class, e -> {
                    assertThat(e.code()).isEqualTo("PROPOSAL_NOT_FOUND");
                    assertThat(e.httpStatus()).isEqualTo(404);
                    assertThat(e.getMessage()).contains("already processed");
                });
    }

    @Test
    void agent_server_error_is_502() {
        server.expect(requestTo("http://agent/approve")).andRespond(withServerError());

        assertThatThrownBy(() -> client.approve("p_1", "approved", "a_X"))
                .isInstanceOfSatisfying(AgentException.class, e -> assertThat(e.httpStatus()).isEqualTo(502));
    }
}
