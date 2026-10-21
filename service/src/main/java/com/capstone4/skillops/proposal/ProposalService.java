package com.capstone4.skillops.proposal;

import static com.capstone4.skillops.proposal.ProposalRules.integer;
import static com.capstone4.skillops.proposal.ProposalRules.map;
import static com.capstone4.skillops.proposal.ProposalRules.maps;
import static com.capstone4.skillops.proposal.ProposalRules.str;
import static com.capstone4.skillops.proposal.ProposalRules.strings;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import org.springframework.stereotype.Service;

import com.capstone4.skillops.scenario.RunLedger;

import tools.jackson.databind.PropertyNamingStrategies;
import tools.jackson.databind.annotation.JsonNaming;
import tools.jackson.databind.json.JsonMapper;

/**
 * 수정 제안서 저장(계약 5 §10 save_proposal). 근거로 적은 실행 번호가 이 서비스가 실제로 돌린 모의 실행인지
 * {@link RunLedger} 로 확인하고, 합격 후보를 다시 계산한 뒤에만 저장한다. 시험을 거치지 않은 수정안이 운영자 화면에 올라가지 않게 하려는 것이다.
 */
@Service
public class ProposalService {

    static final String PROPOSED = "proposed";
    static final String NO_VALID_FIX = "no_valid_fix";

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private final ProposalRepository repository;
    private final RunLedger ledger;
    private final Clock clock;

    public ProposalService(ProposalRepository repository, RunLedger ledger) {
        this(repository, ledger, Clock.systemUTC());
    }

    ProposalService(ProposalRepository repository, RunLedger ledger, Clock clock) {
        this.repository = repository;
        this.ledger = ledger;
        this.clock = clock;
    }

    /** save_proposal 의 결과. {proposal_id, status, valid_candidate_nos} */
    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record SavedProposal(String proposalId, String status, List<Integer> validCandidateNos) {
    }

    /** 잘못된 제안서는 IllegalArgumentException(MCP 결과는 INVALID_INPUT). 메시지는 Agent 개발자가 읽는다 */
    public SavedProposal save(Map<String, Object> proposal) {
        if (proposal == null || proposal.isEmpty()) {
            throw new IllegalArgumentException("proposal 이 비어 있습니다");
        }
        String threadId = str(proposal.get("thread_id"));
        if (threadId != null) {
            Optional<Proposal> existing = repository.findByThreadId(threadId);
            if (existing.isPresent()) {
                // B 가 같은 진단으로 다시 저장해도 제안서는 하나다.
                return savedOf(existing.get());
            }
        }
        String status = str(proposal.get("status"));
        if (!PROPOSED.equals(status) && !NO_VALID_FIX.equals(status)) {
            throw new IllegalArgumentException("status 는 proposed 나 no_valid_fix 여야 합니다 (받은 값 " + status + ")");
        }
        List<Integer> valid = checkCandidates(maps(proposal.get("candidates")));
        if (PROPOSED.equals(status) && valid.isEmpty()) {
            throw new IllegalArgumentException("합격한 후보가 없는데 status 가 proposed 입니다");
        }
        if (NO_VALID_FIX.equals(status) && !valid.isEmpty()) {
            throw new IllegalArgumentException("합격한 후보 " + valid + " 가 있는데 status 가 no_valid_fix 입니다");
        }
        if (PROPOSED.equals(status)) {
            checkRootCause(map(proposal.get("root_cause")));
            Integer recommended = integer(proposal.get("recommended_candidate_no"));
            if (recommended == null || !valid.contains(recommended)) {
                throw new IllegalArgumentException(
                        "recommended_candidate_no " + recommended + " 은 합격한 후보가 아닙니다 (합격 후보 " + valid + ")");
            }
        }
        String proposalId = "p_" + UUID.randomUUID().toString().replace("-", "");
        Instant now = clock.instant();
        @SuppressWarnings("unchecked")
        Map<String, Object> document = JSON.convertValue(ProposalDocumentWriter.write(proposalId, proposal, now),
                Map.class);
        repository.save(Proposal.create(proposalId, threadId, status, proposal, document, valid, now));
        return new SavedProposal(proposalId, status, valid);
    }

    public Optional<Proposal> find(String proposalId) {
        return repository.findById(proposalId);
    }

    /** 만든 시각이 늦은 것부터 100개 */
    public List<Proposal> recent() {
        return repository.findTop100ByOrderByCreatedAtDesc();
    }

    /** 번호와 근거 실행을 확인하고, 합격 후보 번호를 돌려준다 */
    private List<Integer> checkCandidates(List<Map<String, Object>> candidates) {
        List<Integer> valid = new ArrayList<>();
        for (int i = 0; i < candidates.size(); i++) {
            Map<String, Object> candidate = candidates.get(i);
            Integer no = integer(candidate.get("candidate_no"));
            if (no == null || no != i + 1) {
                throw new IllegalArgumentException(
                        "candidate_no 는 1부터 빠짐없이 매겨야 합니다 (" + (i + 1) + "번째 후보의 값 " + no + ")");
            }
            if (str(candidate.get("validation_error")) == null) {
                checkEvidence(no, candidate);
            }
            boolean passes = ProposalRules.passes(candidate);
            if (candidate.get("valid") instanceof Boolean agentValid && agentValid != passes) {
                throw new IllegalArgumentException("후보 " + no + " 의 valid(" + agentValid + ")가 시험 결과로 다시 계산한 값("
                        + passes + ")과 다릅니다");
            }
            if (passes) {
                valid.add(no);
            }
        }
        return List.copyOf(valid);
    }

    private void checkEvidence(int no, Map<String, Object> candidate) {
        String workspaceId = str(candidate.get("workspace_id"));
        if (workspaceId == null) {
            throw new IllegalArgumentException("후보 " + no + " 의 workspace_id 가 없습니다");
        }
        if (map(candidate.get("results")) == null) {
            throw new IllegalArgumentException("후보 " + no + " 의 results 가 없습니다");
        }
        List<String> evidence = strings(candidate.get("evidence_run_ids"));
        if (evidence.isEmpty()) {
            throw new IllegalArgumentException("후보 " + no + " 의 evidence_run_ids 가 비어 있습니다");
        }
        Set<String> ran = ledger.runIdsOf(workspaceId);
        for (String runId : evidence) {
            if (!ran.contains(runId)) {
                throw new IllegalArgumentException(
                        "후보 " + no + " 의 근거 실행 " + runId + " 을 연습 공간 " + workspaceId + " 에서 찾을 수 없습니다");
            }
        }
    }

    private void checkRootCause(Map<String, Object> rootCause) {
        List<String> proofs = rootCause == null ? List.of() : strings(rootCause.get("proof_run_ids"));
        if (proofs.isEmpty()) {
            throw new IllegalArgumentException("root_cause.proof_run_ids 가 비어 있습니다");
        }
        for (String runId : proofs) {
            if (!ledger.known(runId)) {
                throw new IllegalArgumentException("원인 근거 실행 " + runId + " 을 찾을 수 없습니다");
            }
        }
    }

    private static SavedProposal savedOf(Proposal proposal) {
        return new SavedProposal(proposal.getProposalId(), proposal.getStatus(), proposal.getValidCandidateNos());
    }
}
