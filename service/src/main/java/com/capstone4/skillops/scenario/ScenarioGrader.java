package com.capstone4.skillops.scenario;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.springframework.stereotype.Component;

/**
 * 모의 실행 결과(RunOutcome)를 시험 주문의 기대값(Expectation)과 비교해 합격 여부와 떨어진 이유를 낸다.
 * "LLM 은 제안하고 harness 가 판정한다"에서 판정이 여기서 나온다.
 *
 * 떨어진 이유의 순서와 문구는 계약이다. 이유는 거친 skill 순서, 최종 상태, 재현 기준, 배송 건수, 확인 조건 순으로 쌓고,
 * 불합격이면서 오류가 난 단계가 있으면 "&lt;skill&gt; 단계 오류: &lt;error&gt;" 를 맨 앞에 붙인다.
 * ScenarioRunner 가 이 목록을 "; " 로 이어 계약 5 의 failures[].reason 으로 내보내므로, 순서나 문구를 바꾸면 Agent 가 읽는 값이 바뀐다.
 * 진단 Agent 의 skillops-agent/skillops_agent/contracts.py 에 있는 CHECK_FAILURE_PREFIX 는 "확인 조건 실패:" 와 글자 그대로 같아야 하고,
 * skillops-agent/skillops_agent/nodes.py 의 _evaluate_intent_checks 가 그 앞머리로 의도 위반을 가린다.
 * skillops-agent/skillops_agent/nodes.py 의 _reproduce_failure 는 reason 에 원래 오류 category 가 들어 있는지 보는데,
 * category 는 맨 앞의 단계 오류 이유에 들어간다.
 * 순서와 문구는 service/src/test/java/com/capstone4/skillops/scenario/ScenarioGraderTest.java 가 글자 단위로 고정한다.
 *
 * service/src/main/java/com/capstone4/skillops/scenario/ScenarioRunner.java 의 gradeAndRecord() 가 실행 한 번마다 grade 를 부른다.
 * 채점 규칙을 더하거나 바꾸려면 grade 의 Stream.of 목록에 규칙 메서드를 넣거나 고친다. 목록 순서가 곧 이유 순서다.
 */
@Component
public class ScenarioGrader {

    /**
     * 배송 건수를 셀 때 보는 skill 이다. 성공(ok)한 이 skill 단계 가운데 SHIPMENT_OUTPUT_KEY 를 내놓은 단계 수가 배송 건수다.
     * 세는 방법은 시험 주문을 쓰는 D 와 다시 맞출 수 있다.
     */
    static final String SHIPPING_SKILL = "shipping-reserve";
    /** SHIPPING_SKILL 단계가 이 이름의 값을 null 이 아닌 값으로 내놓았으면 배송 한 건으로 센다. */
    static final String SHIPMENT_OUTPUT_KEY = "shipping.id";

    /**
     * 실행 한 번을 채점한다. 규칙 메서드가 돌려준 이유를 목록 순서대로 모으고, 이유가 하나도 없어야 합격이다.
     * 불합격이고 오류가 난 단계가 있으면 그 단계의 오류 이유를 맨 앞에 붙인다.
     * GradeResult.skill 에는 합격이어도 처음 오류가 난 단계의 skill 이 들어간다.
     *
     * @param extraChecks run_scenarios 의 extra_checks 다. 시험 주문 파일의 확인 조건 뒤에 채점한다
     */
    public GradeResult grade(Expectation expectation, List<Check> extraChecks, RunOutcome run) {
        // 이 목록의 순서가 곧 이유의 순서이고, 확인 조건 이유는 이 목록 뒤에 붙는다.
        List<String> reasons = Stream.of(
                        skillOrderReason(expectation, run),
                        finalStatusReason(expectation, run),
                        stoppedByErrorReason(expectation, run),
                        shipmentReason(expectation, run))
                .filter(Objects::nonNull)
                .collect(Collectors.toCollection(ArrayList::new));
        reasons.addAll(failedCheckReasons(expectation, extraChecks, run));

        // 오류 단계 이유는 불합격일 때만 맨 앞에 붙인다. 합격이면 오류 단계가 있어도 이유를 비워 둔다.
        RunOutcome.Step errorStep = firstErrorStep(run);
        boolean passed = reasons.isEmpty();
        if (!passed && errorStep != null) {
            reasons.addFirst(errorStep.skill() + " 단계 오류: " + errorStep.error());
        }
        return new GradeResult(passed, errorStep == null ? null : errorStep.skill(), List.copyOf(reasons));
    }

    /**
     * 성공(ok)한 단계의 skill 순서가 기대(skills_called)와 다르면 그 이유를 돌려주고, 같거나 기대가 없으면 null 을 돌려준다.
     * 오류가 났거나 건너뛴 단계는 순서에 넣지 않는다.
     */
    private static String skillOrderReason(Expectation expectation, RunOutcome run) {
        if (expectation.skillsCalled() == null) {
            return null;
        }
        List<String> called = run.steps().stream()
                .filter(s -> RunOutcome.Step.OK.equals(s.status()))
                .map(RunOutcome.Step::skill)
                .toList();
        if (expectation.skillsCalled().equals(called)) {
            return null;
        }
        return "거친 skill 순서 기대 " + expectation.skillsCalled() + ", 실제 " + called;
    }

    /** 최종 상태가 기대(final_status)와 다르면 그 이유를 돌려주고, 같거나 기대가 없으면 null 을 돌려준다. */
    private static String finalStatusReason(Expectation expectation, RunOutcome run) {
        if (expectation.finalStatus() == null || expectation.finalStatus().equals(run.finalStatus())) {
            return null;
        }
        return "최종 상태 기대 " + expectation.finalStatus() + ", 실제 " + run.finalStatus();
    }

    /**
     * 재현 주문 기준을 본다. Expectation.mustNotFail 이 켜져 있는데 최종 상태가 FAILED 면 그 이유를 돌려주고,
     * 아니면 null 을 돌려준다. REJECTED 나 HOLD 로 끝나는 것은 이 기준에서 합격이다.
     */
    private static String stoppedByErrorReason(Expectation expectation, RunOutcome run) {
        if (expectation.mustNotFail() && RunOutcome.FAILED.equals(run.finalStatus())) {
            return "오류로 멈추지 않아야 하는데 최종 상태가 FAILED";
        }
        return null;
    }

    /** 배송 건수가 기대(shipments)와 다르면 그 이유를 돌려주고, 같거나 기대가 없으면 null 을 돌려준다. 세는 방법은 SHIPPING_SKILL 을 따른다. */
    private static String shipmentReason(Expectation expectation, RunOutcome run) {
        if (expectation.shipments() == null) {
            return null;
        }
        long shipments = run.steps().stream()
                .filter(s -> SHIPPING_SKILL.equals(s.skill()) && RunOutcome.Step.OK.equals(s.status()))
                .filter(s -> s.output() != null && s.output().get(SHIPMENT_OUTPUT_KEY) != null)
                .count();
        if (shipments == expectation.shipments()) {
            return null;
        }
        return "배송 건수 기대 " + expectation.shipments() + ", 실제 " + shipments;
    }

    /**
     * 맞지 않는 확인 조건마다 이유를 하나씩 만든다. 시험 주문 파일의 확인 조건을 먼저 보고 extra_checks 를 그 뒤에 본다.
     * 이유 앞머리 "확인 조건 실패:" 는 skillops-agent/skillops_agent/contracts.py 의 CHECK_FAILURE_PREFIX 와 글자 그대로 같아야 한다.
     */
    private static List<String> failedCheckReasons(Expectation expectation, List<Check> extraChecks, RunOutcome run) {
        Map<String, Object> context = contextOf(run);
        return Stream.concat(expectation.checks().stream(), extraChecks.stream())
                .filter(c -> !c.holds(context))
                .map(c -> "확인 조건 실패: " + c.text() + " (실제 " + c.actualText(context) + ")")
                .toList();
    }

    /** status 가 error 인 첫 단계를 돌려준다. 없으면 null 을 돌려준다. */
    private static RunOutcome.Step firstErrorStep(RunOutcome run) {
        return run.steps().stream()
                .filter(s -> RunOutcome.Step.ERROR.equals(s.status()))
                .findFirst()
                .orElse(null);
    }

    /**
     * 확인 조건이 값을 찾는 문맥을 만든다. 모든 단계의 받은 값과 내놓은 값을 순서대로 합치고, 같은 이름이면 뒤 단계 값이 이긴다.
     * failedCheckReasons 가 부른다. 진단 Agent 가 조건에 쓸 값 이름을 고를 때 보는
     * skillops-agent/skillops_agent/contracts.py 의 context_keys 도 받은 값과 내놓은 값이라는 같은 범위를 쓴다.
     * 범위를 바꾸면 그 함수도 함께 고친다.
     */
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
