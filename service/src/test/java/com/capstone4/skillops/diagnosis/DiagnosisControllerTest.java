package com.capstone4.skillops.diagnosis;

import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.http.*;
import org.springframework.web.server.ResponseStatusException;
import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.*;
import static org.springframework.test.web.client.response.MockRestResponseCreators.*;

/**
 * service/src/main/java/com/capstone4/skillops/diagnosis/DiagnosisController.java 의 기본 중계 동작을 보호한다.
 * 저장 이벤트와 서비스 토큰 헤더가 Agent 의 POST /diagnose 로 넘어가는지, 틀린 서비스 토큰은 Agent 로 넘기기 전에 401 로 막히는지,
 * 진단 상태 조회가 Agent 의 GET /runs/{id}/events 응답을 그대로 돌려주는지 본다.
 */
class DiagnosisControllerTest {
    @Test void forwardsChangeAndServerTokenToAgent(){
        var builder=RestClient.builder().baseUrl("http://agent").defaultHeader("X-Service-Token","server-secret");
        var server=MockRestServiceServer.bindTo(builder).build();
        var controller=new DiagnosisController(builder.build(),"server-secret");
        server.expect(requestTo("http://agent/diagnose")).andExpect(method(HttpMethod.POST))
            .andExpect(header("X-Service-Token","server-secret"))
            .andExpect(jsonPath("$.change_id").value("change"))
            .andRespond(withSuccess("{\"thread_id\":\"thread\",\"duplicate\":true}",MediaType.APPLICATION_JSON));
        assertThat(controller.changed("server-secret",Map.of("type","skill_saved","change_id","change")).get("thread_id")).isEqualTo("thread");
        server.verify();
    }
    @Test void wrongInternalTokenIsRejectedBeforeForwarding(){
        var controller=new DiagnosisController(RestClient.builder().baseUrl("http://agent").build(),"secret");
        assertThatThrownBy(()->controller.changed("wrong",Map.of("type","skill_saved","change_id","change")))
            .isInstanceOf(ResponseStatusException.class).satisfies(e->assertThat(((ResponseStatusException)e).getStatusCode().value()).isEqualTo(401));
    }
    @Test void progressIsReadWithoutExposingToken(){
        UUID id=UUID.randomUUID();
        var builder=RestClient.builder().baseUrl("http://agent");
        var server=MockRestServiceServer.bindTo(builder).build();
        server.expect(requestTo("http://agent/runs/"+id+"/events"))
            .andRespond(withSuccess("{\"diagnosis_status\":\"VALIDATED\"}",MediaType.APPLICATION_JSON));
        var controller=new DiagnosisController(builder.build(),"");
        assertThat(controller.status(id).get("diagnosis_status")).isEqualTo("VALIDATED");
        server.verify();
    }
}
