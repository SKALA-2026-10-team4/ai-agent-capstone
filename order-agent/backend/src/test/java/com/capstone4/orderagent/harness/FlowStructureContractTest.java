package com.capstone4.orderagent.harness;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import tools.jackson.databind.json.JsonMapper;

/** service와 같은 flow 문서를 실제 SkillLoader.validateFlow로 검사한다. */
class FlowStructureContractTest {

    record Case(String id, Map<String, String> skills, List<String> reasons, String code) {
    }

    record Contract(int maxSteps, List<Case> cases) {
    }

    /** 실행기의 실제 수락·거부 및 첫 오류 종류를 공통 기대값과 비교한다. */
    @ParameterizedTest(name = "{index}: {0}")
    @MethodSource("cases")
    void matchesSharedFlowContract(Case testCase) {
        var files = new TreeMap<String, byte[]>();
        testCase.skills().forEach((id, text) -> files.put(SkillLoader.skillPath(id), text.getBytes(StandardCharsets.UTF_8)));
        var loader = new SkillLoader();
        var flow = loader.load(files, "order-flow");

        if (testCase.code() == null) {
            assertThat(loader.validateFlow(files, flow)).hasSize(flow.steps().size());
        } else {
            assertThatThrownBy(() -> loader.validateFlow(files, flow)).as(testCase.id())
                    .isInstanceOfSatisfying(AgentException.class, error -> {
                        assertThat(error.category()).isEqualTo(testCase.code());
                        if (testCase.reasons().contains("NESTED_FLOW_NOT_SUPPORTED")) {
                            assertThat(error.details()).containsEntry("reason", "NESTED_FLOW_NOT_SUPPORTED");
                        }
                    });
        }
    }

    /** 실제 실행 정책의 상한이 service 공통 계약과 같은지 확인한다. */
    @Test
    void usesSharedStepLimit() {
        assertThat(OrderFlowPolicy.MAX_FLOW_STEPS).isEqualTo(contract().maxSteps());
    }

    /** service 테스트와 공유하는 flow 입력 목록을 제공한다. */
    static Stream<Case> cases() {
        return contract().cases().stream();
    }

    /** 저장소의 공통 flow 계약 문서를 읽는다. */
    private static Contract contract() {
        return JsonMapper.builder().build().readValue(
                Path.of("../../docs/contracts/examples/flow-structure-cases.json").toFile(), Contract.class);
    }
}
