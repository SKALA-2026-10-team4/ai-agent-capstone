package com.capstone4.skillops.api;

import java.time.Instant;

import com.capstone4.skillops.approval.Approval;

import tools.jackson.databind.PropertyNamingStrategies;
import tools.jackson.databind.annotation.JsonNaming;

/**
 * 화면에 내보내는 승인 기록 한 건이다. JSON 칸은 proposal_id, decision, candidate_no, intent_confirmed, approval_token,
 * approved_by, approver_name, decided_at, used_at 의 9칸이고 이 순서로 나간다.
 * 엔티티(service/src/main/java/com/capstone4/skillops/approval/Approval.java)의 agent_result 와 agent_message 는 내보내지 않는다.
 * 두 칸은 예전에 결정을 Agent 로 보내던 때의 칸이다. 지금 만드는 기록의 agent_result 는 언제나 NOT_SENT 이고, 그 전에 남은
 * 기록에는 ACCEPTED 같은 값이 있다.
 * used_at 은 운영 반영(promote_workspace)이 approval_token 을 쓴 시각을 담을 칸이지만, 지금 이 칸을 채우는 코드가 없어 언제나
 * null 이다.
 * 같은 패키지(service/src/main/java/com/capstone4/skillops/api/)의 ApprovalController 가 approve, reject, recent 응답으로,
 * ProposalController 가 상세의 decision 칸으로 이 모양을 쓴다. frontend/src/views/ProposalView.vue 가 결정 내용을 보여 준다.
 * service/src/test/java/com/capstone4/skillops/api/ProposalApiContractTest.java 의 VIEW_KEYS 가 칸 이름과 순서를 고정한다.
 */
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public record ApprovalView(String proposalId, String decision, Integer candidateNo, boolean intentConfirmed,
        String approvalToken, String approvedBy, String approverName, Instant decidedAt, Instant usedAt) {

    /** 승인 기록 엔티티를 화면용 9칸으로 옮긴다. */
    public static ApprovalView of(Approval a) {
        return new ApprovalView(a.getProposalId(), a.getDecision(), a.getCandidateNo(), a.isIntentConfirmed(),
                a.getApprovalToken(), a.getApprovedBy(), a.getApproverName(), a.getDecidedAt(), a.getUsedAt());
    }
}
