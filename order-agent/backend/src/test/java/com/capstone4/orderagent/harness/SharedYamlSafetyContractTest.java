package com.capstone4.orderagent.harness;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.TreeMap;
import java.util.stream.Stream;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import tools.jackson.databind.json.JsonMapper;

/** service와 같은 YAML 안전 fixture와 정확한 상한을 실제 실행 로더에 넣는다. */
class SharedYamlSafetyContractTest {

    private static final String HEADER = "name: payment\nversion: 1\nowner: backend\ndescription: test\n"
            + "inputs: []\noutputs: []\ndepends_on: []\n";

    record Case(String id, String yaml, boolean valid) {
    }

    /** 기존 실행 오류 코드와 원문 바이트 해시를 유지한 상태로 공용 정책을 적용한다. */
    @ParameterizedTest(name = "{index}: {0}")
    @MethodSource("cases")
    void matchesSharedSafetyRules(Case testCase) {
        byte[] bytes = testCase.yaml().getBytes(StandardCharsets.UTF_8);
        var files = new TreeMap<String, byte[]>();
        files.put(SkillLoader.skillPath("payment"), bytes);
        var loader = new SkillLoader();
        if (testCase.valid()) {
            var skill = loader.load(files, "payment");
            assertThat(skill.id()).isEqualTo("payment");
            assertThat(skill.hash()).isEqualTo(Json.hash(bytes));
        } else {
            assertThatThrownBy(() -> loader.load(files, "payment"))
                    .isInstanceOf(AgentException.class).hasMessage("INVALID_SKILL_FORMAT");
        }
    }

    /** 공통 fixture와 생성한 ASCII/Unicode 경계를 LF/CRLF로 대조한다. */
    static Stream<Case> cases() {
        Stream<Case> fixed = Arrays.stream(JsonMapper.builder().build().readValue(
                Path.of("../../docs/contracts/examples/skill-yaml-safety-cases.json").toFile(), Case[].class));
        Stream<Case> boundaries = Stream.of("x", "😀").flatMap(character -> Stream.of(99_999, 100_000, 100_001)
                .map(size -> new Case("size-" + size + "-" + character,
                        "padding: " + character.repeat(size - HEADER.length() - "padding: ".length()),
                        size <= 100_000)));
        return Stream.concat(fixed, boundaries).flatMap(testCase -> {
            String text = "---\n" + HEADER + testCase.yaml() + "\n---\nbody\n";
            return Stream.of(new Case(testCase.id() + "-lf", text, testCase.valid()),
                    new Case(testCase.id() + "-crlf", text.replace("\n", "\r\n"), testCase.valid()));
        });
    }
}
