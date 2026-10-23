package com.capstone4.skillops.skill;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import com.capstone4.skillops.snapshot.DependencyGraph;

/** 진단용 그래프를 변경하지 않고 실행을 막는 flow 구조 오류를 모은다. */
public final class FlowStructureValidator {

    /** 주문 Agent의 OrderFlowPolicy와 같은 상한이며 공통 fixture 테스트가 두 값을 대조한다. */
    public static final int MAX_FLOW_STEPS = 12;

    /** 오류 대상 Skill, 실행기 오류 코드, 구조화한 사유와 설명이다. */
    public record Violation(String skill, String code, String reason, String message) {
    }

    /** 정적 검증만 제공하므로 객체 생성을 막는다. */
    private FlowStructureValidator() {
    }

    /** 단계 수·중복·읽기 실패를 모으며 불완전한 분석을 정상으로 판정하지 않는다. */
    public static List<Violation> validate(DependencyGraph graph) {
        List<Violation> errors = new ArrayList<>();
        if (graph.steps().isEmpty()) {
            errors.add(new Violation(graph.entrySkillId(), "INVALID_FLOW", "EMPTY_STEPS", "실행 단계가 비어 있습니다"));
        }
        if (graph.steps().size() > MAX_FLOW_STEPS) {
            errors.add(new Violation(graph.entrySkillId(), "INVALID_FLOW", "TOO_MANY_STEPS",
                    "실행 단계는 " + MAX_FLOW_STEPS + "개를 넘을 수 없습니다"));
        }
        Set<String> seen = new HashSet<>();
        Set<String> duplicates = new HashSet<>();
        for (String step : graph.steps()) {
            if (!seen.add(step) && duplicates.add(step)) {
                errors.add(new Violation(step, "INVALID_FLOW", "DUPLICATE_STEP", "같은 단계를 여러 번 실행할 수 없습니다"));
            }
        }
        List<Violation> unreadable = unreadableSteps(graph);
        errors.addAll(unreadable);
        List<String> nodeIds = graph.nodes().stream().map(DependencyGraph.Node::skillId).toList();
        if ((!graph.complete() && unreadable.isEmpty()) || !graph.steps().equals(nodeIds)) {
            errors.add(new Violation(graph.entrySkillId(), "INVALID_FLOW", "INCOMPLETE_ANALYSIS",
                    "모든 실행 단계의 선언을 확인하지 못했습니다"));
        }
        return List.copyOf(errors);
    }

    /** 같은 노드의 오류를 한 번씩 담고 실행기와 같은 오류 종류로 분류한다. */
    private static List<Violation> unreadableSteps(DependencyGraph graph) {
        List<Violation> errors = new ArrayList<>();
        Set<String> reported = new HashSet<>();
        for (DependencyGraph.Node node : graph.nodes()) {
            if (!reported.add(node.skillId())) {
                continue;
            }
            if (!SkillNames.isValid(node.skillId())) {
                errors.add(new Violation(node.skillId(), "INVALID_SKILL_NAME", "INVALID_SKILL_NAME",
                        "실행 단계의 Skill 이름 규칙에 맞지 않습니다"));
            } else if (DependencyGraph.Node.UNKNOWN_SKILL.equals(node.parseError())) {
                errors.add(new Violation(node.skillId(), "UNKNOWN_SKILL", "UNKNOWN_SKILL", "실행 단계의 SKILL.md가 없습니다"));
            } else if ("NESTED_FLOW_NOT_SUPPORTED".equals(node.parseError())) {
                errors.add(new Violation(node.skillId(), "CONTRACT_VIOLATION", "NESTED_FLOW_NOT_SUPPORTED",
                        "단계 안에 다른 flow를 중첩할 수 없습니다"));
            } else if (node.parseError() != null) {
                errors.add(new Violation(node.skillId(), "INVALID_SKILL_FORMAT", "UNREADABLE_SKILL",
                        "실행 단계의 선언을 읽을 수 없습니다: " + node.parseError()));
            }
        }
        return errors;
    }
}
