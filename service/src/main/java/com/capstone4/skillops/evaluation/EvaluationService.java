package com.capstone4.skillops.evaluation;

import java.util.*;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import static com.capstone4.skillops.evaluation.EvaluationTypes.*;

/** REST와 MCP가 공유하는 평가 접수·조회 유스케이스다. */
@Service
public class EvaluationService {
    private final EvaluationStore store;
    private final EvaluationRunner runner;
    private final EvaluationConfiguration.Settings settings;
    /** 공통 실행·저장 경계를 주입한다. */
    public EvaluationService(EvaluationStore store,EvaluationRunner runner,EvaluationConfiguration.Settings settings) {
        this.store=store; this.runner=runner; this.settings=settings;
    }
    /** 같은 내용은 재등록하지 않고 기존 평가셋을 반환한다. */
    @Transactional
    public SetView register(SetInput input) {
        EvaluationPolicy.validate(input);
        String hash=EvaluationJson.hash(input);
        EvaluationSetRecord row=store.sets().findByHash(hash).orElseGet(() -> {
            EvaluationSetRecord created=new EvaluationSetRecord();
            created.id=UUID.randomUUID().toString(); created.hash=hash; created.payload=EvaluationJson.write(input);
            return store.sets().saveAndFlush(created);
        });
        return setView(row);
    }
    /** 라벨 검토 이력은 인증된 검토자 이름으로만 기록한다. */
    @Transactional
    public SetView review(String setId,String reviewer) {
        EvaluationSetRecord row=store.sets().findById(setId).orElseThrow(() -> new EvaluationException(404,"EVALUATION_SET_NOT_FOUND","평가셋이 없습니다"));
        EvaluationException.require(reviewer!=null && !reviewer.isBlank(),"검토자가 필요합니다");
        row.reviewed=true; row.reviewedBy=reviewer;
        return setView(row);
    }
    /** 검토자가 실제 등록된 라벨과 해시를 확인할 수 있게 한다. */
    public SetDetail definition(String setId) {
        EvaluationSetRecord row=store.sets().findById(setId).orElseThrow(() -> new EvaluationException(404,"EVALUATION_SET_NOT_FOUND","평가셋이 없습니다"));
        return new SetDetail(setView(row),EvaluationJson.read(row.payload,SetInput.class));
    }
    /** 원문·lease 토큰을 제외한 모든 시도 이력을 제공한다. */
    public List<AttemptView> history(String jobId) {
        store.job(jobId);
        return store.attempts().findByJobIdOrderByCreatedAtDescIdDesc(jobId).stream()
                .map(row -> new AttemptView(row.id,row.parentId,row.status,row.reason,row.createdAt)).toList();
    }
    /** 원문·라벨을 고정한 뒤 네트워크 실행 없이 작업을 접수한다. */
    public Accepted submit(Submit request,String idempotencyKey,String scope) {
        String key=key(scope,idempotencyKey);
        String requestHash=EvaluationJson.hash(request);
        Optional<EvaluationJob> existing=store.existing(key,requestHash);
        if (existing.isPresent()) { return store.accepted(existing.get()); }
        EvaluationException.require(request!=null,"평가 요청이 필요합니다");
        EvaluationSetRecord set=store.sets().findById(request.evaluationSetId()).orElseThrow(() -> new EvaluationException(404,"EVALUATION_SET_NOT_FOUND","평가셋이 없습니다"));
        SetInput input=EvaluationJson.read(set.payload,SetInput.class);
        EvaluationPolicy.validate(request,input,settings);
        Plan plan=new Plan(request,input,set.hash,set.reviewed,runner.freeze(request.baselineSnapshotId()),
                runner.freeze(request.candidateSnapshotId()),EvaluationPolicy.VERSION,EvaluationPolicy.GRADER);
        try { return store.accept(key,requestHash,plan); }
        catch (DataIntegrityViolationException race) {
            return store.existing(key,requestHash).map(store::accepted).orElseThrow(() -> race);
        }
    }
    /** 작업 상세는 trace·원문 없이 진행 정보만 반환한다. */
    public View get(String jobId) {
        EvaluationJob job=store.job(jobId);
        EvaluationAttempt latest=store.attempts().findByJobIdOrderByCreatedAtDescIdDesc(jobId).getFirst();
        return view(job,latest,store.trials().countByAttemptIdAndCompletedTrue(latest.id));
    }
    /** 요약 목록을 서버 상한 내에서 안정적인 순서로 조회한다. */
    public Page<View> list(int page,int limit) {
        EvaluationException.require(page>=0 && limit>=1 && limit<=50,"page는 0 이상, limit는 1~50입니다");
        var jobs=store.jobs().findAll(PageRequest.of(page,limit,Sort.by(Sort.Order.desc("createdAt"),Sort.Order.desc("id"))));
        if (jobs.isEmpty()) { return new Page<>(List.of(),null); }
        Map<String,EvaluationAttempt> latest=new HashMap<>();
        store.attempts().findByJobIdInOrderByCreatedAtDescIdDesc(jobs.getContent().stream().map(row -> row.id).toList())
                .forEach(row -> latest.putIfAbsent(row.jobId,row));
        Map<String,Long> counts=new HashMap<>();
        store.trials().counts(latest.values().stream().map(row -> row.id).toList()).forEach(row -> counts.put(row.getAttemptId(),row.getTotal()));
        return new Page<>(jobs.getContent().stream().map(row -> view(row,latest.get(row.id),counts.getOrDefault(latest.get(row.id).id,0L))).toList(),jobs.hasNext()?page+1:null);
    }
    /** 사례 결과는 최대 50개씩 읽어 반환한다. */
    public CasePage<Trial> cases(String jobId,String attemptId,int offset,int limit) {
        store.attempt(jobId,attemptId);
        EvaluationException.require(offset>=0 && limit>=1 && limit<=50,"offset은 0 이상, limit는 1~50입니다");
        List<Trial> rows=store.trialPage(attemptId,offset,limit+1);
        boolean more=rows.size()>limit;
        return new CasePage<>(more?rows.subList(0,limit):rows,more?offset+limit:null);
    }
    /** 저장된 입력과 실제 trial만으로 보고서를 계산한다. */
    public Report report(String jobId,String attemptId) {
        EvaluationAttempt attempt=store.attempt(jobId,attemptId);
        Optional<EvaluationReportRecord> saved=store.reports().findById(attemptId);
        if (saved.isPresent()) { return EvaluationJson.read(saved.get().payload,Report.class); }
        return EvaluationReports.build(jobId,attemptId,attempt.status,store.plan(store.job(jobId)),rows(attemptId));
    }
    /** 검증된 후보 snapshot을 새 제안서 저장 경계에 제공한다. */
    public Verification verification(String jobId,String attemptId) {
        Report report=report(jobId,attemptId);
        if (!"SERVER_VERIFIED".equals(report.verificationStatus())) {
            throw new EvaluationException(409,"EVIDENCE_MISMATCH","서버 검증이 완료된 실제 후보 평가가 아닙니다");
        }
        return new Verification(store.plan(store.job(jobId)),report,rows(attemptId));
    }
    /** 승인 근거의 불변 입력·판정·실행을 함께 전달한다. */
    public record Verification(Plan plan,Report report,List<Trial> trials) {}
    /** 작업 취소를 접수한다. */
    public Accepted cancel(String jobId,String attemptId) { return store.cancel(jobId,attemptId); }
    /** 동일 계획을 새 시도로 재실행한다. */
    public Accepted retry(String jobId,String attemptId,String key,String scope) { return store.retry(jobId,attemptId,key(scope,key)); }
    /** 원문 JSON을 저장 계약 타입으로 복원한다. */
    private List<Trial> rows(String attemptId) {
        return store.trials().findByAttemptIdOrderById(attemptId).stream().map(row -> EvaluationJson.read(row.payload,Trial.class)).toList();
    }
    /** 조회한 행만 사용해 목록에서 추가 쿼리를 발생시키지 않는다. */
    private View view(EvaluationJob job,EvaluationAttempt latest,long completed) {
        Plan plan=store.plan(job);
        return new View(job.id,latest.id,latest.status,latest.reason,plan.evaluationSet().cases().size()*plan.request().repeat()*2,
                completed,plan.baseline().snapshotId(),plan.candidate().snapshotId(),plan.setHash(),
                plan.request().runnerKind(),plan.request().modelMode(),job.createdAt);
    }
    /** 외부 평가셋 버전과 JPA 잠금 버전을 구분한다. */
    private static SetView setView(EvaluationSetRecord row) { return new SetView(row.id,1,row.hash,row.reviewed,row.reviewedBy); }
    /** 권한 범위를 포함해 사용자 간 멱등 키 충돌을 막는다. */
    private static String key(String scope,String key) {
        EvaluationException.require(scope!=null && key!=null && !key.isBlank() && key.length()<=128,"Idempotency-Key가 필요합니다(최대 128자)");
        return EvaluationJson.hash(List.of(scope,key));
    }
}
