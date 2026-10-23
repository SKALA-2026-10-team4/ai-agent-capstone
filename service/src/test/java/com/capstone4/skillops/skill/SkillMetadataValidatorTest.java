package com.capstone4.skillops.skill;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import tools.jackson.databind.json.JsonMapper;

/** 실제 주문 Agent 테스트와 같은 문서를 읽어 필수 정보 판정 차이를 방지한다. */
class SkillMetadataValidatorTest {

    record Case(String id, String skill, String text, boolean valid, List<String> fields) {
    }

    /** 공유 fixture의 문서와 예상 오류 필드를 비교한다. */
    @ParameterizedTest(name = "{index}: {0}")
    @MethodSource("cases")
    void matchesExecutionMetadataContract(Case testCase) {
        var errors = SkillMetadataValidator.validate(testCase.skill(), testCase.text());

        assertThat(errors.isEmpty()).as(testCase.id()).isEqualTo(testCase.valid());
        assertThat(errors).extracting(SkillMetadataValidator.Violation::field)
                .as(testCase.id()).containsExactlyElementsOf(testCase.fields());
        assertThat(errors).allSatisfy(error -> {
            assertThat(error.skill()).isEqualTo(testCase.skill());
            assertThat(error.code()).isIn("INVALID_SKILL_FORMAT", "INVALID_SKILL_NAME");
            assertThat(error.message()).isNotBlank();
        });
    }

    /** 실행기와 동일한 YAML 코드 포인트 상한을 적용한다. */
    @Test
    void rejectsOversizedYaml() {
        var errors = SkillMetadataValidator.validate("payment", "---\nname: payment\npadding: "
                + "x".repeat(100_001) + "\n---\nBody\n");

        assertThat(errors).extracting(SkillMetadataValidator.Violation::field).containsExactly("frontmatter");
    }

    /** 두 Java 모듈이 공유하는 메타데이터 계약 fixture를 읽는다. */
    static Stream<Case> cases() {
        return Arrays.stream(JsonMapper.builder().build().readValue(
                Path.of("../docs/contracts/examples/skill-metadata-cases.json").toFile(), Case[].class));
    }
}
