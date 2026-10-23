package com.capstone4.skillops.workspace;

import java.util.List;
import java.util.Map;

import org.yaml.snakeyaml.error.YAMLException;

import com.capstone4.skillops.skill.SkillFrontmatter;

/**
 * 공통 SkillFrontmatter로 name, version, inputs, outputs, steps를 읽는 workspace 어댑터다.
 * C-1과 같은 YAML 파서를 사용하므로 여러 줄 목록과 따옴표도 같은 값으로 읽힌다.
 * 누락된 선택 목록은 비우되 명시적 null이나 잘못된 타입은 입력 오류로 거부한다.
 *
 * 쓰는 곳은 버전 확인과 가짜 실행기다. service/src/main/java/com/capstone4/skillops/workspace/WorkspaceService.java 와
 * service/src/main/java/com/capstone4/skillops/workspace/LiveFolderSkillVersionStore.java 의 versionOf 가 version 을,
 * service/src/main/java/com/capstone4/skillops/scenario/ContractOnlyFlowRunner.java 가 steps, inputs, outputs 를 읽는다.
 * name 값을 쓰는 production 코드는 지금 없고, service/src/test/java/com/capstone4/skillops/workspace/SkillHeaderTest.java
 * 만 이 값을 확인한다. 설계상 정식 레지스트리(계약 일관성 검사)는 A 의 registry 패키지 몫이지만 지금 그 패키지에는 package-info.java 만 있고,
 * apply_patch 의 연결 검사는 C-1 의 DependencyAnalyzer 가 한다. record 의 accessor 는 읽은 값을 그대로 돌려준다.
 *
 * @param version 버전이 없으면 null이며, 있으면 1 이상의 Integer여야 한다
 * @param steps   order-flow 에만 있는 실행 순서다. 다른 skill 은 빈 목록이다
 */
public record SkillHeader(String name, Integer version, List<String> inputs, List<String> outputs, List<String> steps) {

    /** YAML을 읽고 workspace 필드 타입을 검사하며 파싱 실패를 INVALID_INPUT용 입력 예외로 바꾼다. */
    public static SkillHeader parse(String fullText) {
        try {
            Map<String, Object> fields = SkillFrontmatter.requireFields(fullText);
            return new SkillHeader(nameOf(fields), versionOf(fields), strings(fields, "inputs"),
                    strings(fields, "outputs"), strings(fields, "steps"));
        } catch (YAMLException error) {
            throw new IllegalArgumentException("SKILL.md YAML을 읽을 수 없습니다: " + error.getMessage(), error);
        }
    }

    /** 선언된 이름은 비공백 문자열로 읽으며 누락된 이름은 기존처럼 null로 둔다. */
    private static String nameOf(Map<String, Object> fields) {
        if (!fields.containsKey("name")) {
            return null;
        }
        if (!(fields.get("name") instanceof String name) || name.isBlank()) {
            throw new IllegalArgumentException("name 은 비어 있지 않은 문자열이어야 합니다");
        }
        return name;
    }

    /** 시작 버전은 누락 시 null, 선언 시 1 이상의 Integer로 읽는다. */
    private static Integer versionOf(Map<String, Object> fields) {
        if (!fields.containsKey("version")) {
            return null;
        }
        if (!(fields.get("version") instanceof Integer version) || version < 1) {
            throw new IllegalArgumentException("version 은 1 이상의 정수여야 합니다");
        }
        return version;
    }

    /** 선택 목록의 누락은 허용하고 null·잘못된 항목은 빈 목록 대신 입력 오류로 알린다. */
    private static List<String> strings(Map<String, Object> fields, String key) {
        if (!fields.containsKey(key)) {
            return List.of();
        }
        if (!(fields.get(key) instanceof List<?> values)
                || values.stream().anyMatch(value -> !(value instanceof String text) || text.isBlank())) {
            throw new IllegalArgumentException(key + " 는 비어 있지 않은 문자열의 목록이어야 합니다");
        }
        return values.stream().map(String.class::cast).toList();
    }
}
