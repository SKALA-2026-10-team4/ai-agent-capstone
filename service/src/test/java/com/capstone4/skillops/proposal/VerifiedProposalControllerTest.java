package com.capstone4.skillops.proposal;

import static org.mockito.Mockito.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import com.capstone4.skillops.evaluation.EvaluationConfiguration;

@WebMvcTest(VerifiedProposalController.class)
@Import(EvaluationConfiguration.class)
class VerifiedProposalControllerTest {
    private static final String INPUT = """
            {"evaluation_id":"job","attempt_id":"attempt","summary":"summary","intent_text":"intent"}
            """;
    @Autowired MockMvc mvc;
    @MockitoBean VerifiedProposalService proposals;

    /** MCP의 인증 제거가 REST 제안 저장 권한에 영향을 주지 않는다. */
    @Test
    void anonymousCannotSaveThroughRest() throws Exception {
        mvc.perform(post("/api/verified-proposals").contentType("application/json").content(INPUT))
                .andExpect(status().isUnauthorized());
        verifyNoInteractions(proposals);
    }

    /** 로그인했어도 OPERATOR가 아니면 REST 저장을 거부한다. */
    @Test
    void ownerCannotSaveThroughRest() throws Exception {
        mvc.perform(post("/api/verified-proposals").with(jwt().authorities(new SimpleGrantedAuthority("ROLE_OWNER")))
                .contentType("application/json").content(INPUT)).andExpect(status().isForbidden());
        verifyNoInteractions(proposals);
    }

    /** OPERATOR의 기존 REST 저장은 유지한다. */
    @Test
    void operatorCanSaveThroughRest() throws Exception {
        when(proposals.save(any())).thenReturn(new VerifiedProposalService.Saved("p_test", "proposed", List.of(1), "SERVER_VERIFIED"));
        mvc.perform(post("/api/verified-proposals").with(jwt().authorities(new SimpleGrantedAuthority("ROLE_OPERATOR")))
                .contentType("application/json").content(INPUT)).andExpect(status().isOk())
                .andExpect(jsonPath("proposal_id").value("p_test"));
        verify(proposals).save(new VerifiedProposalService.Input("job", "attempt", "summary", "intent"));
    }
}
