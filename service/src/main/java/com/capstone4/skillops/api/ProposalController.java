package com.capstone4.skillops.api;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

import com.capstone4.skillops.approval.ApprovalService;
import com.capstone4.skillops.proposal.Proposal;
import com.capstone4.skillops.proposal.ProposalException;
import com.capstone4.skillops.proposal.ProposalService;

import jakarta.validation.constraints.Pattern;
import tools.jackson.databind.PropertyNamingStrategies;
import tools.jackson.databind.annotation.JsonNaming;

/**
 * 화면이 읽는 수정 제안서 REST API 다. GET /api/proposals 는 최근 제안서 목록을 요약으로 돌려주고,
 * GET /api/proposals/{proposalId} 는 본문, 원본 제안서, 마지막 결정을 담은 상세를 돌려준다.
 * 로그인한 사람은 누구나 읽고(service/src/main/java/com/capstone4/skillops/auth/SecurityConfig.java), 결정은 같은 패키지
 * (service/src/main/java/com/capstone4/skillops/api/)의 {@link ApprovalController} 에서 OPERATOR 만 한다.
 * 제안서는 service/src/main/java/com/capstone4/skillops/proposal/ProposalService.java 에서 읽고, 마지막 결정은
 * service/src/main/java/com/capstone4/skillops/approval/ApprovalService.java 에서 읽는다.
 * 화면은 frontend/src/api/proposals.js 의 listProposals() 와 getProposal() 로 부른다. listProposals() 는
 * frontend/src/views/ProposalListView.vue 와 frontend/src/components/AppSidebar.vue(검토 대기 숫자)가 쓰고, getProposal() 은
 * frontend/src/views/ProposalView.vue 가 쓴다. 상세의 document 는 frontend/src/components/ProposalDocument.vue 가 그린다.
 */
@RestController
@Validated
public class ProposalController {

    private final ProposalService proposals;
    private final ApprovalService approvals;

    /** Spring 이 ProposalService 와 ApprovalService 빈을 넣어 만든다. */
    public ProposalController(ProposalService proposals, ApprovalService approvals) {
        this.proposals = proposals;
        this.approvals = approvals;
    }

    /**
     * 만든 시각이 늦은 제안서 100개를 {items: [...]} 로 감싸 요약으로 돌려준다. 개수 100 은
     * service/src/main/java/com/capstone4/skillops/proposal/ProposalRepository.java 의 메서드 이름
     * findTop100ByOrderByCreatedAtDesc 에 들어 있다.
     */
    @GetMapping("/api/proposals")
    public Map<String, List<ProposalSummary>> list() {
        return Map.of("items", proposals.recent().stream().map(ProposalSummary::of).toList());
    }

    /**
     * 제안서 한 건의 상세를 돌려준다. 없는 번호면 승인 기록을 읽지 않고, ProposalApiExceptionHandler.toResponse 로 만든
     * 404 PROPOSAL_NOT_FOUND 응답을 반환값으로 돌려준다(문구와 코드는
     * service/src/main/java/com/capstone4/skillops/proposal/ProposalException.java 의 notFound() 에 있다).
     * 예외를 던지지 않고 반환값으로 쓰는 이유는 JSON 을 받지 않는 클라이언트(Accept: text/html 등)에게 리팩토링 전처럼 406 을 주기
     * 위해서다. 예외 처리기에서 쓰면 협상 실패가 처리기 밖으로 새어 500 이 된다.
     * service/src/test/java/com/capstone4/skillops/api/ContentNegotiationContractTest.java 가 이 동작을 고정한다.
     * 있으면 ApprovalService.latestFor 로 마지막 결정을 찾아 붙이고, 아직 결정하지 않았으면 decision 은 null 이다.
     * 경로의 번호 형식은 service/src/main/java/com/capstone4/skillops/proposal/Proposal.java 의 ID_PATTERN 으로 검사한다.
     */
    @GetMapping("/api/proposals/{proposalId}")
    public ResponseEntity<?> detail(@PathVariable @Pattern(regexp = Proposal.ID_PATTERN) String proposalId) {
        return proposals.find(proposalId)
                .<ResponseEntity<?>>map(proposal -> ResponseEntity.ok(ProposalDetail.of(proposal,
                        approvals.latestFor(proposalId).map(ApprovalView::of).orElse(null))))
                .orElseGet(() -> ProposalApiExceptionHandler.toResponse(ProposalException.notFound()));
    }

    /**
     * 목록 한 줄의 요약이다. title 과 summary 는 저장할 때 만든 본문(document)에서, root_cause_skill, recommended_candidate_no,
     * candidate_count 는 Agent 가 보낸 원본(payload)에서 읽는다. JSON 칸 이름은 snake_case 이고, frontend/src/api/proposals.js 의
     * listProposals() 가 이 모양을 받는다.
     */
    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record ProposalSummary(String proposalId, String status, Instant createdAt, String title, String summary,
            String rootCauseSkill, Integer recommendedCandidateNo, List<Integer> validCandidateNos,
            int candidateCount) {

        /**
         * 제안서 엔티티를 목록 요약으로 바꾼다. payload 와 document 는 정해진 타입이 없는 Map 이라 칸마다 타입을 확인하며 읽는다.
         * 글이 아닌 title 은 문자열로 바꾸고, root_cause 가 객체가 아니면 root_cause_skill 을 null 로 두며, candidate_count 는
         * candidates 목록의 원소를 모양과 상관없이 모두 센다.
         * service/src/test/java/com/capstone4/skillops/api/ProposalApiContractTest.java 의
         * summariesReadPayloadAndDocumentLeniently 가 이 읽기 규칙을 고정한다.
         */
        static ProposalSummary of(Proposal p) {
            Map<String, Object> payload = p.getPayload();
            Map<String, Object> document = p.getDocument();
            return new ProposalSummary(p.getProposalId(), p.getStatus(), p.getCreatedAt(), text(document.get("title")),
                    text(document.get("summary")), text(mapOf(payload.get("root_cause")).get("skill")),
                    recommendedNo(p), p.getValidCandidateNos(),
                    payload.get("candidates") instanceof List<?> list ? list.size() : 0);
        }
    }

    /**
     * 제안서 상세다. proposal 은 Agent 가 save_proposal 로 보낸 원본이고, 승인한 뒤에는 intent.confirmed 만 true 로 바뀌어 있다.
     * document 는 저장할 때 만든 본문이고, 결정 전이면 decision 은 null 이다. JSON 칸 이름은 snake_case 이고,
     * frontend/src/api/proposals.js 의 getProposal() 이 이 모양을 받는다.
     */
    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record ProposalDetail(String proposalId, String status, Instant createdAt, Instant decidedAt,
            String threadId, List<Integer> validCandidateNos, Integer recommendedCandidateNo,
            Map<String, Object> document, Map<String, Object> proposal, ApprovalView decision) {

        /** 제안서 엔티티와 마지막 결정을 상세 응답으로 묶는다. */
        static ProposalDetail of(Proposal p, ApprovalView decision) {
            return new ProposalDetail(p.getProposalId(), p.getStatus(), p.getCreatedAt(), p.getDecidedAt(),
                    p.getThreadId(), p.getValidCandidateNos(), recommendedNo(p), p.getDocument(), p.getPayload(),
                    decision);
        }
    }

    /** Agent 가 원본에 적은 추천 후보 번호(recommended_candidate_no)를 읽는다. 숫자가 아니면 null 이고, 2.0 같은 소수는 정수로 자른다. */
    private static Integer recommendedNo(Proposal p) {
        return number(p.getPayload().get("recommended_candidate_no"));
    }

    /** 값을 글로 바꾼다. null 은 null 로 두고, 글이 아닌 값은 String.valueOf 로 바꾼다. */
    private static String text(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    /** 숫자면 int 로 바꾸고, 숫자가 아니면 null 을 돌려준다. */
    private static Integer number(Object value) {
        return value instanceof Number n ? n.intValue() : null;
    }

    /** 객체(Map)면 그대로 돌려주고, 아니면 빈 Map 을 돌려줘 이어지는 get 이 null 을 내게 한다. */
    @SuppressWarnings("unchecked")
    private static Map<String, Object> mapOf(Object value) {
        return value instanceof Map<?, ?> m ? (Map<String, Object>) m : Map.of();
    }
}
