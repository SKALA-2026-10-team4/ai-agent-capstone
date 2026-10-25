package com.capstone4.skillops.api;

import static org.mockito.BDDMockito.given;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import com.capstone4.skillops.approval.Approval;
import com.capstone4.skillops.approval.ApprovalService;
import com.capstone4.skillops.auth.AuthProperties;
import com.capstone4.skillops.auth.SecurityConfig;
import com.capstone4.skillops.auth.TokenService;
import com.capstone4.skillops.proposal.Proposal;
import com.capstone4.skillops.proposal.ProposalService;

/**
 * 화면이 읽는 제안서 목록과 상세(service/src/main/java/com/capstone4/skillops/api/ProposalController.java)를 보호한다.
 * 로그인하지 않으면 401 이고 로그인한 사람은 역할과 상관없이 읽는지, 목록 요약의 칸 값, 상세에 담기는 본문과 원본 제안서와
 * 마지막 결정, 결정 전 상세의 빈 decision, 없는 번호의 404 PROPOSAL_NOT_FOUND 를 본다.
 */
@WebMvcTest(ProposalController.class)
@Import({ SecurityConfig.class, TokenService.class })
class ProposalControllerTest {

    @Autowired
    MockMvc mockMvc;

    @Autowired
    TokenService tokens;

    @Autowired
    AuthProperties properties;

    @MockitoBean
    ProposalService proposals;

    @MockitoBean
    ApprovalService approvals;

    /** 설정의 데모 계정으로 토큰을 발급해 토큰 문자열을 돌려준다. */
    private String tokenFor(String username) {
        return tokens.issue(properties.users().stream().filter(u -> u.username().equals(username)).findFirst().orElseThrow())
                .token();
    }

    /** 목록과 상세 시험에 쓰는 제안서 예시를 만든다. 후보는 둘이고, 추천 후보와 합격 후보는 1번이다. */
    static Proposal proposal() {
        Map<String, Object> payload = Map.of(
                "root_cause", Map.of("skill", "inventory-check"),
                "candidates", List.of(Map.of("candidate_no", 1), Map.of("candidate_no", 2)),
                "recommended_candidate_no", 1,
                "status", "proposed");
        Map<String, Object> document = Map.of(
                "title", "inventory-check 수정 제안서",
                "summary", "요약 문장입니다.",
                "sections", List.of(Map.of("key", "overview", "heading", "개요")),
                "tone_warnings", List.of());
        return Proposal.create("p_abc", "thread-1", "proposed", payload, document, List.of(1),
                Instant.parse("2026-10-21T05:10:00Z"));
    }

    @Test
    void listRequiresLogin() throws Exception {
        mockMvc.perform(get("/api/proposals")).andExpect(status().isUnauthorized());
    }

    @Test
    void ownerCanReadList() throws Exception {
        given(proposals.recent()).willReturn(List.of(proposal()));

        mockMvc.perform(get("/api/proposals").header("Authorization", "Bearer " + tokenFor("owner")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].proposal_id").value("p_abc"))
                .andExpect(jsonPath("$.items[0].status").value("proposed"))
                .andExpect(jsonPath("$.items[0].title").value("inventory-check 수정 제안서"))
                .andExpect(jsonPath("$.items[0].summary").value("요약 문장입니다."))
                .andExpect(jsonPath("$.items[0].root_cause_skill").value("inventory-check"))
                .andExpect(jsonPath("$.items[0].recommended_candidate_no").value(1))
                .andExpect(jsonPath("$.items[0].valid_candidate_nos[0]").value(1))
                .andExpect(jsonPath("$.items[0].candidate_count").value(2));
    }

    @Test
    void detailCarriesDocumentAndDecision() throws Exception {
        given(proposals.find("p_abc")).willReturn(Optional.of(proposal()));
        given(approvals.latestFor("p_abc")).willReturn(Optional.of(new Approval("p_abc", 1, "approved", true,
                "operator", "운영자", "a_TESTCODE01", Instant.parse("2026-10-21T06:00:00Z"))));

        mockMvc.perform(get("/api/proposals/p_abc").header("Authorization", "Bearer " + tokenFor("owner")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.proposal_id").value("p_abc"))
                .andExpect(jsonPath("$.thread_id").value("thread-1"))
                .andExpect(jsonPath("$.document.sections[0].heading").value("개요"))
                .andExpect(jsonPath("$.proposal.candidates[0].candidate_no").value(1))
                .andExpect(jsonPath("$.recommended_candidate_no").value(1))
                .andExpect(jsonPath("$.decision.candidate_no").value(1))
                .andExpect(jsonPath("$.decision.approval_token").value("a_TESTCODE01"))
                .andExpect(jsonPath("$.decision.approver_name").value("운영자"));
    }

    /** 개발 요청서(development_request)는 저장한 원본 제안서에 있으므로 상세의 proposal 에 그대로 나온다(docs/plans/2026-10-26-behavior-edit-root-fix.md 2.11 절). */
    @Test
    void detailCarriesTheDevelopmentRequest() throws Exception {
        Map<String, Object> request = Map.of(
                "kind", "needs_tool",
                "title", "shipping.weather 값을 만드는 도구 필요",
                "gaps", List.of(Map.of("key", "shipping.weather", "needed_by", "shipping-reserve", "field", "inputs",
                        "kind", "missing_value")),
                "options", List.of(Map.of("type", "new_tool", "suggested_name", "weather.lookup")),
                "interim", Map.of("summary", "입력에서 값을 빼면 주문이 끝까지 처리됩니다.", "proof_run_ids",
                        List.of("r_d0000000000000000000000000000002")));
        Map<String, Object> payload = Map.of("candidates", List.of(), "status", "no_valid_fix", "development_request", request);
        given(proposals.find("p_dev")).willReturn(Optional.of(Proposal.create("p_dev", "thread-2", "no_valid_fix", payload,
                Map.of("sections", List.of(Map.of("key", "development_request", "heading", "개발 요청"))), List.of(),
                Instant.parse("2026-10-26T01:30:00Z"))));

        mockMvc.perform(get("/api/proposals/p_dev").header("Authorization", "Bearer " + tokenFor("owner")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("no_valid_fix"))
                .andExpect(jsonPath("$.proposal.development_request.kind").value("needs_tool"))
                .andExpect(jsonPath("$.proposal.development_request.gaps[0].key").value("shipping.weather"))
                .andExpect(jsonPath("$.proposal.development_request.options[0].suggested_name").value("weather.lookup"))
                .andExpect(jsonPath("$.proposal.development_request.interim.proof_run_ids[0]")
                        .value("r_d0000000000000000000000000000002"))
                .andExpect(jsonPath("$.document.sections[0].heading").value("개발 요청"));
    }

    @Test
    void undecidedDetailHasNullDecision() throws Exception {
        given(proposals.find("p_abc")).willReturn(Optional.of(proposal()));

        mockMvc.perform(get("/api/proposals/p_abc").header("Authorization", "Bearer " + tokenFor("operator")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.decision").doesNotExist());
    }

    @Test
    void unknownProposalIs404() throws Exception {
        given(proposals.find("p_none")).willReturn(Optional.empty());

        mockMvc.perform(get("/api/proposals/p_none").header("Authorization", "Bearer " + tokenFor("owner")))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("PROPOSAL_NOT_FOUND"))
                .andExpect(jsonPath("$.message").value("제안서를 찾을 수 없습니다."));
    }
}
