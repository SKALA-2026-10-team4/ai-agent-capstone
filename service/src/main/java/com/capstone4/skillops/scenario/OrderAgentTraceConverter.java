package com.capstone4.skillops.scenario;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Collectors;

/**
 * 주문 Agent 의 실행 기록(GET /api/executions/{id}/trace)을 채점용 {@link RunOutcome} 으로 바꾼다.
 * events 가운데 SKILL_STARTED(받은 값), SKILL_FINISHED(상태·내놓은 값), EXECUTION_ERROR(오류)만 읽고 나머지는 무시한다.
 * 단계 순서는 step_no 순서다. 최종 상태는 trace 맨 위의 final_status 를 그대로 쓴다.
 */
public final class OrderAgentTraceConverter {

    private OrderAgentTraceConverter() {
    }

    public static RunOutcome toOutcome(String runId, Map<String, Object> trace) {
        Map<Integer, StepBuilder> byStepNo = new TreeMap<>();
        Map<String, Object> executionError = null;
        for (Map<String, Object> event : listOfMaps(trace.get("events"))) {
            Map<String, Object> data = mapOf(event.get("data"));
            String skill = (String) event.get("skill_id");
            switch (String.valueOf(event.get("kind"))) {
                case "SKILL_STARTED" -> {
                    StepBuilder step = byStepNo.computeIfAbsent(stepNo(data), n -> new StepBuilder(skill));
                    step.input = mapOf(data.get("input"));
                }
                case "SKILL_FINISHED" -> {
                    StepBuilder step = byStepNo.computeIfAbsent(stepNo(data), n -> new StepBuilder(skill));
                    step.status = (String) data.get("status");
                    step.output = mapOf(data.get("output"));
                }
                case "EXECUTION_ERROR" -> {
                    executionError = data;
                    executionError.putIfAbsent("skill_id", skill);
                }
                default -> {
                    // 선택·도구 호출 등 다른 기록은 채점에 쓰지 않는다
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
     * 실행 중 오류면 주문 Agent 가 그 skill 의 SKILL_FINISHED(status error)를 이미 남겼으므로 그 단계에 오류 문구를 붙인다.
     * 정적 검사에서 막혀 단계가 없으면 그 skill 의 error 단계를 덧붙인다. 어느 skill 인지 모르면(skill_id 없음) 단계를 만들지 않는다.
     */
    private static void attachError(List<StepBuilder> steps, Map<String, Object> error) {
        Object skill = error.get("skill_id");
        if (!(skill instanceof String failed)) {
            return;
        }
        StepBuilder target = steps.isEmpty() ? null : steps.getLast();
        if (target == null || !failed.equals(target.skill) || "ok".equals(target.status)) {
            target = new StepBuilder(failed);
            steps.add(target);
        }
        target.status = "error";
        target.error = errorText(error);
    }

    /** "&lt;category&gt;: &lt;message&gt; (빠진 값 &lt;missing_inputs&gt;)". 비어 있는 칸은 뺀다. */
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

    private static String joined(Object values) {
        if (!(values instanceof List<?> list)) {
            return "";
        }
        return list.stream().map(String::valueOf).collect(Collectors.joining(", "));
    }

    private static int stepNo(Map<String, Object> data) {
        if (data.get("step_no") instanceof Number n) {
            return n.intValue();
        }
        throw new IllegalStateException("주문 Agent trace 의 단계 기록에 step_no 가 없습니다: " + data);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> mapOf(Object value) {
        return value instanceof Map<?, ?> map ? new LinkedHashMap<>((Map<String, Object>) map) : new LinkedHashMap<>();
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> listOfMaps(Object value) {
        if (!(value instanceof List<?> list)) {
            return List.of();
        }
        return list.stream().filter(Map.class::isInstance).map(e -> (Map<String, Object>) e).toList();
    }

    /** 한 단계를 모으는 동안만 쓰는 칸. SKILL_FINISHED 가 없으면(기록이 중간에 끊김) 오류 단계로 본다. */
    private static final class StepBuilder {
        final String skill;
        Map<String, Object> input = new LinkedHashMap<>();
        Map<String, Object> output = new LinkedHashMap<>();
        String status;
        String error;

        StepBuilder(String skill) {
            this.skill = skill;
        }

        RunOutcome.Step build() {
            if (status == null) {
                return new RunOutcome.Step(skill, "error", input, output, skill + " 단계가 끝났다는 기록이 없습니다");
            }
            if ("error".equals(status) && error == null) {
                error = "주문 Agent 가 " + skill + " 단계를 오류로 기록했습니다";
            }
            return new RunOutcome.Step(skill, status, input, output, error);
        }
    }
}
