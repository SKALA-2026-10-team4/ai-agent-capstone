package com.capstone4.skillops.evaluation;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import static com.capstone4.skillops.evaluation.EvaluationTypes.*;

/** DB에 접수된 작업을 한 trial씩 실행하고 즉시 결과를 저장한다. */
@Component
@ConditionalOnProperty(name="skillops.evaluation.worker-enabled",havingValue="true",matchIfMissing=true)
public class EvaluationWorker {
    private static final Logger LOG=LoggerFactory.getLogger(EvaluationWorker.class);
    private final EvaluationStore store;
    private final EvaluationRunner runner;
    /** 작업 저장소와 불변 snapshot 실행기를 받는다. */
    public EvaluationWorker(EvaluationStore store,EvaluationRunner runner) { this.store=store; this.runner=runner; }
    /** 만료 작업을 대조하고 대기 작업 하나를 실행한다. */
    @Scheduled(fixedDelayString="${skillops.evaluation.poll-ms:1000}")
    public void tick() {
        try { store.recover(); store.claim().ifPresent(this::run); }
        catch (RuntimeException error) { LOG.error("평가 작업자 처리 실패",error); }
    }
    /** 기준·후보를 회차별로 실행하며 중단 후 새 외부 요청을 보내지 않는다. */
    public void run(EvaluationStore.Claim claim) {
        Plan plan=claim.plan();
        if (!plan.baseline().errors().isEmpty() || !plan.candidate().errors().isEmpty()) {
            store.finish(claim,Status.BLOCKED,"DOCUMENT_INVALID: "+plan.baseline().errors()+plan.candidate().errors());
            return;
        }
        long start=System.nanoTime();
        for (int round=0;round<plan.request().repeat();round++) {
            for (CaseInput item:plan.evaluationSet().cases()) {
                for (Arm arm:Arm.values()) {
                    if (!store.running(claim)) { store.finish(claim,Status.CANCELLED,"취소 또는 실행권 소실"); return; }
                    if (expired(start,plan)) { store.finish(claim,Status.STOPPED,"BUDGET_EXCEEDED: 실행 시간 한도"); return; }
                    try {
                        Trial trial=runner.execute(claim.attemptId(),plan,item,arm,round);
                        store.record(claim,trial);
                        if ("EVIDENCE_MISMATCH".equals(trial.errorCode()) || "TRACE_MISSING".equals(trial.errorCode())) {
                            store.finish(claim,Status.INTERRUPTED,"상위 실행 설정·snapshot 불일치"); return;
                        }
                    } catch (RuntimeException error) {
                        Trial unknown=new Trial(UUID.randomUUID().toString(),claim.attemptId(),arm,item.caseId(),item.suite(),round,
                                null,null,Verdict.UNMEASURABLE,Verdict.UNMEASURABLE,Verdict.UNMEASURABLE,null,
                                List.of(error.getClass().getSimpleName()+": "+String.valueOf(error.getMessage())),"UPSTREAM_ERROR",null,null,null,null,Map.of());
                        store.record(claim,unknown);
                        store.finish(claim,Status.INTERRUPTED,"실행 또는 응답 확인 실패: 자동 재전송하지 않습니다"); return;
                    }
                    if (expired(start,plan)) { store.finish(claim,Status.STOPPED,"BUDGET_EXCEEDED: 늦은 결과는 보존했습니다"); return; }
                }
            }
        }
        store.finish(claim,Status.SUCCEEDED,null);
    }
    /** wall time은 진행 중 요청의 늦은 응답까지 포함한다. */
    private static boolean expired(long start,Plan plan) { return (System.nanoTime()-start)/1000000>=plan.request().limits().wallTimeMs(); }
}
