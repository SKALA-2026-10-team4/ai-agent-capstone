package com.capstone4.skillops.scenario;

import java.time.Instant;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;

/**
 * {@link RunLedger} 의 한 줄이며 scenario_runs 표에 저장된다. 모의 실행 한 번이 한 줄이고 run_id 가 기본 키다.
 * 연습 공간별로 찾는 일이 있어 workspace_id 에 색인을 둔다. 표는 service/src/main/resources/application.yml 의
 * JPA ddl-auto(update) 설정에 따라 이 클래스 모양대로 만들어진다.
 * service/src/main/java/com/capstone4/skillops/scenario/JpaRunLedger.java 만 이 클래스를 만들고 읽는다.
 * scenario_id와 passed는 제안 저장 시 실제 실행 결과를 대조하는 데 사용한다.
 */
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

    /** 그 실행 한 번의 채점 결과다. */
    @Column(nullable = false)
    private boolean passed;

    /** 장부에 남긴 시각(UTC)이다. */
    @Column(name = "recorded_at", nullable = false)
    private Instant recordedAt;

    /** JPA 가 줄을 읽어 객체를 만들 때 쓴다. */
    protected ScenarioRunRecord() {
    }

    /** JpaRunLedger 의 record 가 실행 한 번을 남길 때 쓴다. */
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

    /** 실제 실행한 시나리오 번호를 반환한다. */
    public String getScenarioId() {
        return scenarioId;
    }

    /** 서버 채점기가 기록한 통과 여부를 반환한다. */
    public boolean isPassed() {
        return passed;
    }
}
