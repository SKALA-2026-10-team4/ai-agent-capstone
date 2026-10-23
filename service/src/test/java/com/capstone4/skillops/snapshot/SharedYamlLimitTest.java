package com.capstone4.skillops.snapshot;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.error.YAMLException;

import com.capstone4.skillops.skill.SkillFrontmatter;
import com.capstone4.skillops.workspace.SkillHeader;

/** 분석·workspace도 실행기와 같은 frontmatter 크기 제한을 적용하는지 확인한다. */
class SharedYamlLimitTest {

    /** 세 파싱 경로가 YAML 상한 초과를 일관되게 거부한다. */
    @Test
    void oversizedYamlIsRejectedByAnalysisWorkspaceAndExecution() {
        String text = "---\nname: payment\nversion: 1\npadding: " + "x".repeat(100_001)
                + "\n---\nbody\n";
        assertThatThrownBy(() -> Frontmatter.parse(text)).isInstanceOf(YAMLException.class);
        assertThatThrownBy(() -> SkillHeader.parse(text)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> SkillFrontmatter.readForExecution(text)).isInstanceOf(YAMLException.class);
    }

    /** 상한 초과도 원문 diff와 식별자를 보존하고 구조화된 파싱 변화만 포기한다. */
    @Test
    void oversizedYamlStillProducesRawDiffEvidence() {
        String before = "---\nname: payment\nversion: 1\n---\nbody\n";
        String after = before.replace("version: 1", "version: 1\npadding: " + "x".repeat(100_001));
        var diff = SkillDiffer.diff("skills/payment/SKILL.md", before, after, "before", "after");
        assertThat(diff.frontmatterParseError()).isNotBlank();
        assertThat(diff.hunks()).isNotEmpty();
        assertThat(diff.hunks()).allSatisfy(hunk -> assertThat(hunk.hunkId()).isNotBlank());
    }
}
