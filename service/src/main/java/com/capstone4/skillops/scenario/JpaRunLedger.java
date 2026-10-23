package com.capstone4.skillops.scenario;

import java.time.Clock;
import java.util.Set;
import java.util.stream.Collectors;

import org.springframework.stereotype.Component;

/**
 * {@link RunLedger} 를 scenario_runs 표에 두는 운영 구현이다. 실행 한 번이 한 줄이고 run_id 가 기본 키다.
 * Spring 이 빈으로 만들어 service/src/main/java/com/capstone4/skillops/scenario/ScenarioRunner.java 와
 * service/src/main/java/com/capstone4/skillops/proposal/ProposalService.java 의 생성자에 주입한다.
 */
@Component
public class JpaRunLedger implements RunLedger {

    private final ScenarioRunRecordRepository records;
    /** recorded_at 에 쓰는 UTC 시계다. */
    private final Clock clock = Clock.systemUTC();

    /** Spring Data JPA 가 만든 scenario_runs 저장소를 주입받는다. */
    public JpaRunLedger(ScenarioRunRecordRepository records) {
        this.records = records;
    }

    /** 모의 실행 한 번을 scenario_runs 한 줄로 저장한다. */
    @Override
    public void record(String workspaceId, String scenarioId, String runId, boolean passed) {
        records.save(new ScenarioRunRecord(runId, workspaceId, scenarioId, passed, clock.instant()));
    }

    /** 이 연습 공간에서 돌린 실행 번호를 고칠 수 없는 집합으로 돌려준다. */
    @Override
    public Set<String> runIdsOf(String workspaceId) {
        return records.findByWorkspaceId(workspaceId).stream().map(ScenarioRunRecord::getRunId)
                .collect(Collectors.toUnmodifiableSet());
    }

    /** run_id 기본 키로 그 실행이 장부에 있는지 본다. */
    @Override
    public boolean known(String runId) {
        return records.existsById(runId);
    }
}
