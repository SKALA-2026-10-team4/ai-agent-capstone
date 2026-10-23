package com.capstone4.skillops.api;

import java.util.List;
import java.util.Map;

import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import com.capstone4.skillops.approval.ApprovalService;
import com.capstone4.skillops.auth.TokenService;
import com.capstone4.skillops.proposal.Proposal;
import com.capstone4.skillops.proposal.ProposalException;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import tools.jackson.databind.PropertyNamingStrategies;
import tools.jackson.databind.annotation.JsonNaming;

/**
 * 운영자가 수정 제안서의 후보를 승인하거나 제안서를 거절하는 REST API 다. 승인 기록 목록 조회도 함께 맡는다.
 * POST /api/proposals/{proposalId}/approve 는 본문 {candidate_no, intent_confirmed} 를 받아 승인 기록과 approval_token 을
 * 돌려준다. candidate_no 는 제안서의 후보 번호(1부터)이고 합격한 후보만 받는다. 거절(POST /api/proposals/{proposalId}/reject)과
 * 승인 기록 조회(GET /api/approvals)는 화면에 필요해서 덧붙였다.
 * 결정은 OPERATOR 역할만 할 수 있다(service/src/main/java/com/capstone4/skillops/auth/SecurityConfig.java). 결정은 저장된 제안서에
 * 표시하며, 같은 제안서는 한 번만 결정할 수 있다.
 * 결정 기록은 service/src/main/java/com/capstone4/skillops/approval/ApprovalService.java 가 남기고, 결정 규칙은
 * service/src/main/java/com/capstone4/skillops/proposal/ProposalService.java 의 markDecided 와 checkDecidable 에 있다.
 * 서비스가 거부하면 service/src/main/java/com/capstone4/skillops/proposal/ProposalException.java 가 올라오고, 같은 패키지
 * (service/src/main/java/com/capstone4/skillops/api/)의 ProposalApiExceptionHandler 가 {code, message} 응답으로 바꾼다.
 * 컨트롤러가 직접 판단하는 의도 미확인 거부만 예외 대신 반환값으로 돌려준다(approve 의 설명 참고).
 * 화면은 frontend/src/api/proposals.js 의 approveProposal(), rejectProposal(), listApprovals() 로 /service 접두어를 붙여 부른다.
 * approveProposal() 과 rejectProposal() 은 frontend/src/views/ProposalView.vue 가 쓰고, listApprovals() 를 부르는 화면 코드는
 * 지금 없다.
 */
@RestController
@Validated
public class ApprovalController {

    private final ApprovalService approvals;

    /** Spring 이 ApprovalService 빈을 넣어 만든다. */
    public ApprovalController(ApprovalService approvals) {
        this.approvals = approvals;
    }

    /**
     * 후보 하나를 승인한다. 요청은 SecurityConfig 의 OPERATOR 검사, 경로의 제안서 번호 형식 검사
     * (service/src/main/java/com/capstone4/skillops/proposal/Proposal.java 의 ID_PATTERN), 본문 검증(candidate_no 1 이상,
     * intent_confirmed 필수)을 모두 통과해야 이 메서드 안으로 들어온다.
     * intent_confirmed 가 false 면 ApprovalService 를 부르지 않고, ProposalApiExceptionHandler.toResponse 로 만든
     * 400 INTENT_NOT_CONFIRMED 응답을 반환값으로 돌려준다. 예외를 던지지 않는 이유는 JSON 을 받지 않는 클라이언트에게 리팩토링
     * 전처럼 406 을 주기 위해서다(service/src/test/java/com/capstone4/skillops/api/ContentNegotiationContractTest.java 가 고정한다).
     * 통과하면 토큰의 사용자를 결정자로 ApprovalService.approve 에 넘기고, 저장된 승인 기록을 ApprovalView 에 담아 200 으로 돌려준다.
     * 반환형을 ResponseEntity 로 두면 응답 본문이 리팩토링 전과 같은 방식(Transfer-Encoding: chunked)으로 나간다.
     * 제안서 번호 형식이 틀리면 ConstraintViolationException 이 나고, 본문 검증에 실패하면 Spring 기본 400 이 나간다. 두 경우 모두
     * ProposalApiExceptionHandler 가 다루지 않아 {code, message} 본문이 아니다.
     */
    @PostMapping("/api/proposals/{proposalId}/approve")
    public ResponseEntity<?> approve(@PathVariable @Pattern(regexp = Proposal.ID_PATTERN) String proposalId,
            @Valid @RequestBody ApproveRequest request, @AuthenticationPrincipal Jwt jwt) {
        // 설계 기준 3(의도 보존)에 따라, 담당자가 의도 해석을 확인하지 않은 승인은 받지 않는다.
        if (!request.intentConfirmed()) {
            return ProposalApiExceptionHandler.toResponse(ProposalException.intentNotConfirmed());
        }
        return ResponseEntity.ok(ApprovalView.of(
                approvals.approve(proposalId, request.candidateNo(), true, TokenService.userOf(jwt))));
    }

    /**
     * 제안서를 거절한다. 본문은 받지 않고 토큰의 사용자를 결정자로 ApprovalService.reject 에 넘긴다. 승인할 수정안이 없는
     * no_valid_fix 제안서를 닫을 때도 이 경로를 쓴다. 응답은 candidate_no 와 approval_token 이 null 인 ApprovalView 다.
     */
    @PostMapping("/api/proposals/{proposalId}/reject")
    public ApprovalView reject(@PathVariable @Pattern(regexp = Proposal.ID_PATTERN) String proposalId,
            @AuthenticationPrincipal Jwt jwt) {
        return ApprovalView.of(approvals.reject(proposalId, TokenService.userOf(jwt)));
    }

    /** 최근 결정 50개를 결정 시각이 늦은 것부터 {items: [...]} 로 감싸 돌려준다. 로그인한 사람이면 역할과 상관없이 읽는다. */
    @GetMapping("/api/approvals")
    public Map<String, List<ApprovalView>> recent() {
        return Map.of("items", approvals.recent().stream().map(ApprovalView::of).toList());
    }

    /**
     * 승인 요청 본문이다. JSON 칸 이름은 candidate_no, intent_confirmed 이고 두 칸 모두 필수이며 candidate_no 는 1 이상이어야 한다.
     * 칸 타입이 Integer 와 Boolean 이라 칸이 빠지면 null 이 되어 @NotNull 검증에서 400 으로 끝난다.
     */
    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record ApproveRequest(@NotNull @Min(1) Integer candidateNo, @NotNull Boolean intentConfirmed) {
    }
}
