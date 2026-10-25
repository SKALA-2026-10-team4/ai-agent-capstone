package com.capstone4.orderagent.harness;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.TreeMap;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import tools.jackson.databind.json.JsonMapper;

/** service와 같은 입력 fixture를 실제 실행 로더에 넣어 필수 정보 기준을 고정한다. */
class SkillMetadataContractTest {

    record Case(String id, String skill, String text, boolean valid, List<String> fields) {
    }

    /** service의 예상 메타데이터 판정과 실제 로더의 수락 여부를 대조한다. */
    @ParameterizedTest(name = "{index}: {0}")
    @MethodSource("cases")
    void matchesSharedMetadataContract(Case testCase) {
        var files = new TreeMap<String, byte[]>();
        files.put(SkillLoader.skillPath(testCase.skill()), testCase.text().getBytes(StandardCharsets.UTF_8));
        var loader = new SkillLoader();

        if (testCase.valid()) {
            assertThat(loader.load(files, testCase.skill()).id()).as(testCase.id()).isEqualTo(testCase.skill());
        } else {
            assertThatThrownBy(() -> loader.load(files, testCase.skill())).as(testCase.id())
                    .isInstanceOf(AgentException.class)
                    .hasMessage(testCase.id().equals("invalid-folder-name") ? "INVALID_SKILL_NAME" : "INVALID_SKILL_FORMAT");
        }
    }

    /** 실제 실행 로더의 YAML 길이 상한도 함께 보호한다. */
    @Test
    void rejectsOversizedYaml() {
        var files = new TreeMap<String, byte[]>();
        files.put(SkillLoader.skillPath("payment"), ("---\nname: payment\npadding: "
                + "x".repeat(100_001) + "\n---\nBody\n").getBytes(StandardCharsets.UTF_8));

        assertThatThrownBy(() -> new SkillLoader().load(files, "payment"))
                .isInstanceOf(AgentException.class).hasMessage("INVALID_SKILL_FORMAT");
    }

    /** service 테스트와 공유하는 문서들을 저장소의 계약 디렉터리에서 읽는다. */
    static Stream<Case> cases() {
        return Arrays.stream(JsonMapper.builder().build().readValue(
                Path.of("../../docs/contracts/examples/skill-metadata-cases.json").toFile(), Case[].class));
    }
}
