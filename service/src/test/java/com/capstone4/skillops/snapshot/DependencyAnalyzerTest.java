package com.capstone4.skillops.snapshot;

import static com.capstone4.skillops.snapshot.SnapshotTestSupport.assertFailure;
import static com.capstone4.skillops.snapshot.SnapshotTestSupport.failure;
import static com.capstone4.skillops.snapshot.SnapshotTestSupport.flowSkill;
import static com.capstone4.skillops.snapshot.SnapshotTestSupport.liveSources;
import static com.capstone4.skillops.snapshot.SnapshotTestSupport.source;
import static com.capstone4.skillops.snapshot.SnapshotTestSupport.stepSkill;
import static org.assertj.core.api.Assertions.assertThat;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

/**
 * service/src/main/java/com/capstone4/skillops/snapshot/DependencyAnalyzer.java 의 정적 연결 계산 규칙을 보호한다.
 * get_dependency_graph 와 apply_patch 의 끊긴 연결 검사가 같은 규칙을 쓰므로 두 도구의 결과를 함께 지킨다.
 * 운영 skill 원문(저장소 루트의 skills/)으로 결과 전체를 고정하고, 주문 입력 키, 빠진 입력과 선행 skill, 공급자가 여럿인 값,
 * 읽지 못한 skill, 형식 오류 문구, entry 오류를 작은 flow 로 하나씩 확인한다. edges 는 flow_order 전체, depends_on 전체,
 * io 전체 순서다.
 */
class DependencyAnalyzerTest {

    /** 운영 order-flow 의 steps 순서다. */
    static final List<String> LIVE_STEPS = List.of("order-intake", "inventory-check", "payment", "shipping-reserve", "notify");

    @Test
    void liveFlowIsCompleteWithSixteenEdgesInGroupOrder() {
        DependencyGraph g = DependencyAnalyzer.analyze("order-flow", liveSources());

        assertThat(g.entrySkillId()).isEqualTo("order-flow");
        assertThat(g.steps()).containsExactlyElementsOf(LIVE_STEPS);
        assertThat(g.complete()).isTrue();
        assertThat(g.missing()).isEmpty();
        assertThat(g.ambiguous()).isEmpty();
        assertThat(g.edges()).containsExactly(
                edge("flow_order", "order-intake", "inventory-check"),
                edge("flow_order", "inventory-check", "payment"),
                edge("flow_order", "payment", "shipping-reserve"),
                edge("flow_order", "shipping-reserve", "notify"),
                edge("depends_on", "order-intake", "inventory-check"),
                edge("depends_on", "inventory-check", "payment"),
                edge("depends_on", "payment", "shipping-reserve"),
                edge("depends_on", "shipping-reserve", "notify"),
                io("order-intake", "inventory-check", "order.id"),
                io("order-intake", "payment", "order.id", "order.total"),
                io("inventory-check", "payment", "inventory.reserved"),
                io("order-intake", "shipping-reserve", "order.id"),
                io("payment", "shipping-reserve", "payment.id"),
                io("order-intake", "notify", "order.id"),
                io("payment", "notify", "payment.status"),
                io("shipping-reserve", "notify", "shipping.id", "shipping.eta"));
    }

    @Test
    void liveNodesCarrySourcePathHashAndDeclaration() {
        List<DependencyGraph.Node> nodes = DependencyAnalyzer.analyze("order-flow", liveSources()).nodes();

        assertThat(nodes).extracting(DependencyGraph.Node::skillId).containsExactlyElementsOf(LIVE_STEPS);
        assertThat(nodes.get(0)).isEqualTo(new DependencyGraph.Node("order-intake", "skills/order-intake/SKILL.md",
                "hash-order-intake", List.of("order.customer", "order.items", "order.payment_method", "order.shipping"),
                List.of("order.id", "order.total"), List.of(), null));
        assertThat(nodes.get(2)).isEqualTo(new DependencyGraph.Node("payment", "skills/payment/SKILL.md", "hash-payment",
                List.of("order.id", "order.total", "order.payment_method", "inventory.reserved"),
                List.of("payment.id", "payment.status"), List.of("inventory-check"), null));
    }

    /**
     * 장애 F1 의 broken 글에서는 payment 가 받는 inventory.reserved 의 공급자가 없어져 missing 하나만 생긴다. 파일은 모두
     * 읽었으므로 complete 는 true 다.
     */
    @Test
    void f1BrokenMissesInventoryReservedButStaysComplete() {
        Map<String, SkillSource> sources = liveSources();
        sources.put("inventory-check", source("inventory-check", SnapshotTestSupport.f1Broken()));

        DependencyGraph g = DependencyAnalyzer.analyze("order-flow", sources);

        assertThat(g.complete()).isTrue();
        assertThat(g.missing()).containsExactly(new DependencyGraph.Missing("payment", List.of("inventory.reserved"), List.of()));
        assertThat(g.edges()).doesNotContain(io("inventory-check", "payment", "inventory.reserved"));
        assertThat(g.edges()).filteredOn(e -> e.type().equals("io")).hasSize(7);
    }

    /** 같은 값을 여럿이 내놓으면 가장 가까운(마지막) skill 에서 io 를 긋고, 공급자 전체를 steps 순서로 남긴다. */
    @Test
    void secondProviderMakesAmbiguityAndTakesTheIoEdge() {
        Map<String, SkillSource> sources = liveSources();
        sources.put("payment", source("payment", SnapshotTestSupport.liveText("payment")
                .replace("outputs:    [payment.id, payment.status]", "outputs:    [payment.id, payment.status, order.id]")));

        DependencyGraph g = DependencyAnalyzer.analyze("order-flow", sources);

        assertThat(g.complete()).isTrue();
        assertThat(g.missing()).isEmpty();
        assertThat(g.ambiguous()).containsExactly(
                new DependencyGraph.Ambiguous("shipping-reserve", "order.id", List.of("order-intake", "payment")),
                new DependencyGraph.Ambiguous("notify", "order.id", List.of("order-intake", "payment")));
        assertThat(g.edges()).filteredOn(e -> e.type().equals("io")).containsExactly(
                io("order-intake", "inventory-check", "order.id"),
                io("order-intake", "payment", "order.id", "order.total"),
                io("inventory-check", "payment", "inventory.reserved"),
                io("payment", "shipping-reserve", "order.id", "payment.id"),
                io("payment", "notify", "order.id", "payment.status"),
                io("shipping-reserve", "notify", "shipping.id", "shipping.eta"));
    }

    @Test
    void orderInputKeysNeedNoProvider() {
        Map<String, SkillSource> sources = sources(flowSkill("f", "a"),
                "a", stepSkill("a", "order.id, order.items, order.customer, order.payment_method, order.shipping", "", ""));

        DependencyGraph g = DependencyAnalyzer.analyze("f", sources);

        assertThat(g.missing()).isEmpty();
        assertThat(g.edges()).isEmpty();
        assertThat(g.complete()).isTrue();
    }

    @Test
    void unprovidedInputIsMissing() {
        Map<String, SkillSource> sources = sources(flowSkill("f", "a"), "a", stepSkill("a", "order.total, x.y", "", ""));

        assertThat(DependencyAnalyzer.analyze("f", sources).missing())
                .containsExactly(new DependencyGraph.Missing("a", List.of("order.total", "x.y"), List.of()));
    }

    /** 뒤 단계나 steps 에 없는 skill 을 depends_on 하면 missing_dependencies 다. depends_on edge 는 그대로 긋는다. */
    @Test
    void dependencyOnLaterOrUnknownSkillIsMissing() {
        Map<String, SkillSource> sources = sources(flowSkill("f", "a, b"),
                "a", stepSkill("a", "", "k", "b, nowhere"),
                "b", stepSkill("b", "k", "", "a"));

        DependencyGraph g = DependencyAnalyzer.analyze("f", sources);

        assertThat(g.missing()).containsExactly(new DependencyGraph.Missing("a", List.of(), List.of("b", "nowhere")));
        assertThat(g.edges()).containsExactly(
                edge("flow_order", "a", "b"),
                edge("depends_on", "b", "a"),
                edge("depends_on", "nowhere", "a"),
                edge("depends_on", "a", "b"),
                io("a", "b", "k"));
        assertThat(g.complete()).isTrue();
    }

    @Test
    void selfDependencyIsMissing() {
        Map<String, SkillSource> sources = sources(flowSkill("f", "a"), "a", stepSkill("a", "", "", "a"));

        assertThat(DependencyAnalyzer.analyze("f", sources).missing())
                .containsExactly(new DependencyGraph.Missing("a", List.of(), List.of("a")));
    }

    /**
     * 파일이 없는 단계는 UNKNOWN_SKILL 노드가 되고 complete=false 다. 끝난 것으로 보므로 그 skill 을 depends_on 하는
     * 뒤 단계는 missing 이 아니지만, 그 outputs 는 알 수 없으므로 그 값을 받는 뒤 단계는 missing_inputs 가 생긴다.
     */
    @Test
    void unknownSkillIsDoneButProvidesNothing() {
        Map<String, SkillSource> sources = sources(flowSkill("f", "a, ghost, c"),
                "a", stepSkill("a", "", "k", ""),
                "c", stepSkill("c", "k, g.out", "", "ghost"));

        DependencyGraph g = DependencyAnalyzer.analyze("f", sources);

        assertThat(g.complete()).isFalse();
        assertThat(g.nodes()).extracting(DependencyGraph.Node::skillId).containsExactly("a", "ghost", "c");
        assertThat(g.nodes().get(1)).isEqualTo(
                new DependencyGraph.Node("ghost", null, null, List.of(), List.of(), List.of(), "UNKNOWN_SKILL"));
        assertThat(g.missing()).containsExactly(new DependencyGraph.Missing("c", List.of("g.out"), List.of()));
        assertThat(g.edges()).containsExactly(
                edge("flow_order", "a", "ghost"),
                edge("flow_order", "ghost", "c"),
                edge("depends_on", "ghost", "c"),
                io("a", "c", "k"));
    }

    @Test
    void nameMismatchIsAParseErrorNode() {
        Map<String, SkillSource> sources = sources(flowSkill("f", "a, b"),
                "a", stepSkill("other", "", "k", ""),
                "b", stepSkill("b", "", "", "a"));

        DependencyGraph g = DependencyAnalyzer.analyze("f", sources);

        assertThat(g.complete()).isFalse();
        assertThat(g.nodes().get(0)).isEqualTo(new DependencyGraph.Node("a", "skills/a/SKILL.md", "hash-a", List.of(),
                List.of(), List.of(), "INVALID_SKILL_FORMAT: name 이 폴더 이름(a)과 다릅니다."));
        // 읽지 못한 skill 도 끝난 것으로 보므로 a 를 depends_on 하는 b 는 missing 이 아니다.
        assertThat(g.missing()).isEmpty();
    }

    @Test
    void stepWithStepsIsNestedFlow() {
        Map<String, SkillSource> sources = sources(flowSkill("f", "a"), "a", flowSkill("a", "b"));

        DependencyGraph.Node node = DependencyAnalyzer.analyze("f", sources).nodes().get(0);

        assertThat(node.parseError()).isEqualTo("NESTED_FLOW_NOT_SUPPORTED");
    }

    @Test
    void formatErrorsUseTheContractMessages() {
        assertThat(parseErrorOf("# frontmatter 없음\n")).isEqualTo("INVALID_SKILL_FORMAT: frontmatter 가 없습니다.");
        assertThat(parseErrorOf("---\nname: a\noutputs: []\ndepends_on: []\n---\n"))
                .isEqualTo("INVALID_SKILL_FORMAT: inputs 는 문자열 목록이어야 합니다.");
        assertThat(parseErrorOf("---\nname: a\ninputs: x\noutputs: []\ndepends_on: []\n---\n"))
                .isEqualTo("INVALID_SKILL_FORMAT: inputs 는 문자열 목록이어야 합니다.");
        assertThat(parseErrorOf("---\nname: a\ninputs: []\noutputs: [\" \"]\ndepends_on: []\n---\n"))
                .isEqualTo("INVALID_SKILL_FORMAT: outputs 는 문자열 목록이어야 합니다.");
        assertThat(parseErrorOf("---\nname: a\ninputs: []\noutputs: []\ndepends_on: [1]\n---\n"))
                .isEqualTo("INVALID_SKILL_FORMAT: depends_on 는 문자열 목록이어야 합니다.");
        assertThat(parseErrorOf("---\nname: a\ninputs: []\noutputs: []\ndepends_on: []\nsteps: x\n---\n"))
                .isEqualTo("INVALID_SKILL_FORMAT: steps 는 문자열 목록이어야 합니다.");
        assertThat(parseErrorOf("---\nname: a\ninputs: [x\n---\n")).isNotBlank();
    }

    @Test
    void parseErrorNodeDoesNotProvideItsOutputs() {
        Map<String, SkillSource> sources = sources(flowSkill("f", "a, b"),
                "a", "---\nname: a\ninputs: x\noutputs: [k]\ndepends_on: []\n---\n",
                "b", stepSkill("b", "k", "", ""));

        DependencyGraph g = DependencyAnalyzer.analyze("f", sources);

        assertThat(g.missing()).containsExactly(new DependencyGraph.Missing("b", List.of("k"), List.of()));
        assertThat(g.edges()).containsExactly(edge("flow_order", "a", "b"));
    }

    @Test
    void missingEntryIsSkillNotFoundWithoutSnapshotId() {
        assertFailure(failure(() -> DependencyAnalyzer.analyze("order-flow", Map.of())),
                "SKILL_NOT_FOUND", "snapshot 에 skills/order-flow/SKILL.md 가 없습니다.", false, null, null);
    }

    @Test
    void entryWithoutStepsIsInvalidFlow() {
        assertFailure(failure(() -> DependencyAnalyzer.analyze("order-flow",
                        Map.of("order-flow", source("order-flow", "---\nname: order-flow\n---\n")))),
                "INVALID_FLOW", "entry flow 를 읽을 수 없습니다: order-flow 에 steps 가 없습니다.", false, null, null);
        assertFailure(failure(() -> DependencyAnalyzer.analyze("order-flow",
                        Map.of("order-flow", source("order-flow", flowSkill("order-flow", ""))))),
                "INVALID_FLOW", "entry flow 를 읽을 수 없습니다: order-flow 에 steps 가 없습니다.", false, null, null);
    }

    @Test
    void entryWithBadStepsIsInvalidFlow() {
        assertFailure(failure(() -> DependencyAnalyzer.analyze("order-flow",
                        Map.of("order-flow", source("order-flow", "---\nname: order-flow\nsteps: a\n---\n")))),
                "INVALID_FLOW", "entry flow 를 읽을 수 없습니다: INVALID_SKILL_FORMAT: steps 는 문자열 목록이어야 합니다.",
                false, null, null);
        OrderAgentException yaml = failure(() -> DependencyAnalyzer.analyze("order-flow",
                Map.of("order-flow", source("order-flow", "---\nsteps: [a\n---\n"))));
        assertThat(yaml.code()).isEqualTo("INVALID_FLOW");
        assertThat(yaml.getMessage()).startsWith("entry flow 를 읽을 수 없습니다: ");
        assertThat(yaml.snapshotId()).isNull();
    }

    /** entry 는 name 검사를 하지 않고 steps 만 읽는다. */
    @Test
    void flowStepsReadsOnlyTheEntrySteps() {
        assertThat(DependencyAnalyzer.flowSteps("order-flow", source("order-flow", flowSkill("anything", "b, a, b"))))
                .containsExactly("b", "a", "b");
    }

    /** steps 에 같은 skill 이 두 번 있으면 두 번 계산한다(서비스는 조회만 한 번 한다). */
    @Test
    void repeatedStepIsVisitedTwice() {
        Map<String, SkillSource> sources = sources(flowSkill("f", "a, a"), "a", stepSkill("a", "", "k", ""));

        DependencyGraph g = DependencyAnalyzer.analyze("f", sources);

        assertThat(g.nodes()).extracting(DependencyGraph.Node::skillId).containsExactly("a", "a");
        assertThat(g.edges()).containsExactly(edge("flow_order", "a", "a"));
    }

    /** entry f 와 단계 skill 들을 skill ID 를 키로 하는 분석기 입력으로 만든다. 단계는 id 와 글을 번갈아 넘긴다. */
    private static Map<String, SkillSource> sources(String entryText, String... steps) {
        Map<String, SkillSource> sources = new LinkedHashMap<>();
        sources.put("f", source("f", entryText));
        for (int i = 0; i < steps.length; i += 2) {
            sources.put(steps[i], source(steps[i], steps[i + 1]));
        }
        return sources;
    }

    /** 단계 a 하나만 있는 flow 에서 text 를 a 의 SKILL.md 로 넣었을 때 a 노드의 parse_error 를 돌려준다. */
    private static String parseErrorOf(String text) {
        return DependencyAnalyzer.analyze("f", sources(flowSkill("f", "a"), "a", text)).nodes().get(0).parseError();
    }

    /** keys 가 빈 edge 를 만든다. flow_order 와 depends_on edge 를 기대값으로 쓸 때 부른다. */
    private static DependencyGraph.Edge edge(String type, String from, String to) {
        return new DependencyGraph.Edge(type, from, to, List.of());
    }

    /** from 에서 to 로 keys 를 넘기는 io edge 를 만든다. */
    private static DependencyGraph.Edge io(String from, String to, String... keys) {
        return new DependencyGraph.Edge("io", from, to, List.of(keys));
    }
}
