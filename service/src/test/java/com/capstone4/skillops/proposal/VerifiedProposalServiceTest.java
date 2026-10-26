package com.capstone4.skillops.proposal;

import java.util.*;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import com.capstone4.skillops.evaluation.*;
import static com.capstone4.skillops.evaluation.EvaluationFixtures.*;
import static com.capstone4.skillops.evaluation.EvaluationTypes.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class VerifiedProposalServiceTest {
    private final EvaluationService evaluations=mock(EvaluationService.class);
    private final ProposalRepository proposals=mock(ProposalRepository.class);
    private final VerifiedProposalReceiptRepository receipts=mock(VerifiedProposalReceiptRepository.class);
    private final VerifiedProposalService service=new VerifiedProposalService(evaluations,proposals,receipts);
    /** 실패·미완료 평가를 제안 생성으로 우회할 수 없다. */
    @Test void failedEvaluationCannotCreateAnApprovableProposal() {
        when(evaluations.verification("job","attempt")).thenThrow(new EvaluationException(409,"EVIDENCE_MISMATCH","실패 평가"));
        assertThatThrownBy(() -> service.save(new VerifiedProposalService.Input("job","attempt","수정 설명","의도"))).isInstanceOf(EvaluationException.class);
        verifyNoInteractions(proposals);
        verify(receipts,never()).saveAndFlush(any());
    }
    /** 서버 판정과 snapshot을 저장하고 하지 않은 분석을 문서에 주장하지 않는다. */
    @Test void actualEvidenceProducesSnapshotProposalWithoutInventingAnalysis() {
        Plan plan=plan(Runner.ORDER_HARNESS);
        when(evaluations.verification("job","attempt")).thenReturn(new EvaluationService.Verification(plan,
                EvaluationReports.build("job","attempt",Status.SUCCEEDED,plan,trials(plan)),trials(plan)));
        var saved=service.save(new VerifiedProposalService.Input("job","attempt","수정 설명","주문 유지"));
        ArgumentCaptor<Proposal> proposal=ArgumentCaptor.forClass(Proposal.class);
        verify(proposals).saveAndFlush(proposal.capture());
        assertThat(saved.validCandidateNos()).containsExactly(1);
        assertThat(proposal.getValue().getPayload()).containsEntry("analysis_status","NOT_PERFORMED");
        assertThat(proposal.getValue().getPayload()).containsEntry("snapshot_id",plan.candidate().snapshotId());
        assertThat(proposal.getValue().getDocument().toString()).doesNotContain("하나씩 되돌려 시험했지만");
        assertThat(proposal.getValue().getDocument().toString()).contains("원인 분석은 수행하지 않았습니다");
    }
    /** 같은 검증 시도의 다른 내용은 멱등 충돌로 처리한다. */
    @Test void reusedAttemptCannotChangeTheProposalBody() {
        VerifiedProposalReceipt existing=new VerifiedProposalReceipt();
        existing.attemptId="attempt"; existing.requestHash="other"; existing.proposalId="p_test";
        when(receipts.findById("attempt")).thenReturn(Optional.of(existing));
        assertThatThrownBy(() -> service.save(new VerifiedProposalService.Input("job","attempt","다른 설명","의도")))
                .isInstanceOf(EvaluationException.class).hasMessageContaining("내용이 다릅니다");
        verifyNoInteractions(evaluations,proposals);
    }
}
