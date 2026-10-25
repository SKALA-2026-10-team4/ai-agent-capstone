package com.capstone4.skillops.evaluation;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import static com.capstone4.skillops.evaluation.EvaluationTypes.*;

/** 네트워크 호출 밖의 짧은 트랜잭션으로 평가 상태와 증거를 저장한다. */
@Service
public class EvaluationStore {
    @PersistenceContext private EntityManager entityManager;
    private final EvaluationSetRepository sets;
    private final EvaluationJobRepository jobs;
    private final EvaluationAttemptRepository attempts;
    private final EvaluationTrialRepository trials;
    private final EvaluationReportRepository reports;
    /** 저장소 의존성을 주입한다. */
    public EvaluationStore(EvaluationSetRepository sets, EvaluationJobRepository jobs,
            EvaluationAttemptRepository attempts, EvaluationTrialRepository trials, EvaluationReportRepository reports) {
        this.sets=sets; this.jobs=jobs; this.attempts=attempts; this.trials=trials; this.reports=reports;
    }
    /** 트랜잭션 프록시를 거쳐 평가셋 저장소에 접근한다. */
    EvaluationSetRepository sets() { return sets; }
    /** 작업 저장소를 제공한다. */
    EvaluationJobRepository jobs() { return jobs; }
    /** 시도 저장소를 제공한다. */
    EvaluationAttemptRepository attempts() { return attempts; }
    /** 실행 결과 저장소를 제공한다. */
    EvaluationTrialRepository trials() { return trials; }
    /** 확정 보고서 저장소를 제공한다. */
    EvaluationReportRepository reports() { return reports; }
    /** 사례 상세는 DB에서 요청한 범위만 읽는다. */
    @Transactional(readOnly=true)
    public List<Trial> trialPage(String attemptId,int offset,int limit) {
        return entityManager.createQuery("select t.payload from EvaluationTrialRecord t where t.attemptId=:id order by t.id",String.class)
                .setParameter("id",attemptId).setFirstResult(offset).setMaxResults(limit).getResultList().stream()
                .map(json -> EvaluationJson.read(json,Trial.class)).toList();
    }
    /** 실행권을 나타내는 토큰과 고정 계획이다. */
    public record Claim(String attemptId, String token, Plan plan) {}
    /** 작업이 없으면 같은 오류 코드로 응답한다. */
    EvaluationJob job(String id) { return jobs.findById(id).orElseThrow(() -> missing("작업")); }
    /** 다른 평가의 attempt를 교차 참조하지 못하게 한다. */
    EvaluationAttempt attempt(String jobId, String attemptId) {
        EvaluationAttempt attempt=attempts.findById(attemptId).orElseThrow(() -> missing("시도"));
        if (!attempt.jobId.equals(jobId)) { throw missing("시도"); }
        return attempt;
    }
    /** 저장된 계획을 복원한다. */
    Plan plan(EvaluationJob job) { return EvaluationJson.read(job.plan, Plan.class); }
    /** 권한 범위가 같은 중복 접수만 재사용한다. */
    Optional<EvaluationJob> existing(String key, String hash) {
        Optional<EvaluationJob> existing=jobs.findByRequestKey(key);
        existing.ifPresent(job -> {
            if (!job.requestHash.equals(hash)) { throw new EvaluationException(409,"IDEMPOTENCY_CONFLICT","같은 키의 입력이 다릅니다"); }
        });
        return existing;
    }
    /** 작업과 첫 시도를 원자적으로 접수한다. */
    @Transactional
    public Accepted accept(String key, String hash, Plan plan) {
        EvaluationJob job=new EvaluationJob();
        job.id=UUID.randomUUID().toString(); job.requestKey=key; job.requestHash=hash;
        job.plan=EvaluationJson.write(plan); job.createdAt=Instant.now();
        jobs.saveAndFlush(job);
        return accepted(newAttempt(job.id,null,null));
    }
    /** 동일한 불변 입력을 새 시도로 실행하되 이전 시도를 덮어쓰지 않는다. */
    @Transactional
    public Accepted retry(String jobId, String parentId, String key) {
        jobs.locked(jobId).orElseThrow(() -> missing("작업"));
        Optional<EvaluationAttempt> duplicate=attempts.findByRetryKey(key);
        if (duplicate.isPresent()) {
            EvaluationAttempt found=duplicate.get();
            if (!found.jobId.equals(jobId) || !Objects.equals(found.parentId,parentId)) {
                throw new EvaluationException(409,"IDEMPOTENCY_CONFLICT","재실행 대상이 다릅니다");
            }
            return accepted(found);
        }
        EvaluationAttempt parent=attempt(jobId,parentId);
        List<EvaluationAttempt> history=attempts.findByJobIdOrderByCreatedAtDescIdDesc(jobId);
        if (history.size()>=3 || !history.getFirst().id.equals(parentId)
                || !List.of(Status.FAILED,Status.INTERRUPTED,Status.CANCELLED,Status.STOPPED).contains(parent.status)) {
            throw new EvaluationException(409,"INVALID_EVALUATION_STATE","최근 중단·실패 시도만 최대 3회까지 재실행할 수 있습니다");
        }
        if (parent.status==Status.INTERRUPTED) {
            throw new EvaluationException(409,"INVALID_EVALUATION_STATE","외부 실행의 확인되지 않은 효과를 먼저 대조해야 합니다");
        }
        return accepted(newAttempt(jobId,parentId,key));
    }
    /** 실행 전·도중 취소 요청을 저장한다. */
    @Transactional
    public Accepted cancel(String jobId, String attemptId) {
        EvaluationAttempt current=attempts.locked(attemptId).orElseThrow(() -> missing("시도"));
        if (!current.jobId.equals(jobId)) { throw missing("시도"); }
        if (current.status==Status.QUEUED) { current.status=Status.CANCELLED; }
        else if (current.status==Status.RUNNING) { current.status=Status.CANCEL_REQUESTED; }
        return accepted(current);
    }
    /** lease가 만료된 외부 실행은 재전송하지 않고 중단으로 남긴다. */
    @Transactional
    public void recover() {
        for (EvaluationAttempt stale:attempts.findByStatusInAndLeaseUntilBefore(
                List.of(Status.RUNNING,Status.CANCEL_REQUESTED),Instant.now())) {
            EvaluationAttempt current=attempts.locked(stale.id).orElseThrow();
            if (current.leaseUntil!=null && current.leaseUntil.isBefore(Instant.now())
                    && List.of(Status.RUNNING,Status.CANCEL_REQUESTED).contains(current.status)) {
                current.status=Status.INTERRUPTED; current.reason="WORKER_LOST: 외부 실행을 자동 재전송하지 않습니다";
                saveReport(current);
            }
        }
    }
    /** 작업 하나를 선점하고 전체 실행 시간보다 긴 fencing lease를 부여한다. */
    @Transactional
    public Optional<Claim> claim() {
        List<EvaluationAttempt> pending=attempts.queued(Status.QUEUED,PageRequest.of(0,1));
        if (pending.isEmpty()) { return Optional.empty(); }
        EvaluationAttempt current=pending.getFirst();
        if (current.status!=Status.QUEUED) { return Optional.empty(); }
        Plan plan=plan(job(current.jobId));
        current.status=Status.RUNNING; current.startedAt=Instant.now(); current.token=UUID.randomUUID().toString();
        current.leaseUntil=Instant.now().plusMillis(plan.request().limits().wallTimeMs()+180000);
        return Optional.of(new Claim(current.id,current.token,plan));
    }
    /** 취소·lease 소실 후에는 다음 실행을 시작하지 않는다. */
    public boolean running(Claim claim) {
        EvaluationAttempt current=attempts.findById(claim.attemptId()).orElseThrow();
        return Objects.equals(current.token,claim.token()) && current.status==Status.RUNNING
                && current.leaseUntil.isAfter(Instant.now());
    }
    /** 실행 결과를 즉시 저장하며 취소 중 늦은 결과도 증거로 남긴다. */
    @Transactional
    public void record(Claim claim, Trial trial) {
        EvaluationAttempt current=attempts.locked(claim.attemptId()).orElseThrow();
        if (!Objects.equals(current.token,claim.token())) { return; }
        EvaluationException.require(claim.attemptId().equals(trial.attemptId()),"다른 시도의 결과를 저장할 수 없습니다");
        EvaluationTrialRecord row=new EvaluationTrialRecord();
        row.id=trial.trialId(); row.attemptId=trial.attemptId(); row.payload=EvaluationJson.write(trial); row.runId=trial.runId();
        trials.saveAndFlush(row);
    }
    /** 작업 결과를 확정하고 이미 요청된 취소를 우선 반영한다. */
    @Transactional
    public void finish(Claim claim, Status status, String reason) {
        EvaluationAttempt current=attempts.locked(claim.attemptId()).orElseThrow();
        if (!Objects.equals(current.token,claim.token()) || current.status==Status.INTERRUPTED) { return; }
        current.status=current.status==Status.CANCEL_REQUESTED ? Status.CANCELLED : status;
        current.reason=reason; current.token=null;
        saveReport(current);
    }
    /** 중복 접수 응답은 첫 시도를 가리킨다. */
    Accepted accepted(EvaluationJob job) {
        List<EvaluationAttempt> all=attempts.findByJobIdOrderByCreatedAtDescIdDesc(job.id);
        return accepted(all.getLast());
    }
    /** 상태와 접수 결과를 같은 형태로 반환한다. */
    private Accepted accepted(EvaluationAttempt attempt) {
        return new Accepted(attempt.jobId,attempt.id,attempt.status,"/api/evaluations/"+attempt.jobId);
    }
    /** 새 시도를 생성한다. */
    private EvaluationAttempt newAttempt(String jobId,String parentId,String retryKey) {
        EvaluationAttempt current=new EvaluationAttempt();
        current.id=UUID.randomUUID().toString(); current.jobId=jobId; current.parentId=parentId;
        current.retryKey=retryKey; current.status=Status.QUEUED; current.createdAt=Instant.now();
        return attempts.saveAndFlush(current);
    }
    /** 같은 트랜잭션 안에서 종료 상태와 보고서를 함께 기록한다. */
    private void saveReport(EvaluationAttempt current) {
        List<Trial> rows=trials.findByAttemptIdOrderById(current.id).stream().map(row -> EvaluationJson.read(row.payload,Trial.class)).toList();
        EvaluationReportRecord report=new EvaluationReportRecord();
        report.attemptId=current.id;
        report.payload=EvaluationJson.write(EvaluationReports.build(current.jobId,current.id,current.status,plan(job(current.jobId)),rows));
        reports.save(report);
    }
    /** 리소스 존재 여부를 외부에 일관되게 알린다. */
    private static EvaluationException missing(String name) { return new EvaluationException(404,"EVALUATION_NOT_FOUND",name+"를 찾을 수 없습니다"); }
}
