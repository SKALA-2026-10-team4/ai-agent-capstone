package com.capstone4.skillops.scenario;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Service;

import com.capstone4.skillops.workspace.WorkspaceService;
import com.capstone4.skillops.workspace.WorkspaceSkill;

/**
 * ⑨ 시험 주문 돌리기. 연습 공간의 skill 로 시험 주문을 repeat 번 모의 실행하고 채점한다.
 * 시험 주문은 기준 주문(시험 주문 목록의 g-NN)과 재현 주문(주문 Agent 에서 실패했던 실제 주문) 두 가지다.
 * 입력은 주문을 하나라도 돌리기 전에 모두 검사하고, 재현 주문의 원래 주문도 그 전에 모두 꺼내 둔다.
 * 잘못된 입력으로 일부만 돌고 비용이 나가지 않게 하려는 것이다.
 */
@Service
public class ScenarioRunner {

    static final int DEFAULT_REPEAT = 2;
    static final int MAX_REPEAT = 5;
    /** scenario_ids 안의 재현 주문 번호 앞머리. f- 뒤가 하이픈 있는 UUID 일 때만 재현 주문이다(f-01 등은 목록에서 찾는다) */
    static final String REPLAY_PREFIX = "f-";

    private final WorkspaceService workspaces;
    private final ScenarioCatalog catalog;
    private final FlowRunner flowRunner;
    private final ScenarioGrader grader;
    private final OrderAgentRunClient orderAgent;
    private final RunLedger ledger;

    public ScenarioRunner(WorkspaceService workspaces, ScenarioCatalog catalog, FlowRunner flowRunner,
            ScenarioGrader grader, OrderAgentRunClient orderAgent, RunLedger ledger) {
        this.workspaces = workspaces;
        this.catalog = catalog;
        this.flowRunner = flowRunner;
        this.grader = grader;
        this.orderAgent = orderAgent;
        this.ledger = ledger;
    }

    /**
     * @param scenarioIds  기준 주문 번호(g-01 등) 또는 재현 주문 f-&lt;execution_id&gt;. 결과는 넣은 순서대로다
     * @param replayRunIds 재현할 실행 번호. execution_id(UUID) 또는 run_id(r_ + 32자리 16진수). 결과는 scenario_ids 뒤에 온다
     */
    public ScenarioRunReport run(String workspaceId, List<String> scenarioIds, List<String> replayRunIds,
            List<String> extraChecks, Integer repeat) {
        int times = repeat == null ? DEFAULT_REPEAT : repeat;
        if (times < 1 || times > MAX_REPEAT) {
            throw new IllegalArgumentException("repeat 는 1~" + MAX_REPEAT + " 사이여야 합니다 (받은 값 " + repeat + ")");
        }
        List<String> ids = scenarioIds == null ? List.of() : scenarioIds;
        List<String> replays = replayRunIds == null ? List.of() : replayRunIds;
        if (ids.isEmpty() && replays.isEmpty()) {
            throw new IllegalArgumentException("scenario_ids 나 replay_run_ids 가운데 하나는 넣으세요");
        }
        List<Check> checks = extraChecks == null ? List.of() : extraChecks.stream().map(Check::parse).toList();
        Map<String, WorkspaceSkill> skills = workspaces.skillsOf(workspaceId);
        List<Planned> planned = plan(ids, replays);
        if (planned.stream().anyMatch(Planned::isReplay) && !flowRunner.supportsReplay()) {
            throw new IllegalArgumentException("재현 주문은 skillops.flow-runner=harness 에서만 돌릴 수 있습니다");
        }
        // 원래 주문을 모두 꺼낸 뒤에 돌린다. 하나라도 없으면(IllegalArgumentException) 아무것도 돌리지 않는다.
        Map<String, Map<String, Object>> originals = new HashMap<>();
        List<Planned> ready = planned.stream()
                .map(p -> p.isReplay()
                        ? p.withOrder(originals.computeIfAbsent(p.executionId(), orderAgent::originalOrder))
                        : p)
                .toList();

        List<ScenarioRunReport.ScenarioResult> results = new ArrayList<>();
        // 모의 실행은 돌리는 동안 실제 재고를 잡으므로 차례로 하나씩 돌린다(병렬로 돌리지 않는다).
        for (Planned scenario : ready) {
            List<String> runIds = new ArrayList<>();
            List<ScenarioRunReport.Failure> failures = new ArrayList<>();
            for (int i = 0; i < times; i++) {
                RunOutcome outcome = flowRunner.runDryRun(
                        new FlowRunner.DryRunRequest(scenario.scenarioId(), scenario.order(), workspaceId, skills));
                runIds.add(outcome.runId());
                GradeResult grade = grader.grade(scenario.expectation(), checks, outcome);
                // 제안서의 근거 실행 번호를 save_proposal 이 확인할 수 있게 남긴다.
                ledger.record(workspaceId, scenario.scenarioId(), outcome.runId(), grade.passed());
                if (!grade.passed()) {
                    failures.add(new ScenarioRunReport.Failure(outcome.runId(), grade.skill(),
                            String.join("; ", grade.reasons())));
                }
            }
            results.add(new ScenarioRunReport.ScenarioResult(scenario.scenarioId(), scenario.replayOfRunId(),
                    failures.isEmpty(), List.copyOf(runIds), List.copyOf(failures)));
        }
        boolean allPassed = results.stream().allMatch(ScenarioRunReport.ScenarioResult::passed);
        return new ScenarioRunReport(workspaceId, allPassed, List.copyOf(results));
    }

    /**
     * 돌릴 주문 목록. scenario_ids 의 f-&lt;UUID&gt; 는 재현 주문(scenario_id 는 받은 그대로, replay_of_run_id 는 f- 뒤),
     * 그 밖의 번호는 시험 주문 목록에서 찾는다(없으면 목록의 오류). replay_run_ids 는 모두 재현 주문이고
     * scenario_id 는 f-&lt;execution_id&gt;, replay_of_run_id 는 받은 값이다.
     */
    private List<Planned> plan(List<String> ids, List<String> replays) {
        List<Planned> planned = new ArrayList<>();
        for (String id : ids) {
            String suffix = id != null && id.startsWith(REPLAY_PREFIX) ? id.substring(REPLAY_PREFIX.length()) : null;
            if (suffix != null && OrderAgentRunClient.EXECUTION_ID.matcher(suffix).matches()) {
                planned.add(Planned.replay(id, suffix, OrderAgentRunClient.executionIdOf(suffix)));
            } else {
                Scenario scenario = catalog.get(id);
                planned.add(new Planned(scenario.id(), null, null, scenario.order(), expectationOf(scenario)));
            }
        }
        for (String runId : replays) {
            String executionId = OrderAgentRunClient.executionIdOf(runId);
            if (executionId == null) {
                throw new IllegalArgumentException("replay_run_ids 의 " + runId + " 를 읽을 수 없습니다. "
                        + "실행 번호(UUID)나 run_id(r_ 와 32자리 16진수)를 넣으세요");
            }
            planned.add(Planned.replay(REPLAY_PREFIX + executionId, runId, executionId));
        }
        return planned;
    }

    /** 시험 주문 파일의 확인 조건이 잘못됐으면 어느 파일인지 밝힌다. Agent 의 extra_checks 오류와 헷갈리지 않게 하려는 것이다. */
    private static Expectation expectationOf(Scenario scenario) {
        try {
            return Expectation.of(scenario.expect());
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("시험 주문 " + scenario.id() + " 파일의 " + e.getMessage(), e);
        }
    }

    /**
     * 돌릴 시험 주문 한 건.
     *
     * @param replayOfRunId 재현 주문이면 받은 실행 번호, 기준 주문이면 null
     * @param executionId   재현 주문이면 원래 주문을 꺼낼 execution_id, 기준 주문이면 null
     * @param order         재현 주문은 원래 주문을 꺼내기 전까지 null
     */
    private record Planned(String scenarioId, String replayOfRunId, String executionId, Map<String, Object> order,
            Expectation expectation) {

        static Planned replay(String scenarioId, String replayOfRunId, String executionId) {
            return new Planned(scenarioId, replayOfRunId, executionId, null, Expectation.replay());
        }

        boolean isReplay() {
            return executionId != null;
        }

        Planned withOrder(Map<String, Object> original) {
            return new Planned(scenarioId, replayOfRunId, executionId, original, expectation);
        }
    }
}
