package com.capstone4.skillops.approval;

import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * 운영자의 승인·거절 결정 한 번을 approvals 표의 한 줄로 남기는 엔티티다.
 * 계약 5(docs/contracts/mcp-tools-input-output.md)의 저장 공간 목록에 approvals 로 적힌 표이고, 칸 구성은 A 가 맡는다.
 * service/src/main/java/com/capstone4/skillops/approval/ApprovalService.java 의 recordDecision 이 결정마다 새 줄을 만든다.
 * service/src/main/java/com/capstone4/skillops/api/ApprovalView.java 가 이 가운데 화면에 내보낼 9개 칸을 골라 JSON 으로 바꾸며,
 * id, agentResult, agentMessage 는 내보내지 않는다.
 * 결정은 저장된 제안서(proposals 표)에도 표시하지만 SkillOps Agent 로는 보내지 않는다.
 * 칸 값을 바꾸는 메서드는 없고, getter 는 칸 값을 그대로 돌려준다.
 */
@Entity
@Table(name = "approvals")
public class Approval {

    @Id
    private UUID id;

    @Column(nullable = false, length = 64)
    private String proposalId;

    /** 승인한 후보 번호다. 제안서의 candidate_no 와 같이 1부터 센다. 거절이면 null 이다. */
    private Integer candidateNo;

    /**
     * 결정 값이다. service/src/main/java/com/capstone4/skillops/proposal/ProposalStatus.java 의 APPROVED(approved)나
     * REJECTED(rejected) 문자열을 그대로 쓴다.
     */
    @Column(nullable = false, length = 16)
    private String decision;

    /**
     * 담당자가 승인 화면에서 의도 해석을 확인했는지 남긴다.
     * service/src/main/java/com/capstone4/skillops/api/ApprovalController.java 가 확인하지 않은 승인을 거부하므로 API 로 들어온
     * 승인 기록은 늘 true 이고, 거절 기록은 false 다. 같은 사실을 제안서 쪽에는
     * service/src/main/java/com/capstone4/skillops/proposal/Proposal.java 의 decide 가 payload 의 intent.confirmed 로 남긴다.
     */
    @Column(nullable = false)
    private boolean intentConfirmed;

    /** 결정한 운영자의 username 이다. 이름과 달리 거절 기록에도 같은 칸을 쓴다. */
    @Column(nullable = false, length = 64)
    private String approvedBy;

    /** 결정한 운영자의 표시 이름이다. service/src/main/java/com/capstone4/skillops/auth/AuthUser.java 의 displayName 을 담는다. */
    @Column(length = 64)
    private String approverName;

    /**
     * 운영 반영(promote_workspace)에 넣을 승인 번호이며 a_ 뒤에 10글자가 붙는다. 칸 이름은 계약 5 v0 의 approval_token 을 따른다.
     * 거절이면 null 이다. 계약은 이 번호를 한 번만 쓸 수 있다고 정했고, 표의 unique 제약은 같은 번호가 두 줄에 저장되지 않게 막는다.
     */
    @Column(unique = true, length = 32)
    private String approvalToken;

    @Column(nullable = false)
    private Instant decidedAt;

    /**
     * promote_workspace 가 approval_token 을 쓴 시각을 C-2 가 채울 칸이다.
     * 지금 service 에는 promote_workspace 가 없어서 이 칸을 채우는 코드가 없고, 값은 늘 null 이다.
     */
    private Instant usedAt;

    /**
     * 예전에 결정을 SkillOps Agent 로 보내던 때의 전달 결과 칸이다. 지금은 보내지 않으므로 새 기록은 늘 NOT_SENT 이고,
     * 그 전에 남은 기록에는 ACCEPTED 같은 값이 있다. 칸이 nullable=false 라서 새 기록에도 값을 채운다.
     */
    @Column(nullable = false, length = 32)
    private String agentResult;

    /** 예전에 Agent 가 돌려준 메시지를 500자까지 담던 칸이다. 지금은 채우지 않으므로 새 기록에서는 null 이다. */
    @Column(length = 500)
    private String agentMessage;

    /** JPA 가 표에서 읽은 줄로 객체를 만들 때 쓰는 생성자다. */
    protected Approval() {
    }

    /**
     * 결정 한 번의 기록을 만든다. id 는 새 UUID 로 정하고 agentResult 는 NOT_SENT 로 둔다.
     * ApprovalService 의 recordDecision 이 부르고, 테스트도 mock 이 돌려줄 기록을 만들 때 부른다.
     */
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
        this.agentResult = "NOT_SENT";
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
