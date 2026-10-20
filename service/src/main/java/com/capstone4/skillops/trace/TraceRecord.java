package com.capstone4.skillops.trace;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * trace 한 건을 담는 행. steps 와 skill_versions 는 실행마다 모양이 달라 JSON 컬럼에 통째로 둔다.
 * 밖으로 내보낼 때는 항상 {@link #toTrace()} 로 계약 2 모양으로 바꾼다.
 */
@Entity
@Table(name = "trace_run")
public class TraceRecord {

    @Id
    @Column(name = "run_id")
    private String runId;

    @Column(name = "order_id")
    private String orderId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private RunMode mode;

    private String workspace;

    @Column(name = "scenario_id")
    private String scenarioId;

    @Column(name = "started_at", nullable = false)
    private Instant startedAt;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "skill_versions")
    private Map<String, Integer> skillVersions;

    @JdbcTypeCode(SqlTypes.JSON)
    private List<TraceStep> steps;

    @Enumerated(EnumType.STRING)
    @Column(name = "final_status", nullable = false)
    private FinalStatus finalStatus;

    protected TraceRecord() {
    }

    public static TraceRecord from(Trace trace) {
        TraceRecord r = new TraceRecord();
        r.runId = trace.runId();
        r.orderId = trace.orderId();
        r.mode = trace.mode();
        r.workspace = trace.workspace();
        r.scenarioId = trace.scenarioId();
        r.startedAt = trace.startedAt();
        r.skillVersions = trace.skillVersions();
        r.steps = trace.steps();
        r.finalStatus = trace.finalStatus();
        return r;
    }

    public Trace toTrace() {
        return new Trace(runId, orderId, mode, workspace, scenarioId, startedAt,
                skillVersions, steps, finalStatus);
    }
}
