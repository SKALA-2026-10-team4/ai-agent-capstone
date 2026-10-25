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
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Consumer;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import com.capstone4.skillops.proposal.ProposalService.SavedProposal;
import com.capstone4.skillops.scenario.TestFixtures.InMemoryRunLedger;

import tools.jackson.databind.json.JsonMapper;

/**
 * 제안서 저장과 결정 표시의 동작을 고정하는 특성 테스트다.
 * 대상은 service/src/main/java/com/capstone4/skillops/proposal/ 의 ProposalService, ProposalValidator, ProposalJudge, Proposal,
 * ProposalRules 다. save_proposal 거부 메시지 전체와 여러 규칙을 어겼을 때 어느 메시지가 먼저 나오는지, 같은 진단 재저장이 검사보다
 * 먼저 일어나는지, 저장하는 본문과 원본과 결과 값, 결정 표시 규칙과 오류 코드·문구·HTTP 상태, Proposal.decide 의 payload 교체,
 * ProposalRules.passes 의 경계를 확인한다.
 * Agent 의 valid, status, recommended_candidate_no, results 가 서버 판정과 다르면 예전에는 거부했지만, 지금은 서버 값으로 덮고
 * server_overrides 에 남긴다(docs/plans/2026-10-26-behavior-edit-root-fix.md 2.7 절). 장부와 연습 공간 기록은 ProposalTestBench 의
 * withF1 이 F1 픽스처에 맞춰 채운다.
 */
class ProposalCharacterizationTest {

    static final Instant NOW = Instant.parse("2026-10-21T05:10:00Z");
    static final JsonMapper JSON = JsonMapper.builder().build();

    ProposalRepository repository = mock(ProposalRepository.class);
    ProposalTestBench bench = new ProposalTestBench().withF1();
    InMemoryRunLedger ledger = bench.ledger;
    ProposalService service = new ProposalService(repository, ledger, bench.judge(), Clock.fixed(NOW, ZoneOffset.UTC));

    @BeforeEach
    void setUp() {
        given(repository.save(any(Proposal.class))).willAnswer(call -> call.getArgument(0));
        given(repository.findByThreadId(anyString())).willReturn(Optional.empty());
    }

    /** 저장된 제안서 payload 의 server_overrides 를 꺼낸다. */
    @SuppressWarnings("unchecked")
    List<Map<String, Object>> overrides() {
        return (List<Map<String, Object>>) captured().getPayload().get("server_overrides");
    }

    /** server_overrides 의 한 칸을 만든다. */
    static Map<String, Object> override(Integer candidateNo, String field, Object agentValue, Object serverValue) {
        Map<String, Object> entry = new LinkedHashMap<>();
        entry.put("candidate_no", candidateNo);
        entry.put("field", field);
        entry.put("agent_value", agentValue);
        entry.put("server_value", serverValue);
        return entry;
    }

    /** F1 픽스처(service/src/test/resources/proposal/f1-proposed.json)를 새로 읽어 change 로 고친 제안서를 돌려준다. */
    static Map<String, Object> f1(Consumer<Map<String, Object>> change) {
        Map<String, Object> proposal = ProposalDocumentWriterTest.fixture("f1-proposed.json");
        change.accept(proposal);
        return proposal;
    }

    /** JSON 객체 값을 Map 으로 형 변환한다. */
    @SuppressWarnings("unchecked")
    static Map<String, Object> map(Object value) {
        return (Map<String, Object>) value;
    }

    /** 제안서의 index 번째 후보를 꺼낸다. index 는 0부터 센다. */
    @SuppressWarnings("unchecked")
    static Map<String, Object> candidate(Map<String, Object> proposal, int index) {
        return ((List<Map<String, Object>>) proposal.get("candidates")).get(index);
    }

    /** index 번째 후보의 results 를 꺼낸다. */
    static Map<String, Object> results(Map<String, Object> proposal, int index) {
        return map(candidate(proposal, index).get("results"));
    }

    /** save 가 정확히 이 메시지의 IllegalArgumentException 으로 거부하고 아무것도 저장하지 않았는지 본다. */
    void assertRejected(Map<String, Object> proposal, String message) {
        assertThatThrownBy(() -> service.save(proposal)).isExactlyInstanceOf(IllegalArgumentException.class)
                .hasMessage(message);
        then(repository).should(never()).save(any());
    }

    /** 저장소의 save 에 넘어간 제안서를 꺼낸다. */
    Proposal captured() {
        ArgumentCaptor<Proposal> saved = ArgumentCaptor.forClass(Proposal.class);
        then(repository).should().save(saved.capture());
        return saved.getValue();
    }

    // ── save_proposal 거부 메시지 전체 ─────────────────────────────────────

    @Test
    void emptyProposalMessage() {
        assertRejected(null, "proposal 이 비어 있습니다");
        assertRejected(Map.of(), "proposal 이 비어 있습니다");
    }

    @Test
    void statusMessage() {
        assertRejected(f1(p -> p.put("status", "approved")), "status 는 proposed 나 no_valid_fix 여야 합니다 (받은 값 approved)");
        assertRejected(f1(p -> p.remove("status")), "status 는 proposed 나 no_valid_fix 여야 합니다 (받은 값 null)");
        assertRejected(f1(p -> p.put("status", " ")), "status 는 proposed 나 no_valid_fix 여야 합니다 (받은 값 null)");
    }

    /** snapshot_id 가 없으면 의도와 근거를 대조할 수 없으므로 거부한다(docs/plans/2026-10-26-behavior-edit-root-fix.md 2.10 절 V3-1). */
    @Test
    void snapshotIdMessage() {
        assertRejected(f1(p -> p.remove("snapshot_id")), "snapshot_id 가 없습니다");
        assertRejected(f1(p -> p.put("snapshot_id", null)), "snapshot_id 가 없습니다");
        assertRejected(f1(p -> p.put("snapshot_id", " ")), "snapshot_id 가 없습니다");
    }

    /** snapshot_id 는 status 다음, 후보보다 먼저 본다. */
    @Test
    void snapshotIdIsCheckedAfterStatusAndBeforeCandidates() {
        assertRejected(f1(p -> {
            p.put("status", "bogus");
            p.remove("snapshot_id");
        }), "status 는 proposed 나 no_valid_fix 여야 합니다 (받은 값 bogus)");
        assertRejected(f1(p -> {
            p.remove("snapshot_id");
            candidate(p, 1).put("candidate_no", 3);
        }), "snapshot_id 가 없습니다");
    }

    /** 진단한 snapshot 의 metadata 를 주문 Agent 에서 읽지 못하면 Agent 값으로 판정하지 않고 저장을 거부한다. 오류 code 는 주문 Agent 의 것이다. */
    @Test
    void unreadableSnapshotMetadataRejectsTheSave() {
        bench.metadata.clear();

        assertThatThrownBy(() -> service.save(f1(p -> {
        }))).isInstanceOf(com.capstone4.skillops.snapshot.OrderAgentException.class).hasMessageContaining("snapshot");
        then(repository).should(never()).save(any());
    }

    @Test
    void candidateNumberMessage() {
        assertRejected(f1(p -> candidate(p, 1).put("candidate_no", 3)),
                "candidate_no 는 1부터 빠짐없이 매겨야 합니다 (2번째 후보의 값 3)");
        assertRejected(f1(p -> candidate(p, 0).remove("candidate_no")),
                "candidate_no 는 1부터 빠짐없이 매겨야 합니다 (1번째 후보의 값 null)");
    }

    @Test
    void evidenceMessages() {
        assertRejected(f1(p -> candidate(p, 0).put("workspace_id", null)), "후보 1 의 workspace_id 가 없습니다");
        assertRejected(f1(p -> candidate(p, 0).put("results", null)), "후보 1 의 results 가 없습니다");
        assertRejected(f1(p -> candidate(p, 0).put("evidence_run_ids", List.of())), "후보 1 의 evidence_run_ids 가 비어 있습니다");
        ledger.clear();
        assertRejected(f1(p -> {
        }), "후보 1 의 근거 실행 r_c1000000000000000000000000000001 을 연습 공간 w_c1 에서 찾을 수 없습니다");
    }

    /** Agent 의 valid 가 서버 판정과 달라도 거부하지 않는다. 서버 값으로 덮고 server_overrides 에 남긴다. */
    @Test
    void agentValidIsOverriddenNotRejected() {
        SavedProposal saved = service.save(f1(p -> candidate(p, 1).put("valid", true)));

        assertThat(saved.validCandidateNos()).containsExactly(1);
        assertThat(overrides()).containsExactly(override(2, "valid", true, false));
        assertThat(candidate(captured().getPayload(), 1)).containsEntry("valid", false);
    }

    @Test
    void agentValidThatIsNotBooleanIsNotCompared() {
        SavedProposal saved = service.save(f1(p -> {
            candidate(p, 0).remove("valid");
            candidate(p, 1).put("valid", "true");
        }));
        assertThat(saved.validCandidateNos()).containsExactly(1);
    }

    /** Agent 의 results 는 장부로 다시 계산한 값으로 덮는다. 합격 후보가 그대로면 status 와 추천도 그대로다. */
    @Test
    void agentResultsAreRecomputedFromTheLedger() {
        SavedProposal saved = service.save(f1(p -> {
            results(p, 0).put("fixes_failure", false);
            candidate(p, 0).put("valid", false);
        }));

        assertThat(saved).extracting(SavedProposal::status, SavedProposal::validCandidateNos)
                .containsExactly("proposed", List.of(1));
        assertThat(overrides()).containsExactly(override(1, "results.fixes_failure", false, true),
                override(1, "valid", false, true));
        assertThat(results(captured().getPayload(), 0)).containsEntry("fixes_failure", true);
    }

    /** 합격 후보가 있는데 no_valid_fix 로 보내면 서버가 proposed 로 바꾼다. */
    @Test
    void statusFollowsTheServerVerdict() {
        SavedProposal saved = service.save(f1(p -> p.put("status", "no_valid_fix")));

        assertThat(saved.status()).isEqualTo("proposed");
        assertThat(overrides()).containsExactly(override(null, "status", "no_valid_fix", "proposed"));
        assertThat(captured().getStatus()).isEqualTo("proposed");
    }

    /** 장부에 재현 실행이 모자라면 서버는 합격 후보가 없다고 보고, status 와 추천을 no_valid_fix 와 null 로 바꾼다. */
    @Test
    void missingReproRunsTurnTheProposalIntoNoValidFix() {
        ledger.clear();
        ledger.record("w_ablation", ProposalTestBench.REPRO, "r_a0000000000000000000000000000001", false);
        ledger.record("w_ablation", ProposalTestBench.REPRO, "r_a0000000000000000000000000000002", true);
        ledger.record("w_c1", "g-01", "r_c1000000000000000000000000000001", true);
        ledger.record("w_c1", "g-01", "r_c1000000000000000000000000000002", true);
        ledger.record("w_c2", "g-01", "r_c2000000000000000000000000000001", true);
        ledger.record("w_c2", "g-02", "r_c2000000000000000000000000000002", false);

        SavedProposal saved = service.save(f1(p -> {
        }));

        assertThat(saved).extracting(SavedProposal::status, SavedProposal::validCandidateNos)
                .containsExactly("no_valid_fix", List.of());
        assertThat(overrides()).containsExactly(
                override(1, "results.fixes_failure", true, false),
                override(1, "results.keeps_old_orders", Map.of("passed", 3, "total", 3), Map.of("passed", 1, "total", 3)),
                override(1, "valid", true, false),
                override(2, "results.fixes_failure", true, false),
                override(2, "results.keeps_old_orders", Map.of("passed", 2, "total", 3), Map.of("passed", 0, "total", 3)),
                override(null, "status", "proposed", "no_valid_fix"),
                override(null, "recommended_candidate_no", 1, null));
        assertThat(captured().getPayload()).containsEntry("recommended_candidate_no", null);
    }

    @Test
    void rootCauseMessages() {
        assertRejected(f1(p -> map(p.get("root_cause")).put("proof_run_ids", List.of())),
                "root_cause.proof_run_ids 가 비어 있습니다");
        assertRejected(f1(p -> p.put("root_cause", null)), "root_cause.proof_run_ids 가 비어 있습니다");
        assertRejected(f1(p -> map(p.get("root_cause")).put("proof_run_ids", List.of("r_ffffffffffffffffffffffffffffffff"))),
                "원인 근거 실행 r_ffffffffffffffffffffffffffffffff 을 찾을 수 없습니다");
    }

    /** 추천 후보가 합격 후보가 아니면 서버가 합격 후보 가운데 rank 가 가장 작은 후보로 바꾼다. 숫자가 아닌 값도 바꾼다. */
    @Test
    void recommendedIsReplacedByAPassingCandidate() {
        for (Object agent : new Object[] { 2, null, "1" }) {
            Map<String, Object> proposal = f1(p -> p.put("recommended_candidate_no", agent));
            SavedProposal saved = service.save(proposal);

            assertThat(saved.validCandidateNos()).containsExactly(1);
            Proposal stored = captured();
            assertThat(stored.getPayload()).containsEntry("recommended_candidate_no", 1);
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> entries = (List<Map<String, Object>>) stored.getPayload().get("server_overrides");
            assertThat(entries).containsExactly(override(null, "recommended_candidate_no", agent, 1));
            org.mockito.Mockito.clearInvocations(repository);
        }
    }

    // ── 여러 규칙을 어긴 입력은 먼저 검사하는 규칙의 메시지가 나온다 ─────────────────

    @Test
    void statusIsCheckedBeforeCandidates() {
        assertRejected(f1(p -> {
            p.put("status", "bogus");
            candidate(p, 1).put("candidate_no", 3);
        }), "status 는 proposed 나 no_valid_fix 여야 합니다 (받은 값 bogus)");
    }

    @Test
    void candidatesAreCheckedInOrder() {
        assertRejected(f1(p -> {
            candidate(p, 0).put("evidence_run_ids", List.of("r_unknown"));
            candidate(p, 1).put("valid", true);
        }), "후보 1 의 근거 실행 r_unknown 을 연습 공간 w_c1 에서 찾을 수 없습니다");
        // valid 불일치는 더 이상 거부 사유가 아니므로 다음 후보의 번호 검사가 먼저 걸린다.
        assertRejected(f1(p -> {
            candidate(p, 0).put("valid", false);
            candidate(p, 1).put("candidate_no", 3);
        }), "candidate_no 는 1부터 빠짐없이 매겨야 합니다 (2번째 후보의 값 3)");
    }

    @Test
    void numberIsCheckedBeforeEvidenceAndWorkspaceBeforeResults() {
        assertRejected(f1(p -> {
            candidate(p, 0).put("candidate_no", 2);
            candidate(p, 0).put("workspace_id", null);
        }), "candidate_no 는 1부터 빠짐없이 매겨야 합니다 (1번째 후보의 값 2)");
        assertRejected(f1(p -> {
            candidate(p, 0).put("workspace_id", null);
            candidate(p, 0).put("results", null);
        }), "후보 1 의 workspace_id 가 없습니다");
    }

    /** 원인 근거는 서버가 proposed 로 판정했을 때만 본다. Agent 가 합격 후보가 없다고 적어도 서버가 합격으로 보면 원인 근거를 요구한다. */
    @Test
    void rootCauseIsCheckedWhenTheServerProposes() {
        assertRejected(f1(p -> {
            results(p, 0).put("fixes_failure", false);
            candidate(p, 0).put("valid", false);
            p.put("root_cause", null);
        }), "root_cause.proof_run_ids 가 비어 있습니다");
        assertRejected(f1(p -> {
            p.put("root_cause", null);
            p.put("recommended_candidate_no", 2);
        }), "root_cause.proof_run_ids 가 비어 있습니다");
    }

    /** 서버가 no_valid_fix 로 판정하면 원인 근거가 없어도 저장한다. */
    @Test
    void noRootCauseIsAcceptedWhenTheServerFindsNoPassingCandidate() {
        bench.workspaces.remove("w_c1");

        SavedProposal saved = service.save(f1(p -> p.put("root_cause", null)));

        assertThat(saved.status()).isEqualTo("no_valid_fix");
        assertThat(overrides()).contains(override(1, "results.evidence_matches", null, false),
                override(null, "status", "proposed", "no_valid_fix"));
    }

    @Test
    void validationErrorCandidateSkipsEvidenceButStillChecksNumber() {
        SavedProposal saved = service.save(f1(p -> {
            Map<String, Object> second = candidate(p, 1);
            second.put("validation_error", "patch context does not match");
            second.put("workspace_id", null);
            second.put("results", null);
            second.put("evidence_run_ids", null);
        }));
        assertThat(saved.validCandidateNos()).containsExactly(1);
    }

    // ── 같은 진단 재저장 ─────────────────────────────────────────────────

    @Test
    void sameThreadReturnsFirstProposalBeforeAnyCheck() {
        Proposal first = Proposal.create("p_first", "5b0c2f3e-8f0a-4a57-9a3c-1c2d3e4f5a6b", "proposed", Map.of(), Map.of(),
                null, Instant.parse("2026-10-21T05:00:00Z"));
        given(repository.findByThreadId("5b0c2f3e-8f0a-4a57-9a3c-1c2d3e4f5a6b")).willReturn(Optional.of(first));

        SavedProposal again = service.save(f1(p -> {
            p.put("status", "bogus");
            p.put("candidates", "not-a-list");
        }));

        assertThat(again).isEqualTo(new SavedProposal("p_first", "proposed", List.of()));
        then(repository).should(never()).save(any());
    }

    @Test
    void missingOrBlankThreadIdIsNotLookedUp() {
        service.save(f1(p -> p.remove("thread_id")));
        service.save(f1(p -> p.put("thread_id", " ")));
        then(repository).should(never()).findByThreadId(any());
    }

    // ── 저장하는 값 ──────────────────────────────────────────────────────

    /**
     * 저장하는 payload 는 받은 제안서를 서버 판정으로 덮은 사본이고, 본문은 그 사본으로 만든다. 받은 제안서는 바꾸지 않는다.
     * 서버가 채운 칸(metadata_valid, flow_valid, evidence_matches, server_overrides)을 빼면 받은 제안서와 같다.
     */
    @Test
    void storedDocumentIsTheWriterOutputOfTheServerPayload() {
        Map<String, Object> input = f1(p -> {
        });
        Map<String, Object> untouched = f1(p -> {
        });
        SavedProposal saved = service.save(input);

        Proposal stored = captured();
        @SuppressWarnings("unchecked")
        Map<String, Object> expected = JSON.convertValue(
                ProposalDocumentWriter.write(saved.proposalId(), stored.getPayload(), NOW), Map.class);
        assertThat(stored.getDocument()).isEqualTo(expected);
        assertThat(input).isEqualTo(untouched);
        assertThat(stored.getPayload()).isNotSameAs(input).containsEntry("server_overrides", List.of());
        assertThat(results(stored.getPayload(), 0)).containsEntry("metadata_valid", true).containsEntry("flow_valid", true)
                .containsEntry("evidence_matches", true);
        Map<String, Object> withoutServerFields = new LinkedHashMap<>(stored.getPayload());
        withoutServerFields.remove("server_overrides");
        for (int i = 0; i < 2; i++) {
            results(withoutServerFields, i).remove("metadata_valid");
            results(withoutServerFields, i).remove("flow_valid");
            results(withoutServerFields, i).remove("evidence_matches");
        }
        assertThat(withoutServerFields).isEqualTo(untouched);
        assertThat(stored.getStatus()).isEqualTo("proposed");
        assertThat(stored.getThreadId()).isEqualTo("5b0c2f3e-8f0a-4a57-9a3c-1c2d3e4f5a6b");
        assertThat(stored.getValidCandidateNos()).containsExactly(1);
        assertThat(stored.getCreatedAt()).isEqualTo(NOW);
        assertThat(stored.getDecidedAt()).isNull();
    }

    @Test
    void savedProposalUsesLocalValuesNotTheRepositoryResult() {
        given(repository.save(any(Proposal.class))).willReturn(null);

        SavedProposal saved = service.save(f1(p -> {
        }));

        assertThat(saved.proposalId()).matches("p_[0-9a-f]{32}");
        assertThat(saved.status()).isEqualTo("proposed");
        assertThat(saved.validCandidateNos()).containsExactly(1);
    }

    @Test
    void noValidFixIsStoredWithEmptyValidList() {
        SavedProposal saved = service.save(ProposalDocumentWriterTest.fixture("no-root-cause.json"));
        assertThat(saved.status()).isEqualTo("no_valid_fix");
        assertThat(captured().getValidCandidateNos()).isEmpty();
    }

    // ── 결정 표시(markDecided) ─────────────────────────────────────────────

    /**
     * 주어진 상태로 저장된 제안서 p_1 을 저장소 mock 이 돌려주게 한다.
     * no_valid_fix 면 합격 후보가 없고, 그 밖의 상태면 1번이 합격 후보다.
     */
    Proposal stored(String status) {
        Proposal proposal = Proposal.create("p_1", "thread-1", status, f1(p -> {
        }), Map.of(), "no_valid_fix".equals(status) ? List.of() : List.of(1), Instant.parse("2026-10-21T05:00:00Z"));
        given(repository.findById("p_1")).willReturn(Optional.of(proposal));
        return proposal;
    }

    /** 던진 예외가 정확히 ProposalException 이고 코드, 문구, HTTP 상태가 기대와 같은지 본다. */
    static void assertProblem(Throwable thrown, String code, String message, int httpStatus) {
        assertThat(thrown).isExactlyInstanceOf(ProposalException.class).hasMessage(message);
        ProposalException e = (ProposalException) thrown;
        assertThat(e.code()).isEqualTo(code);
        assertThat(e.httpStatus()).isEqualTo(httpStatus);
    }

    @Test
    void unknownDecisionIsRejectedBeforeLookup() {
        assertThatThrownBy(() -> service.markDecided("p_1", "maybe", 1)).isExactlyInstanceOf(IllegalArgumentException.class)
                .hasMessage("decision 은 approved 나 rejected 여야 합니다 (받은 값 maybe)");
        assertThatThrownBy(() -> service.markDecided("p_1", null, null)).isExactlyInstanceOf(IllegalArgumentException.class)
                .hasMessage("decision 은 approved 나 rejected 여야 합니다 (받은 값 null)");
        then(repository).should(never()).findById(any());
    }

    @Test
    void decisionProblemsCarryCodeMessageAndStatus() {
        given(repository.findById("p_none")).willReturn(Optional.empty());
        assertProblem(catchThrowable(() -> service.markDecided("p_none", "approved", 1)), "PROPOSAL_NOT_FOUND",
                "제안서를 찾을 수 없습니다.", 404);

        stored("rejected");
        assertProblem(catchThrowable(() -> service.markDecided("p_1", "approved", 99)), "PROPOSAL_ALREADY_DECIDED",
                "이미 결정한 제안서입니다.", 409);

        stored("no_valid_fix");
        assertProblem(catchThrowable(() -> service.markDecided("p_1", "approved", null)), "NO_VALID_FIX",
                "승인할 수 있는 수정안이 없는 제안서입니다.", 409);

        stored("proposed");
        assertProblem(catchThrowable(() -> service.markDecided("p_1", "approved", 2)), "CANDIDATE_NOT_VALID",
                "모든 확인을 통과한 후보만 승인할 수 있습니다.", 400);
        then(repository).should(never()).save(any());
    }

    @Test
    void rejectionIgnoresTheCandidateNumber() {
        stored("proposed");
        Proposal decided = service.markDecided("p_1", "rejected", 99);
        assertThat(decided.getStatus()).isEqualTo("rejected");
        assertThat(decided.getDecidedAt()).isEqualTo(NOW);
    }

    @Test
    void markDecidedReturnsWhatTheRepositorySaved() {
        stored("proposed");
        Proposal other = Proposal.create("p_other", null, "approved", Map.of(), Map.of(), List.of(1), NOW);
        given(repository.save(any(Proposal.class))).willReturn(other);

        assertThat(service.markDecided("p_1", "approved", 1)).isSameAs(other);
    }

    // ── Proposal.decide ────────────────────────────────────────────────

    @Test
    void approvingWithoutIntentReplacesPayloadWithEqualCopy() {
        Map<String, Object> payload = new LinkedHashMap<>(Map.of("status", "proposed"));
        Proposal proposal = Proposal.create("p_1", null, "proposed", payload, Map.of(), List.of(1), NOW);

        proposal.decide("approved", NOW);

        assertThat(proposal.getPayload()).isEqualTo(Map.of("status", "proposed")).isNotSameAs(payload);
        assertThat(proposal.getStatus()).isEqualTo("approved");
        assertThat(proposal.getDecidedAt()).isEqualTo(NOW);
    }

    @Test
    void approvingCopiesIntentAndLeavesTheOriginalUntouched() {
        Map<String, Object> payload = f1(p -> {
        });
        Map<String, Object> originalIntent = map(payload.get("intent"));
        Proposal proposal = Proposal.create("p_1", null, "proposed", payload, Map.of(), List.of(1), NOW);

        proposal.decide("approved", NOW);

        Map<String, Object> intent = map(proposal.getPayload().get("intent"));
        assertThat(intent).isNotSameAs(originalIntent).containsEntry("confirmed", true)
                .containsEntry("text", "예약 가능 수량을 계속 내놓고 싶다.");
        assertThat(intent.keySet()).containsExactly("text", "checks", "confirmed");
        assertThat(originalIntent.get("confirmed")).isNull();
        assertThat(payload.get("intent")).isSameAs(originalIntent);
        assertThat(proposal.getPayload().keySet()).containsExactlyElementsOf(payload.keySet());
    }

    @Test
    void approvingKeepsIntentThatIsNotAnObject() {
        Map<String, Object> payload = new HashMap<>();
        payload.put("intent", "text only");
        Proposal proposal = Proposal.create("p_1", null, "proposed", payload, Map.of(), List.of(1), NOW);

        proposal.decide("approved", NOW);

        assertThat(proposal.getPayload()).containsEntry("intent", "text only").isNotSameAs(payload);
    }

    @Test
    void rejectingKeepsThePayloadInstance() {
        Map<String, Object> payload = f1(p -> {
        });
        Proposal proposal = Proposal.create("p_1", null, "proposed", payload, Map.of(), List.of(1), NOW);

        proposal.decide("rejected", NOW);

        assertThat(proposal.getPayload()).isSameAs(payload);
        assertThat(proposal.getStatus()).isEqualTo("rejected");
    }

    // ── ProposalRules.passes 경계 ─────────────────────────────────────────

    /** F1 의 1번 후보를 꺼낸다. 모든 확인을 통과한 후보다. */
    static Map<String, Object> passing() {
        return candidate(ProposalDocumentWriterTest.fixture("f1-proposed.json"), 0);
    }

    /** 합격 후보를 새로 읽어 change 로 고친 후보를 돌려준다. */
    static Map<String, Object> passingWith(Consumer<Map<String, Object>> change) {
        Map<String, Object> candidate = passing();
        change.accept(candidate);
        return candidate;
    }

    @Test
    void passesNeedsEveryCriterion() {
        assertThat(ProposalRules.passes(passing())).isTrue();
        assertThat(ProposalRules.passes(passingWith(c -> c.put("validation_error", "x")))).isFalse();
        assertThat(ProposalRules.passes(passingWith(c -> c.put("validation_error", " ")))).isTrue();
        assertThat(ProposalRules.passes(passingWith(c -> c.remove("results")))).isFalse();
        assertThat(ProposalRules.passes(passingWith(c -> c.put("results", "not-a-map")))).isFalse();
        assertThat(ProposalRules.passes(passingWith(c -> map(c.get("results")).remove("keeps_old_orders")))).isFalse();
        assertThat(ProposalRules.passes(passingWith(c -> map(c.get("results")).put("keeps_old_orders",
                Map.of("passed", 0, "total", 0))))).isFalse();
        assertThat(ProposalRules.passes(passingWith(c -> map(c.get("results")).put("keeps_old_orders",
                Map.of("passed", 2, "total", 3))))).isFalse();
        assertThat(ProposalRules.passes(passingWith(c -> map(c.get("results")).put("keeps_old_orders",
                Map.of("passed", 3))))).isFalse();
        assertThat(ProposalRules.passes(passingWith(c -> map(c.get("results")).put("keeps_old_orders",
                Map.of("total", 3))))).isFalse();
        assertThat(ProposalRules.passes(passingWith(c -> map(c.get("results")).put("keeps_old_orders",
                Map.of("passed", 3.0, "total", 3.7))))).isTrue();
        assertThat(ProposalRules.passes(passingWith(c -> map(c.get("results")).put("fixes_failure", false)))).isFalse();
        assertThat(ProposalRules.passes(passingWith(c -> map(c.get("results")).put("fixes_failure", "true")))).isFalse();
        assertThat(ProposalRules.passes(passingWith(c -> map(c.get("results")).remove("keeps_intent")))).isFalse();
        assertThat(ProposalRules.passes(passingWith(c -> map(c.get("results")).put("no_broken_links", false)))).isFalse();
    }

    /** 명시된 메타데이터 검사 실패·null·문자열은 탈락시키고 과거 제안서의 누락은 호환한다. */
    @Test
    void metadataResultParticipatesInValidityWithoutReinterpretingLegacyProposals() {
        assertThat(ProposalRules.passes(passing())).isTrue();
        assertThat(ProposalRules.passes(passingWith(c -> map(c.get("results")).put("metadata_valid", true)))).isTrue();
        assertThat(ProposalRules.passes(passingWith(c -> map(c.get("results")).put("metadata_valid", false)))).isFalse();
        assertThat(ProposalRules.passes(passingWith(c -> map(c.get("results")).put("metadata_valid", null)))).isFalse();
        assertThat(ProposalRules.passes(passingWith(c -> map(c.get("results")).put("metadata_valid", "true")))).isFalse();
    }

    /** 새 flow 판정이 있으면 엄격한 Boolean true를 요구하고 과거 누락은 호환한다. */
    /** 서버가 쓰는 evidence_matches 도 있으면 Boolean true 여야 하고, 없는 과거 제안서는 기존 판정을 유지한다. */
    @Test
    void evidenceMatchParticipatesInValidity() {
        assertThat(ProposalRules.passes(passingWith(c -> map(c.get("results")).put("evidence_matches", true)))).isTrue();
        assertThat(ProposalRules.passes(passingWith(c -> map(c.get("results")).put("evidence_matches", false)))).isFalse();
        assertThat(ProposalRules.passes(passingWith(c -> map(c.get("results")).put("evidence_matches", null)))).isFalse();
    }

    @Test
    void flowResultParticipatesInValidityWithoutReinterpretingLegacyProposals() {
        assertThat(ProposalRules.passes(passing())).isTrue();
        assertThat(ProposalRules.passes(passingWith(c -> map(c.get("results")).put("flow_valid", true)))).isTrue();
        assertThat(ProposalRules.passes(passingWith(c -> map(c.get("results")).put("flow_valid", false)))).isFalse();
        assertThat(ProposalRules.passes(passingWith(c -> map(c.get("results")).put("flow_valid", null)))).isFalse();
        assertThat(ProposalRules.passes(passingWith(c -> map(c.get("results")).put("flow_valid", "true")))).isFalse();
    }
}
