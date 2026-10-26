package com.capstone4.skillops.proposal;

import java.time.Instant;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.PropertyNamingStrategies;
import tools.jackson.databind.annotation.JsonNaming;
import tools.jackson.databind.json.JsonMapper;
import com.capstone4.skillops.evaluation.*;
import static com.capstone4.skillops.evaluation.EvaluationTypes.*;

/** 기존 제안 저장과 분리해 서버가 검증한 snapshot만 승인 대상으로 저장한다. */
@Service
public class VerifiedProposalService {
    private final EvaluationService evaluations;
    private final ProposalRepository proposals;
    private final VerifiedProposalReceiptRepository receipts;
    /** 평가·제안·멱등 저장소를 받는다. */
    public VerifiedProposalService(EvaluationService evaluations,ProposalRepository proposals,VerifiedProposalReceiptRepository receipts) {
        this.evaluations=evaluations; this.proposals=proposals; this.receipts=receipts;
    }
    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record Input(String evaluationId,String attemptId,String summary,String intentText) {}
    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record Saved(String proposalId,String status,List<Integer> validCandidateNos,String verificationStatus) {}
    /** 서버 저장 결과에서 후보 한 개를 구성하고 기존 사람 승인 API로 연결한다. */
    @Transactional
    @SuppressWarnings("unchecked")
    public Saved save(Input input) {
        EvaluationException.require(input!=null && input.attemptId()!=null && input.evaluationId()!=null,"평가·시도 ID가 필요합니다");
        EvaluationException.require(input.summary()!=null && !input.summary().isBlank() && input.summary().length()<=2000
                && input.intentText()!=null && !input.intentText().isBlank() && input.intentText().length()<=2000,"설명과 의도는 1~2000자입니다");
        String hash=EvaluationJson.hash(input);
        Optional<VerifiedProposalReceipt> existing=receipts.findById(input.attemptId());
        if (existing.isPresent()) {
            if (!existing.get().requestHash.equals(hash)) { throw new EvaluationException(409,"IDEMPOTENCY_CONFLICT","같은 검증 시도의 제안 내용이 다릅니다"); }
            Proposal stored=proposals.findById(existing.get().proposalId).orElseThrow();
            return new Saved(existing.get().proposalId,stored.getStatus(),List.of(1),"SERVER_VERIFIED");
        }
        EvaluationService.Verification verified=evaluations.verification(input.evaluationId(),input.attemptId());
        Plan plan=verified.plan();
        List<Trial> candidate=verified.trials().stream().filter(row -> row.arm()==Arm.CANDIDATE).toList();
        long regressionCount=candidate.stream().filter(row -> row.suite()==Suite.REGRESSION).map(Trial::caseId).distinct().count();
        List<String> intentChecks=plan.evaluationSet().cases().stream().filter(row -> row.intentChecks()!=null).flatMap(row -> row.intentChecks().stream()).distinct().toList();
        EvaluationException.require(!intentChecks.isEmpty(),"서버 검증 제안에는 실제 의도 검사 조건이 필요합니다");
        Map<String,Object> results=Map.of("fixes_failure",true,"keeps_old_orders",Map.of("passed",regressionCount,"total",regressionCount),
                "keeps_intent",true,"no_broken_links",true,"metadata_valid",true,"flow_valid",true,"changed_skill_count",changedSkills(plan));
        Map<String,Object> snapshotCandidate=new LinkedHashMap<>();
        snapshotCandidate.put("candidate_no",1); snapshotCandidate.put("kind","snapshot"); snapshotCandidate.put("patches",List.of());
        snapshotCandidate.put("snapshot_id",plan.candidate().snapshotId()); snapshotCandidate.put("snapshot_hash",plan.candidate().hash());
        snapshotCandidate.put("verification_attempt_id",input.attemptId()); snapshotCandidate.put("results",results); snapshotCandidate.put("valid",true);
        snapshotCandidate.put("evidence_run_ids",candidate.stream().map(Trial::runId).toList()); snapshotCandidate.put("explanation",input.summary());
        Map<String,Object> payload=new LinkedHashMap<>();
        payload.put("schema_version",2); payload.put("status","proposed"); payload.put("verification_status","SERVER_VERIFIED");
        payload.put("evaluation_id",input.evaluationId()); payload.put("verification_attempt_id",input.attemptId());
        payload.put("baseline_snapshot_id",plan.baseline().snapshotId()); payload.put("snapshot_id",plan.candidate().snapshotId());
        payload.put("intent",Map.of("text",input.intentText(),"checks",intentChecks)); payload.put("candidates",List.of(snapshotCandidate));
        payload.put("recommended_candidate_no",1); payload.put("analysis_status","NOT_PERFORMED");
        Instant now=Instant.now();
        String id=Proposal.newId();
        Map<String,Object> document=JsonMapper.builder().build().convertValue(VerifiedProposalDocument.write(input,verified,intentChecks),Map.class);
        proposals.saveAndFlush(Proposal.create(id,null,"proposed",payload,document,List.of(1),now));
        VerifiedProposalReceipt receipt=new VerifiedProposalReceipt();
        receipt.attemptId=input.attemptId(); receipt.requestHash=hash; receipt.proposalId=id;
        receipts.saveAndFlush(receipt);
        return new Saved(id,"proposed",List.of(1),"SERVER_VERIFIED");
    }
    /** snapshot 원문 차이로 변경 Skill 수를 계산한다. */
    private static int changedSkills(Plan plan) {
        Set<String> names=new HashSet<>(plan.baseline().skills().keySet()); names.addAll(plan.candidate().skills().keySet());
        return (int)names.stream().filter(name -> !Objects.equals(plan.baseline().skills().get(name),plan.candidate().skills().get(name))).count();
    }
}
