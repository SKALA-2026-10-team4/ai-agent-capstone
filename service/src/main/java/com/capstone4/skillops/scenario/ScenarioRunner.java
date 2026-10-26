package com.capstone4.skillops.scenario;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Service;

import com.capstone4.skillops.workspace.WorkspaceService;
import com.capstone4.skillops.workspace.WorkspaceSkill;

/**
 * ⑨ run_scenarios 의 본체다. 연습 공간의 skill 로 시험 주문을 repeat 번씩 모의 실행하고, 실행마다 채점해 장부에 남긴 뒤
 * 결과 묶음 ScenarioRunReport 를 만든다.
 * 시험 주문은 두 가지다. 기준 주문은 시험 주문 목록(ScenarioCatalog)의 g-NN 이고, 재현 주문은 주문 Agent 에서 실패했던
 * 실제 주문을 다시 돌리는 f-&lt;execution_id&gt; 다.
 *
 * 입력은 주문을 하나라도 돌리기 전에 모두 검사하고, 재현 주문의 원래 주문도 그 전에 모두 꺼내 둔다.
 * 잘못된 입력으로 일부만 돌고 비용이 나가지 않게 하려는 것이다.
 * run 본문의 호출 순서가 곧 오류 우선순위다. 여러 입력이 함께 틀리면 먼저 검사한 쪽의 오류 문구가 나간다.
 * 순서는 repeat 범위, 빈 입력, extra_checks 문법, intent_expectations 개수와 문법, 연습 공간, 시험 주문 번호와 재현 번호,
 * 실행기의 재현 지원, 원래 주문 꺼내기, 의도 scope 문맥(상품 단가 읽기)이다. 모두 통과하면 연습 공간을 고정하고(WorkspaceService.freeze)
 * 돌리기 시작한다. 고정된 연습 공간은 apply_patch 를 WORKSPACE_FROZEN 으로 거부한다.
 *
 * 담당자 의도(intent_expectations)의 채점 규칙은 IntentPlan 과 withIntent 에 있다(docs/plans/2026-10-26-behavior-edit-root-fix.md
 * 2.4 절). 실행마다 장부에 scenario_id, passed, intent_scoped, checks_hash, intent_matches(채점에 붙은 expectation 의 자리)를
 * 남기고, save_proposal 재판정이 이 장부로 후보를 다시 판정한다.
 * 같은 연습 공간에 apply_patch 와 run_scenarios 가 동시에 오면 WorkspaceService 가 연습 공간 번호별 잠금으로 apply 와 freeze 를
 * 차례로 세우고, 이 클래스는 고정한 뒤 원문을 다시 읽는다. 잠금은 서비스 프로세스 하나 안에서만 통하므로 service 를 여러 개 띄우면
 * 이 경합은 막지 못한다(알려진 한계).
 * service/src/test/java/com/capstone4/skillops/scenario/ScenarioRunnerTest.java 와
 * service/src/test/java/com/capstone4/skillops/scenario/ScenarioRunnerReplayTest.java 가 이 순서를 고정하므로,
 * 단계를 더하거나 옮기면 두 테스트의 순서 검사도 함께 고친다.
 *
 * service/src/main/java/com/capstone4/skillops/mcp/WorkspaceTools.java 의 runScenarios() 가 MCP 입력 여섯 개를 그대로 run 에 넘긴다.
 * 그 메서드는 Spring AI MCP Server 가 @McpTool 로 등록해 run_scenarios 도구로 노출한다.
 * 예외는 service/src/main/java/com/capstone4/skillops/mcp/WorkspaceToolResults.java 의 run() 이 도구 오류로 바꾸며,
 * IllegalArgumentException 은 INVALID_INPUT, 그 밖의 RuntimeException 은 INTERNAL_ERROR 가 된다.
 * 진단 Agent 에서는 skillops-agent/skillops_agent/tools.py 의 MCPTools.run_scenarios 가 이 도구를 부른다.
 * 모의 실행은 FlowRunner 구현이, 채점은 ScenarioGrader 가, 실행 번호 기록은 RunLedger 구현이 맡는다.
 */
@Service
public class ScenarioRunner {

    /**
     * repeat 를 넣지 않았을 때 같은 주문을 돌리는 횟수다. AI 가 처리하는 단계가 있어 한 번의 통과는 우연일 수 있으므로 여러 번 돌린다.
     * 이 값과 MIN_REPEAT, MAX_REPEAT 는 service/src/main/java/com/capstone4/skillops/mcp/WorkspaceTools.java 의 도구 설명 문구와
     * docs/contracts/mcp-tools-input-output.md 의 run_scenarios 절 보내는 값 표에도 글자로 적혀 있으므로 함께 고친다.
     * 설명 문구가 상수와 같은지는 service/src/test/java/com/capstone4/skillops/mcp/WorkspaceToolsTest.java 의
     * runScenariosDescriptionsMatchRepeatConstants 가 본다.
     * 진단 Agent 는 skillops-agent/skillops_agent/tools.py 의 MCPTools.run_scenarios 에 기본값 2 를 따로 두고 repeat 를 늘 보낸다.
     * 단계별로 넣는 횟수는 skillops-agent/skillops_agent/config.py 의 PREFLIGHT_REPEAT, INTENT_CHECK_REPEAT, ABLATION_REPEAT,
     * VERIFY_REPEAT 이며 MIN_REPEAT 와 MAX_REPEAT 사이여야 한다.
     */
    public static final int DEFAULT_REPEAT = 2;
    /** repeat 로 받는 가장 작은 값이다. */
    public static final int MIN_REPEAT = 1;
    /** repeat 로 받는 가장 큰 값이다. 모의 실행은 차례로 하나씩 돌므로 이 값을 키우면 run_scenarios 한 번이 그만큼 오래 걸린다. */
    public static final int MAX_REPEAT = 5;
    /**
     * scenario_ids 에서 재현 주문 번호를 알아보는 앞머리다. f- 뒤가 하이픈 있는 UUID 일 때만 재현 주문으로 보고,
     * f-01 같은 번호는 시험 주문 목록에서 찾는다. 진단 Agent 는 skillops-agent/skillops_agent/contracts.py 의
     * replay_scenario_id 로 같은 모양의 번호를 만든다.
     */
    static final String REPLAY_PREFIX = "f-";
    /** 실행 한 번에 떨어진 이유가 여럿일 때 failures[].reason 에서 이유 사이에 넣는 글자다. ScenarioRunReport.Failure 에 적힌 계약이다. */
    static final String REASON_SEPARATOR = "; ";

    private final WorkspaceService workspaces;
    private final ScenarioCatalog catalog;
    private final FlowRunner flowRunner;
    private final ScenarioGrader grader;
    private final OrderAgentRunClient orderAgent;
    private final RunLedger ledger;

    /** Spring 이 빈으로 만들며 생성자로 주입한다. flowRunner 에는 skillops.flow-runner 설정으로 고른 실행기 하나가 들어온다. */
    public ScenarioRunner(WorkspaceService workspaces, ScenarioCatalog catalog, FlowRunner flowRunner,
            ScenarioGrader grader, OrderAgentRunClient orderAgent, RunLedger ledger) {
        this.workspaces = workspaces;
        this.catalog = catalog;
        this.flowRunner = flowRunner;
        this.grader = grader;
        this.orderAgent = orderAgent;
        this.ledger = ledger;
    }

    /** 의도 없이 돌린다. {@code run(workspaceId, scenarioIds, replayRunIds, extraChecks, null, repeat)} 과 같고 테스트가 쓴다. */
    public ScenarioRunReport run(String workspaceId, List<String> scenarioIds, List<String> replayRunIds,
            List<String> extraChecks, Integer repeat) {
        return run(workspaceId, scenarioIds, replayRunIds, extraChecks, null, repeat);
    }

    /**
     * 입력을 모두 검사하고 재현 주문의 원래 주문까지 꺼낸 뒤, 시험 주문을 넣은 순서대로 하나씩 돌려 결과 묶음을 만든다.
     * 본문의 호출 순서가 검사 순서이자 오류 우선순위이므로, 단계를 바꿀 때는 이 순서를 고친다.
     * 잘못된 입력은 아무것도 돌리기 전에 IllegalArgumentException 으로 끝난다.
     * 돌리는 도중 실행기가 예외를 던지면 예외가 그대로 올라가고 결과 묶음은 만들지 않는다. 그 전에 채점한 실행은 장부에 남는다.
     *
     * @param scenarioIds        기준 주문 번호(g-01 등)나 재현 주문 f-&lt;execution_id&gt; 다. 결과는 넣은 순서대로 나온다
     * @param replayRunIds       재현할 실행 번호다. execution_id(UUID)나 run_id(r_ 와 32자리 16진수)를 받고, 결과는 scenario_ids 뒤에 온다
     * @param extraChecks        이번 호출에만 모든 시험 주문에 덧붙이는 확인 조건이다. 문법은 Check 를 따른다
     * @param intentExpectations 담당자 의도의 기대 결과 0~10개다. null 이나 빈 목록이면 의도 없이 채점한다. 규칙은 IntentPlan 에 있다
     * @param repeat             시험 주문마다 돌릴 횟수다. null 이면 DEFAULT_REPEAT 다
     */
    public ScenarioRunReport run(String workspaceId, List<String> scenarioIds, List<String> replayRunIds,
            List<String> extraChecks, List<IntentExpectation> intentExpectations, Integer repeat) {
        // 검사 단계다. 이 순서가 오류 우선순위이고, withIntent 까지 끝나야 모의 실행을 시작한다.
        int times = repeatTimes(repeat);
        List<String> ids = orEmpty(scenarioIds);
        List<String> replays = orEmpty(replayRunIds);
        requireSomethingToRun(ids, replays);
        List<Check> checks = parseChecks(extraChecks);
        IntentPlan intent = IntentPlan.parse(intentExpectations);
        Map<String, WorkspaceSkill> skills = workspaces.skillsOf(workspaceId);
        List<Planned> planned = plan(ids, replays);
        requireReplaySupport(planned);
        List<Planned> ready = withIntent(withOriginalOrders(planned), intent);
        String checksHash = intent.checksHash(checks);

        // 시험을 시작한 연습 공간은 고정한다. 이후의 apply_patch 는 WORKSPACE_FROZEN 으로 거부되어, 장부의 실행과 연습 공간의 수정이 어긋나지 않는다.
        // 검사 단계에서 읽은 원문은 그 뒤 고정 전에 끝난 apply_patch 를 모를 수 있으므로, 고정한 뒤 원문을 다시 읽어 돌린다.
        workspaces.freeze(workspaceId);
        skills = workspaces.skillsOf(workspaceId);
        List<ScenarioRunReport.ScenarioResult> results = new ArrayList<>();
        // harness 실행기의 모의 실행은 도는 동안 주문 Agent 의 실제 재고를 잡으므로 병렬로 돌리지 않고 차례로 하나씩 돌린다.
        // 병렬로 바꾸려면 HarnessFlowRunner 의 공정 락도 함께 본다. 두 곳이 함께 실행 순서를 정한다.
        for (Planned scenario : ready) {
            results.add(runScenario(workspaceId, skills, checks, checksHash, times, scenario));
        }
        return report(workspaceId, results);
    }

    /** 돌릴 횟수를 정한다. 넣지 않으면 DEFAULT_REPEAT 이고, MIN_REPEAT 부터 MAX_REPEAT 까지의 범위 밖이면 IllegalArgumentException 을 던진다. */
    private static int repeatTimes(Integer repeat) {
        int times = repeat == null ? DEFAULT_REPEAT : repeat;
        if (times < MIN_REPEAT || times > MAX_REPEAT) {
            throw new IllegalArgumentException(
                    "repeat 는 " + MIN_REPEAT + "~" + MAX_REPEAT + " 사이여야 합니다 (받은 값 " + repeat + ")");
        }
        return times;
    }

    /** 넣지 않은 목록(null)은 빈 목록으로 본다. */
    private static List<String> orEmpty(List<String> values) {
        return values == null ? List.of() : values;
    }

    /** scenario_ids 와 replay_run_ids 가 모두 비어 있으면 IllegalArgumentException 을 던진다. */
    private static void requireSomethingToRun(List<String> ids, List<String> replays) {
        if (ids.isEmpty() && replays.isEmpty()) {
            throw new IllegalArgumentException("scenario_ids 나 replay_run_ids 가운데 하나는 넣으세요");
        }
    }

    /**
     * extra_checks 를 돌리기 전에 모두 읽는다. 하나라도 문법이 틀리면 Check.parse 가 IllegalArgumentException 을 던져
     * 아무것도 돌리지 않는다. 진단 Agent 는 이 거절을 피하려고 skillops-agent/skillops_agent/nodes.py 의 _split_checks 에서
     * 읽지 못할 조건을 미리 걸러 낸다.
     */
    private static List<Check> parseChecks(List<String> extraChecks) {
        return extraChecks == null ? List.of() : extraChecks.stream().map(Check::parse).toList();
    }

    /**
     * 돌릴 주문 목록을 만든다. scenario_ids 를 넣은 순서대로 담고 그 뒤에 replay_run_ids 를 넣은 순서대로 담으므로,
     * 결과 순서도 이와 같다. scenario_ids 의 번호가 틀리면 replay_run_ids 를 보기 전에 그 오류가 나간다.
     * 번호 하나를 읽는 규칙은 planScenarioId 와 planReplayRunId 에 있다.
     */
    private List<Planned> plan(List<String> ids, List<String> replays) {
        List<Planned> planned = new ArrayList<>();
        for (String id : ids) {
            planned.add(planScenarioId(id));
        }
        for (String runId : replays) {
            planned.add(planReplayRunId(runId));
        }
        return planned;
    }

    /**
     * scenario_ids 의 번호 하나를 계획으로 바꾼다. f-&lt;UUID&gt; 면 재현 주문이며, scenario_id 에는 받은 번호를,
     * replay_of_run_id 에는 f- 뒤 글자를 받은 그대로 쓰고 원래 주문은 소문자로 바꾼 execution_id 로 꺼낸다.
     * 그 밖의 번호는 시험 주문 목록에서 찾고, 없으면 ScenarioCatalog.get 의 오류가 나간다.
     */
    private Planned planScenarioId(String id) {
        String suffix = replaySuffixOf(id);
        if (suffix != null) {
            return Planned.replay(id, suffix, OrderAgentRunClient.executionIdOf(suffix));
        }
        Scenario scenario = catalog.get(id);
        return Planned.golden(scenario.id(), scenario.order(), expectationOf(scenario));
    }

    /**
     * replay_run_ids 의 번호 하나를 재현 주문 계획으로 바꾼다. scenario_id 는 f- 뒤에 소문자 execution_id 를 붙인 것이고,
     * replay_of_run_id 는 받은 값 그대로다. execution_id 나 run_id 모양이 아니면 IllegalArgumentException 을 던진다.
     */
    private static Planned planReplayRunId(String runId) {
        String executionId = OrderAgentRunClient.executionIdOf(runId);
        if (executionId == null) {
            throw new IllegalArgumentException("replay_run_ids 의 " + runId + " 를 읽을 수 없습니다. "
                    + "실행 번호(UUID)나 run_id(r_ 와 32자리 16진수)를 넣으세요");
        }
        return Planned.replay(REPLAY_PREFIX + executionId, runId, executionId);
    }

    /** 재현 주문 번호면 f- 뒤의 글자를 받은 그대로 돌려주고, 아니면 null 을 돌려준다. f- 뒤가 하이픈 있는 UUID 일 때만 재현 주문이다. */
    private static String replaySuffixOf(String id) {
        if (id == null || !id.startsWith(REPLAY_PREFIX)) {
            return null;
        }
        String suffix = id.substring(REPLAY_PREFIX.length());
        return OrderAgentRunClient.isExecutionId(suffix) ? suffix : null;
    }

    /**
     * 재현 주문이 하나라도 있는데 실행기가 재현을 못 하면 IllegalArgumentException 을 던진다.
     * 기본 실행기 ContractOnlyFlowRunner 는 재현을 못 하므로, 재현 주문을 돌리려면 skillops.flow-runner 를 harness 로 켠다.
     * 이 검사가 원래 주문 꺼내기보다 앞서므로 contract-only 에서는 주문 Agent 를 부르지 않는다.
     */
    private void requireReplaySupport(List<Planned> planned) {
        if (planned.stream().anyMatch(Planned::isReplay) && !flowRunner.supportsReplay()) {
            throw new IllegalArgumentException("재현 주문은 skillops.flow-runner=harness 에서만 돌릴 수 있습니다");
        }
    }

    /**
     * 재현 주문마다 주문 Agent 에서 원래 주문을 꺼내 채운 목록을 돌려준다. 같은 execution_id 는 한 번만 꺼낸다.
     * 모의 실행을 시작하기 전에 모두 꺼내므로, 하나라도 주문 Agent 에 없으면 OrderAgentRunClient.originalOrder 의
     * IllegalArgumentException 으로 끝나고 아무것도 돌리지 않는다.
     */
    private List<Planned> withOriginalOrders(List<Planned> planned) {
        Map<String, Map<String, Object>> originals = new HashMap<>();
        return planned.stream()
                .map(p -> p.isReplay()
                        ? p.withOrder(originals.computeIfAbsent(p.executionId(), orderAgent::originalOrder))
                        : p)
                .toList();
    }

    /**
     * 시험 주문마다 담당자 의도를 채점 기준에 반영한다. 의도가 없으면 그대로 돌려준다. 규칙은 이렇다.
     * scope 가 null 인 checks 는 모든 시험 주문에 더한다. 기준 주문이 scope 에 맞으면 시험 주문 파일의 expect 대신
     * Expectation.inScope(맞는 checks)로 채점하고 intent_scoped 를 참으로 둔다. 맞지 않으면 expect 를 그대로 쓴다.
     * 재현 주문은 불변식에 맞는 checks 를 더하고, 맞는 것이 있으면 intent_scoped 를 참으로 둔다.
     * 어느 경우든 채점에 붙은 expectation 의 자리(IntentPlan.matchedIndices, scope 가 null 인 것 포함)를 장부 intent_matches 로 남긴다.
     * scope 는 시험 주문의 처음 값(IntentPlan.scopeContextOf)으로 평가한다. scope 가 order.total 을 쓰면 상품 단가를 여기서 한 번만
     * 읽으며, 주문 Agent 에 닿지 못하면 OrderAgentRunClient 의 IllegalStateException 이 아무것도 돌리기 전에 올라간다.
     */
    private List<Planned> withIntent(List<Planned> planned, IntentPlan intent) {
        if (intent.isEmpty()) {
            return planned;
        }
        boolean withTotal = intent.needsTotal();
        Map<String, BigDecimal> prices = withTotal ? pricesOrEmpty(orderAgent.unitPrices()) : Map.of();
        List<Check> unscoped = intent.unscopedChecks();
        return planned.stream().map(p -> {
            Map<String, Object> scopeContext = IntentPlan.scopeContextOf(p.order(), withTotal, prices);
            List<Check> scoped = intent.scopedChecks(scopeContext);
            List<Integer> matches = intent.matchedIndices(scopeContext);
            if (p.isReplay()) {
                return p.withExpectation(p.expectation().plus(scoped).plus(unscoped), !scoped.isEmpty(), matches);
            }
            if (scoped.isEmpty()) {
                return p.withExpectation(p.expectation().plus(unscoped), false, matches);
            }
            return p.withExpectation(Expectation.inScope(scoped).plus(unscoped), true, matches);
        }).toList();
    }

    /** 단가를 못 읽어 null 이 오면 빈 맵으로 본다. 그때 order.total 은 null 이 된다. */
    private static Map<String, BigDecimal> pricesOrEmpty(Map<String, BigDecimal> prices) {
        return prices == null ? Map.of() : prices;
    }

    /**
     * 시험 주문 하나를 times 번 돌린다. 실행마다 바로 채점하고 장부에 남기며, 한 번이라도 떨어지면 그 시험 주문은 불합격이다.
     * 돌린 실행 번호는 모두 run_ids 에 담겨 제안서의 근거로 쓰인다.
     */
    private ScenarioRunReport.ScenarioResult runScenario(String workspaceId, Map<String, WorkspaceSkill> skills,
            List<Check> checks, String checksHash, int times, Planned scenario) {
        List<String> runIds = new ArrayList<>();
        List<ScenarioRunReport.Failure> failures = new ArrayList<>();
        for (int i = 0; i < times; i++) {
            RunOutcome outcome = flowRunner.runDryRun(
                    new FlowRunner.DryRunRequest(scenario.scenarioId(), scenario.order(), workspaceId, skills));
            runIds.add(outcome.runId());
            GradeResult grade = gradeAndRecord(workspaceId, scenario, checks, checksHash, outcome);
            if (!grade.passed()) {
                failures.add(failureOf(outcome.runId(), grade));
            }
        }
        return new ScenarioRunReport.ScenarioResult(scenario.scenarioId(), scenario.replayOfRunId(),
                failures.isEmpty(), List.copyOf(runIds), List.copyOf(failures), scenario.intentScoped());
    }

    /** 실행 한 번을 채점하고 곧바로 장부에 남긴다. 실행마다 기록하므로 뒤 실행이 예외로 끝나도 앞서 돌린 실행은 장부에 남는다. */
    private GradeResult gradeAndRecord(String workspaceId, Planned scenario, List<Check> checks, String checksHash,
            RunOutcome outcome) {
        GradeResult grade = grader.grade(scenario.expectation(), checks, outcome);
        // 제안서의 근거 실행 번호를 save_proposal 이 확인하고 판정을 다시 계산할 수 있게 남긴다.
        // service/src/main/java/com/capstone4/skillops/proposal/ProposalValidator.java 와 ProposalJudge.java 가 이 장부를 읽는다.
        ledger.record(workspaceId, scenario.scenarioId(), outcome.runId(), grade.passed(), scenario.intentScoped(),
                checksHash, scenario.intentMatches());
        return grade;
    }

    /** 떨어진 실행 한 번을 failures[] 한 칸으로 만든다. 이유가 여럿이면 ScenarioGrader 가 정한 순서 그대로 REASON_SEPARATOR 로 잇는다. */
    private static ScenarioRunReport.Failure failureOf(String runId, GradeResult grade) {
        return new ScenarioRunReport.Failure(runId, grade.skill(), String.join(REASON_SEPARATOR, grade.reasons()));
    }

    /** 시험 주문별 결과를 모아 결과 묶음을 만든다. 시험 주문이 모두 합격이어야 all_passed 가 true 다. */
    private static ScenarioRunReport report(String workspaceId, List<ScenarioRunReport.ScenarioResult> results) {
        boolean allPassed = results.stream().allMatch(ScenarioRunReport.ScenarioResult::passed);
        return new ScenarioRunReport(workspaceId, allPassed, List.copyOf(results));
    }

    /**
     * 시험 주문 파일의 expect 로 채점 기준을 만든다. 파일의 확인 조건이 잘못됐으면 어느 시험 주문 파일인지 밝힌
     * IllegalArgumentException 을 던진다. Agent 가 넣은 extra_checks 의 오류와 헷갈리지 않게 하려는 것이다.
     */
    private static Expectation expectationOf(Scenario scenario) {
        try {
            return Expectation.of(scenario.expect());
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("시험 주문 " + scenario.id() + " 파일의 " + e.getMessage(), e);
        }
    }

    /**
     * 돌릴 시험 주문 한 건의 계획이다. plan 이 만들고, 재현 주문이면 withOriginalOrders 가 order 를 채운다.
     *
     * @param scenarioId    결과의 scenario_id 다
     * @param replayOfRunId 재현 주문이면 받은 실행 번호이고, 기준 주문이면 null 이다
     * @param executionId   재현 주문이면 원래 주문을 꺼낼 소문자 execution_id 이고, 기준 주문이면 null 이다
     * @param order         모의 실행에 넘길 주문이다. 재현 주문은 원래 주문을 꺼내기 전까지 null 이다
     * @param expectation   채점 기준이다
     * @param intentScoped  의도의 scope 에 맞는 expectation 이 붙었으면 true 다. 결과의 intent_scoped 와 장부에 그대로 남는다
     * @param intentMatches 채점에 붙은 expectation 의 자리다. 의도 없이 돌리면 null 이고, 장부 intent_matches 에 그대로 남는다
     */
    private record Planned(String scenarioId, String replayOfRunId, String executionId, Map<String, Object> order,
            Expectation expectation, boolean intentScoped, List<Integer> intentMatches) {

        /** 시험 주문 목록에서 찾은 기준 주문의 계획을 만든다. */
        static Planned golden(String scenarioId, Map<String, Object> order, Expectation expectation) {
            return new Planned(scenarioId, null, null, order, expectation, false, null);
        }

        /** 재현 주문의 계획을 만든다. 채점 기준은 Expectation.replay 이고 order 는 아직 비어 있다. */
        static Planned replay(String scenarioId, String replayOfRunId, String executionId) {
            return new Planned(scenarioId, replayOfRunId, executionId, null, Expectation.replay(), false, null);
        }

        /** execution_id 가 있으면 재현 주문이다. */
        boolean isReplay() {
            return executionId != null;
        }

        /** 원래 주문을 채운 복사본을 돌려준다. */
        Planned withOrder(Map<String, Object> original) {
            return new Planned(scenarioId, replayOfRunId, executionId, original, expectation, intentScoped, intentMatches);
        }

        /** 의도를 반영한 채점 기준, intent_scoped, 붙은 expectation 의 자리로 바꾼 복사본을 돌려준다. withIntent 가 쓴다. */
        Planned withExpectation(Expectation intended, boolean scoped, List<Integer> matches) {
            return new Planned(scenarioId, replayOfRunId, executionId, order, intended, scoped, matches);
        }
    }
}
