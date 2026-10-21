package com.capstone4.skillops.scenario;

import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import com.capstone4.skillops.workspace.SkillHeader;
import com.capstone4.skillops.workspace.WorkspaceSkill;

/**
 * A 의 실행기가 붙기 전까지 쓰는 가짜 실행기. AI 를 부르지 않고 SKILL.md 맨 위 칸(받는 값·내놓는 값)만 보고 흉내 낸다.
 * 받아야 할 값을 앞에서 아무도 내놓지 않으면 그 단계에서 멈추므로, F1 처럼 연결이 끊긴 장애는 그대로 재현된다.
 * 내놓는 값은 {@link #CANNED} 에 있으면 그 값, 없으면 "fake:값이름" 이다. 그래서 늘 SHIPPED 아니면 FAILED 로 끝난다.
 */
@Component
@ConditionalOnProperty(name = "skillops.flow-runner", havingValue = "contract-only", matchIfMissing = true)
public class ContractOnlyFlowRunner implements FlowRunner {

    static final Map<String, Object> CANNED = Map.of(
            "inventory.reserved", true,
            "payment.status", "PAID",
            "shipping.id", "sh_fake_1",
            "notify.sent", true);

    private static final String ID_CHARS = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789";

    private final SecureRandom random = new SecureRandom();

    @Override
    public RunOutcome runDryRun(DryRunRequest request) {
        Map<String, WorkspaceSkill> skills = request.skills();
        WorkspaceSkill flow = skills.get("order-flow");
        if (flow == null) {
            throw new IllegalArgumentException("연습 공간에 order-flow 가 없습니다");
        }
        List<String> order = SkillHeader.parse(flow.fullText()).steps();
        for (String skill : order) {
            if (!skills.containsKey(skill)) {
                throw new IllegalArgumentException("order-flow 의 실행 순서에 있는 " + skill + " 가 연습 공간에 없습니다");
            }
        }

        Map<String, Object> context = new LinkedHashMap<>();
        request.order().forEach((key, value) -> context.put("order." + key, value));

        List<RunOutcome.Step> steps = new ArrayList<>();
        boolean failed = false;
        for (String skill : order) {
            if (failed) {
                steps.add(new RunOutcome.Step(skill, "skipped", new LinkedHashMap<>(), new LinkedHashMap<>(), null));
                continue;
            }
            SkillHeader header = SkillHeader.parse(skills.get(skill).fullText());
            String missing = header.inputs().stream().filter(k -> !context.containsKey(k)).findFirst().orElse(null);
            if (missing != null) {
                steps.add(new RunOutcome.Step(skill, "error", new LinkedHashMap<>(), new LinkedHashMap<>(),
                        "required input " + missing + " not found"));
                failed = true;
                continue;
            }
            Map<String, Object> input = new LinkedHashMap<>();
            header.inputs().forEach(k -> input.put(k, context.get(k)));
            Map<String, Object> output = new LinkedHashMap<>();
            header.outputs().forEach(k -> output.put(k, CANNED.getOrDefault(k, "fake:" + k)));
            context.putAll(output);
            steps.add(new RunOutcome.Step(skill, "ok", input, output, null));
        }
        return new RunOutcome(newRunId(), failed ? "FAILED" : "SHIPPED", steps);
    }

    private String newRunId() {
        StringBuilder id = new StringBuilder("r_fake");
        for (int i = 0; i < 8; i++) {
            id.append(ID_CHARS.charAt(random.nextInt(ID_CHARS.length())));
        }
        return id.toString();
    }
}
