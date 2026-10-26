package com.capstone4.skillops.skill;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.yaml.snakeyaml.error.YAMLException;

import com.capstone4.skillops.snapshot.DependencyGraph;

/**
 * 진단용 그래프를 변경하지 않고 실행을 막는 flow 구조 오류를 모은다.
 * validate 는 그래프만 보고, ruleKeyErrors 는 단계 원문의 실행 규칙(rules) 조건 키가 inputs 에 있는지 본다.
 */
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

    /**
     * 읽을 수 있는 단계마다 실행 규칙(rules)의 조건 값 이름이 그 단계의 inputs 에 선언됐는지 본다. 선언되지 않은 값 이름이 있는 단계는
     * {code: CONTRACT_VIOLATION, reason: RULE_KEY_NOT_INPUT} 오류 하나를 실행 순서대로 낸다. 주문 Agent 의 SkillLoader.validateFlow 가
     * 업무 도구를 부르기 전에 같은 조건으로 거부한다(docs/plans/2026-10-26-behavior-edit-root-fix.md 2.2 절).
     * rules 형식이 틀린 단계는 metadata_errors 에서 이미 알리므로 여기서는 건너뛴다. 같은 단계는 한 번만 본다.
     * service/src/main/java/com/capstone4/skillops/workspace/WorkspaceService.java 의 apply 가 validate 결과 뒤에 이어 붙인다.
     *
     * @param texts skill 이름별 SKILL.md 원문이다. 그래프 노드의 원문을 여기서 찾는다
     */
    public static List<Violation> ruleKeyErrors(DependencyGraph graph, Map<String, String> texts) {
        List<Violation> errors = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (DependencyGraph.Node node : graph.nodes()) {
            if (node.parseError() != null || !seen.add(node.skillId()) || texts.get(node.skillId()) == null) {
                continue;
            }
            List<SkillRules.Rule> rules;
            try {
                rules = SkillRules.read(SkillFrontmatter.readForExecution(texts.get(node.skillId())).fields());
            } catch (IllegalArgumentException | YAMLException unreadable) {
                // 형식 오류는 SkillMetadataValidator 가 rules 또는 frontmatter 오류로 알린다.
                continue;
            }
            String key = SkillRules.undeclaredKey(rules, node.inputs());
            if (key != null) {
                errors.add(new Violation(node.skillId(), "CONTRACT_VIOLATION", "RULE_KEY_NOT_INPUT",
                        "실행 규칙의 조건 값 " + key + " 이 이 단계의 inputs 에 없습니다"));
            }
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
