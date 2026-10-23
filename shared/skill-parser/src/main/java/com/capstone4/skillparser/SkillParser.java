package com.capstone4.skillparser;

import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;
import org.yaml.snakeyaml.error.YAMLException;

/** Spring·도메인·HTTP에 의존하지 않는 공통 SKILL 경계 해석과 안전한 YAML 파서다. */
public final class SkillParser {

    /** YAML frontmatter의 상한이며 본문이나 UTF-8 바이트 수를 뜻하지 않는다. */
    public static final int MAX_YAML_CODE_POINTS = 100_000;
    /** YAML 파서의 기존 기본 중첩 상한을 양쪽 서버에 명시적으로 고정한다. */
    public static final int MAX_NESTING_DEPTH = 50;

    private static final Pattern ANALYSIS_BLOCK = Pattern.compile("\\A---\\R(.*?)\\R---(\\R|\\z)", Pattern.DOTALL);
    private static final Pattern SKILL_NAME = Pattern.compile("[a-z][a-z0-9]*(?:-[a-z0-9]+)*");

    /** 실행 경계로 읽은 필드와 LF로 해석한 본문이다. 입력 원문 자체는 변경하지 않는다. */
    public record ExecutionDocument(Map<String, Object> fields, String body) {
    }

    /** 정적 파싱 기능만 제공한다. */
    private SkillParser() {
    }

    /** 진단·diff의 기존 경계를 사용하며 블록 부재·객체가 아닌 YAML은 빈 맵으로 반환한다. */
    public static Map<String, Object> parseForAnalysis(String text) {
        String yaml = yamlBlock(text);
        return yaml == null ? Map.of() : fieldsOf(yaml);
    }

    /** workspace의 기존 입력 오류와 빈 객체 거부 규칙을 유지한다. */
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

    /** 실제 로더의 LF/CRLF·닫는 구분선 뒤 줄바꿈 규칙으로 읽는다. */
    public static ExecutionDocument readForExecution(String text) {
        String normalized = text == null ? "" : text.replace("\r\n", "\n");
        int end = normalized.indexOf("\n---\n", 4);
        if (!normalized.startsWith("---\n") || end < 0) {
            throw new IllegalArgumentException("실행용 SKILL.md의 frontmatter 구분선이 없습니다");
        }
        return new ExecutionDocument(fieldsOf(normalized.substring(4, end)), normalized.substring(end + 5));
    }

    /** 잘못된 UTF-8을 대체하지 않고 거부한 뒤 실행용 문서로 읽는다. */
    public static ExecutionDocument readForExecution(byte[] bytes) throws CharacterCodingException {
        String text = StandardCharsets.UTF_8.newDecoder().decode(ByteBuffer.wrap(bytes)).toString();
        return readForExecution(text);
    }

    /** 모든 Java 소비자가 같은 64자 kebab-case Skill 이름 규칙을 사용한다. */
    public static boolean isValidName(String name) {
        return name != null && name.length() <= 64 && SKILL_NAME.matcher(name).matches();
    }

    /** hunk 분류를 위한 원문 닫는 구분선의 1부터 시작하는 줄 번호를 반환한다. */
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

    /** 원문을 다시 쓰지 않고 진단용 첫 블록만 추출한다. */
    private static String yamlBlock(String text) {
        if (text == null) {
            return null;
        }
        var matcher = ANALYSIS_BLOCK.matcher(text);
        return matcher.find() ? matcher.group(1) : null;
    }

    /** 모든 프로필에 같은 YAML 안전 설정과 정확한 코드 포인트 한도를 적용한다. */
    private static Map<String, Object> fieldsOf(String yaml) {
        String normalized = yaml.replace("\r\n", "\n");
        if (normalized.codePointCount(0, normalized.length()) > MAX_YAML_CODE_POINTS) {
            throw new YAMLException("SKILL.md YAML exceeds " + MAX_YAML_CODE_POINTS + " code points");
        }
        LoaderOptions options = new LoaderOptions();
        options.setAllowDuplicateKeys(false);
        options.setMaxAliasesForCollections(0);
        options.setCodePointLimit(MAX_YAML_CODE_POINTS);
        options.setNestingDepthLimit(MAX_NESTING_DEPTH);
        Object loaded = new Yaml(new SafeConstructor(options)).load(normalized);
        if (!(loaded instanceof Map<?, ?> map)) {
            return Map.of();
        }
        Map<String, Object> fields = new LinkedHashMap<>();
        map.forEach((key, value) -> fields.put(String.valueOf(key), value));
        return fields;
    }
}
