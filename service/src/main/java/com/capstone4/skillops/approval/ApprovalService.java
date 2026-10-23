package com.capstone4.skillops.approval;

import java.time.Clock;
import java.util.List;
import java.util.Optional;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.capstone4.skillops.auth.AuthUser;
import com.capstone4.skillops.common.RandomIds;
import com.capstone4.skillops.proposal.ProposalService;
import com.capstone4.skillops.proposal.ProposalStatus;

/**
 * 운영자의 승인과 거절을 받아 저장된 제안서에 결정을 표시하고, approvals 표에 결정 기록을 남긴다.
 * 두 일은 한 트랜잭션 안에서 일어나므로 함께 남거나 함께 남지 않는다. 제안서 표시가 거부되면 기록을 만들지 않는다.
 * 커밋할 때 service/src/main/java/com/capstone4/skillops/proposal/Proposal.java 의 @Version 검사가 다른 운영자의 결정과
 * 부딪힘을 찾으면 두 변경이 함께 되돌아가고, service/src/main/java/com/capstone4/skillops/api/ProposalApiExceptionHandler.java 가
 * 409 PROPOSAL_ALREADY_DECIDED 로 응답한다.
 *
 * service/src/main/java/com/capstone4/skillops/api/ApprovalController.java 가 approve, reject, recent 를 부르고,
 * service/src/main/java/com/capstone4/skillops/api/ProposalController.java 의 detail 이 latestFor 를 부른다.
 * 결정을 받을 수 있는지 정하는 규칙은 service/src/main/java/com/capstone4/skillops/proposal/ProposalService.java 의 markDecided 와
 * checkDecidable 에 있고, 이 클래스는 표시와 기록의 순서, 승인 번호 발급만 맡는다.
 * SkillOps Agent 는 제안서를 저장한 뒤 바로 끝나므로 결정을 Agent 로 보내지 않는다. 운영 반영(promote_workspace)은 아직 연결되지 않았다.
 */
@Service
public class ApprovalService {

    /** 승인 번호에 쓰는 글자 32개다. 사람이 읽고 옮겨 적을 때 헷갈리지 않도록 I, O, 0, 1 은 넣지 않았다. */
    private static final String TOKEN_CHARS = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789";
    /** 승인 번호 앞에 붙는 글자다. 계약 5 가 정한 승인 번호 모양(a_ 로 시작)을 따른다. */
    private static final String TOKEN_PREFIX = "a_";
    /** 접두어 뒤에 붙이는 무작위 글자 수다. 승인 번호 칸(approval_token) 길이 32 안에 들어간다. */
    private static final int TOKEN_LENGTH = 10;

    private final ApprovalRepository approvals;
    private final ProposalService proposals;
    /** 결정 시각을 정하는 시계다. 테스트는 고정 시계를 넣는다. */
    private final Clock clock;

    /**
     * Spring 이 빈을 만들 때 쓰는 생성자다. 시계는 UTC 시스템 시계를 쓴다.
     * 생성자가 둘이면 Spring 은 어느 쪽을 쓸지 정하지 못해 인자 없는 생성자를 찾다가 빈을 만들지 못한다.
     * 그래서 @Autowired 로 이 생성자를 고르게 한다. ProposalService 가 같은 구조에서 이 표시를 빠뜨려
     * 기동에 실패한 적이 있으므로(커밋 943b585), 같은 오류가 이 클래스에서 다시 생기지 않도록 이 표시를 지우지 않는다.
     */
    @Autowired
    public ApprovalService(ApprovalRepository approvals, ProposalService proposals) {
        this(approvals, proposals, Clock.systemUTC());
    }

    /** 테스트가 고정 시계(Clock.fixed)를 넣으려고 쓰는 생성자다. 같은 패키지의 테스트만 부른다. */
    ApprovalService(ApprovalRepository approvals, ProposalService proposals, Clock clock) {
        this.approvals = approvals;
        this.proposals = proposals;
        this.clock = clock;
    }

    /**
     * 운영자의 승인을 처리한다. 합격한 후보 번호(1부터)를 받아 제안서에 approved 를 표시하고, approval_token 을 발급해 기록한다.
     * intentConfirmed 는 받은 값을 그대로 기록한다. 의도를 확인하지 않은 승인은 ApprovalController 의 approve 가 먼저 거부한다.
     * 제안서를 결정할 수 없으면 markDecided 가 던진 ProposalException 이 그대로 올라가고 기록은 남지 않는다.
     */
    @Transactional
    public Approval approve(String proposalId, int candidateNo, boolean intentConfirmed, AuthUser user) {
        return recordDecision(proposalId, ProposalStatus.APPROVED, candidateNo, intentConfirmed, user);
    }

    /**
     * 운영자의 거절을 처리한다. 제안서에 rejected 를 표시하고 후보 번호와 승인 번호 없이 기록한다.
     * 쓸 수 있는 수정안이 없는 제안서(no_valid_fix)를 운영자가 확인하고 닫을 때도 이 메서드를 쓴다.
     */
    @Transactional
    public Approval reject(String proposalId, AuthUser user) {
        return recordDecision(proposalId, ProposalStatus.REJECTED, null, false, user);
    }

    /**
     * 결정 시각이 늦은 것부터 결정 기록 50개를 돌려준다. ApprovalController 의 recent 가 GET /api/approvals 응답으로 내보낸다.
     * frontend/src/api/proposals.js 의 listApprovals 가 이 경로를 부르지만, 지금 그 함수를 쓰는 화면은 없다.
     */
    public List<Approval> recent() {
        return approvals.findTop50ByOrderByDecidedAtDesc();
    }

    /** 이 제안서의 마지막 결정 기록을 찾는다. ProposalController 의 detail 이 상세 응답의 decision 칸을 채울 때 부르며, 결정 전이면 비어 있다. */
    public Optional<Approval> latestFor(String proposalId) {
        return approvals.findTopByProposalIdOrderByDecidedAtDesc(proposalId);
    }

    /**
     * 결정 한 번을 처리하는 순서를 고정한다. 먼저 ProposalService 의 markDecided 로 제안서에 결정을 표시하고,
     * 표시가 거부되면 예외가 그대로 올라가 기록을 만들지 않는다. 표시가 끝나면 결정 기록을 저장하는데, 승인일 때만 승인 번호를
     * 발급해 함께 담는다. 돌려주는 값은 저장소가 돌려준 기록이다.
     * approve 와 reject 가 모두 이 메서드를 거치므로, 결정 처리 순서를 바꾸려면 이곳만 고치면 된다.
     */
    private Approval recordDecision(String proposalId, String decision, Integer candidateNo, boolean intentConfirmed,
            AuthUser user) {
        proposals.markDecided(proposalId, decision, candidateNo);
        return approvals.save(new Approval(proposalId, candidateNo, decision, intentConfirmed, user.username(),
                user.displayName(), ProposalStatus.APPROVED.equals(decision) ? newToken() : null, clock.instant()));
    }

    /**
     * 새 승인 번호를 만든다. 계약 5 의 모양대로 a_ 뒤에 TOKEN_CHARS 에서 무작위로 고른 글자 10개를 붙인다.
     * 무작위 글자는 service/src/main/java/com/capstone4/skillops/common/RandomIds.java 의 next 가 SecureRandom 으로 고른다.
     */
    private static String newToken() {
        return RandomIds.next(TOKEN_PREFIX, TOKEN_LENGTH, TOKEN_CHARS);
    }
}
