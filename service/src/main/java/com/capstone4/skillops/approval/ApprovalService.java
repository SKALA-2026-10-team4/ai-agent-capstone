package com.capstone4.skillops.approval;

import java.security.SecureRandom;
import java.time.Clock;
import java.util.List;
import java.util.Optional;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.capstone4.skillops.auth.AuthUser;
import com.capstone4.skillops.proposal.ProposalService;

/**
 * 운영자의 승인·거절. 저장된 제안서에 결정을 표시하고 승인 기록을 남긴다. 둘은 한 트랜잭션이라 함께 남거나 함께 남지 않는다.
 * SkillOps Agent 는 제안서를 저장한 뒤 바로 끝나므로 결정을 Agent 로 보내지 않는다. 운영 반영(promote)은 아직 연결되지 않았다.
 */
@Service
public class ApprovalService {

    private static final char[] CODE_CHARS = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789".toCharArray();

    private final ApprovalRepository approvals;
    private final ProposalService proposals;
    private final Clock clock;
    private final SecureRandom random = new SecureRandom();

    @Autowired
    public ApprovalService(ApprovalRepository approvals, ProposalService proposals) {
        this(approvals, proposals, Clock.systemUTC());
    }

    ApprovalService(ApprovalRepository approvals, ProposalService proposals, Clock clock) {
        this.approvals = approvals;
        this.proposals = proposals;
        this.clock = clock;
    }

    /** 운영자 승인: 합격한 후보 번호(1부터)를 받아 제안서에 표시하고 approval_token 을 발급해 기록한다. */
    @Transactional
    public Approval approve(String proposalId, int candidateNo, boolean intentConfirmed, AuthUser user) {
        proposals.markDecided(proposalId, "approved", candidateNo);
        return approvals.save(new Approval(proposalId, candidateNo, "approved", intentConfirmed, user.username(),
                user.displayName(), newToken(), clock.instant()));
    }

    /** 운영자 거절: 제안서에 표시하고 토큰 없이 기록한다. 쓸 수 있는 수정안이 없는 제안서를 닫을 때도 쓴다. */
    @Transactional
    public Approval reject(String proposalId, AuthUser user) {
        proposals.markDecided(proposalId, "rejected", null);
        return approvals.save(new Approval(proposalId, null, "rejected", false, user.username(), user.displayName(),
                null, clock.instant()));
    }

    public List<Approval> recent() {
        return approvals.findTop50ByOrderByDecidedAtDesc();
    }

    /** 이 제안서의 마지막 결정 */
    public Optional<Approval> latestFor(String proposalId) {
        return approvals.findTopByProposalIdOrderByDecidedAtDesc(proposalId);
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
