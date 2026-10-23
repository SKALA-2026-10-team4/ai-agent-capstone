package com.capstone4.skillops.proposal;

import static com.capstone4.skillops.proposal.ProposalRules.str;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import com.capstone4.skillops.scenario.RunLedger;

import tools.jackson.databind.PropertyNamingStrategies;
import tools.jackson.databind.annotation.JsonNaming;
import tools.jackson.databind.json.JsonMapper;

/**
 * 수정 제안서의 저장, 결정 표시, 조회를 맡는 서비스다. 저장은 계약 5 §10(docs/contracts/mcp-tools-input-output.md)의 save_proposal 이다.
 *
 * 저장(save)은 Agent 가 보낸 제안서를 그대로 믿지 않는다. 근거로 적은 실행 번호가 이 서비스가 실제로 돌린 모의 실행인지
 * service/src/main/java/com/capstone4/skillops/scenario/RunLedger.java 장부로 확인하고, 합격 후보를 시험 결과로 다시 계산한 뒤에만 저장한다.
 * 시험을 거치지 않은 수정안이 운영자 화면에 올라가지 않게 하려는 것이다.
 * 거부 규칙과 그 순서는 service/src/main/java/com/capstone4/skillops/proposal/ProposalValidator.java 에, 합격 조건은
 * service/src/main/java/com/capstone4/skillops/proposal/ProposalRules.java 의 passes 에, 본문 문장은
 * service/src/main/java/com/capstone4/skillops/proposal/ProposalDocumentWriter.java 에 있다.
 * 결정 표시(markDecided)는 같은 제안서를 한 번만 결정하게 하고, 승인은 합격 후보 번호로만 받는다.
 *
 * service/src/main/java/com/capstone4/skillops/mcp/ProposalTools.java 가 save 를,
 * service/src/main/java/com/capstone4/skillops/approval/ApprovalService.java 가 markDecided 를,
 * service/src/main/java/com/capstone4/skillops/api/ProposalController.java 가 find 와 recent 를 부른다.
 * RunLedger 구현은 service/src/main/java/com/capstone4/skillops/scenario/JpaRunLedger.java 이고, run_scenarios 가 실행할 때마다
 * service/src/main/java/com/capstone4/skillops/scenario/ScenarioRunner.java 의 gradeAndRecord 가 실행 번호를 남긴다.
 */
@Service
public class ProposalService {

    /** 본문 레코드를 snake_case 키의 Map 으로 바꾸는 Jackson 3 변환기다. 키 이름은 ProposalDocument 의 @JsonNaming 을 따른다. */
    private static final JsonMapper JSON = JsonMapper.builder().build();

    private final ProposalRepository repository;
    private final ProposalValidator validator;
    /** 제안서를 만든 시각과 결정 시각을 정하는 시계다. 테스트는 고정 시계를 넣는다. */
    private final Clock clock;

    /**
     * Spring 이 빈을 만들 때 쓰는 생성자다. 시계는 UTC 시스템 시계를 쓴다.
     * 생성자가 둘이면 Spring 은 어느 쪽을 쓸지 정하지 못해 인자 없는 생성자를 찾다가 빈을 만들지 못한다. 그래서 @Autowired 로 이 생성자를 고르게 한다.
     * 이 표시가 없어 기동에 실패한 적이 있고(커밋 943b585),
     * service/src/test/java/com/capstone4/skillops/proposal/ProposalServiceWiringTest.java 가 같은 오류의 재발을 막는다.
     */
    @Autowired
    public ProposalService(ProposalRepository repository, RunLedger ledger) {
        this(repository, ledger, Clock.systemUTC());
    }

    /** 테스트가 고정 시계를 넣으려고 쓰는 생성자다. 거부 규칙 검사기(ProposalValidator)도 여기서 같은 장부로 만든다. */
    ProposalService(ProposalRepository repository, RunLedger ledger, Clock clock) {
        this.repository = repository;
        this.validator = new ProposalValidator(ledger);
        this.clock = clock;
    }

    /**
     * save_proposal 이 Agent 에게 돌려주는 결과다. JSON 으로는 {proposal_id, status, valid_candidate_nos} 이고,
     * skillops-agent/skillops_agent/nodes.py 의 save_proposal 노드가 proposal_id 와 valid_candidate_nos 를 읽는다.
     */
    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record SavedProposal(String proposalId, String status, List<Integer> validCandidateNos) {
    }

    /**
     * save_proposal 로 받은 제안서를 검사하고 저장한다. 먼저 빈 제안서를 거부하고, thread_id 로 이미 저장한 제안서가 있으면
     * 아무 검사 없이 그 제안서의 결과를 돌려준다. 이 재저장 규칙은 설계 결정
     * D9(docs/superpowers/plans/2026-10-21-proposal-save-and-approval.md)이고, Agent 가 재시도해도 제안서가 둘이 되지 않게 한다.
     * 처음 보는 진단이면 ProposalValidator 의 validCandidateNos 가 거부 규칙을 순서대로 검사하며 합격 후보 번호를 다시 계산하고, store 가 저장한다.
     * 잘못된 제안서는 IllegalArgumentException 으로 거부하고 MCP 결과는 INVALID_INPUT 이 된다. 메시지는 Agent 개발자가 읽는다.
     */
    public SavedProposal save(Map<String, Object> proposal) {
        if (proposal == null || proposal.isEmpty()) {
            throw new IllegalArgumentException("proposal 이 비어 있습니다");
        }
        String threadId = str(proposal.get("thread_id"));
        if (threadId != null) {
            Optional<Proposal> existing = repository.findByThreadId(threadId);
            if (existing.isPresent()) {
                // Agent 가 같은 진단으로 다시 저장해도 제안서는 하나만 둔다.
                return savedOf(existing.get());
            }
        }
        String status = str(proposal.get("status"));
        List<Integer> valid = validator.validCandidateNos(status, proposal);
        return store(proposal, threadId, status, valid);
    }

    /**
     * 운영자의 결정을 제안서에 표시하고 저장한다. 같은 제안서는 한 번만 결정할 수 있다.
     * 승인은 합격한 후보 번호(1부터)로만 받고, 쓸 수 있는 수정안이 없는 제안서는 거절(닫기)만 받는다.
     * 결정 값 확인, 제안서 찾기, checkDecidable 순서로 보며, 결정 값이 틀리면 IllegalArgumentException, 그 밖의 거부는 ProposalException 이다.
     * ApprovalService 의 recordDecision 이 트랜잭션 안에서 부르므로 이 저장은 승인 기록과 함께 커밋된다. 결과는 저장소가 돌려준 엔티티다.
     */
    public Proposal markDecided(String proposalId, String decision, Integer candidateNo) {
        requireKnownDecision(decision);
        Proposal proposal = repository.findById(proposalId).orElseThrow(ProposalException::notFound);
        checkDecidable(proposal, decision, candidateNo);
        proposal.decide(decision, clock.instant());
        return repository.save(proposal);
    }

    /** 제안서 번호로 저장된 제안서를 찾는다. ProposalController 의 detail 이 상세 응답을 만들 때 부른다. */
    public Optional<Proposal> find(String proposalId) {
        return repository.findById(proposalId);
    }

    /** 만든 시각이 늦은 것부터 제안서 100개를 돌려준다. ProposalController 의 list 가 목록 응답을 만들 때 부른다. */
    public List<Proposal> recent() {
        return repository.findTop100ByOrderByCreatedAtDesc();
    }

    /**
     * 검사를 통과한 제안서에 새 번호와 저장 시각을 붙이고, 본문을 만들어 함께 저장한다.
     * 돌려주는 결과는 저장소가 돌려준 엔티티가 아니라 여기서 정한 값으로 만든다.
     */
    private SavedProposal store(Map<String, Object> proposal, String threadId, String status, List<Integer> valid) {
        String proposalId = Proposal.newId();
        Instant now = clock.instant();
        Map<String, Object> document = documentOf(proposalId, proposal, now);
        repository.save(Proposal.create(proposalId, threadId, status, proposal, document, valid, now));
        return new SavedProposal(proposalId, status, valid);
    }

    /**
     * ProposalDocumentWriter 가 만든 본문을 snake_case 키의 Map 으로 바꾼다.
     * Hibernate 의 JSON 칸은 Jackson 2 로 값을 쓰므로 Jackson 3 의 @JsonNaming 규칙을 모른다. 그래서 레코드를 그대로 넣지 않고
     * 여기서 Jackson 3 로 Map 을 만들어 넣는다(docs/superpowers/plans/2026-10-21-proposal-save-and-approval.md 의 Global Constraints).
     */
    @SuppressWarnings("unchecked")
    private static Map<String, Object> documentOf(String proposalId, Map<String, Object> proposal, Instant createdAt) {
        return JSON.convertValue(ProposalDocumentWriter.write(proposalId, proposal, createdAt), Map.class);
    }

    /**
     * decision 이 approved 나 rejected 인지 확인한다. 잘못된 값이면 제안서를 찾기 전에 IllegalArgumentException 으로 거부한다.
     * 결정 값은 ApprovalService 가 상수로 넘기므로 이 검사는 호출하는 코드의 실수를 잡는다.
     */
    private static void requireKnownDecision(String decision) {
        if (!ProposalStatus.isDecided(decision)) {
            throw new IllegalArgumentException("decision 은 approved 나 rejected 여야 합니다 (받은 값 " + decision + ")");
        }
    }

    /**
     * 결정을 받을 수 있는지 확인한다. 이미 결정한 제안서인지, 수정안이 없는 제안서를 승인하려는지, 합격하지 않은 후보를 승인하려는지를
     * 이 순서로 보고, 걸리면 해당 ProposalException 을 던진다. 거절은 후보 번호를 보지 않는다.
     * 결정 규칙을 더하거나 순서를 바꾸려면 이 메서드를 고친다.
     */
    private static void checkDecidable(Proposal proposal, String decision, Integer candidateNo) {
        String status = proposal.getStatus();
        if (ProposalStatus.isDecided(status)) {
            throw ProposalException.alreadyDecided();
        }
        if (ProposalStatus.APPROVED.equals(decision)) {
            if (ProposalStatus.NO_VALID_FIX.equals(status)) {
                throw ProposalException.noValidFix();
            }
            if (candidateNo == null || !proposal.getValidCandidateNos().contains(candidateNo)) {
                throw ProposalException.candidateNotValid();
            }
        }
    }

    /** 이미 저장한 제안서로 save_proposal 결과를 만든다. 같은 진단을 다시 저장할 때 쓴다. */
    private static SavedProposal savedOf(Proposal proposal) {
        return new SavedProposal(proposal.getProposalId(), proposal.getStatus(), proposal.getValidCandidateNos());
    }
}
