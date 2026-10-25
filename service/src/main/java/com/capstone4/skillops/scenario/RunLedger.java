package com.capstone4.skillops.scenario;

import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * run_scenarios 가 돌린 모의 실행의 장부다. 실행 번호마다 어느 연습 공간에서 어떤 시험 주문으로 돌렸고 합격했는지,
 * 담당자 의도의 scope 에 든 주문이었는지(intent_scoped), 어떤 확인 조건으로 채점했는지(checks_hash), 의도의 어느 expectation 이
 * 채점에 붙었는지(intent_matches) 남긴다.
 * save_proposal 이 제안서의 근거 실행 번호(evidence_run_ids, proof_run_ids)가 실제로 이 서비스에서 돌린 것인지 확인하고,
 * 후보의 판정(fixes_failure, keeps_old_orders, keeps_intent)을 Agent 의 값이 아니라 이 장부로 다시 계산할 때 읽는다.
 * 쓰는 쪽은 service/src/main/java/com/capstone4/skillops/scenario/ScenarioRunner.java 의 gradeAndRecord() 이고,
 * 읽는 쪽은 service/src/main/java/com/capstone4/skillops/proposal/ProposalValidator.java 의 checkEvidence() 와 checkRootCause(),
 * service/src/main/java/com/capstone4/skillops/proposal/ProposalJudge.java 의 judge() 다.
 * 운영 구현은 JpaRunLedger 이고, 테스트는 service/src/test/java/com/capstone4/skillops/scenario/TestFixtures.java 의
 * InMemoryRunLedger 를 쓴다.
 */
public interface RunLedger {

    /**
     * 장부의 한 줄이다.
     *
     * @param runId         모의 실행 번호다
     * @param scenarioId    돌린 시험 주문 번호다(g-NN 또는 f-&lt;execution_id&gt;)
     * @param passed        그 실행 한 번의 채점 결과다
     * @param intentScoped  의도의 scope 에 맞는 expectation 이 하나라도 붙은 실행이면 true 다. 칸이 생기기 전 줄은 null 이다
     * @param checksHash    채점에 붙은 확인 조건의 지문이다(IntentPlan.checksHash). 덧붙인 조건이 없으면 null 이다
     * @param intentMatches 채점에 붙은 expectation 의 번호(IntentPlan.canonicalIndices 의 자리, 작은 것부터)다. scope 가 null 인
     *                      expectation 도 늘 들어간다. 의도 없이 돌린 실행이나 칸이 생기기 전 줄은 null 이다
     */
    record Run(String runId, String scenarioId, boolean passed, Boolean intentScoped, String checksHash,
            List<Integer> intentMatches) {
    }

    /** 모의 실행 한 번을 남긴다. passed 는 그 실행 한 번의 채점 결과이고, intentMatches 는 의도 없이 돌렸으면 null 이다. */
    void record(String workspaceId, String scenarioId, String runId, boolean passed, boolean intentScoped,
            String checksHash, List<Integer> intentMatches);

    /** 붙은 expectation 번호 없이 남긴다. 테스트가 장부를 미리 채울 때 쓴다. */
    default void record(String workspaceId, String scenarioId, String runId, boolean passed, boolean intentScoped,
            String checksHash) {
        record(workspaceId, scenarioId, runId, passed, intentScoped, checksHash, null);
    }

    /** 의도도 덧붙인 조건도 없이 돌린 실행을 남긴다. 테스트가 장부를 미리 채울 때 쓴다. */
    default void record(String workspaceId, String scenarioId, String runId, boolean passed) {
        record(workspaceId, scenarioId, runId, passed, false, null, null);
    }

    /** 이 연습 공간에서 돌린 실행 번호를 돌려준다. 없으면 빈 집합이다. 후보의 evidence_run_ids 가 그 후보의 연습 공간에서 돈 것인지 볼 때 쓴다. */
    Set<String> runIdsOf(String workspaceId);

    /** 이 연습 공간에서 돌린 실행 줄을 모두 돌려준다. 순서는 정하지 않는다. save_proposal 재판정이 쓴다. */
    List<Run> runsOf(String workspaceId);

    /** 어느 연습 공간에서든 돌린 적이 있는 실행 번호이면 true 를 돌려준다. 원인의 근거인 proof_run_ids 를 볼 때 쓴다. */
    boolean known(String runId);

    /**
     * 실행 번호 하나의 줄을 연습 공간을 가리지 않고 찾는다. 없으면 빈 값이다. save_proposal 재판정이 root_cause.proof_run_ids 가운데
     * 재현 시나리오(repro_scenario_ids)가 실제로 떨어진 실행이 있는지 볼 때 쓴다.
     */
    Optional<Run> find(String runId);
}
