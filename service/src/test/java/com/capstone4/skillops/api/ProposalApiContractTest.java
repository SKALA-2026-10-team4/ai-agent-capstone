package com.capstone4.skillops.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.never;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.RequestBuilder;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import com.capstone4.skillops.approval.Approval;
import com.capstone4.skillops.approval.ApprovalService;
import com.capstone4.skillops.auth.AuthProperties;
import com.capstone4.skillops.auth.AuthUser;
import com.capstone4.skillops.auth.SecurityConfig;
import com.capstone4.skillops.auth.TokenService;
import com.capstone4.skillops.proposal.Proposal;
import com.capstone4.skillops.proposal.ProposalException;
import com.capstone4.skillops.proposal.ProposalService;

import jakarta.servlet.ServletException;
import jakarta.validation.ConstraintViolationException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * 제안서 REST API 의 응답 계약을 고정하는 특성 테스트다. 응답 본문을 그대로 비교한다.
 * 보호하는 production 코드는 service/src/main/java/com/capstone4/skillops/api/ 의 ApprovalController, ProposalController,
 * ApprovalView, ProposalApiExceptionHandler 와 service/src/main/java/com/capstone4/skillops/auth/SecurityConfig.java 가 쓰는
 * 오류 본문이다.
 * 승인 기록 9칸, 오류 본문 {code, message}, 로그인과 권한 오류 본문, 경로의 제안서 번호 형식, 목록 요약과 상세의 JSON 모양을 본다.
 * 화면(frontend/src/api/proposals.js)이 이 모양을 그대로 읽으므로, 여기서 실패하면 화면도 깨질 수 있다.
 */
@WebMvcTest(controllers = { ApprovalController.class, ProposalController.class })
@Import({ SecurityConfig.class, TokenService.class })
class ProposalApiContractTest {

    static final JsonMapper JSON = JsonMapper.builder().build();
    static final Instant CREATED = Instant.parse("2026-10-21T05:10:00Z");
    static final Instant DECIDED = Instant.parse("2026-10-21T06:00:00Z");
    static final String APPROVE = "/api/proposals/p_stub_001/approve";
    static final String REJECT = "/api/proposals/p_stub_001/reject";
    static final String BODY = "{\"candidate_no\":1,\"intent_confirmed\":true}";
    static final AuthUser OPERATOR = new AuthUser("operator", "운영자", List.of("OPERATOR"));
    // ApprovalView 가 내보내는 9칸의 이름과 순서다.
    static final List<String> VIEW_KEYS = List.of("proposal_id", "decision", "candidate_no", "intent_confirmed",
            "approval_token", "approved_by", "approver_name", "decided_at", "used_at");
    // SecurityConfig.writeError 가 401 과 403 에 쓰는 본문 그대로다.
    static final String UNAUTHORIZED_BODY = "{\"code\":\"UNAUTHORIZED\",\"message\":\"로그인이 필요합니다.\"}";
    static final String FORBIDDEN_BODY = "{\"code\":\"FORBIDDEN\",\"message\":\"이 작업을 할 권한이 없습니다.\"}";

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

    /** 설정의 데모 계정으로 토큰을 발급해 Authorization 헤더 값(Bearer ...)을 만든다. */
    String bearer(String username) {
        return "Bearer " + tokens.issue(properties.users().stream().filter(u -> u.username().equals(username))
                .findFirst().orElseThrow()).token();
    }

    /** operator 토큰과 주어진 본문을 담은 승인 요청을 만든다. */
    MockHttpServletRequestBuilder approve(String body) {
        return post(APPROVE).header("Authorization", bearer("operator")).contentType(MediaType.APPLICATION_JSON)
                .content(body);
    }

    /** 요청을 보내고 결과를 돌려준다. */
    MvcResult perform(RequestBuilder request) throws Exception {
        return mockMvc.perform(request).andReturn();
    }

    /** 응답 본문을 UTF-8 글로 읽는다. */
    static String text(MvcResult result) throws Exception {
        return result.getResponse().getContentAsString(StandardCharsets.UTF_8);
    }

    /** 응답 본문을 JSON 트리로 읽는다. */
    static JsonNode json(MvcResult result) throws Exception {
        return JSON.readTree(text(result));
    }

    /** JSON 글을 트리로 읽는다. 기대값과 키 순서에 상관없이 비교할 때 쓴다. */
    static JsonNode tree(String json) {
        return JSON.readTree(json);
    }

    /** 상태 코드가 기대와 같고 응답 형식이 JSON 인지 본다. */
    static void assertJson(MvcResult result, int status) {
        assertThat(result.getResponse().getStatus()).isEqualTo(status);
        assertThat(MediaType.parseMediaType(result.getResponse().getContentType()))
                .matches(type -> type.isCompatibleWith(MediaType.APPLICATION_JSON));
    }

    /** 오류 응답의 상태와 본문을 본다. 본문은 code 와 message 두 칸뿐이어야 하며, 키 순서는 보지 않는다. */
    static void assertError(MvcResult result, int status, String code, String message) throws Exception {
        assertJson(result, status);
        JsonNode body = json(result);
        assertThat(body.propertyNames()).containsExactlyInAnyOrder("code", "message");
        assertThat(body).isEqualTo(JSON.createObjectNode().put("code", code).put("message", message));
    }

    /** 후보 1번을 승인한 기록 예시를 만든다. */
    static Approval approved() {
        return new Approval("p_stub_001", 1, "approved", true, "operator", "운영자", "a_TESTCODE01", DECIDED);
    }

    /** 거절한 기록 예시를 만든다. 후보 번호와 승인 번호가 없다. */
    static Approval rejected() {
        return new Approval("p_stub_001", null, "rejected", false, "operator", "운영자", null, DECIDED);
    }

    // approved() 와 rejected() 를 ApprovalView 로 내보냈을 때의 기대 본문이다.
    static final String APPROVED_VIEW = """
            {"proposal_id":"p_stub_001","decision":"approved","candidate_no":1,"intent_confirmed":true,
             "approval_token":"a_TESTCODE01","approved_by":"operator","approver_name":"운영자",
             "decided_at":"2026-10-21T06:00:00Z","used_at":null}""";

    static final String REJECTED_VIEW = """
            {"proposal_id":"p_stub_001","decision":"rejected","candidate_no":null,"intent_confirmed":false,
             "approval_token":null,"approved_by":"operator","approver_name":"운영자",
             "decided_at":"2026-10-21T06:00:00Z","used_at":null}""";

    // ── 승인 기록(ApprovalView) ─────────────────────────────────────────

    @Test
    void approvalResponseIsTheNineFieldView() throws Exception {
        given(approvals.approve(eq("p_stub_001"), eq(1), eq(true), eq(OPERATOR))).willReturn(approved());

        MvcResult result = perform(approve(BODY));

        assertJson(result, 200);
        assertThat(json(result).propertyNames()).containsExactlyElementsOf(VIEW_KEYS);
        assertThat(json(result)).isEqualTo(tree(APPROVED_VIEW));
    }

    @Test
    void rejectionResponseIsTheSameView() throws Exception {
        given(approvals.reject(eq("p_stub_001"), eq(OPERATOR))).willReturn(rejected());

        MvcResult result = perform(post(REJECT).header("Authorization", bearer("operator")));

        assertJson(result, 200);
        assertThat(json(result).propertyNames()).containsExactlyElementsOf(VIEW_KEYS);
        assertThat(json(result)).isEqualTo(tree(REJECTED_VIEW));
    }

    @Test
    void approvalHistoryWrapsViewsInItems() throws Exception {
        given(approvals.recent()).willReturn(List.of(approved(), rejected()));

        MvcResult result = perform(get("/api/approvals").header("Authorization", bearer("owner")));

        assertJson(result, 200);
        assertThat(json(result).propertyNames()).containsExactly("items");
        assertThat(json(result)).isEqualTo(tree("{\"items\":[" + APPROVED_VIEW + "," + REJECTED_VIEW + "]}"));
        assertThat(json(result).get("items").get(0).propertyNames()).containsExactlyElementsOf(VIEW_KEYS);
    }

    // ── 오류 본문 {code, message} ─────────────────────────────────────────

    @Test
    void approvalWithoutIntentIsRefusedBeforeTheService() throws Exception {
        assertError(perform(approve("{\"candidate_no\":1,\"intent_confirmed\":false}")), 400, "INTENT_NOT_CONFIRMED",
                "의도 확인 없이 승인할 수 없습니다.");
        then(approvals).should(never()).approve(any(), anyInt(), anyBoolean(), any());
    }

    @Test
    void approvalProblemsKeepCodeMessageAndStatus() throws Exception {
        Object[][] problems = {
                { "PROPOSAL_NOT_FOUND", "제안서를 찾을 수 없습니다.", 404 },
                { "PROPOSAL_ALREADY_DECIDED", "이미 결정한 제안서입니다.", 409 },
                { "NO_VALID_FIX", "승인할 수 있는 수정안이 없는 제안서입니다.", 409 },
                { "CANDIDATE_NOT_VALID", "모든 확인을 통과한 후보만 승인할 수 있습니다.", 400 } };
        for (Object[] p : problems) {
            willThrow(new ProposalException((String) p[0], (String) p[1], (Integer) p[2])).given(approvals)
                    .approve(any(), anyInt(), anyBoolean(), any());
            assertError(perform(approve(BODY)), (Integer) p[2], (String) p[0], (String) p[1]);
        }
    }

    @Test
    void rejectionProblemsKeepCodeMessageAndStatus() throws Exception {
        willThrow(new ProposalException("PROPOSAL_NOT_FOUND", "제안서를 찾을 수 없습니다.", 404)).given(approvals)
                .reject(any(), any());
        assertError(perform(post(REJECT).header("Authorization", bearer("operator"))), 404, "PROPOSAL_NOT_FOUND",
                "제안서를 찾을 수 없습니다.");

        willThrow(new ProposalException("PROPOSAL_ALREADY_DECIDED", "이미 결정한 제안서입니다.", 409)).given(approvals)
                .reject(any(), any());
        assertError(perform(post(REJECT).header("Authorization", bearer("operator"))), 409, "PROPOSAL_ALREADY_DECIDED",
                "이미 결정한 제안서입니다.");
    }

    @Test
    void concurrentDecisionBecomesAlreadyDecided() throws Exception {
        willThrow(new ObjectOptimisticLockingFailureException(Proposal.class, "p_stub_001")).given(approvals)
                .approve(any(), anyInt(), anyBoolean(), any());
        assertError(perform(approve(BODY)), 409, "PROPOSAL_ALREADY_DECIDED", "이미 결정한 제안서입니다.");

        willThrow(new ObjectOptimisticLockingFailureException(Proposal.class, "p_stub_001")).given(approvals)
                .reject(any(), any());
        assertError(perform(post(REJECT).header("Authorization", bearer("operator"))), 409, "PROPOSAL_ALREADY_DECIDED",
                "이미 결정한 제안서입니다.");
    }

    @Test
    void unknownProposalDetailIs404WithoutReadingDecisions() throws Exception {
        given(proposals.find("p_none")).willReturn(Optional.empty());

        assertError(perform(get("/api/proposals/p_none").header("Authorization", bearer("owner"))), 404,
                "PROPOSAL_NOT_FOUND", "제안서를 찾을 수 없습니다.");
        then(approvals).should(never()).latestFor(any());
    }

    @Test
    void invalidRequestBodyIsBadRequestWithoutBody() throws Exception {
        MvcResult result = perform(approve("{\"candidate_no\":0,\"intent_confirmed\":true}"));

        assertThat(result.getResponse().getStatus()).isEqualTo(400);
        assertThat(text(result)).isEmpty();
        then(approvals).should(never()).approve(any(), anyInt(), anyBoolean(), any());
    }

    /**
     * 경로의 제안서 번호는 service/src/main/java/com/capstone4/skillops/proposal/Proposal.java 의 ID_PATTERN 으로 검사한다.
     * p_ 뒤 62자까지는 받고, p_ 로 시작하지 않는 번호, 빈 꼬리, 허용하지 않는 글자, 63자는 ConstraintViolationException 으로
     * 막히는지 승인, 거절, 상세 세 경로에서 본다.
     */
    @Test
    void proposalIdPatternIsEnforcedAsConstraintViolation() throws Exception {
        String longest = "p_" + "a".repeat(62);
        given(proposals.find(longest)).willReturn(Optional.empty());
        given(approvals.approve(any(), anyInt(), anyBoolean(), any())).willReturn(approved());
        given(approvals.reject(any(), any())).willReturn(rejected());
        assertError(perform(get("/api/proposals/" + longest).header("Authorization", bearer("owner"))), 404,
                "PROPOSAL_NOT_FOUND", "제안서를 찾을 수 없습니다.");
        assertJson(perform(post("/api/proposals/" + longest + "/approve").header("Authorization", bearer("operator"))
                .contentType(MediaType.APPLICATION_JSON).content(BODY)), 200);
        assertJson(perform(post("/api/proposals/p_Z9_/reject").header("Authorization", bearer("operator"))), 200);

        for (String bad : List.of("x_bad", "p_", "p_a-b", "p_" + "a".repeat(63))) {
            for (RequestBuilder request : List.of(
                    post("/api/proposals/" + bad + "/approve").header("Authorization", bearer("operator"))
                            .contentType(MediaType.APPLICATION_JSON).content(BODY),
                    post("/api/proposals/" + bad + "/reject").header("Authorization", bearer("operator")),
                    get("/api/proposals/" + bad).header("Authorization", bearer("owner")))) {
                assertThatThrownBy(() -> mockMvc.perform(request)).as(bad).isInstanceOf(ServletException.class)
                        .hasCauseInstanceOf(ConstraintViolationException.class);
            }
        }
    }

    // ── 로그인·권한 오류(SecurityConfig 가 직접 쓰는 본문) ────────────────────

    @Test
    void protectedPathsNeedLogin() throws Exception {
        for (RequestBuilder request : List.of(get("/api/approvals"), get("/api/diagnoses"),
                get("/api/diagnoses/" + UUID.fromString("5b0c2f3e-8f0a-4a57-9a3c-1c2d3e4f5a6b")), get("/api/proposals"),
                get("/api/proposals/p_abc"), post(APPROVE).contentType(MediaType.APPLICATION_JSON).content(BODY),
                post(REJECT))) {
            MvcResult result = perform(request);
            assertThat(result.getResponse().getStatus()).isEqualTo(401);
            assertThat(result.getResponse().getHeader("WWW-Authenticate")).isEqualTo("Bearer");
            assertThat(text(result)).isEqualTo(UNAUTHORIZED_BODY);
        }
    }

    @Test
    void ownerCannotDecide() throws Exception {
        for (RequestBuilder request : List.of(
                post(APPROVE).header("Authorization", bearer("owner")).contentType(MediaType.APPLICATION_JSON).content(BODY),
                post(REJECT).header("Authorization", bearer("owner")))) {
            MvcResult result = perform(request);
            assertThat(result.getResponse().getStatus()).isEqualTo(403);
            assertThat(text(result)).isEqualTo(FORBIDDEN_BODY);
        }
    }

    // ── 목록 요약과 상세 ─────────────────────────────────────────────────

    // ProposalController.ProposalSummary 가 내보내는 칸의 이름과 순서다.
    static final List<String> SUMMARY_KEYS = List.of("proposal_id", "status", "created_at", "title", "summary",
            "root_cause_skill", "recommended_candidate_no", "valid_candidate_nos", "candidate_count");

    /**
     * 비었거나 모양이 이상한 payload 와 document 도 오류 없이 요약하는지 본다. 숫자 title 은 글로 바뀌고, 객체가 아닌 root_cause 는
     * null 이 되며, 2.0 인 추천 번호는 2 가 되고, candidates 의 원소는 모양과 상관없이 모두 센다.
     */
    @Test
    void summariesReadPayloadAndDocumentLeniently() throws Exception {
        Proposal empty = Proposal.create("p_empty", null, "no_valid_fix", new LinkedHashMap<>(), null, null, CREATED);
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("root_cause", "not-a-map");
        payload.put("recommended_candidate_no", 2.0);
        payload.put("candidates", new ArrayList<>(Arrays.asList(Map.of("candidate_no", 1), "junk", null, 3)));
        Map<String, Object> document = new LinkedHashMap<>();
        document.put("title", 5);
        document.put("summary", "");
        Proposal odd = Proposal.create("p_odd", "thread-2", "proposed", payload, document, List.of(2), CREATED);
        given(proposals.recent()).willReturn(List.of(empty, odd));

        MvcResult result = perform(get("/api/proposals").header("Authorization", bearer("owner")));

        assertJson(result, 200);
        assertThat(json(result).propertyNames()).containsExactly("items");
        assertThat(json(result).get("items").get(0).propertyNames()).containsExactlyElementsOf(SUMMARY_KEYS);
        assertThat(json(result)).isEqualTo(tree("""
                {"items":[
                 {"proposal_id":"p_empty","status":"no_valid_fix","created_at":"2026-10-21T05:10:00Z","title":null,
                  "summary":null,"root_cause_skill":null,"recommended_candidate_no":null,"valid_candidate_nos":[],
                  "candidate_count":0},
                 {"proposal_id":"p_odd","status":"proposed","created_at":"2026-10-21T05:10:00Z","title":"5",
                  "summary":"","root_cause_skill":null,"recommended_candidate_no":2,"valid_candidate_nos":[2],
                  "candidate_count":4}]}"""));
    }

    @Test
    void detailCarriesEveryField() throws Exception {
        Map<String, Object> root = new LinkedHashMap<>();
        root.put("skill", "inventory-check");
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("thread_id", "thread-1");
        payload.put("root_cause", root);
        payload.put("recommended_candidate_no", 1);
        payload.put("status", "proposed");
        Map<String, Object> document = new LinkedHashMap<>();
        document.put("title", "inventory-check 수정 제안서");
        document.put("summary", "요약 문장입니다.");
        Proposal proposal = Proposal.create("p_abc", "thread-1", "proposed", payload, document, List.of(1), CREATED);
        given(proposals.find("p_abc")).willReturn(Optional.of(proposal));
        given(approvals.latestFor("p_abc")).willReturn(Optional.of(approved()));

        MvcResult result = perform(get("/api/proposals/p_abc").header("Authorization", bearer("owner")));

        assertJson(result, 200);
        assertThat(json(result).propertyNames()).containsExactly("proposal_id", "status", "created_at", "decided_at",
                "thread_id", "valid_candidate_nos", "recommended_candidate_no", "document", "proposal", "decision");
        assertThat(json(result).get("decision").propertyNames()).containsExactlyElementsOf(VIEW_KEYS);
        assertThat(json(result)).isEqualTo(tree("""
                {"proposal_id":"p_abc","status":"proposed","created_at":"2026-10-21T05:10:00Z","decided_at":null,
                 "thread_id":"thread-1","valid_candidate_nos":[1],"recommended_candidate_no":1,
                 "document":{"title":"inventory-check 수정 제안서","summary":"요약 문장입니다."},
                 "proposal":{"thread_id":"thread-1","root_cause":{"skill":"inventory-check"},
                             "recommended_candidate_no":1,"status":"proposed"},
                 "decision":""" + APPROVED_VIEW + "}"));
    }

    @Test
    void undecidedDetailHasNullDecisionAndEmptyDefaults() throws Exception {
        Proposal proposal = Proposal.create("p_new", null, "no_valid_fix", new LinkedHashMap<>(), null, null, CREATED);
        given(proposals.find("p_new")).willReturn(Optional.of(proposal));
        given(approvals.latestFor("p_new")).willReturn(Optional.empty());

        MvcResult result = perform(get("/api/proposals/p_new").header("Authorization", bearer("operator")));

        assertJson(result, 200);
        assertThat(json(result)).isEqualTo(tree("""
                {"proposal_id":"p_new","status":"no_valid_fix","created_at":"2026-10-21T05:10:00Z","decided_at":null,
                 "thread_id":null,"valid_candidate_nos":[],"recommended_candidate_no":null,"document":{},
                 "proposal":{},"decision":null}"""));
    }
}
