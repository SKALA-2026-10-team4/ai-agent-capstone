package com.capstone4.skillops.approval;

import java.security.SecureRandom;
import java.time.Clock;
import java.util.List;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import com.capstone4.skillops.auth.AuthUser;

@Service
public class ApprovalService {

    private static final char[] CODE_CHARS = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789".toCharArray();

    private final ApprovalRepository approvals;
    private final AgentClient agent;
    private final Clock clock;
    private final SecureRandom random = new SecureRandom();

    @Autowired
    public ApprovalService(ApprovalRepository approvals, AgentClient agent) {
        this(approvals, agent, Clock.systemUTC());
    }

    ApprovalService(ApprovalRepository approvals, AgentClient agent, Clock clock) {
        this.approvals = approvals;
        this.agent = agent;
        this.clock = clock;
    }

    /** 운영자 승인: approval_token 을 발급해 기록하고 Agent 에 넘긴다. */
    public Approval approve(String proposalId, int candidateNo, boolean intentConfirmed, AuthUser user) {
        return decide(new Approval(proposalId, candidateNo, "approved", intentConfirmed, user.username(),
                user.displayName(), newToken(), clock.instant()));
    }

    /** 운영자 거절: 토큰 없이 기록하고 Agent 에 넘긴다. */
    public Approval reject(String proposalId, AuthUser user) {
        return decide(new Approval(proposalId, null, "rejected", false, user.username(), user.displayName(), null,
                clock.instant()));
    }

    /**
     * 결정을 먼저 기록하고 Agent 에 전달한 뒤 결과를 덧붙인다. 전달에 실패하면 기록은 남기고 예외를 다시 던진다.
     */
    private Approval decide(Approval draft) {
        Approval approval = approvals.save(draft);
        try {
            AgentClient.Reply reply = agent.approve(approval.getProposalId(), approval.getDecision(),
                    approval.getApprovalToken());
            approval.recordAgentResult("ACCEPTED", reply.status());
            return approvals.save(approval);
        } catch (AgentException e) {
            approval.recordAgentResult(e.code(), e.getMessage());
            approvals.save(approval);
            throw e;
        }
    }

    public List<Approval> recent() {
        return approvals.findTop50ByOrderByDecidedAtDesc();
    }

    // 승인 번호 모양(계약 5): a_ + 영문·숫자
    private String newToken() {
        StringBuilder code = new StringBuilder("a_");
        for (int i = 0; i < 10; i++) {
            code.append(CODE_CHARS[random.nextInt(CODE_CHARS.length)]);
        }
        return code.toString();
    }
}
