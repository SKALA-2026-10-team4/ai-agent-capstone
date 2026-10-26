package com.capstone4.skillops.scenario;

import java.time.Clock;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

import org.springframework.stereotype.Component;

/**
 * {@link RunLedger} 를 scenario_runs 표에 두는 운영 구현이다. 실행 한 번이 한 줄이고 run_id 가 기본 키다.
 * Spring 이 빈으로 만들어 service/src/main/java/com/capstone4/skillops/scenario/ScenarioRunner.java 와
 * service/src/main/java/com/capstone4/skillops/proposal/ProposalService.java, ProposalJudge.java 의 생성자에 주입한다.
 * intent_matches 칸은 번호를 쉼표로 이은 글이다(예: "0,2"). 의도로 돌렸는데 붙은 expectation 이 없으면 빈 글이다.
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
    public void record(String workspaceId, String scenarioId, String runId, boolean passed, boolean intentScoped,
            String checksHash, List<Integer> intentMatches) {
        records.save(new ScenarioRunRecord(runId, workspaceId, scenarioId, passed, intentScoped, checksHash,
                matchesText(intentMatches), clock.instant()));
    }

    /** 이 연습 공간에서 돌린 실행 번호를 고칠 수 없는 집합으로 돌려준다. */
    @Override
    public Set<String> runIdsOf(String workspaceId) {
        return records.findByWorkspaceId(workspaceId).stream().map(ScenarioRunRecord::getRunId)
                .collect(Collectors.toUnmodifiableSet());
    }

    /**
     * 이 연습 공간의 줄을 장부 한 줄 모양으로 바꿔 돌려준다. 새 칸이 생기기 전의 줄은 intent_scoped, checks_hash, intent_matches 가
     * null 이다.
     */
    @Override
    public List<Run> runsOf(String workspaceId) {
        return records.findByWorkspaceId(workspaceId).stream().map(JpaRunLedger::runOf).toList();
    }

    /** run_id 기본 키로 그 실행이 장부에 있는지 본다. */
    @Override
    public boolean known(String runId) {
        return records.existsById(runId);
    }

    /** run_id 기본 키로 줄 하나를 찾는다. */
    @Override
    public Optional<Run> find(String runId) {
        return runId == null ? Optional.empty() : records.findById(runId).map(JpaRunLedger::runOf);
    }

    /** 표의 한 줄을 장부 한 줄 모양으로 바꾼다. */
    private static Run runOf(ScenarioRunRecord r) {
        return new Run(r.getRunId(), r.getScenarioId(), r.isPassed(), r.getIntentScoped(), r.getChecksHash(),
                matchesOf(r.getIntentMatches()));
    }

    /** 번호 목록을 칸에 넣을 글로 바꾼다. null 은 null 이다. */
    static String matchesText(List<Integer> matches) {
        return matches == null ? null : matches.stream().map(String::valueOf).collect(Collectors.joining(","));
    }

    /** 칸의 글을 번호 목록으로 읽는다. null 은 null, 빈 글은 빈 목록이고, 숫자가 아닌 조각은 버린다. */
    static List<Integer> matchesOf(String text) {
        if (text == null) {
            return null;
        }
        return Arrays.stream(text.split(",")).map(String::strip).filter(s -> s.matches("[0-9]{1,3}"))
                .map(Integer::valueOf).toList();
    }
}
