package com.capstone4.skillops.api;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;

import javax.crypto.spec.SecretKeySpec;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import com.capstone4.skillops.approval.Approval;
import com.capstone4.skillops.approval.ApprovalService;
import com.capstone4.skillops.auth.AuthProperties;
import com.capstone4.skillops.auth.AuthUser;
import com.capstone4.skillops.auth.SecurityConfig;
import com.capstone4.skillops.auth.TokenService;
import com.capstone4.skillops.proposal.Proposal;
import com.capstone4.skillops.proposal.ProposalException;
import com.nimbusds.jose.jwk.source.ImmutableSecret;

/**
 * 승인 API(service/src/main/java/com/capstone4/skillops/api/ApprovalController.java)와 그 보안 규칙
 * (service/src/main/java/com/capstone4/skillops/auth/SecurityConfig.java)을 보호한다.
 * 결정에는 로그인이 필요하고 OPERATOR 만 승인과 거절 POST 를 할 수 있으며, 위조하거나 만료된 토큰은 401 로 막히는지 본다.
 * 승인자가 토큰의 사용자로 기록되는지, 본문 검증과 의도 확인 거절이 400 인지, ProposalException 과 낙관적 잠금 실패가
 * ProposalApiExceptionHandler 를 거쳐 제 코드와 상태로 나가는지, OWNER 도 승인 기록 목록을 읽는지도 본다. MCP(/mcp)와 다른
 * 담당자의 /api 경로는 인증 때문에 막히지 않아야 한다.
 */
@WebMvcTest(ApprovalController.class)
@Import({ SecurityConfig.class, TokenService.class })
class ApprovalControllerTest {

    private static final String BODY = "{\"candidate_no\":1,\"intent_confirmed\":true}";
    private static final String APPROVE = "/api/proposals/p_stub_001/approve";

    @Autowired
    MockMvc mockMvc;

    @Autowired
    TokenService tokens;

    @Autowired
    AuthProperties properties;

    @Autowired
    JwtEncoder encoder;

    @MockitoBean
    ApprovalService approvals;

    /** 설정의 데모 계정으로 토큰을 발급해 토큰 문자열을 돌려준다. */
    private String tokenFor(String username) {
        return tokens.issue(properties.users().stream().filter(u -> u.username().equals(username)).findFirst().orElseThrow())
                .token();
    }

    /** 기본 본문(BODY)을 담은 승인 요청을 만든다. token 이 null 이면 Authorization 헤더를 붙이지 않는다. */
    private MockHttpServletRequestBuilder approve(String token) {
        MockHttpServletRequestBuilder request = post(APPROVE).contentType(MediaType.APPLICATION_JSON).content(BODY);
        return token == null ? request : request.header("Authorization", "Bearer " + token);
    }

    @Test
    void approval_requires_login() throws Exception {
        mockMvc.perform(approve(null))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHORIZED"));
    }

    @Test
    void owner_cannot_approve() throws Exception {
        mockMvc.perform(approve(tokenFor("owner")))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));
    }

    @Test
    void operator_approves_and_is_recorded_as_approver() throws Exception {
        Approval saved = new Approval("p_stub_001", 1, "approved", true, "operator", "운영자", "a_TESTCODE01",
                Instant.now());
        given(approvals.approve(eq("p_stub_001"), eq(1), eq(true),
                eq(new AuthUser("operator", "운영자", List.of("OPERATOR"))))).willReturn(saved);

        mockMvc.perform(approve(tokenFor("operator")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.approved_by").value("operator"))
                .andExpect(jsonPath("$.candidate_no").value(1))
                .andExpect(jsonPath("$.agent_result").doesNotExist())
                .andExpect(jsonPath("$.approval_token").value("a_TESTCODE01"));
    }

    @Test
    void proposalErrorKeepsCodeAndStatus() throws Exception {
        given(approvals.approve(any(), anyInt(), anyBoolean(), any()))
                .willThrow(new ProposalException("PROPOSAL_ALREADY_DECIDED", "이미 결정한 제안서입니다.", 409));

        mockMvc.perform(approve(tokenFor("operator")))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("PROPOSAL_ALREADY_DECIDED"))
                .andExpect(jsonPath("$.message").value("이미 결정한 제안서입니다."));
    }

    @Test
    void optimisticLockFailureBecomesConflict() throws Exception {
        given(approvals.approve(any(), anyInt(), anyBoolean(), any()))
                .willThrow(new ObjectOptimisticLockingFailureException(Proposal.class, "p_stub_001"));

        mockMvc.perform(approve(tokenFor("operator")))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("PROPOSAL_ALREADY_DECIDED"));
    }

    @Test
    void candidateNoBelowOneIsBadRequest() throws Exception {
        mockMvc.perform(post(APPROVE).header("Authorization", "Bearer " + tokenFor("operator"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"candidate_no\":0,\"intent_confirmed\":true}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void approval_without_intent_confirmation_is_refused() throws Exception {
        mockMvc.perform(post(APPROVE).header("Authorization", "Bearer " + tokenFor("operator"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"candidate_no\":1,\"intent_confirmed\":false}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INTENT_NOT_CONFIRMED"));
    }

    @Test
    void missing_candidate_is_rejected() throws Exception {
        mockMvc.perform(post(APPROVE).header("Authorization", "Bearer " + tokenFor("operator"))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"intent_confirmed\":true}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void operator_can_reject_and_owner_cannot() throws Exception {
        given(approvals.reject(eq("p_stub_001"), any())).willReturn(
                new Approval("p_stub_001", null, "rejected", false, "operator", "운영자", null, Instant.now()));

        mockMvc.perform(post("/api/proposals/p_stub_001/reject").header("Authorization", "Bearer " + tokenFor("operator")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.decision").value("rejected"))
                .andExpect(jsonPath("$.approval_token").doesNotExist());
        mockMvc.perform(post("/api/proposals/p_stub_001/reject").header("Authorization", "Bearer " + tokenFor("owner")))
                .andExpect(status().isForbidden());
    }

    @Test
    void other_api_paths_stay_open_for_teammates() throws Exception {
        // 인증을 붙인 경로 밖의 /api 는 막지 않는다. /api/skills 는 이 slice 에 없는 경로이므로,
        // 보안 필터가 401 이나 403 으로 막지 않았는지만 본다.
        mockMvc.perform(get("/api/skills"))
                .andExpect(result -> org.assertj.core.api.Assertions.assertThat(result.getResponse().getStatus())
                        .isNotIn(401, 403));
    }

    @Test
    void owner_can_read_approval_history() throws Exception {
        given(approvals.recent()).willReturn(List.of());

        mockMvc.perform(get("/api/approvals").header("Authorization", "Bearer " + tokenFor("owner")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items").isArray());
    }

    @Test
    void forged_token_is_rejected() throws Exception {
        byte[] otherKey = "another-secret-that-is-at-least-32-bytes!".getBytes(StandardCharsets.UTF_8);
        JwtEncoder forger = new NimbusJwtEncoder(new ImmutableSecret<>(new SecretKeySpec(otherKey, "HmacSHA256")));
        String forged = forger.encode(JwtEncoderParameters.from(JwsHeader.with(MacAlgorithm.HS256).build(),
                claims(Instant.now().plusSeconds(600)))).getTokenValue();

        mockMvc.perform(approve(forged)).andExpect(status().isUnauthorized());
    }

    @Test
    void expired_token_is_rejected() throws Exception {
        String expired = encoder.encode(JwtEncoderParameters.from(JwsHeader.with(MacAlgorithm.HS256).build(),
                claims(Instant.now().minusSeconds(3600)))).getTokenValue();

        mockMvc.perform(approve(expired)).andExpect(status().isUnauthorized());
    }

    @Test
    void mcp_endpoint_is_not_behind_login() throws Exception {
        // 이 slice 에는 MCP 서버가 없어 404 가 나지만, 보안 필터가 401 이나 403 으로 막지 않는지만 본다.
        mockMvc.perform(post("/mcp").contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(result -> org.assertj.core.api.Assertions.assertThat(result.getResponse().getStatus())
                        .isNotIn(401, 403));
    }

    /** 위조 토큰과 만료 토큰 시험에 쓰는 operator 의 claim 묶음을 만든다. 발급 시각은 만료 2시간 전이다. */
    private static JwtClaimsSet claims(Instant expiresAt) {
        return JwtClaimsSet.builder().issuer(TokenService.ISSUER).subject("operator")
                .claim("roles", List.of("OPERATOR")).issuedAt(expiresAt.minusSeconds(7200)).expiresAt(expiresAt).build();
    }
}
