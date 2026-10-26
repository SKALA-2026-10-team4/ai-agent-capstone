package com.capstone4.skillops.evaluation;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.*;
import org.springframework.data.repository.query.Param;
import static com.capstone4.skillops.evaluation.EvaluationTypes.*;

/** 평가셋의 중복 등록을 내용 해시로 찾는다. */
interface EvaluationSetRepository extends JpaRepository<EvaluationSetRecord,String> {
    /** 같은 내용의 불변 평가셋을 찾는다. */
    Optional<EvaluationSetRecord> findByHash(String hash);
}
/** job 접수와 동일 작업의 재실행을 직렬화한다. */
interface EvaluationJobRepository extends JpaRepository<EvaluationJob,String> {
    /** 권한 범위가 포함된 멱등 키로 기존 작업을 찾는다. */
    Optional<EvaluationJob> findByRequestKey(String key);
    /** 재실행 시 job을 잠가 활성 attempt 중복을 막는다. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select j from EvaluationJob j where j.id=:id")
    Optional<EvaluationJob> locked(@Param("id") String id);
}
/** 작업 상태·lease를 검색한다. */
interface EvaluationAttemptRepository extends JpaRepository<EvaluationAttempt,String> {
    /** 대기 작업 하나를 DB 잠금으로 선점한다. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select a from EvaluationAttempt a where a.status=:status order by a.createdAt, a.id")
    List<EvaluationAttempt> queued(@Param("status") Status status, Pageable page);
    /** 상태 변경·결과 저장 시 동일 attempt를 잠근다. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select a from EvaluationAttempt a where a.id=:id")
    Optional<EvaluationAttempt> locked(@Param("id") String id);
    /** 가장 최근의 시도를 찾는다. */
    List<EvaluationAttempt> findByJobIdOrderByCreatedAtDescIdDesc(String jobId);
    /** 목록에서 작업마다 별도 쿼리하지 않고 시도를 한 번에 읽는다. */
    List<EvaluationAttempt> findByJobIdInOrderByCreatedAtDescIdDesc(List<String> jobIds);
    /** 멱등 재실행 요청을 찾는다. */
    Optional<EvaluationAttempt> findByRetryKey(String key);
    /** 소실된 작업자의 실행을 찾는다. */
    List<EvaluationAttempt> findByStatusInAndLeaseUntilBefore(List<Status> statuses, Instant before);
}
/** trial 원문과 판정은 같은 행으로 저장한다. */
interface EvaluationTrialRepository extends JpaRepository<EvaluationTrialRecord,String> {
    /** 원문 없는 완료 수 projection이다. */
    interface Count {
        /** 집계 대상 시도 ID다. */
        String getAttemptId();
        /** 실제 업무 결과가 확인된 trial 수다. */
        long getTotal();
    }
    /** 목록의 완료 수를 한 쿼리로 집계한다. */
    @Query(value="select attempt_id as \"attemptId\", count(*) filter (where cast(payload as jsonb)->>'task' in ('PASS','FAIL')) as total "
            + "from evaluation_trials where attempt_id in (:ids) group by attempt_id",nativeQuery=true)
    List<Count> counts(@Param("ids") List<String> ids);
    /** 시도 안의 저장된 trial을 결정적 ID 순서로 읽는다. */
    List<EvaluationTrialRecord> findByAttemptIdOrderById(String id);
    /** 상태 조회에서 trace 본문을 읽지 않고 완료 수만 센다. */
    @Query(value="select count(*) from evaluation_trials where attempt_id=:id and cast(payload as jsonb)->>'task' in ('PASS','FAIL')",nativeQuery=true)
    long countByAttemptIdAndCompletedTrue(@Param("id") String id);
}
/** 확정 보고서 원문을 시도별로 조회한다. */
interface EvaluationReportRepository extends JpaRepository<EvaluationReportRecord,String> {}
