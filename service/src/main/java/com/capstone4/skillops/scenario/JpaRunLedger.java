package com.capstone4.skillops.scenario;

import java.time.Clock;
import java.util.Set;
import java.util.stream.Collectors;

import org.springframework.stereotype.Component;

/** {@link RunLedger} 를 scenario_runs 표에 둔다. */
@Component
public class JpaRunLedger implements RunLedger {

    private final ScenarioRunRecordRepository records;
    private final Clock clock = Clock.systemUTC();

    public JpaRunLedger(ScenarioRunRecordRepository records) {
        this.records = records;
    }

    @Override
    public void record(String workspaceId, String scenarioId, String runId, boolean passed) {
        records.save(new ScenarioRunRecord(runId, workspaceId, scenarioId, passed, clock.instant()));
    }

    @Override
    public Set<String> runIdsOf(String workspaceId) {
        return records.findByWorkspaceId(workspaceId).stream().map(ScenarioRunRecord::getRunId)
                .collect(Collectors.toUnmodifiableSet());
    }

    @Override
    public boolean known(String runId) {
        return records.existsById(runId);
    }
}
