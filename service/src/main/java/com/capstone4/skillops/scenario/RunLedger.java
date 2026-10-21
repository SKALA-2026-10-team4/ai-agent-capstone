package com.capstone4.skillops.scenario;

import java.util.Set;

/**
 * run_scenarios 가 돌린 모의 실행의 장부. 실행 번호마다 어느 연습 공간에서 어떤 시험 주문으로 돌렸는지 남긴다.
 * save_proposal 이 제안서의 근거 실행 번호(evidence_run_ids, proof_run_ids)가 실제로 이 서비스에서 돌린 것인지 확인할 때 읽는다.
 */
public interface RunLedger {

    /** 모의 실행 한 번을 남긴다. passed 는 그 실행 한 번의 채점 결과다 */
    void record(String workspaceId, String scenarioId, String runId, boolean passed);

    /** 이 연습 공간에서 돌린 실행 번호. 없으면 빈 집합 */
    Set<String> runIdsOf(String workspaceId);

    /** 어느 연습 공간에서든 돌린 적이 있는 실행 번호인지 */
    boolean known(String runId);
}
