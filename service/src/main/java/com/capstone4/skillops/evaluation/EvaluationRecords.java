package com.capstone4.skillops.evaluation;

import java.time.Instant;
import jakarta.persistence.*;
import static com.capstone4.skillops.evaluation.EvaluationTypes.*;

/** 불변 평가셋 원문과 권한으로 확인된 검토 이력을 저장한다. */
@Entity
@Table(name="evaluation_sets")
class EvaluationSetRecord {
    @Id String id;
    @Column(nullable=false, unique=true, length=64) String hash;
    @Column(nullable=false, columnDefinition="text") String payload;
    boolean reviewed;
    String reviewedBy;
    @Version Long version;
    /** JPA 복원을 위한 생성자다. */
    protected EvaluationSetRecord() {}
}

/** 고정 입력과 접수 멱등 키를 보관하며 재실행에도 입력을 변경하지 않는다. */
@Entity
@Table(name="evaluation_jobs")
class EvaluationJob {
    @Id String id;
    @Column(nullable=false, unique=true, length=64) String requestKey;
    @Column(nullable=false, length=64) String requestHash;
    @Column(nullable=false, columnDefinition="text") String plan;
    @Column(nullable=false) Instant createdAt;
    @Version Long version;
    /** JPA 복원을 위한 생성자다. */
    protected EvaluationJob() {}
}

/** 시도별 상태·lease·종료 사유를 보존한다. 재시작 시 외부 실행을 중복 전송하지 않는다. */
@Entity
@Table(name="evaluation_attempts", indexes=@Index(name="idx_eval_attempt_queue",columnList="status,createdAt"))
class EvaluationAttempt {
    @Id String id;
    @Column(nullable=false) String jobId;
    @Column(unique=true) String retryKey;
    String parentId;
    @Enumerated(EnumType.STRING) @Column(nullable=false) Status status;
    @Column(columnDefinition="text") String reason;
    String token;
    Instant leaseUntil;
    Instant startedAt;
    @Column(nullable=false) Instant createdAt;
    @Version Long version;
    /** JPA 복원을 위한 생성자다. */
    protected EvaluationAttempt() {}
}

/** 실제 실행·trace·서버 판정을 trial마다 즉시 저장한다. */
@Entity
@Table(name="evaluation_trials", indexes=@Index(name="idx_eval_trial_attempt",columnList="attemptId"))
class EvaluationTrialRecord {
    @Id String id;
    @Column(nullable=false) String attemptId;
    @Column(nullable=false, columnDefinition="text") String payload;
    @Column(unique=true) String runId;
    /** JPA 복원을 위한 생성자다. */
    protected EvaluationTrialRecord() {}
}

/** 종료 시점의 비교 보고서를 정책 버전과 함께 보존한다. */
@Entity
@Table(name="evaluation_reports")
class EvaluationReportRecord {
    @Id String attemptId;
    @Column(nullable=false, columnDefinition="text") String payload;
    /** JPA 복원용 생성자다. */
    protected EvaluationReportRecord() {}
}
