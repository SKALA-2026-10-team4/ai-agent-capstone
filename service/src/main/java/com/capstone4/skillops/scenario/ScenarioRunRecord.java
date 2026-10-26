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
 * recorded_at 은 남기기만 하고 읽는 코드가 없다.
 * intent_scoped 와 checks_hash 는 save_proposal 재판정(docs/plans/2026-10-26-behavior-edit-root-fix.md 2.7 절)을 위해, intent_matches 는
 * scoped expectation 마다 맞은 실행이 있는지 보려고(같은 문서 2.10 절 V3-4) 나중에 더한 칸이다.
 * ddl-auto: update 는 이미 있는 표에 NOT NULL 칸을 더하지 못하므로 셋 다 null 을 받는 래퍼 타입이고, 옛 줄은 null 이다.
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

    /** 의도의 scope 에 맞는 expectation 이 붙은 실행이면 true 다. 칸이 생기기 전 줄은 null 이다. */
    @Column(name = "intent_scoped")
    private Boolean intentScoped;

    /** 채점에 덧붙인 확인 조건의 지문이다(IntentPlan.checksHash). 덧붙인 조건이 없거나 칸이 생기기 전 줄이면 null 이다. */
    @Column(name = "checks_hash", length = 80)
    private String checksHash;

    /**
     * 채점에 붙은 expectation 번호(IntentPlan.canonicalIndices 의 자리)를 쉼표로 이은 글이다. 의도로 돌렸는데 붙은 것이 없으면 빈 글,
     * 의도 없이 돌렸거나 칸이 생기기 전 줄이면 null 이다. expectation 은 10개까지라 글은 길어야 19자다.
     */
    @Column(name = "intent_matches", length = 64)
    private String intentMatches;

    /** 장부에 남긴 시각(UTC)이다. */
    @Column(name = "recorded_at", nullable = false)
    private Instant recordedAt;

    /** JPA 가 줄을 읽어 객체를 만들 때 쓴다. */
    protected ScenarioRunRecord() {
    }

    /** JpaRunLedger 의 record 가 실행 한 번을 남길 때 쓴다. */
    ScenarioRunRecord(String runId, String workspaceId, String scenarioId, boolean passed, Boolean intentScoped,
            String checksHash, String intentMatches, Instant recordedAt) {
        this.runId = runId;
        this.workspaceId = workspaceId;
        this.scenarioId = scenarioId;
        this.passed = passed;
        this.intentScoped = intentScoped;
        this.checksHash = checksHash;
        this.intentMatches = intentMatches;
        this.recordedAt = recordedAt;
    }

    public String getRunId() {
        return runId;
    }

    public String getWorkspaceId() {
        return workspaceId;
    }

    public String getScenarioId() {
        return scenarioId;
    }

    public boolean isPassed() {
        return passed;
    }

    public Boolean getIntentScoped() {
        return intentScoped;
    }

    public String getChecksHash() {
        return checksHash;
    }

    public String getIntentMatches() {
        return intentMatches;
    }
}
