package com.capstone4.skillops.approval;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.mockito.InOrder;

import com.capstone4.skillops.auth.AuthUser;
import com.capstone4.skillops.proposal.ProposalException;
import com.capstone4.skillops.proposal.ProposalService;

/**
 * service/src/main/java/com/capstone4/skillops/approval/ApprovalService.java 의 승인·거절 기록 동작을 리팩토링 전 그대로 고정하는 특성 테스트다.
 * 승인 번호 모양과 agent_result 의 NOT_SENT 까지 기록의 모든 칸 값을 보고, 제안서 표시가 기록 저장보다 먼저 일어나는 순서,
 * 저장소가 돌려준 기록을 그대로 돌려주는 것, 표시가 거부되면 기록을 남기지 않는 것, 최근 50개 조회를 확인한다.
 * 저장소와 ProposalService(service/src/main/java/com/capstone4/skillops/proposal/ProposalService.java)는 mock 이고 시계는 NOW 로 고정한다.
 */
class ApprovalServiceCharacterizationTest {

    static final Instant NOW = Instant.parse("2026-10-21T01:00:00Z");

    private final ApprovalRepository repository = mock(ApprovalRepository.class);
    private final ProposalService proposals = mock(ProposalService.class);
    private final ApprovalService service = new ApprovalService(repository, proposals, Clock.fixed(NOW, ZoneOffset.UTC));
    private final AuthUser operator = new AuthUser("operator", "운영자", List.of("OPERATOR"));

    {
        // 저장소의 save 는 받은 기록을 그대로 돌려주게 한다.
        given(repository.save(any(Approval.class))).willAnswer(call -> call.getArgument(0));
    }

    @Test
    void rejectionRecordFields() {
        Approval approval = service.reject("p_1", operator);

        assertThat(approval.getId()).isNotNull();
        assertThat(approval.getProposalId()).isEqualTo("p_1");
        assertThat(approval.getCandidateNo()).isNull();
        assertThat(approval.getDecision()).isEqualTo("rejected");
        assertThat(approval.isIntentConfirmed()).isFalse();
        assertThat(approval.getApprovedBy()).isEqualTo("operator");
        assertThat(approval.getApproverName()).isEqualTo("운영자");
        assertThat(approval.getApprovalToken()).isNull();
        assertThat(approval.getDecidedAt()).isEqualTo(NOW);
        assertThat(approval.getUsedAt()).isNull();
        assertThat(approval.getAgentResult()).isEqualTo("NOT_SENT");
        assertThat(approval.getAgentMessage()).isNull();
    }

    @Test
    void approvalRecordFieldsPassIntentThrough() {
        Approval approval = service.approve("p_1", 2, false, operator);

        assertThat(approval.getProposalId()).isEqualTo("p_1");
        assertThat(approval.getCandidateNo()).isEqualTo(2);
        assertThat(approval.getDecision()).isEqualTo("approved");
        assertThat(approval.isIntentConfirmed()).isFalse();
        assertThat(approval.getApprovedBy()).isEqualTo("operator");
        assertThat(approval.getApproverName()).isEqualTo("운영자");
        assertThat(approval.getApprovalToken()).matches("a_[A-HJ-NP-Z2-9]{10}");
        assertThat(approval.getDecidedAt()).isEqualTo(NOW);
        assertThat(approval.getAgentResult()).isEqualTo("NOT_SENT");
    }

    @Test
    void proposalIsMarkedBeforeTheRecordIsSaved() {
        service.approve("p_1", 1, true, operator);
        service.reject("p_2", operator);

        InOrder order = inOrder(proposals, repository);
        order.verify(proposals).markDecided("p_1", "approved", 1);
        order.verify(repository).save(any(Approval.class));
        order.verify(proposals).markDecided("p_2", "rejected", null);
        order.verify(repository).save(any(Approval.class));
    }

    @Test
    void decisionsReturnWhatTheRepositorySaved() {
        Approval other = new Approval("p_other", null, "rejected", false, "x", "y", null, NOW);
        given(repository.save(any(Approval.class))).willReturn(other);

        assertThat(service.approve("p_1", 1, true, operator)).isSameAs(other);
        assertThat(service.reject("p_1", operator)).isSameAs(other);
    }

    @Test
    void refusedRejectionLeavesNoRecord() {
        given(proposals.markDecided("p_1", "rejected", null))
                .willThrow(new ProposalException("PROPOSAL_ALREADY_DECIDED", "이미 결정한 제안서입니다.", 409));

        assertThatThrownBy(() -> service.reject("p_1", operator)).isInstanceOf(ProposalException.class);
        then(repository).should(never()).save(any());
    }

    /** 무작위로 만든 승인 번호 200개가 모두 a_ 뒤에 I, O, 0, 1 을 뺀 글자 10개가 붙은 모양인지 본다. */
    @Test
    void approvalTokensUseTheFixedAlphabet() {
        for (int i = 0; i < 200; i++) {
            assertThat(service.approve("p_" + i, 1, true, operator).getApprovalToken()).matches("a_[A-HJ-NP-Z2-9]{10}");
        }
    }

    @Test
    void recentReadsTheLatestFifty() {
        Approval last = new Approval("p_1", 1, "approved", true, "operator", "운영자", "a_TESTCODE01", NOW);
        given(repository.findTop50ByOrderByDecidedAtDesc()).willReturn(List.of(last));

        assertThat(service.recent()).containsExactly(last);
    }
}
