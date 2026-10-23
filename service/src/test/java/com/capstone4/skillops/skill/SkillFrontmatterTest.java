package com.capstone4.skillops.skill;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.error.YAMLException;

/** service의 workspace와 snapshot이 공유하는 YAML 해석 계약을 확인한다. */
class SkillFrontmatterTest {

    /** 목록 표현과 따옴표가 달라도 같은 YAML 값을 읽는다. */
    @Test
    void inlineAndBlockListsHaveTheSameMeaning() {
        var inline = SkillFrontmatter.parse("---\noutputs: [\"inventory.reserved\", 'payment.status']\n---\nbody\n");
        var block = SkillFrontmatter.parse("---\noutputs:\n  - inventory.reserved\n  - payment.status\n---\nbody\n");

        assertThat(block).isEqualTo(inline).containsEntry("outputs", List.of("inventory.reserved", "payment.status"));
    }

    /** 필드 누락과 명시적인 null을 소비자가 구분할 수 있게 보존한다. */
    @Test
    void preservesMissingAndExplicitNullFields() {
        var fields = SkillFrontmatter.parse("---\noutputs: null\n---\nbody\n");

        assertThat(fields).containsEntry("outputs", null).doesNotContainKey("inputs");
    }

    /** 엄격한 조회는 frontmatter가 없거나 YAML 객체가 아니면 거부한다. */
    @Test
    void requiredFieldsRejectMissingAndNonMapDocuments() {
        assertThatThrownBy(() -> SkillFrontmatter.requireFields("# body\n"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("SKILL.md 맨 위 칸(--- 사이)을 찾을 수 없습니다");
        assertThatThrownBy(() -> SkillFrontmatter.requireFields("---\n- payment\n---\nbody\n"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("YAML 객체");
    }

    /** 느슨한 diff 조회도 중복 키와 깨진 YAML을 조용히 통과시키지 않는다. */
    @Test
    void rejectsDuplicateKeysAndMalformedYaml() {
        assertThatThrownBy(() -> SkillFrontmatter.parse("---\nversion: 1\nversion: 2\n---\nbody\n"))
                .isInstanceOf(YAMLException.class);
        assertThatThrownBy(() -> SkillFrontmatter.parse("---\noutputs: [broken\n---\nbody\n"))
                .isInstanceOf(YAMLException.class);
    }
}
