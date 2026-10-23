package com.capstone4.skillops.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.never;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import com.capstone4.skillops.approval.ApprovalService;
import com.capstone4.skillops.auth.AuthProperties;
import com.capstone4.skillops.auth.SecurityConfig;
import com.capstone4.skillops.auth.TokenService;
import com.capstone4.skillops.proposal.ProposalService;

/**
 * service/src/main/java/com/capstone4/skillops/api/ProposalController.java 의 detail 과
 * service/src/main/java/com/capstone4/skillops/api/ApprovalController.java 의 approve 가 거부 응답(없는 제안서 404,
 * 의도 미확인 400)을 컨트롤러 반환값으로 직접 쓴다는 점을 고정한다.
 * 반환값으로 쓰는 응답은 클라이언트가 JSON 을 받지 않을 때 Spring 이 406 으로 처리한다. 같은 응답을 예외 처리기에서 쓰면
 * 협상 실패가 처리기 밖으로 새어 500 이 된다. 리팩토링 전 코드(커밋 ec39713)도 406 이었고, 이 테스트는 그 코드에서도 통과한다.
 */
@WebMvcTest(controllers = { ApprovalController.class, ProposalController.class })
@Import({ SecurityConfig.class, TokenService.class })
class ContentNegotiationContractTest {

    @Autowired
    MockMvc mockMvc;

    @Autowired
    TokenService tokens;

    @Autowired
    AuthProperties properties;

    @MockitoBean
    ApprovalService approvals;

    @MockitoBean
    ProposalService proposals;

    /** 설정의 데모 계정으로 토큰을 발급해 Authorization 헤더 값을 만든다. */
    String bearer(String username) {
        return "Bearer " + tokens.issue(properties.users().stream().filter(u -> u.username().equals(username))
                .findFirst().orElseThrow()).token();
    }

    /** JSON 을 받지 않는 클라이언트가 없는 제안서를 조회하면 404 대신 406 을 받는다. */
    @Test
    void missingProposalWithNonJsonAcceptIsNotAcceptable() throws Exception {
        given(proposals.find("p_none")).willReturn(Optional.empty());

        int status = mockMvc.perform(get("/api/proposals/p_none").header("Authorization", bearer("owner"))
                .accept(MediaType.TEXT_HTML)).andReturn().getResponse().getStatus();

        assertThat(status).isEqualTo(406);
    }

    /** JSON 을 받지 않는 클라이언트의 의도 미확인 승인은 400 대신 406 을 받고, 서비스는 호출되지 않는다. */
    @Test
    void unconfirmedApprovalWithNonJsonAcceptIsNotAcceptable() throws Exception {
        int status = mockMvc.perform(post("/api/proposals/p_stub_001/approve")
                .header("Authorization", bearer("operator")).contentType(MediaType.APPLICATION_JSON)
                .accept(MediaType.APPLICATION_XML).content("{\"candidate_no\":1,\"intent_confirmed\":false}"))
                .andReturn().getResponse().getStatus();

        assertThat(status).isEqualTo(406);
        then(approvals).should(never()).approve(anyString(), anyInt(), anyBoolean(), any());
    }
}
