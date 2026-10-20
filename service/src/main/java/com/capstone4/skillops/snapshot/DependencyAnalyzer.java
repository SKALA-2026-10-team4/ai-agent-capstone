package com.capstone4.skillops.snapshot;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.yaml.snakeyaml.error.YAMLException;

/**
 * flow 의 정적 연결 관계를 계산한다. 판단 규칙은 주문 Agent SkillLoader.validateFlow 와 같다.
 * steps 순서대로 inputs 가 주문 입력 키나 앞선 outputs 에 있는지, depends_on 이 이미 끝났는지 본다.
 * 다른 점은 첫 위반에서 멈추지 않고 끝까지 계산한다는 것이다(위반한 skill 도 실행됐다고 보고 이어 간다).
 */
public final class DependencyAnalyzer {

    /** 주문 입력에서 오는 키. 주문 Agent SkillLoader.validateFlow 의 초기 available 과 같아야 한다. */
    static final List<String> ORDER_INPUT_KEYS = List.of(
            "order.id", "order.items", "order.customer", "order.payment_method", "order.shipping");

    private DependencyAnalyzer() {
    }

    /** @param sources skill ID → SKILL.md. snapshot 에 파일이 없는 skill 은 넣지 않는다 */
    public static DependencyGraph analyze(String entrySkillId, Map<String, SkillSource> sources) {
        List<String> steps = flowSteps(entrySkillId, sources.get(entrySkillId));

        Set<String> orderKeys = new HashSet<>(ORDER_INPUT_KEYS);
        Map<String, List<String>> providers = new LinkedHashMap<>();
        Set<String> done = new HashSet<>();
        List<DependencyGraph.Node> nodes = new ArrayList<>();
        List<DependencyGraph.Edge> flowEdges = new ArrayList<>();
        List<DependencyGraph.Edge> dependsOnEdges = new ArrayList<>();
        List<DependencyGraph.Edge> ioEdges = new ArrayList<>();
        List<DependencyGraph.Missing> missing = new ArrayList<>();
        List<DependencyGraph.Ambiguous> ambiguous = new ArrayList<>();
        boolean complete = true;

        for (int i = 0; i < steps.size(); i++) {
            String id = steps.get(i);
            if (i > 0) {
                flowEdges.add(new DependencyGraph.Edge("flow_order", steps.get(i - 1), id, List.of()));
            }
            SkillSource source = sources.get(id);
            if (source == null) {
                nodes.add(new DependencyGraph.Node(id, null, null, List.of(), List.of(), List.of(), "UNKNOWN_SKILL"));
                complete = false;
                done.add(id);
                continue;
            }
            Declaration d;
            try {
                d = declaration(id, source.text());
            } catch (YAMLException | IllegalArgumentException e) {
                nodes.add(new DependencyGraph.Node(id, source.path(), source.fileHash(), List.of(), List.of(), List.of(),
                        e.getMessage()));
                complete = false;
                done.add(id);
                continue;
            }

            List<String> missingInputs = new ArrayList<>();
            Map<String, List<String>> keysByProvider = new LinkedHashMap<>();
            for (String key : d.inputs()) {
                List<String> from = providers.getOrDefault(key, List.of());
                if (from.isEmpty()) {
                    if (!orderKeys.contains(key)) {
                        missingInputs.add(key);
                    }
                    continue;
                }
                keysByProvider.computeIfAbsent(from.get(from.size() - 1), k -> new ArrayList<>()).add(key);
                if (from.size() > 1) {
                    ambiguous.add(new DependencyGraph.Ambiguous(id, key, List.copyOf(from)));
                }
            }
            List<String> missingDeps = d.dependsOn().stream().filter(dep -> !done.contains(dep)).toList();
            if (!missingInputs.isEmpty() || !missingDeps.isEmpty()) {
                missing.add(new DependencyGraph.Missing(id, List.copyOf(missingInputs), missingDeps));
            }
            keysByProvider.forEach((from, keys) -> ioEdges.add(new DependencyGraph.Edge("io", from, id, List.copyOf(keys))));
            d.dependsOn().forEach(dep -> dependsOnEdges.add(new DependencyGraph.Edge("depends_on", dep, id, List.of())));

            nodes.add(new DependencyGraph.Node(id, source.path(), source.fileHash(), d.inputs(), d.outputs(), d.dependsOn(), null));
            done.add(id);
            for (String key : d.outputs()) {
                providers.computeIfAbsent(key, k -> new ArrayList<>()).add(id);
            }
        }

        List<DependencyGraph.Edge> edges = new ArrayList<>(flowEdges);
        edges.addAll(dependsOnEdges);
        edges.addAll(ioEdges);
        return new DependencyGraph(entrySkillId, steps, nodes, edges, missing, ambiguous, complete);
    }

    /** entry flow 의 steps. 서비스가 어떤 파일을 받아 올지 정할 때도 쓴다. */
    static List<String> flowSteps(String entrySkillId, SkillSource entry) {
        if (entry == null) {
            throw new OrderAgentException("SKILL_NOT_FOUND", "snapshot 에 skills/" + entrySkillId + "/SKILL.md 가 없습니다.",
                    false, null, null);
        }
        try {
            List<String> steps = strings(Frontmatter.parse(entry.text()), "steps", false);
            if (steps.isEmpty()) {
                throw new IllegalArgumentException(entrySkillId + " 에 steps 가 없습니다.");
            }
            return steps;
        } catch (YAMLException | IllegalArgumentException e) {
            throw new OrderAgentException("INVALID_FLOW", "entry flow 를 읽을 수 없습니다: " + e.getMessage(), false, null, null);
        }
    }

    private record Declaration(List<String> inputs, List<String> outputs, List<String> dependsOn) {
    }

    /** 단계 skill 의 선언. 주문 Agent 와 같이 name 일치, 중첩 flow 금지, 목록 키 필수를 확인한다. */
    private static Declaration declaration(String id, String text) {
        Map<String, Object> fm = Frontmatter.parse(text);
        if (fm.isEmpty()) {
            throw new IllegalArgumentException("INVALID_SKILL_FORMAT: frontmatter 가 없습니다.");
        }
        if (!id.equals(fm.get("name"))) {
            throw new IllegalArgumentException("INVALID_SKILL_FORMAT: name 이 폴더 이름(" + id + ")과 다릅니다.");
        }
        if (!strings(fm, "steps", false).isEmpty()) {
            throw new IllegalArgumentException("NESTED_FLOW_NOT_SUPPORTED");
        }
        return new Declaration(strings(fm, "inputs", true), strings(fm, "outputs", true), strings(fm, "depends_on", true));
    }

    private static List<String> strings(Map<String, Object> fm, String key, boolean required) {
        Object value = fm.get(key);
        if (value == null && !required) {
            return List.of();
        }
        if (!(value instanceof List<?> list) || list.stream().anyMatch(v -> !(v instanceof String s) || s.isBlank())) {
            throw new IllegalArgumentException("INVALID_SKILL_FORMAT: " + key + " 는 문자열 목록이어야 합니다.");
        }
        return list.stream().map(Object::toString).toList();
    }
}
