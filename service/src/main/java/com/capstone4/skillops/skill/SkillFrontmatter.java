package com.capstone4.skillops.skill;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;

/** workspace와 snapshot 분석이 공유하는 frontmatter 경계·안전한 YAML 해석 기능이다. */
public final class SkillFrontmatter {

    // 기존 snapshot diff의 구분선·LF/CRLF·EOF 규칙을 보존한다.
    private static final Pattern BLOCK = Pattern.compile("\\A---\\R(.*?)\\R---(\\R|\\z)", Pattern.DOTALL);

    /** 정적 함수만 제공하므로 인스턴스 생성을 막는다. */
    private SkillFrontmatter() {
    }

    /** diff용으로 원문을 읽으며 frontmatter가 없거나 YAML 객체가 아니면 빈 맵을 반환한다. */
    public static Map<String, Object> parse(String text) {
        String yaml = yamlBlock(text);
        return yaml == null ? Map.of() : fieldsOf(yaml);
    }

    /** workspace용으로 비어 있지 않은 YAML 객체를 요구하며 누락된 문서를 입력 오류로 알린다. */
    public static Map<String, Object> requireFields(String text) {
        String yaml = yamlBlock(text);
        if (yaml == null) {
            throw new IllegalArgumentException("SKILL.md 맨 위 칸(--- 사이)을 찾을 수 없습니다");
        }
        Map<String, Object> fields = fieldsOf(yaml);
        if (fields.isEmpty()) {
            throw new IllegalArgumentException("SKILL.md 맨 위 칸은 비어 있지 않은 YAML 객체여야 합니다");
        }
        return fields;
    }

    /** 원문에서 기존 계약에 맞는 첫 frontmatter 블록을 추출한다. */
    private static String yamlBlock(String text) {
        if (text == null) {
            return null;
        }
        Matcher matcher = BLOCK.matcher(text);
        return matcher.find() ? matcher.group(1) : null;
    }

    /** 호출별 안전한 YAML 파서로 값을 읽고 원래 필드 순서와 null을 보존한다. */
    private static Map<String, Object> fieldsOf(String yaml) {
        LoaderOptions options = new LoaderOptions();
        options.setAllowDuplicateKeys(false);
        options.setMaxAliasesForCollections(0);
        Object loaded = new Yaml(new SafeConstructor(options)).load(yaml);
        if (!(loaded instanceof Map<?, ?> map)) {
            return Map.of();
        }
        Map<String, Object> fields = new LinkedHashMap<>();
        map.forEach((key, value) -> fields.put(String.valueOf(key), value));
        return fields;
    }

    /** diff hunk 분류에 쓰는 닫는 구분선의 1부터 시작하는 줄 번호를 반환한다. */
    public static int closingLine(String text) {
        if (text == null) {
            return 0;
        }
        List<String> lines = text.lines().toList();
        if (lines.isEmpty() || !lines.getFirst().equals("---")) {
            return 0;
        }
        for (int index = 1; index < lines.size(); index++) {
            if (lines.get(index).equals("---")) {
                return index + 1;
            }
        }
        return 0;
    }
}
