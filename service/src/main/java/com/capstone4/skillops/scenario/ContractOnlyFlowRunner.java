package com.capstone4.skillops.scenario;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import com.capstone4.skillops.common.RandomIds;
import com.capstone4.skillops.workspace.SkillHeader;
import com.capstone4.skillops.workspace.WorkspaceService;
import com.capstone4.skillops.workspace.WorkspaceSkill;

/**
 * 기본 실행기다. skillops.flow-runner 가 contract-only 이거나 설정이 없으면 이 실행기가 뜬다.
 * A 의 실행기가 붙기 전까지 쓰려고 만든 가짜 실행기로, AI 를 부르지 않고 SKILL.md 맨 위 칸의 inputs 와 outputs 만 보고
 * order-flow 의 steps 순서대로 흉내 낸다.
 * 받아야 할 값을 앞 단계에서 아무도 내놓지 않으면 그 단계는 error, 뒤 단계는 skipped 가 되고 최종 상태는 FAILED 다.
 * 그래서 F1 처럼 skill 사이 연결이 끊긴 장애는 그대로 재현된다. 끝까지 가면 SHIPPED 이므로 늘 SHIPPED 아니면 FAILED 로 끝난다.
 * 내놓는 값은 {@link #CANNED} 에 있으면 그 값, 없으면 "fake:값이름" 이다.
 * 실제 주문을 흉내로 돌리면 고쳐졌다는 근거가 되지 않으므로 재현 주문은 받지 않는다. supportsReplay 는 기본값 false 를 쓴다.
 *
 * Spring 이 @ConditionalOnProperty 로 이 빈을 고르고, service/src/main/java/com/capstone4/skillops/scenario/ScenarioRunner.java 의
 * runScenario() 가 runDryRun 을 부른다. 실행 번호는 r_fake 로 시작해 주문 Agent 에는 없는 번호다.
 * 진단 Agent 의 skillops-agent/skillops_agent/contracts.py 에 있는 validate_report 는 run_id 를 UUID 로 읽으므로
 * 이 실행기의 결과를 받지 않는다. 진단 Agent 와 연동할 때는 skillops.flow-runner 를 harness 로 켠다.
 */
@Component
@ConditionalOnProperty(name = "skillops.flow-runner", havingValue = "contract-only", matchIfMissing = true)
public class ContractOnlyFlowRunner implements FlowRunner {

    /**
     * 흉내 낸 단계가 내놓는 값 가운데 미리 정해 둔 값이다. 여기 없는 값 이름은 "fake:값이름" 을 내놓는다.
     * 운영 skill 로 돌리면 기준 주문 g-01 의 확인 조건과 배송 건수를 만족하는 값이며,
     * service/src/test/java/com/capstone4/skillops/scenario/ContractOnlyFlowRunnerTest.java 가 이를 확인한다.
     */
    static final Map<String, Object> CANNED = Map.of(
            "inventory.reserved", true,
            "payment.status", "PAID",
            "shipping.id", "sh_fake_1",
            "notify.sent", true);

    /**
     * order-flow 의 steps 순서대로 단계를 흉내 낸다. 문맥은 주문 칸으로 시작하고 단계가 내놓은 값이 차례로 더해진다.
     * 한 단계가 error 가 되면 뒤 단계는 맨 위 칸을 읽지 않고 skipped 로 둔다. 그래서 뒤 skill 의 맨 위 칸이 깨져 있어도 오류가 나지 않는다.
     * entry flow 나 steps 에 있는 skill 이 연습 공간에 없으면 IllegalArgumentException 을 던진다.
     * request.order() 가 null 이면 stepsOf 의 검사를 마친 뒤 initialContext 에서 NullPointerException 이 난다. 이 예외 문구는
     * MCP run_scenarios 오류(INTERNAL_ERROR)의 message 로 그대로 나가므로, 리팩토링 전처럼 DryRunRequest.order() 의 반환값이
     * null 이라는 문구가 되도록 initialContext 가 request 를 받아 order() 를 직접 부른다.
     * service/src/test/java/com/capstone4/skillops/scenario/ContractOnlyFlowRunnerTest.java 의 nullOrderFailsAfterFlowChecks 가 고정한다.
     */
    @Override
    public RunOutcome runDryRun(DryRunRequest request) {
        Map<String, WorkspaceSkill> skills = request.skills();
        List<String> order = stepsOf(skills);
        Map<String, Object> context = initialContext(request);

        List<RunOutcome.Step> steps = new ArrayList<>();
        boolean failed = false;
        for (String skill : order) {
            if (failed) {
                steps.add(skipped(skill));
                continue;
            }
            RunOutcome.Step step = simulate(skill, SkillHeader.parse(skills.get(skill).fullText()), context);
            steps.add(step);
            failed = RunOutcome.Step.ERROR.equals(step.status());
        }
        return new RunOutcome(newRunId(), failed ? RunOutcome.FAILED : RunOutcome.SHIPPED, steps);
    }

    /**
     * entry flow 의 steps 를 실행 순서로 돌려준다. entry flow 이름은
     * service/src/main/java/com/capstone4/skillops/workspace/WorkspaceService.java 의 ENTRY_SKILL(order-flow)이다.
     * entry flow 나 steps 에 있는 skill 이 연습 공간에 없으면 IllegalArgumentException 을 던진다.
     */
    private static List<String> stepsOf(Map<String, WorkspaceSkill> skills) {
        WorkspaceSkill flow = skills.get(WorkspaceService.ENTRY_SKILL);
        if (flow == null) {
            throw new IllegalArgumentException("연습 공간에 " + WorkspaceService.ENTRY_SKILL + " 가 없습니다");
        }
        List<String> order = SkillHeader.parse(flow.fullText()).steps();
        for (String skill : order) {
            if (!skills.containsKey(skill)) {
                throw new IllegalArgumentException(
                        WorkspaceService.ENTRY_SKILL + " 의 실행 순서에 있는 " + skill + " 가 연습 공간에 없습니다");
            }
        }
        return order;
    }

    /** 주문 칸마다 order. 접두어를 붙인 이름으로 시작 문맥을 만든다. order-intake 의 inputs(order.customer 등)가 이 이름을 받는다. */
    private static Map<String, Object> initialContext(DryRunRequest request) {
        Map<String, Object> context = new LinkedHashMap<>();
        request.order().forEach((key, value) -> context.put("order." + key, value));
        return context;
    }

    /**
     * 단계 하나를 흉내 낸다. 맨 위 칸의 inputs 가 문맥에 모두 있으면 outputs 를 만들어 문맥에 더하고 ok 단계를 돌려준다.
     * 하나라도 없으면 처음 빠진 값 이름을 적은 error 단계를 돌려주며, 이때 받은 값과 내놓은 값은 비워 둔다.
     */
    private static RunOutcome.Step simulate(String skill, SkillHeader header, Map<String, Object> context) {
        String missing = header.inputs().stream().filter(k -> !context.containsKey(k)).findFirst().orElse(null);
        if (missing != null) {
            return new RunOutcome.Step(skill, RunOutcome.Step.ERROR, new LinkedHashMap<>(), new LinkedHashMap<>(),
                    "required input " + missing + " not found");
        }
        Map<String, Object> input = new LinkedHashMap<>();
        header.inputs().forEach(k -> input.put(k, context.get(k)));
        Map<String, Object> output = new LinkedHashMap<>();
        header.outputs().forEach(k -> output.put(k, CANNED.getOrDefault(k, "fake:" + k)));
        context.putAll(output);
        return new RunOutcome.Step(skill, RunOutcome.Step.OK, input, output, null);
    }

    /** 앞 단계 오류 때문에 돌리지 않은 단계를 만든다. */
    private static RunOutcome.Step skipped(String skill) {
        return new RunOutcome.Step(skill, RunOutcome.Step.SKIPPED, new LinkedHashMap<>(), new LinkedHashMap<>(), null);
    }

    /** r_fake 뒤에 영문자와 숫자 8자를 붙인 실행 번호를 만든다. 주문 Agent 의 run_id(r_ 와 32자리 16진수)와 겹치지 않는 모양이다. */
    private static String newRunId() {
        return RandomIds.next("r_fake", 8, RandomIds.ALPHANUMERIC);
    }
}
