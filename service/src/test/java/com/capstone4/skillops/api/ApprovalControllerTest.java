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
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import com.capstone4.skillops.approval.AgentException;
import com.capstone4.skillops.approval.Approval;
import com.capstone4.skillops.approval.ApprovalService;
import com.capstone4.skillops.auth.AuthProperties;
import com.capstone4.skillops.auth.AuthUser;
import com.capstone4.skillops.auth.SecurityConfig;
import com.capstone4.skillops.auth.TokenService;
import com.nimbusds.jose.jwk.source.ImmutableSecret;

/** 승인 API 의 보안 규칙: 로그인 필요, OPERATOR 만 POST, 위조·만료 토큰 거절, MCP 는 막지 않음. */
@WebMvcTest(ApprovalController.class)
@Import({ SecurityConfig.class, TokenService.class })
class ApprovalControllerTest {

    private static final String BODY = "{\"candidate\":1,\"intent_confirmed\":true}";
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

    private String tokenFor(String username) {
        return tokens.issue(properties.users().stream().filter(u -> u.username().equals(username)).findFirst().orElseThrow())
                .token();
    }

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
                .andExpect(jsonPath("$.candidate").value(1))
                .andExpect(jsonPath("$.approval_token").value("a_TESTCODE01"));
    }

    @Test
    void agent_failure_is_reported_with_its_code() throws Exception {
        given(approvals.approve(any(), anyInt(), anyBoolean(), any()))
                .willThrow(new AgentException("AGENT_UNAVAILABLE", "SkillOps Agent 에 연결하지 못했습니다.", 502));

        mockMvc.perform(approve(tokenFor("operator")))
                .andExpect(status().isBadGateway())
                .andExpect(jsonPath("$.code").value("AGENT_UNAVAILABLE"));
    }

    @Test
    void approval_without_intent_confirmation_is_refused() throws Exception {
        mockMvc.perform(post(APPROVE).header("Authorization", "Bearer " + tokenFor("operator"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"candidate\":1,\"intent_confirmed\":false}"))
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
        // 인증을 붙인 곳 밖의 /api 는 막지 않는다(없는 경로라 404, 401 이 아님).
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
        // 이 slice 에는 MCP 서버가 없어 404 지만, 보안 필터가 401 로 막지 않는다는 것을 본다.
        mockMvc.perform(post("/mcp").contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(result -> org.assertj.core.api.Assertions.assertThat(result.getResponse().getStatus())
                        .isNotIn(401, 403));
    }

    private static JwtClaimsSet claims(Instant expiresAt) {
        return JwtClaimsSet.builder().issuer(TokenService.ISSUER).subject("operator")
                .claim("roles", List.of("OPERATOR")).issuedAt(expiresAt.minusSeconds(7200)).expiresAt(expiresAt).build();
    }
}
