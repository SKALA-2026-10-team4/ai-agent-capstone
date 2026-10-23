package com.capstone4.skillops.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willThrow;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import java.nio.charset.StandardCharsets;
import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.RequestBuilder;

import com.capstone4.skillops.approval.ApprovalService;
import com.capstone4.skillops.auth.AuthController;
import com.capstone4.skillops.auth.AuthProperties;
import com.capstone4.skillops.auth.SecurityConfig;
import com.capstone4.skillops.auth.TokenService;
import com.capstone4.skillops.common.ApiError;
import com.capstone4.skillops.proposal.Proposal;
import com.capstone4.skillops.proposal.ProposalService;

/**
 * REST 오류 본문을 {@link ApiError}(service/src/main/java/com/capstone4/skillops/common/ApiError.java) 하나로 만들어 언제나
 * code, message 순서로 내보내는 동작을 보호한다. 응답 문자열을 키 순서까지 그대로 비교한다.
 * 대상은 service/src/main/java/com/capstone4/skillops/api/ 의 ProposalController.detail 404, ApprovalController.approve 의
 * 의도 확인 거절 400, 거절 중 낙관적 잠금 실패를 409 로 바꾸는 ProposalApiExceptionHandler.decidedConcurrently, 그리고
 * service/src/main/java/com/capstone4/skillops/auth/AuthController.java 의 로그인 실패 401 이다.
 * ApiError.response 가 만드는 응답과 생성자의 null 검사도 함께 본다.
 */
@WebMvcTest(controllers = { ApprovalController.class, ProposalController.class, AuthController.class })
@Import({ SecurityConfig.class, TokenService.class })
class ApiErrorBodyTest {

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

    /** 요청을 보내 상태 코드와 응답 본문 문자열이 기대와 정확히 같은지 본다. */
    void assertBody(RequestBuilder request, int status, String body) throws Exception {
        MvcResult result = mockMvc.perform(request).andReturn();
        assertThat(result.getResponse().getStatus()).isEqualTo(status);
        assertThat(result.getResponse().getContentAsString(StandardCharsets.UTF_8)).isEqualTo(body);
    }

    @Test
    void errorBodiesListCodeThenMessage() throws Exception {
        given(proposals.find("p_none")).willReturn(Optional.empty());
        willThrow(new ObjectOptimisticLockingFailureException(Proposal.class, "p_stub_001")).given(approvals)
                .reject(any(), any());

        assertBody(get("/api/proposals/p_none").header("Authorization", bearer("owner")), 404,
                "{\"code\":\"PROPOSAL_NOT_FOUND\",\"message\":\"제안서를 찾을 수 없습니다.\"}");
        assertBody(post("/api/proposals/p_stub_001/approve").header("Authorization", bearer("operator"))
                .contentType(MediaType.APPLICATION_JSON).content("{\"candidate_no\":1,\"intent_confirmed\":false}"), 400,
                "{\"code\":\"INTENT_NOT_CONFIRMED\",\"message\":\"의도 확인 없이 승인할 수 없습니다.\"}");
        assertBody(post("/api/proposals/p_stub_001/reject").header("Authorization", bearer("operator")), 409,
                "{\"code\":\"PROPOSAL_ALREADY_DECIDED\",\"message\":\"이미 결정한 제안서입니다.\"}");
        assertBody(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                .content("{\"username\":\"operator\",\"password\":\"nope\"}"), 401,
                "{\"code\":\"INVALID_CREDENTIALS\",\"message\":\"아이디 또는 비밀번호가 올바르지 않습니다.\"}");
    }

    @Test
    void apiErrorBuildsTheResponse() {
        ResponseEntity<ApiError> response = ApiError.response(409, "SOME_CODE", "문구");

        assertThat(response.getStatusCode().value()).isEqualTo(409);
        assertThat(response.getBody()).isEqualTo(new ApiError("SOME_CODE", "문구"));
    }

    @Test
    void apiErrorNeedsCodeAndMessage() {
        assertThatThrownBy(() -> new ApiError(null, "문구")).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new ApiError("SOME_CODE", null)).isInstanceOf(NullPointerException.class);
    }
}
