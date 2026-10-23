package com.capstone4.skillops.snapshot;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Path;
import java.util.Arrays;
import java.util.stream.Stream;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.yaml.snakeyaml.error.YAMLException;

import com.capstone4.skillops.skill.SkillMetadataValidator;
import com.capstone4.skillops.workspace.SkillHeader;

import tools.jackson.databind.json.JsonMapper;

/** 주문 Agent와 같은 fixture 및 크기 경계를 실제 service 어댑터에서 검증한다. */
class SharedYamlSafetyContractTest {

    private static final String HEADER = "name: payment\nversion: 1\nowner: backend\ndescription: test\n"
            + "inputs: []\noutputs: []\ndepends_on: []\n";

    record Case(String id, String yaml, boolean valid) {
    }

    /** 모든 service 경로가 같은 안전 정책으로 수락·거부하고 필드 오류를 보존한다. */
    @ParameterizedTest(name = "{index}: {0}")
    @MethodSource("cases")
    void matchesSharedSafetyRules(Case testCase) {
        String text = testCase.yaml();
        if (testCase.valid()) {
            assertThat(Frontmatter.parse(text)).containsEntry("name", "payment");
            assertThat(SkillHeader.parse(text).version()).isEqualTo(1);
            assertThat(SkillMetadataValidator.validate("payment", text)).isEmpty();
        } else {
            assertThatThrownBy(() -> Frontmatter.parse(text)).isInstanceOf(YAMLException.class);
            assertThatThrownBy(() -> SkillHeader.parse(text)).isInstanceOf(IllegalArgumentException.class);
            assertThat(SkillMetadataValidator.validate("payment", text))
                    .extracting(SkillMetadataValidator.Violation::field).containsExactly("frontmatter");
        }
    }

    /** 저장소 공통 fixture와 실제 크기 경계를 LF/CRLF로 제공한다. */
    static Stream<Case> cases() {
        Stream<Case> fixed = Arrays.stream(JsonMapper.builder().build().readValue(
                Path.of("../docs/contracts/examples/skill-yaml-safety-cases.json").toFile(), Case[].class));
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
