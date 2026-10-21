package com.capstone4.skillops.approval;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.Test;

import com.capstone4.skillops.auth.AuthUser;
import com.capstone4.skillops.proposal.ProposalException;
import com.capstone4.skillops.proposal.ProposalService;

/** 승인·거절은 저장된 제안서에 결정을 표시한 뒤 기록한다. SkillOps Agent 로는 보내지 않는다. */
class ApprovalServiceTest {

    private final ApprovalRepository repository = mock(ApprovalRepository.class);
    private final ProposalService proposals = mock(ProposalService.class);
    private final ApprovalService service = new ApprovalService(repository, proposals,
            Clock.fixed(Instant.parse("2026-10-21T01:00:00Z"), ZoneOffset.UTC));
    private final AuthUser operator = new AuthUser("operator", "운영자", List.of("OPERATOR"));

    {
        given(repository.save(any(Approval.class))).willAnswer(call -> call.getArgument(0));
    }

    @Test
    void approvalRecordsCandidateNumberAndMarksProposal() {
        Approval approval = service.approve("p_1", 1, true, operator);

        then(proposals).should().markDecided("p_1", "approved", 1);
        assertThat(approval.getProposalId()).isEqualTo("p_1");
        assertThat(approval.getCandidateNo()).isEqualTo(1);
        assertThat(approval.getDecision()).isEqualTo("approved");
        assertThat(approval.isIntentConfirmed()).isTrue();
        assertThat(approval.getApprovedBy()).isEqualTo("operator");
        assertThat(approval.getApprovalToken()).matches("a_[A-Z2-9]{10}");
        assertThat(approval.getUsedAt()).isNull();
        assertThat(approval.getAgentResult()).isEqualTo("NOT_SENT");
        assertThat(approval.getDecidedAt()).isEqualTo(Instant.parse("2026-10-21T01:00:00Z"));
    }

    @Test
    void rejectHasNoTokenAndNoCandidate() {
        Approval approval = service.reject("p_1", operator);

        then(proposals).should().markDecided("p_1", "rejected", null);
        assertThat(approval.getApprovalToken()).isNull();
        assertThat(approval.getCandidateNo()).isNull();
        assertThat(approval.getDecision()).isEqualTo("rejected");
    }

    @Test
    void rejectsCandidateThatDidNotPass() {
        given(proposals.markDecided("p_1", "approved", 2)).willThrow(
                new ProposalException("CANDIDATE_NOT_VALID", "모든 확인을 통과한 후보만 승인할 수 있습니다.", 400));

        assertThatThrownBy(() -> service.approve("p_1", 2, true, operator)).isInstanceOf(ProposalException.class);
        then(repository).should(never()).save(any());
    }

    @Test
    void latestDecisionIsReadFromRepository() {
        Approval last = new Approval("p_1", 1, "approved", true, "operator", "운영자", "a_TESTCODE01",
                Instant.parse("2026-10-21T01:00:00Z"));
        given(repository.findTopByProposalIdOrderByDecidedAtDesc("p_1")).willReturn(Optional.of(last));

        assertThat(service.latestFor("p_1")).contains(last);
        assertThat(service.latestFor("p_none")).isEmpty();
    }
}
