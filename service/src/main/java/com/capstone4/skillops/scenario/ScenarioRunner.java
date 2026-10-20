package com.capstone4.skillops.scenario;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Service;

import com.capstone4.skillops.workspace.WorkspaceService;
import com.capstone4.skillops.workspace.WorkspaceSkill;

/**
 * ⑨ 시험 주문 돌리기. 연습 공간의 skill 로 시험 주문을 repeat 번 모의 실행하고 채점한다.
 * 입력은 주문을 하나라도 돌리기 전에 모두 검사한다. 잘못된 입력으로 일부만 돌고 비용이 나가지 않게 하려는 것이다.
 */
@Service
public class ScenarioRunner {

    static final int DEFAULT_REPEAT = 2;
    static final int MAX_REPEAT = 5;

    private final WorkspaceService workspaces;
    private final ScenarioCatalog catalog;
    private final FlowRunner flowRunner;
    private final ScenarioGrader grader;

    public ScenarioRunner(WorkspaceService workspaces, ScenarioCatalog catalog, FlowRunner flowRunner,
            ScenarioGrader grader) {
        this.workspaces = workspaces;
        this.catalog = catalog;
        this.flowRunner = flowRunner;
        this.grader = grader;
    }

    public ScenarioRunReport run(String workspaceId, List<String> scenarioIds, List<String> extraChecks,
            Integer repeat) {
        int times = repeat == null ? DEFAULT_REPEAT : repeat;
        if (times < 1 || times > MAX_REPEAT) {
            throw new IllegalArgumentException("repeat 는 1~" + MAX_REPEAT + " 사이여야 합니다 (받은 값 " + repeat + ")");
        }
        if (scenarioIds == null || scenarioIds.isEmpty()) {
            throw new IllegalArgumentException(
                    "scenario_ids 에 시험 주문 번호를 하나 이상 넣으세요. 있는 번호: " + catalog.ids());
        }
        List<Check> checks = extraChecks == null ? List.of() : extraChecks.stream().map(Check::parse).toList();
        Map<String, WorkspaceSkill> skills = workspaces.skillsOf(workspaceId);
        List<Scenario> scenarios = scenarioIds.stream().map(catalog::get).toList();

        List<ScenarioRunReport.ScenarioResult> results = new ArrayList<>();
        for (Scenario scenario : scenarios) {
            Expectation expectation = Expectation.of(scenario.expect());
            List<String> runIds = new ArrayList<>();
            List<ScenarioRunReport.Failure> failures = new ArrayList<>();
            for (int i = 0; i < times; i++) {
                RunOutcome outcome = flowRunner.runDryRun(
                        new FlowRunner.DryRunRequest(scenario.id(), scenario.order(), workspaceId, skills));
                runIds.add(outcome.runId());
                GradeResult grade = grader.grade(expectation, checks, outcome);
                if (!grade.passed()) {
                    failures.add(new ScenarioRunReport.Failure(outcome.runId(), grade.skill(),
                            String.join("; ", grade.reasons())));
                }
            }
            results.add(new ScenarioRunReport.ScenarioResult(scenario.id(), null, failures.isEmpty(),
                    List.copyOf(runIds), List.copyOf(failures)));
        }
        boolean allPassed = results.stream().allMatch(ScenarioRunReport.ScenarioResult::passed);
        return new ScenarioRunReport(workspaceId, allPassed, List.copyOf(results));
    }
}
