package com.capstone4.skillops.approval;

import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * 승인 기록(계약 5 저장 공간 목록의 approvals, 담당 A). 승인 한 번에 한 줄.
 * A 가 화면 승인 때 쓰고, C-2 의 promote_workspace 가 approval_token 을 확인한 뒤 used_at 을 채운다.
 * Agent 전달에 실패해도 누가 언제 무엇을 결정했는지는 남긴다.
 */
@Entity
@Table(name = "approvals")
public class Approval {

    @Id
    private UUID id;

    @Column(nullable = false, length = 64)
    private String proposalId;

    /** 승인한 후보 번호(candidates 의 인덱스). 거절이면 null. */
    private Integer candidateNo;

    /** approved | rejected */
    @Column(nullable = false, length = 16)
    private String decision;

    /** 담당자가 승인 화면에서 의도 해석을 확인했는지(proposal.intent.confirmed 로 쓰인다) */
    @Column(nullable = false)
    private boolean intentConfirmed;

    @Column(nullable = false, length = 64)
    private String approvedBy;

    @Column(length = 64)
    private String approverName;

    /** promote_workspace 에 넣는 승인 번호(계약 5 v0 이름). 거절이면 null. 한 번만 쓸 수 있다. */
    @Column(unique = true, length = 32)
    private String approvalToken;

    @Column(nullable = false)
    private Instant decidedAt;

    /** promote_workspace 가 approval_token 을 쓴 시각. C-2 가 채운다. */
    private Instant usedAt;

    /** ACCEPTED · PROPOSAL_NOT_FOUND · AGENT_UNAVAILABLE · AGENT_ERROR */
    @Column(nullable = false, length = 32)
    private String agentResult;

    @Column(length = 500)
    private String agentMessage;

    protected Approval() {
    }

    public Approval(String proposalId, Integer candidateNo, String decision, boolean intentConfirmed, String approvedBy,
            String approverName, String approvalToken, Instant decidedAt) {
        this.id = UUID.randomUUID();
        this.proposalId = proposalId;
        this.candidateNo = candidateNo;
        this.decision = decision;
        this.intentConfirmed = intentConfirmed;
        this.approvedBy = approvedBy;
        this.approverName = approverName;
        this.approvalToken = approvalToken;
        this.decidedAt = decidedAt;
        this.agentResult = "PENDING";
    }

    void recordAgentResult(String result, String message) {
        this.agentResult = result;
        this.agentMessage = message == null || message.length() <= 500 ? message : message.substring(0, 500);
    }

    public UUID getId() {
        return id;
    }

    public String getProposalId() {
        return proposalId;
    }

    public Integer getCandidateNo() {
        return candidateNo;
    }

    public String getDecision() {
        return decision;
    }

    public boolean isIntentConfirmed() {
        return intentConfirmed;
    }

    public String getApprovedBy() {
        return approvedBy;
    }

    public String getApproverName() {
        return approverName;
    }

    public String getApprovalToken() {
        return approvalToken;
    }

    public Instant getDecidedAt() {
        return decidedAt;
    }

    public Instant getUsedAt() {
        return usedAt;
    }

    public String getAgentResult() {
        return agentResult;
    }

    public String getAgentMessage() {
        return agentMessage;
    }
}
