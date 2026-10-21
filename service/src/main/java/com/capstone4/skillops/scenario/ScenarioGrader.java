package com.capstone4.skillops.scenario;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import org.springframework.stereotype.Component;

/**
 * 모의 실행 결과를 시험 주문의 기대값과 비교한다. "LLM 은 제안하고 harness 가 판정한다"에서 판정이 여기서 나온다.
 * 떨어진 이유는 Agent 가 그대로 읽으므로 문구를 바꾸면 계약 5 의 failures[].reason 이 바뀐다.
 */
@Component
public class ScenarioGrader {

    /** 배송 건수는 성공한 이 skill 가운데 shipping.id 를 내놓은 단계 수로 센다. D 와 다시 맞출 수 있다. */
    static final String SHIPPING_SKILL = "shipping-reserve";

    public GradeResult grade(Expectation expectation, List<Check> extraChecks, RunOutcome run) {
        List<String> reasons = new ArrayList<>();

        if (expectation.skillsCalled() != null) {
            List<String> called = run.steps().stream()
                    .filter(s -> "ok".equals(s.status()))
                    .map(RunOutcome.Step::skill)
                    .toList();
            if (!expectation.skillsCalled().equals(called)) {
                reasons.add("거친 skill 순서 기대 " + expectation.skillsCalled() + ", 실제 " + called);
            }
        }
        if (expectation.finalStatus() != null && !expectation.finalStatus().equals(run.finalStatus())) {
            reasons.add("최종 상태 기대 " + expectation.finalStatus() + ", 실제 " + run.finalStatus());
        }
        if (expectation.mustNotFail() && "FAILED".equals(run.finalStatus())) {
            reasons.add("오류로 멈추지 않아야 하는데 최종 상태가 FAILED");
        }
        if (expectation.shipments() != null) {
            long shipments = run.steps().stream()
                    .filter(s -> SHIPPING_SKILL.equals(s.skill()) && "ok".equals(s.status()))
                    .filter(s -> s.output() != null && s.output().get("shipping.id") != null)
                    .count();
            if (shipments != expectation.shipments()) {
                reasons.add("배송 건수 기대 " + expectation.shipments() + ", 실제 " + shipments);
            }
        }
        Map<String, Object> context = contextOf(run);
        Stream.concat(expectation.checks().stream(), extraChecks.stream())
                .filter(c -> !c.holds(context))
                .forEach(c -> reasons.add("확인 조건 실패: " + c.text() + " (실제 " + c.actualText(context) + ")"));

        RunOutcome.Step errorStep = run.steps().stream()
                .filter(s -> "error".equals(s.status()))
                .findFirst()
                .orElse(null);
        boolean passed = reasons.isEmpty();
        if (!passed && errorStep != null) {
            reasons.addFirst(errorStep.skill() + " 단계 오류: " + errorStep.error());
        }
        return new GradeResult(passed, errorStep == null ? null : errorStep.skill(), List.copyOf(reasons));
    }

    /** 모든 단계의 받은 값과 내놓은 값을 순서대로 합친다. 같은 이름이면 뒤 단계 값이 이긴다. */
    static Map<String, Object> contextOf(RunOutcome run) {
        Map<String, Object> context = new LinkedHashMap<>();
        for (RunOutcome.Step step : run.steps()) {
            if (step.input() != null) {
                context.putAll(step.input());
            }
            if (step.output() != null) {
                context.putAll(step.output());
            }
        }
        return context;
    }
}
