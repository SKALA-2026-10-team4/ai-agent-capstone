package com.capstone4.skillops.skill;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.yaml.snakeyaml.error.YAMLException;

/** 주문 Agent의 필수 정보 기준을 검사한다. flow 연결 및 목록 검사는 별도 책임이다. */
public final class SkillMetadataValidator {

    /** 필수 정보 오류의 대상 Skill, 필드, 실행기 오류 코드와 설명이다. */
    public record Violation(String skill, String field, String code, String message) {
    }

    /** 정적 검증 함수만 제공하므로 인스턴스 생성을 막는다. */
    private SkillMetadataValidator() {
    }

    /** 필수 정보 오류를 모으며 문서를 읽을 수 없으면 frontmatter 오류 하나를 반환한다. */
    public static List<Violation> validate(String skill, String text) {
        if (skill == null || skill.length() > 64 || !skill.matches("[a-z][a-z0-9]*(?:-[a-z0-9]+)*")) {
            return List.of(new Violation(skill, "name", "INVALID_SKILL_NAME", "Skill 폴더 이름 규칙에 맞지 않습니다"));
        }
        SkillFrontmatter.ExecutionDocument document;
        try {
            document = SkillFrontmatter.readForExecution(text);
        } catch (IllegalArgumentException | YAMLException error) {
            return List.of(error(skill, "frontmatter", "실행용 YAML frontmatter를 읽을 수 없습니다"));
        }
        List<Violation> errors = new ArrayList<>();
        Map<String, Object> fields = document.fields();
        if (!skill.equals(fields.get("name"))) {
            errors.add(error(skill, "name", "name은 Skill 폴더 이름과 같아야 합니다"));
        }
        if (!(fields.get("version") instanceof Integer version) || version < 1) {
            errors.add(error(skill, "version", "version은 1 이상의 Integer 정수여야 합니다"));
        }
        requireText(errors, skill, fields, "owner");
        requireText(errors, skill, fields, "description");
        if (document.body().strip().isEmpty()) {
            errors.add(error(skill, "body", "본문은 비어 있으면 안 됩니다"));
        }
        return List.copyOf(errors);
    }

    /** 필수 문자열의 누락·null·잘못된 타입·공백을 같은 기준으로 검사한다. */
    private static void requireText(List<Violation> errors, String skill, Map<String, Object> fields, String field) {
        if (!(fields.get(field) instanceof String value) || value.isBlank()) {
            errors.add(error(skill, field, field + "는 비어 있지 않은 문자열이어야 합니다"));
        }
    }

    /** 실행기의 INVALID_SKILL_FORMAT에 대응하는 필드별 오류를 만든다. */
    private static Violation error(String skill, String field, String message) {
        return new Violation(skill, field, "INVALID_SKILL_FORMAT", message);
    }
}
