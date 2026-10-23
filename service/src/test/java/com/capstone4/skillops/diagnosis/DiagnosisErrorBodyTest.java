package com.capstone4.skillops.diagnosis;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withException;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.client.RestClient;

/**
 * 진단 중계의 오류 본문도 service/src/main/java/com/capstone4/skillops/common/ApiError.java 로 만들어 code, message 순서로
 * 내보내는 동작을 보호한다. 대상은 service/src/main/java/com/capstone4/skillops/diagnosis/DiagnosisController.java 의 upstream
 * (Agent 가 503 으로 답한 경우 502)과 unavailable(연결 실패) 처리기이며, 응답 문자열을 키 순서까지 그대로 비교한다.
 */
class DiagnosisErrorBodyTest {

    @Test
    void agentErrorsListCodeThenMessage() throws Exception {
        RestClient.Builder builder = RestClient.builder().baseUrl("http://agent");
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        MockMvc mvc = MockMvcBuilders.standaloneSetup(new DiagnosisController(builder.build(), "")).build();
        server.expect(requestTo("http://agent/runs")).andRespond(withStatus(HttpStatus.SERVICE_UNAVAILABLE));
        server.expect(requestTo("http://agent/runs")).andRespond(withException(new IOException("down")));

        MvcResult upstream = mvc.perform(get("/api/diagnoses")).andReturn();
        MvcResult unavailable = mvc.perform(get("/api/diagnoses")).andReturn();

        assertThat(upstream.getResponse().getStatus()).isEqualTo(502);
        assertThat(upstream.getResponse().getContentAsString(StandardCharsets.UTF_8))
                .isEqualTo("{\"code\":\"DIAGNOSIS_UPSTREAM_ERROR\",\"message\":\"진단 Agent가 요청을 처리하지 못했습니다 (503).\"}");
        assertThat(unavailable.getResponse().getStatus()).isEqualTo(502);
        assertThat(unavailable.getResponse().getContentAsString(StandardCharsets.UTF_8))
                .isEqualTo("{\"code\":\"DIAGNOSIS_UNAVAILABLE\",\"message\":\"진단 Agent에 연결하지 못했습니다. 다시 조회해 주세요.\"}");
        server.verify();
    }
}
