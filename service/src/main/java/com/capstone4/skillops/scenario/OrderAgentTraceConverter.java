package com.capstone4.skillops.scenario;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Collectors;

/**
 * 주문 Agent 의 실행 기록(GET /api/executions/{id}/trace)을 채점용 {@link RunOutcome} 으로 바꾸는 정적 변환기다.
 * events 가운데 SKILL_STARTED(받은 값), SKILL_FINISHED(상태와 내놓은 값), EXECUTION_ERROR(오류)만 읽고 나머지는 무시한다.
 * 이 세 기록은 주문 Agent 의 order-agent/backend/src/main/java/com/capstone4/orderagent/harness/OrderHarness.java 가 남긴다.
 * 단계 순서는 기록 순서가 아니라 step_no 순서다. 최종 상태는 trace 맨 위의 final_status 를 그대로 쓴다.
 * service/src/main/java/com/capstone4/skillops/scenario/HarnessFlowRunner.java 의 runDryRun() 이 toOutcome 을 부른다.
 * 채점기는 RunOutcome 만 알므로, 주문 Agent 의 기록 모양이 바뀌면 이 클래스를 고친다.
 */
public final class OrderAgentTraceConverter {

    /** 단계 시작 기록의 kind 다. data.input 이 그 단계가 받은 값이다. */
    private static final String SKILL_STARTED = "SKILL_STARTED";
    /** 단계 끝 기록의 kind 다. data.status 와 data.output 이 그 단계의 상태와 내놓은 값이다. */
    private static final String SKILL_FINISHED = "SKILL_FINISHED";
    /** 실행을 멈춘 오류 기록의 kind 다. errorText 가 data 의 category, message, missing_inputs, missing_dependencies 를 읽는다. */
    private static final String EXECUTION_ERROR = "EXECUTION_ERROR";

    /** 정적 메서드만 있는 변환기라 객체를 만들지 않는다. */
    private OrderAgentTraceConverter() {
    }

    /**
     * 실행 기록 하나를 RunOutcome 으로 바꾼다. 단계는 step_no 마다 하나이고, SKILL_STARTED 가 받은 값을,
     * SKILL_FINISHED 가 상태와 내놓은 값을 채운다. EXECUTION_ERROR 가 여럿이면 마지막 것만 쓰며,
     * 모든 기록을 읽은 뒤 attachError 로 알맞은 단계에 붙인다.
     * Map 이 아닌 기록은 버리고, 단계 기록에 step_no 가 없으면 IllegalStateException 을 던진다.
     *
     * @param runId 결과에 쓸 실행 번호다. 모의 실행 응답의 run_id 를 넘긴다
     */
    public static RunOutcome toOutcome(String runId, Map<String, Object> trace) {
        Map<Integer, StepBuilder> byStepNo = new TreeMap<>();
        Map<String, Object> executionError = null;
        for (Map<String, Object> event : JsonMaps.listOfMaps(trace.get("events"))) {
            Map<String, Object> data = JsonMaps.mapOf(event.get("data"));
            String skill = (String) event.get("skill_id");
            switch (String.valueOf(event.get("kind"))) {
                case SKILL_STARTED -> {
                    StepBuilder step = stepFor(byStepNo, data, skill);
                    step.input = JsonMaps.mapOf(data.get("input"));
                }
                case SKILL_FINISHED -> {
                    StepBuilder step = stepFor(byStepNo, data, skill);
                    step.status = (String) data.get("status");
                    step.output = JsonMaps.mapOf(data.get("output"));
                }
                case EXECUTION_ERROR -> {
                    executionError = data;
                    executionError.putIfAbsent("skill_id", skill);
                }
                default -> {
                    // skill 선택이나 도구 호출 같은 다른 기록은 채점에 쓰지 않는다.
                }
            }
        }

        List<StepBuilder> steps = new ArrayList<>(byStepNo.values());
        if (executionError != null) {
            attachError(steps, executionError);
        }
        List<RunOutcome.Step> converted = steps.stream().map(StepBuilder::build).toList();
        return new RunOutcome(runId, (String) trace.get("final_status"), converted);
    }

    /**
     * 오류 기록을 알맞은 단계에 붙인다. 실행 중 오류면 주문 Agent 가 그 skill 의 SKILL_FINISHED(status error)를
     * EXECUTION_ERROR 보다 먼저 남겼으므로, 마지막 단계에 오류 문구를 붙인다.
     * 정적 검사에서 막혀 단계가 없거나, 마지막 단계가 다른 skill 이거나, 같은 skill 이어도 ok 로 끝났으면 그 skill 의 error 단계를 덧붙인다.
     * 어느 skill 인지 모르면(skill_id 없음) 단계를 만들지 않는다.
     */
    private static void attachError(List<StepBuilder> steps, Map<String, Object> error) {
        Object skill = error.get("skill_id");
        if (!(skill instanceof String failed)) {
            return;
        }
        StepBuilder target = steps.isEmpty() ? null : steps.getLast();
        if (target == null || !failed.equals(target.skill) || RunOutcome.Step.OK.equals(target.status)) {
            target = new StepBuilder(failed);
            steps.add(target);
        }
        target.status = RunOutcome.Step.ERROR;
        target.error = errorText(error);
    }

    /**
     * 오류 기록을 한 줄 문구로 만든다. 모양은 "&lt;category&gt;: &lt;message&gt; (빠진 값 &lt;missing_inputs&gt;)
     * (먼저 끝나야 할 skill &lt;missing_dependencies&gt;)" 이고 비어 있는 칸은 뺀다. category 가 없으면 EXECUTION_ERROR 로 적는다.
     * ScenarioGrader 가 이 문구를 "단계 오류:" 뒤에 붙여 failures[].reason 맨 앞에 두므로 category 를 맨 앞에 두는 모양을 지킨다.
     * 진단 Agent 의 skillops-agent/skillops_agent/nodes.py 에 있는 _reproduce_failure 가 reason 에 원래 category 가 들어 있는지 보기 때문이다.
     * attachError 가 부르고, service/src/test/java/com/capstone4/skillops/scenario/OrderAgentTraceConverterTest.java 의
     * errorTextListsMissingDependencies 도 직접 부른다.
     */
    static String errorText(Map<String, Object> error) {
        StringBuilder text = new StringBuilder(String.valueOf(error.getOrDefault("category", "EXECUTION_ERROR")));
        if (error.get("message") instanceof String message && !message.isBlank()) {
            text.append(": ").append(message);
        }
        String missingInputs = joined(error.get("missing_inputs"));
        if (!missingInputs.isEmpty()) {
            text.append(" (빠진 값 ").append(missingInputs).append(')');
        }
        String missingDependencies = joined(error.get("missing_dependencies"));
        if (!missingDependencies.isEmpty()) {
            text.append(" (먼저 끝나야 할 skill ").append(missingDependencies).append(')');
        }
        return text.toString();
    }

    /** 목록이면 값을 쉼표와 공백으로 이은 글을, 목록이 아니면 빈 글을 돌려준다. */
    private static String joined(Object values) {
        if (!(values instanceof List<?> list)) {
            return "";
        }
        return list.stream().map(String::valueOf).collect(Collectors.joining(", "));
    }

    /**
     * data 의 step_no 에 해당하는 단계를 돌려준다. 처음 보는 step_no 면 이 기록의 skill 로 새로 만든다.
     * 그래서 한 단계의 skill 은 그 step_no 로 처음 들어온 기록의 skill 이다.
     */
    private static StepBuilder stepFor(Map<Integer, StepBuilder> byStepNo, Map<String, Object> data, String skill) {
        return byStepNo.computeIfAbsent(stepNo(data), n -> new StepBuilder(skill));
    }

    /** data 의 step_no 를 정수로 읽는다. 없거나 숫자가 아니면 IllegalStateException 을 던진다. */
    private static int stepNo(Map<String, Object> data) {
        if (data.get("step_no") instanceof Number n) {
            return n.intValue();
        }
        throw new IllegalStateException("주문 Agent trace 의 단계 기록에 step_no 가 없습니다: " + data);
    }

    /** 한 단계의 기록을 모으는 동안만 쓰는 칸이다. 받은 값과 내놓은 값은 기록이 없으면 빈 맵으로 남는다. */
    private static final class StepBuilder {
        final String skill;
        Map<String, Object> input = new LinkedHashMap<>();
        Map<String, Object> output = new LinkedHashMap<>();
        String status;
        String error;

        /** 그 단계의 skill 로 빈 칸을 만든다. */
        StepBuilder(String skill) {
            this.skill = skill;
        }

        /**
         * 모은 칸으로 RunOutcome.Step 을 만든다. SKILL_FINISHED 가 없으면 기록이 중간에 끊긴 것으로 보고 오류 단계로 만든다.
         * error 상태인데 오류 문구가 없으면 주문 Agent 가 오류로 기록했다는 기본 문구를 넣는다.
         */
        RunOutcome.Step build() {
            if (status == null) {
                return new RunOutcome.Step(skill, RunOutcome.Step.ERROR, input, output, skill + " 단계가 끝났다는 기록이 없습니다");
            }
            if (RunOutcome.Step.ERROR.equals(status) && error == null) {
                error = "주문 Agent 가 " + skill + " 단계를 오류로 기록했습니다";
            }
            return new RunOutcome.Step(skill, status, input, output, error);
        }
    }
}
