package com.capstone4.skillops.snapshot;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.yaml.snakeyaml.error.YAMLException;

/**
 * flow 하나의 정적 연결 관계를 계산하는 순수 규칙이다. entry flow 의 steps 순서대로 단계 skill 의 frontmatter 선언
 * (inputs, outputs, depends_on)을 읽어 nodes, edges, missing(끊긴 연결), ambiguous(공급자가 여럿인 값), complete 를 만든다.
 * 주문 Agent 를 부르지 않고 넘겨받은 글만 본다.
 *
 * 연결 판단 규칙은 주문 Agent 의 정적 계약 검사
 * (order-agent/backend/src/main/java/com/capstone4/orderagent/harness/SkillLoader.java 의 validateFlow, validateInputs)와
 * 같다. steps 순서대로 inputs 가 주문 입력 키(ORDER_INPUT_KEYS)나 앞선 단계의 outputs 에 있는지, depends_on 의 skill 이
 * 이미 끝났는지 본다. 다른 점은 첫 위반에서 멈추지 않고 끝까지 계산한다는 것이다. 위반한 skill 도 실행됐다고 보고 이어
 * 가고, 읽지 못한 skill 도 끝난 것으로 본다. 주문 Agent 가 함께 검사하는 steps 중복, steps 개수 한도
 * (order-agent/backend/src/main/java/com/capstone4/orderagent/harness/OrderFlowPolicy.java 의 MAX_FLOW_STEPS), description,
 * version, owner, 본문 존재는 여기서 보지 않는다.
 *
 * 두 곳이 이 규칙을 쓴다. service/src/main/java/com/capstone4/skillops/snapshot/DependencyGraphService.java 가 MCP 도구
 * get_dependency_graph 의 결과를 만들 때 부르고,
 * service/src/main/java/com/capstone4/skillops/workspace/WorkspaceService.java 의 analyze 가 apply_patch 에서 수정을 적용한
 * 뒤 끊긴 연결(broken_links)과 읽을 수 없게 된 맨 위 칸을 찾을 때 부른다. 그래서 규칙을 바꾸면 두 도구의 결과가 함께 바뀐다.
 *
 * 튜닝 지점은 다음과 같다. 주문 입력 키는 ORDER_INPUT_KEYS, 단계 형식 규칙은 declaration 과 strings, 공급자 선택(가장 가까운
 * skill)과 모호성 기록은 FlowWalk.connect, 읽지 못한 skill 처리는 FlowWalk.unreadable, edges 묶음 순서는
 * FlowWalk.toGraph 에 있다.
 */
public final class DependencyAnalyzer {

    /**
     * 주문 자체가 처음부터 내놓는 컨텍스트 키다. 이 키를 받는 inputs 는 공급자 skill 이 없어도 missing 이 아니다.
     * order-agent/backend/src/main/java/com/capstone4/orderagent/harness/OrderFlowPolicy.java 의 INITIAL_INPUT_KEYS 와
     * 같아야 한다. 주문 Agent 의 SkillLoader.validateFlow 가 그 목록을 초기 available 로 쓰므로, 두 목록이 다르면 이 분석기와
     * 주문 Agent 가 같은 flow 를 다르게 판정한다. 지금은 다섯 키가 같은 순서로 같다. order.total 은 주문 Agent 가 엔트리
     * 선택과 첫 단계 전에 컨텍스트에서 빼므로(OrderFlowPolicy.HIDDEN_INITIAL_KEYS) 여기에도 넣지 않는다.
     * 같은 목록이 get_dependency_graph 설명 글(service/src/main/java/com/capstone4/skillops/mcp/DependencyGraphTools.java)과
     * frontend/src/utils/flowGraph.js 의 ORDER_KEYS 에도 있으므로 키를 바꾸면 네 곳을 함께 고친다. 모듈 사이에서 목록이
     * 같은지 확인하는 테스트는 없다.
     */
    static final List<String> ORDER_INPUT_KEYS = List.of(
            "order.id", "order.items", "order.customer", "order.payment_method", "order.shipping");

    /** 정적 메서드만 있는 도구 클래스라서 객체를 만들지 못하게 막는다. */
    private DependencyAnalyzer() {
    }

    /**
     * entry flow 의 steps 를 차례로 걸으며 연결 관계를 계산한다. 이웃한 두 단계 사이에 flow_order edge 를 긋고, 단계마다
     * FlowWalk.visit 로 선언을 검사한다. steps 에 같은 skill 이 두 번 있으면 두 번 계산한다.
     *
     * @param entrySkillId entry flow 의 skill ID 다.
     * @param sources      skill ID 를 키로, 그 SKILL.md 를 값으로 담는다. snapshot 에 파일이 없는 skill 은 넣지 않으며, 그런
     *                     단계는 UNKNOWN_SKILL 노드가 된다.
     * @throws OrderAgentException entry 가 sources 에 없으면 SKILL_NOT_FOUND, entry 의 steps 를 읽지 못하면 INVALID_FLOW 다.
     */
    public static DependencyGraph analyze(String entrySkillId, Map<String, SkillSource> sources) {
        List<String> steps = flowSteps(entrySkillId, sources.get(entrySkillId));
        FlowWalk walk = new FlowWalk();
        for (int i = 0; i < steps.size(); i++) {
            String id = steps.get(i);
            if (i > 0) {
                walk.flowOrder(steps.get(i - 1), id);
            }
            walk.visit(id, sources.get(id));
        }
        return walk.toGraph(entrySkillId, steps);
    }

    /**
     * entry flow 의 frontmatter 에서 steps 를 적힌 순서대로 읽는다. entry 는 name 을 검사하지 않고 steps 만 본다.
     * analyze 가 계산을 시작할 때 부르고, DependencyGraphService 가 어떤 단계 파일을 받아 올지 정할 때도 부른다.
     * entry 가 없으면 SKILL_NOT_FOUND 를, YAML 이 깨졌거나 steps 가 없거나 비었거나 문자열 목록이 아니면 INVALID_FLOW 를
     * 던진다. 분석기는 snapshot 을 받지 않으므로 두 오류 모두 snapshot_id 가 null 이다.
     */
    static List<String> flowSteps(String entrySkillId, SkillSource entry) {
        if (entry == null) {
            throw SkillPaths.notInSnapshot(entrySkillId, null);
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

    /** 단계 skill 하나의 선언이다. inputs, outputs, depends_on 을 frontmatter 에 적힌 순서대로 담는다. */
    private record Declaration(List<String> inputs, List<String> outputs, List<String> dependsOn) {
    }

    /**
     * 단계 skill 의 frontmatter 를 읽어 선언을 만든다. 주문 Agent SkillLoader 와 같이 frontmatter 가 있는지, name 이 폴더
     * 이름과 같은지, 중첩 flow 가 아닌지(steps 가 없어야 한다), inputs, outputs, depends_on 이 문자열 목록인지 확인한다.
     * 어긋나면 주문 Agent 가 같은 경우에 쓰는 이름(INVALID_SKILL_FORMAT, NESTED_FLOW_NOT_SUPPORTED)으로 시작하는
     * IllegalArgumentException 을, YAML 이 깨지면 YAMLException 을 던진다. FlowWalk.visit 가 두 예외를 잡아 그 노드의
     * parse_error 로 남긴다.
     */
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

    /**
     * frontmatter 의 key 값을 문자열 목록으로 읽는다. required 가 false 이고 값이 없으면 빈 목록을 돌려준다.
     * 값이 목록이 아니거나, 문자열이 아닌 항목이나 공백뿐인 항목이 있으면 INVALID_SKILL_FORMAT 으로 시작하는
     * IllegalArgumentException 을 던진다. 주문 Agent SkillLoader 의 strings 와 같은 규칙이다.
     */
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

    /**
     * steps 를 차례로 걸으며 nodes, edges, missing, ambiguous 를 쌓는 계산 상태다. analyze 한 번마다 새로 만들어 한 번만 쓴다.
     * edges 는 종류별 목록 세 개에 따로 모았다가 toGraph 에서 정해진 순서로 잇는다.
     */
    private static final class FlowWalk {

        /** 공급자 없이도 받을 수 있는 주문 입력 키(ORDER_INPUT_KEYS)의 집합이다. */
        private final Set<String> orderKeys = new HashSet<>(ORDER_INPUT_KEYS);
        /** 값 이름을 키로, 그 값을 outputs 로 내놓은 skill 들을 steps 순서대로 담는다. 마지막 skill 이 가장 가까운 공급자다. */
        private final Map<String, List<String>> providers = new LinkedHashMap<>();
        /** 이미 지나가서 실행됐다고 보는 skill 이다. depends_on 선행 검사에 쓰며 읽지 못한 skill 도 들어간다. */
        private final Set<String> done = new HashSet<>();
        /** steps 순서대로 쌓는 노드다. 같은 skill 이 steps 에 두 번 있으면 노드도 두 개다. */
        private final List<DependencyGraph.Node> nodes = new ArrayList<>();
        // 종류별 edge 목록이다. toGraph 가 flowEdges, dependsOnEdges, ioEdges 순서로 이어 붙인다.
        private final List<DependencyGraph.Edge> flowEdges = new ArrayList<>();
        private final List<DependencyGraph.Edge> dependsOnEdges = new ArrayList<>();
        private final List<DependencyGraph.Edge> ioEdges = new ArrayList<>();
        // 끊긴 연결과 공급자가 여럿인 값이다. 둘 다 단계를 지나는 순서대로 쌓인다.
        private final List<DependencyGraph.Missing> missing = new ArrayList<>();
        private final List<DependencyGraph.Ambiguous> ambiguous = new ArrayList<>();
        /** 읽지 못한 skill 이 하나라도 있으면 false 가 된다. 그때 missing 과 edges 도 불완전하다. */
        private boolean complete = true;

        /** 앞 단계 from 에서 다음 단계 to 로 가는 flow_order edge 를 더한다. analyze 가 이웃한 두 단계마다 부른다. */
        void flowOrder(String from, String to) {
            flowEdges.add(new DependencyGraph.Edge(DependencyGraph.Edge.FLOW_ORDER, from, to, List.of()));
        }

        /**
         * 단계 하나의 선언을 읽어 연결을 검사하고 끝난 것으로 표시한다. source 가 없으면(snapshot 에 SKILL.md 가 없으면)
         * parse_error 가 UNKNOWN_SKILL 인 노드를, 선언을 읽지 못하면 그 이유를 parse_error 로 담은 노드를 unreadable 로 남긴다.
         * 읽은 선언은 connect 로 검사한 뒤 노드로 쌓고 finish 로 outputs 를 공급자로 등록한다.
         */
        void visit(String id, SkillSource source) {
            if (source == null) {
                unreadable(new DependencyGraph.Node(id, null, null, List.of(), List.of(), List.of(),
                        DependencyGraph.Node.UNKNOWN_SKILL));
                return;
            }
            Declaration d;
            try {
                d = declaration(id, source.text());
            } catch (YAMLException | IllegalArgumentException e) {
                unreadable(new DependencyGraph.Node(id, source.path(), source.fileHash(), List.of(), List.of(), List.of(),
                        e.getMessage()));
                return;
            }
            connect(id, d);
            nodes.add(new DependencyGraph.Node(id, source.path(), source.fileHash(), d.inputs(), d.outputs(), d.dependsOn(), null));
            finish(id, d.outputs());
        }

        /**
         * 읽지 못한 skill 의 노드를 남기고 complete 를 false 로 바꾼다. 뒤 단계의 depends_on 검사에서는 끝난 것으로 보지만,
         * 선언을 모르므로 outputs 는 공급자로 등록하지 않는다. 그래서 그 값을 받는 뒤 단계에는 missing_inputs 가 생긴다.
         */
        private void unreadable(DependencyGraph.Node node) {
            nodes.add(node);
            complete = false;
            done.add(node.skillId());
        }

        /**
         * inputs 의 공급자와 depends_on 의 선행 여부를 검사해 ambiguous, missing, io edge, depends_on edge 를 쌓는다.
         * 공급자가 없는 input 은 주문 입력 키가 아니면 missing_inputs 가 된다. 공급자가 여럿이면 io edge 는 가장 가까운(마지막)
         * 공급자에서 긋고 공급자 전체를 ambiguous 에 남긴다. 같은 공급자에서 오는 키는 io edge 하나로 묶는다.
         * 선행 검사는 이 skill 을 끝내기 전에 하므로 자기 자신을 depends_on 하면 missing_dependencies 가 된다.
         * depends_on edge 는 선행 skill 이 steps 에 없거나 뒤에 있어도 그대로 긋는다.
         */
        private void connect(String id, Declaration d) {
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
                // 공급자 선택 규칙이다. 가장 가까운(마지막) 공급자에서 io edge 를 긋는다.
                keysByProvider.computeIfAbsent(from.get(from.size() - 1), k -> new ArrayList<>()).add(key);
                if (from.size() > 1) {
                    ambiguous.add(new DependencyGraph.Ambiguous(id, key, List.copyOf(from)));
                }
            }
            List<String> missingDeps = d.dependsOn().stream().filter(dep -> !done.contains(dep)).toList();
            if (!missingInputs.isEmpty() || !missingDeps.isEmpty()) {
                missing.add(new DependencyGraph.Missing(id, List.copyOf(missingInputs), missingDeps));
            }
            keysByProvider.forEach((from, keys) ->
                    ioEdges.add(new DependencyGraph.Edge(DependencyGraph.Edge.IO, from, id, List.copyOf(keys))));
            d.dependsOn().forEach(dep ->
                    dependsOnEdges.add(new DependencyGraph.Edge(DependencyGraph.Edge.DEPENDS_ON, dep, id, List.of())));
        }

        /** 이 skill 을 끝난 것으로 표시하고, outputs 의 값마다 이 skill 을 공급자 목록 끝에 덧붙인다. */
        private void finish(String id, List<String> outputs) {
            done.add(id);
            for (String key : outputs) {
                providers.computeIfAbsent(key, k -> new ArrayList<>()).add(id);
            }
        }

        /**
         * 쌓은 상태로 결과 DependencyGraph 를 만든다. edges 는 flow_order 전체, depends_on 전체, io 전체 순서로 잇는다.
         * 이 순서가 get_dependency_graph 결과의 edges 순서가 되며,
         * service/src/test/java/com/capstone4/skillops/snapshot/DependencyAnalyzerTest.java 가 이 순서를 고정한다.
         */
        DependencyGraph toGraph(String entrySkillId, List<String> steps) {
            List<DependencyGraph.Edge> edges = new ArrayList<>(flowEdges);
            edges.addAll(dependsOnEdges);
            edges.addAll(ioEdges);
            return new DependencyGraph(entrySkillId, steps, nodes, edges, missing, ambiguous, complete);
        }
    }
}
