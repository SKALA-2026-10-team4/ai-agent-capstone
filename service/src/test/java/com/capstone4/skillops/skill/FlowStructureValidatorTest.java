package com.capstone4.skillops.skill;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import com.capstone4.skillops.snapshot.DependencyAnalyzer;
import com.capstone4.skillops.snapshot.DependencyGraph;
import com.capstone4.skillops.snapshot.OrderAgentException;
import com.capstone4.skillops.snapshot.SkillSource;

import tools.jackson.databind.json.JsonMapper;

/** 실제 주문 Agent와 공통 입력을 사용해 flow 구조 검사 경계를 고정한다. */
class FlowStructureValidatorTest {

    record Case(String id, Map<String, String> skills, List<String> reasons, String code) {
    }

    record Contract(int maxSteps, List<Case> cases) {
    }

    /** 그래프 분석과 실행 구조 검사를 대조하되 빈 entry의 기존 거부 정책을 보존한다. */
    @ParameterizedTest(name = "{index}: {0}")
    @MethodSource("cases")
    void matchesSharedExecutionStructure(Case testCase) {
        var sources = new TreeMap<String, SkillSource>();
        testCase.skills().forEach((id, text) -> sources.put(id, SkillSource.fromText("skills/" + id + "/SKILL.md", text)));
        if (testCase.id().equals("empty-steps")) {
            assertThatThrownBy(() -> DependencyAnalyzer.analyze("order-flow", sources))
                    .isInstanceOfSatisfying(OrderAgentException.class, error -> assertThat(error.code()).isEqualTo("INVALID_FLOW"));
            return;
        }

        DependencyGraph graph = DependencyAnalyzer.analyze("order-flow", sources);
        var errors = FlowStructureValidator.validate(graph);

        assertThat(errors).extracting(FlowStructureValidator.Violation::reason)
                .as(testCase.id()).containsExactlyElementsOf(testCase.reasons());
        if (testCase.code() != null) {
            assertThat(errors.getFirst().code()).as(testCase.id()).isEqualTo(testCase.code());
        }
    }

    /** 두 서버가 공통 계약의 단계 수 상한을 사용하는지 확인한다. */
    @Test
    void usesSharedStepLimit() {
        assertThat(FlowStructureValidator.MAX_FLOW_STEPS).isEqualTo(contract().maxSteps());
    }

    /** 분석이 불완전한데 원인 노드가 없어도 검사 통과로 처리하지 않는다. */
    @Test
    void incompleteGraphWithoutNodeErrorFailsClosed() {
        var graph = new DependencyGraph("order-flow", List.of("payment"), List.of(readableNode("payment")),
                List.of(), List.of(), List.of(), false);

        assertThat(FlowStructureValidator.validate(graph)).extracting(FlowStructureValidator.Violation::reason)
                .containsExactly("INCOMPLETE_ANALYSIS");
    }

    /** complete가 참이어도 실행 순서와 노드가 맞지 않으면 판정을 보류한다. */
    @Test
    void missingNodeCannotPassAsCompleteAnalysis() {
        var graph = new DependencyGraph("order-flow", List.of("payment"), List.of(), List.of(), List.of(), List.of(), true);

        assertThat(FlowStructureValidator.validate(graph)).extracting(FlowStructureValidator.Violation::reason)
                .containsExactly("INCOMPLETE_ANALYSIS");
    }

    /** 직접 전달된 빈 그래프도 구조 검사에서는 거부한다. */
    @Test
    void emptyGraphIsInvalid() {
        var graph = new DependencyGraph("order-flow", List.of(), List.of(), List.of(), List.of(), List.of(), true);

        assertThat(FlowStructureValidator.validate(graph)).extracting(FlowStructureValidator.Violation::reason)
                .containsExactly("EMPTY_STEPS");
    }

    /** 구조 검사는 읽을 수 있는 단계의 입력 공급 오류를 중복해서 분류하지 않는다. */
    @Test
    void inputSupplyRemainsASeparateCheck() {
        var graph = new DependencyGraph("order-flow", List.of("payment"), List.of(readableNode("payment")),
                List.of(), List.of(new DependencyGraph.Missing("payment", List.of("inventory.reserved"), List.of())),
                List.of(), true);

        assertThat(FlowStructureValidator.validate(graph)).isEmpty();
    }

    /** 방어적 그래프 경계 검사에 쓸 정상 노드를 만든다. */
    private static DependencyGraph.Node readableNode(String id) {
        return new DependencyGraph.Node(id, "skills/" + id + "/SKILL.md", "hash", List.of(), List.of(), List.of(), null);
    }

    /** 실제 실행기 테스트와 공유하는 flow 입력 목록을 제공한다. */
    static Stream<Case> cases() {
        return contract().cases().stream();
    }

    /** 저장소의 공통 flow 계약 문서를 읽는다. */
    private static Contract contract() {
        return JsonMapper.builder().build().readValue(
                Path.of("../docs/contracts/examples/flow-structure-cases.json").toFile(), Contract.class);
    }
}
