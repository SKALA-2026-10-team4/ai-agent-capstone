package com.capstone4.skillops.proposal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import com.capstone4.skillops.proposal.ProposalService.SavedProposal;
import com.capstone4.skillops.scenario.TestFixtures.InMemoryRunLedger;

/**
 * service/src/main/java/com/capstone4/skillops/proposal/ProposalService.java 의 저장(save)과 결정 표시(markDecided)의 기본 규칙을
 * 보호하는 단위 테스트다. 저장 쪽은 결과의 계약 모양, 같은 진단 재저장, 근거 실행과 원인 근거 실행의 대조, valid, status,
 * 추천 후보, 후보 번호 검사, 적용에 실패한 후보의 근거 면제를 확인한다. 결정 표시 쪽은 승인할 때만 intent.confirmed 를 채우는지 보고,
 * 두 번째 결정, 수정안 없는 제안서의 승인, 합격하지 않은 후보 번호, 없는 제안서를 거부하는지 본다.
 * 거부 메시지 전체와 규칙의 우선순위는 service/src/test/java/com/capstone4/skillops/proposal/ProposalCharacterizationTest.java 가
 * 따로 고정한다.
 */
class ProposalServiceTest {

    ProposalRepository repository = mock(ProposalRepository.class);
    InMemoryRunLedger ledger = new InMemoryRunLedger();
    ProposalService service = new ProposalService(repository, ledger,
            Clock.fixed(Instant.parse("2026-10-21T05:10:00Z"), ZoneOffset.UTC));
    /** 테스트마다 새로 읽는 F1 제안서다. 테스트가 값을 바꿔 가며 거부 규칙을 시험한다. */
    Map<String, Object> f1Copy = f1();

    @BeforeEach
    void setUp() {
        given(repository.save(any(Proposal.class))).willAnswer(call -> call.getArgument(0));
        given(repository.findByThreadId(anyString())).willReturn(Optional.empty());
        // F1 픽스처가 근거로 적은 원인 실행과 후보 실행을 장부에 미리 남긴다.
        ledger.record("w_ablation", "f-33333333-3333-4333-8333-333333333333", "r_a0000000000000000000000000000001", false);
        ledger.record("w_ablation", "f-33333333-3333-4333-8333-333333333333", "r_a0000000000000000000000000000002", true);
        ledger.record("w_c1", "g-01", "r_c1000000000000000000000000000001", true);
        ledger.record("w_c1", "g-01", "r_c1000000000000000000000000000002", true);
        ledger.record("w_c2", "g-01", "r_c2000000000000000000000000000001", true);
        ledger.record("w_c2", "g-02", "r_c2000000000000000000000000000002", false);
    }

    /** F1 픽스처(service/src/test/resources/proposal/f1-proposed.json)를 새로 읽는다. */
    static Map<String, Object> f1() {
        return ProposalDocumentWriterTest.fixture("f1-proposed.json");
    }

    /** 제안서의 index 번째 후보를 꺼낸다. index 는 0부터 센다. */
    @SuppressWarnings("unchecked")
    static Map<String, Object> candidate(Map<String, Object> proposal, int index) {
        return ((List<Map<String, Object>>) proposal.get("candidates")).get(index);
    }

    /** index 번째 후보의 results 를 꺼낸다. */
    @SuppressWarnings("unchecked")
    static Map<String, Object> results(Map<String, Object> proposal, int index) {
        return (Map<String, Object>) candidate(proposal, index).get("results");
    }

    /** 저장소의 save 에 넘어간 제안서를 꺼낸다. */
    Proposal captured() {
        ArgumentCaptor<Proposal> saved = ArgumentCaptor.forClass(Proposal.class);
        then(repository).should().save(saved.capture());
        return saved.getValue();
    }

    @Test
    void savesAndReturnsContractShape() {
        SavedProposal saved = service.save(f1());

        assertThat(saved.proposalId()).matches("p_[0-9a-f]{32}");
        assertThat(saved.status()).isEqualTo("proposed");
        assertThat(saved.validCandidateNos()).containsExactly(1);
        Proposal stored = captured();
        assertThat(stored.getProposalId()).isEqualTo(saved.proposalId());
        assertThat(stored.getDocument().get("title")).isEqualTo("inventory-check 수정 제안서");
        assertThat(stored.getDocument()).containsKey("tone_warnings");
        assertThat(stored.getThreadId()).isEqualTo("5b0c2f3e-8f0a-4a57-9a3c-1c2d3e4f5a6b");
        assertThat(stored.getValidCandidateNos()).containsExactly(1);
        assertThat(stored.getCreatedAt()).isEqualTo(Instant.parse("2026-10-21T05:10:00Z"));
        assertThat(stored.getDecidedAt()).isNull();
    }

    @Test
    void sameThreadReturnsTheFirstProposal() {
        Proposal first = Proposal.create("p_first", "5b0c2f3e-8f0a-4a57-9a3c-1c2d3e4f5a6b", "proposed", f1(), Map.of(),
                List.of(1), Instant.parse("2026-10-21T05:00:00Z"));
        given(repository.findByThreadId("5b0c2f3e-8f0a-4a57-9a3c-1c2d3e4f5a6b")).willReturn(Optional.of(first));

        SavedProposal again = service.save(f1());

        assertThat(again).isEqualTo(new SavedProposal("p_first", "proposed", List.of(1)));
        then(repository).should(never()).save(any());
    }

    @Test
    void unknownEvidenceRunIsRejected() {
        ledger.clear();
        assertThatThrownBy(() -> service.save(f1())).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("후보 1").hasMessageContaining("근거 실행");
        then(repository).should(never()).save(any());
    }

    @Test
    void evidenceFromAnotherWorkspaceIsRejected() {
        candidate(f1Copy, 0).put("workspace_id", "w_c2");
        assertThatThrownBy(() -> service.save(f1Copy)).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("w_c2");
    }

    @Test
    void unknownProofRunIsRejected() {
        @SuppressWarnings("unchecked")
        Map<String, Object> root = (Map<String, Object>) f1Copy.get("root_cause");
        root.put("proof_run_ids", List.of("r_ffffffffffffffffffffffffffffffff"));
        assertThatThrownBy(() -> service.save(f1Copy)).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("원인 근거 실행");
    }

    @Test
    void agentValidMustMatchRecomputedResult() {
        candidate(f1Copy, 1).put("valid", true);
        assertThatThrownBy(() -> service.save(f1Copy)).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("후보 2").hasMessageContaining("valid");
    }

    @Test
    void proposedWithoutPassingCandidateIsRejected() {
        results(f1Copy, 0).put("fixes_failure", false);
        candidate(f1Copy, 0).put("valid", false);
        assertThatThrownBy(() -> service.save(f1Copy)).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("proposed");
    }

    @Test
    void noValidFixWithPassingCandidateIsRejected() {
        f1Copy.put("status", "no_valid_fix");
        assertThatThrownBy(() -> service.save(f1Copy)).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("no_valid_fix");
    }

    @Test
    void recommendedMustBePassingCandidate() {
        f1Copy.put("recommended_candidate_no", 2);
        assertThatThrownBy(() -> service.save(f1Copy)).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("recommended_candidate_no");
    }

    @Test
    void candidateNumbersMustStartAtOneWithoutGaps() {
        candidate(f1Copy, 1).put("candidate_no", 3);
        assertThatThrownBy(() -> service.save(f1Copy)).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("candidate_no");
    }

    @Test
    void unknownStatusIsRejected() {
        f1Copy.put("status", "approved");
        assertThatThrownBy(() -> service.save(f1Copy)).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("status");
    }

    @Test
    void noValidFixWithoutRootCauseIsAccepted() {
        SavedProposal saved = service.save(ProposalDocumentWriterTest.fixture("no-root-cause.json"));
        assertThat(saved.status()).isEqualTo("no_valid_fix");
        assertThat(saved.validCandidateNos()).isEmpty();
        assertThat(captured().getDocument().get("title")).isEqualTo("Skill 변경 진단 결과");
    }

    @Test
    void rejectedCandidateWithValidationErrorNeedsNoEvidence() {
        Map<String, Object> second = candidate(f1Copy, 1);
        second.put("validation_error", "patch context does not match");
        second.put("results", null);
        second.put("evidence_run_ids", List.of());
        second.put("workspace_id", null);
        assertThat(service.save(f1Copy).validCandidateNos()).containsExactly(1);
    }

    @Test
    void emptyProposalIsRejected() {
        assertThatThrownBy(() -> service.save(null)).isInstanceOf(IllegalArgumentException.class);
    }

    // ── 결정 표시(markDecided) ─────────────────────────────────────────────

    /**
     * 주어진 상태로 저장된 제안서 p_1 을 저장소 mock 이 돌려주게 한다.
     * no_valid_fix 면 합격 후보가 없고, 그 밖의 상태면 1번이 합격 후보다.
     */
    Proposal stored(String status) {
        Proposal proposal = Proposal.create("p_1", "5b0c2f3e-8f0a-4a57-9a3c-1c2d3e4f5a6b", status, f1Copy, Map.of(),
                "no_valid_fix".equals(status) ? List.of() : List.of(1), Instant.parse("2026-10-21T05:00:00Z"));
        given(repository.findById("p_1")).willReturn(Optional.of(proposal));
        return proposal;
    }

    /** 던진 예외가 ProposalException 이고 코드와 HTTP 상태가 기대와 같은지 본다. */
    static void assertProblem(Throwable thrown, String code, int httpStatus) {
        assertThat(thrown).isInstanceOf(ProposalException.class);
        ProposalException e = (ProposalException) thrown;
        assertThat(e.code()).isEqualTo(code);
        assertThat(e.httpStatus()).isEqualTo(httpStatus);
    }

    @Test
    void approvingFillsIntentConfirmed() {
        stored("proposed");

        Proposal decided = service.markDecided("p_1", "approved", 1);

        assertThat(decided.getStatus()).isEqualTo("approved");
        assertThat(decided.getDecidedAt()).isEqualTo(Instant.parse("2026-10-21T05:10:00Z"));
        @SuppressWarnings("unchecked")
        Map<String, Object> intent = (Map<String, Object>) decided.getPayload().get("intent");
        assertThat(intent).containsEntry("confirmed", true);
        then(repository).should().save(decided);
    }

    @Test
    void rejectingKeepsIntentUnconfirmed() {
        stored("proposed");

        Proposal decided = service.markDecided("p_1", "rejected", null);

        assertThat(decided.getStatus()).isEqualTo("rejected");
        @SuppressWarnings("unchecked")
        Map<String, Object> intent = (Map<String, Object>) decided.getPayload().get("intent");
        assertThat(intent.get("confirmed")).isNull();
    }

    @Test
    void secondDecisionIsRejected() {
        stored("approved");
        assertProblem(catchThrowable(() -> service.markDecided("p_1", "approved", 1)), "PROPOSAL_ALREADY_DECIDED", 409);
        assertProblem(catchThrowable(() -> service.markDecided("p_1", "rejected", null)), "PROPOSAL_ALREADY_DECIDED",
                409);
        then(repository).should(never()).save(any());
    }

    @Test
    void noValidFixCannotBeApproved() {
        stored("no_valid_fix");
        assertProblem(catchThrowable(() -> service.markDecided("p_1", "approved", 1)), "NO_VALID_FIX", 409);
        assertThat(service.markDecided("p_1", "rejected", null).getStatus()).isEqualTo("rejected");
    }

    @Test
    void unknownCandidateNumberIsRejected() {
        stored("proposed");
        for (Integer no : new Integer[] { 0, 2, 99, null }) {
            assertProblem(catchThrowable(() -> service.markDecided("p_1", "approved", no)), "CANDIDATE_NOT_VALID", 400);
        }
        then(repository).should(never()).save(any());
    }

    @Test
    void unknownProposalIsNotFound() {
        given(repository.findById("p_none")).willReturn(Optional.empty());
        assertProblem(catchThrowable(() -> service.markDecided("p_none", "rejected", null)), "PROPOSAL_NOT_FOUND", 404);
    }
}
