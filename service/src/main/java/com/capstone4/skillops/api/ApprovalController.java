package com.capstone4.skillops.api;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import org.springframework.http.ResponseEntity;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import com.capstone4.skillops.approval.Approval;
import com.capstone4.skillops.approval.ApprovalService;
import com.capstone4.skillops.auth.TokenService;
import com.capstone4.skillops.proposal.ProposalException;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import tools.jackson.databind.PropertyNamingStrategies;
import tools.jackson.databind.annotation.JsonNaming;

/**
 * 운영자의 수정안 승인/거절. `POST /api/proposals/{id}/approve {candidate_no, intent_confirmed}` → 승인 기록과
 * `approval_token`. candidate_no 는 제안서의 후보 번호(1부터)이고 합격한 후보만 받는다. 거절(`/reject`)과 승인 기록 조회
 * (`GET /api/approvals`)는 화면에 필요해 덧붙였다. OPERATOR 역할만 결정할 수 있다(SecurityConfig).
 * 결정은 저장된 제안서에 표시하며, 같은 제안서는 한 번만 결정할 수 있다.
 */
@RestController
@Validated
public class ApprovalController {

    private static final String PROPOSAL_ID = "p_[A-Za-z0-9_]{1,62}";

    private final ApprovalService approvals;

    public ApprovalController(ApprovalService approvals) {
        this.approvals = approvals;
    }

    @PostMapping("/api/proposals/{proposalId}/approve")
    public ResponseEntity<?> approve(@PathVariable @Pattern(regexp = PROPOSAL_ID) String proposalId,
            @Valid @RequestBody ApproveRequest request, @AuthenticationPrincipal Jwt jwt) {
        // 설계 기준 3(의도 보존): 담당자가 의도 해석을 확인하지 않은 승인은 받지 않는다.
        if (!request.intentConfirmed()) {
            return ResponseEntity.badRequest()
                    .body(Map.of("code", "INTENT_NOT_CONFIRMED", "message", "의도 확인 없이 승인할 수 없습니다."));
        }
        return ResponseEntity.ok(ApprovalView.of(
                approvals.approve(proposalId, request.candidateNo(), true, TokenService.userOf(jwt))));
    }

    @PostMapping("/api/proposals/{proposalId}/reject")
    public ApprovalView reject(@PathVariable @Pattern(regexp = PROPOSAL_ID) String proposalId,
            @AuthenticationPrincipal Jwt jwt) {
        return ApprovalView.of(approvals.reject(proposalId, TokenService.userOf(jwt)));
    }

    @GetMapping("/api/approvals")
    public Map<String, List<ApprovalView>> recent() {
        return Map.of("items", approvals.recent().stream().map(ApprovalView::of).toList());
    }

    @ExceptionHandler(ProposalException.class)
    ResponseEntity<Map<String, String>> proposalRefused(ProposalException e) {
        return ResponseEntity.status(e.httpStatus()).body(Map.of("code", e.code(), "message", e.getMessage()));
    }

    /** 두 운영자가 같은 제안서를 동시에 결정하면 늦게 끝난 쪽이 여기로 온다 */
    @ExceptionHandler(ObjectOptimisticLockingFailureException.class)
    ResponseEntity<Map<String, String>> decidedConcurrently() {
        return ResponseEntity.status(409)
                .body(Map.of("code", "PROPOSAL_ALREADY_DECIDED", "message", "이미 결정한 제안서입니다."));
    }

    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record ApproveRequest(@NotNull @Min(1) Integer candidateNo, @NotNull Boolean intentConfirmed) {
    }

    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record ApprovalView(String proposalId, String decision, Integer candidateNo, boolean intentConfirmed,
            String approvalToken, String approvedBy, String approverName, Instant decidedAt, Instant usedAt) {

        public static ApprovalView of(Approval a) {
            return new ApprovalView(a.getProposalId(), a.getDecision(), a.getCandidateNo(), a.isIntentConfirmed(),
                    a.getApprovalToken(), a.getApprovedBy(), a.getApproverName(), a.getDecidedAt(), a.getUsedAt());
        }
    }
}
