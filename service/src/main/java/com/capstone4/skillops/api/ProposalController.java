package com.capstone4.skillops.api;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

import com.capstone4.skillops.api.ApprovalController.ApprovalView;
import com.capstone4.skillops.approval.ApprovalService;
import com.capstone4.skillops.proposal.Proposal;
import com.capstone4.skillops.proposal.ProposalService;

import jakarta.validation.constraints.Pattern;
import tools.jackson.databind.PropertyNamingStrategies;
import tools.jackson.databind.annotation.JsonNaming;

/**
 * 화면이 읽는 수정 제안서. 목록(`GET /api/proposals`)과 본문·결정까지 담은 상세(`GET /api/proposals/{id}`).
 * 로그인한 사람은 누구나 읽고, 결정은 {@link ApprovalController} 에서 OPERATOR 만 한다.
 */
@RestController
@Validated
public class ProposalController {

    private static final String PROPOSAL_ID = "p_[A-Za-z0-9_]{1,62}";

    private final ProposalService proposals;
    private final ApprovalService approvals;

    public ProposalController(ProposalService proposals, ApprovalService approvals) {
        this.proposals = proposals;
        this.approvals = approvals;
    }

    @GetMapping("/api/proposals")
    public Map<String, List<ProposalSummary>> list() {
        return Map.of("items", proposals.recent().stream().map(ProposalSummary::of).toList());
    }

    @GetMapping("/api/proposals/{proposalId}")
    public ResponseEntity<?> detail(@PathVariable @Pattern(regexp = PROPOSAL_ID) String proposalId) {
        return proposals.find(proposalId)
                .<ResponseEntity<?>>map(p -> ResponseEntity.ok(ProposalDetail.of(p,
                        approvals.latestFor(proposalId).map(ApprovalView::of).orElse(null))))
                .orElseGet(() -> ResponseEntity.status(404)
                        .body(Map.of("code", "PROPOSAL_NOT_FOUND", "message", "제안서를 찾을 수 없습니다.")));
    }

    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record ProposalSummary(String proposalId, String status, Instant createdAt, String title, String summary,
            String rootCauseSkill, Integer recommendedCandidateNo, List<Integer> validCandidateNos,
            int candidateCount) {

        static ProposalSummary of(Proposal p) {
            Map<String, Object> payload = p.getPayload();
            Map<String, Object> document = p.getDocument();
            return new ProposalSummary(p.getProposalId(), p.getStatus(), p.getCreatedAt(), text(document.get("title")),
                    text(document.get("summary")), text(mapOf(payload.get("root_cause")).get("skill")),
                    number(payload.get("recommended_candidate_no")), p.getValidCandidateNos(),
                    payload.get("candidates") instanceof List<?> list ? list.size() : 0);
        }
    }

    /** proposal 은 B 가 보낸 제안서 그대로, document 는 저장할 때 만든 본문이다. 결정 전이면 decision 은 null */
    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record ProposalDetail(String proposalId, String status, Instant createdAt, Instant decidedAt,
            String threadId, List<Integer> validCandidateNos, Integer recommendedCandidateNo,
            Map<String, Object> document, Map<String, Object> proposal, ApprovalView decision) {

        static ProposalDetail of(Proposal p, ApprovalView decision) {
            return new ProposalDetail(p.getProposalId(), p.getStatus(), p.getCreatedAt(), p.getDecidedAt(),
                    p.getThreadId(), p.getValidCandidateNos(), number(p.getPayload().get("recommended_candidate_no")),
                    p.getDocument(), p.getPayload(), decision);
        }
    }

    private static String text(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    private static Integer number(Object value) {
        return value instanceof Number n ? n.intValue() : null;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> mapOf(Object value) {
        return value instanceof Map<?, ?> m ? (Map<String, Object>) m : Map.of();
    }
}
