package com.capstone4.skillops.scenario;

import java.time.Instant;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;

/** {@link RunLedger} 의 한 줄. 모의 실행 한 번이 한 줄이다. */
@Entity
@Table(name = "scenario_runs", indexes = @Index(name = "idx_scenario_runs_workspace", columnList = "workspace_id"))
public class ScenarioRunRecord {

    @Id
    @Column(name = "run_id", length = 64)
    private String runId;

    @Column(name = "workspace_id", nullable = false, length = 64)
    private String workspaceId;

    @Column(name = "scenario_id", nullable = false, length = 64)
    private String scenarioId;

    @Column(nullable = false)
    private boolean passed;

    @Column(name = "recorded_at", nullable = false)
    private Instant recordedAt;

    protected ScenarioRunRecord() {
    }

    ScenarioRunRecord(String runId, String workspaceId, String scenarioId, boolean passed, Instant recordedAt) {
        this.runId = runId;
        this.workspaceId = workspaceId;
        this.scenarioId = scenarioId;
        this.passed = passed;
        this.recordedAt = recordedAt;
    }

    public String getRunId() {
        return runId;
    }

    public String getWorkspaceId() {
        return workspaceId;
    }
}
