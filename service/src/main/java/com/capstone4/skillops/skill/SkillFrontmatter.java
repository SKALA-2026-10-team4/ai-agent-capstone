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

    /** 실행기와 같은 경계로 읽은 메타데이터와 본문이다. 원문은 바꾸지 않는다. */
    public record ExecutionDocument(Map<String, Object> fields, String body) {
    }

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

    /** 주문 실행기처럼 LF/CRLF 경계와 YAML 100,000 코드 포인트 제한을 적용한다. */
    public static ExecutionDocument readForExecution(String text) {
        String normalized = text == null ? "" : text.replace("\r\n", "\n");
        int end = normalized.indexOf("\n---\n", 4);
        if (!normalized.startsWith("---\n") || end < 0) {
            throw new IllegalArgumentException("실행용 SKILL.md의 frontmatter 구분선이 없습니다");
        }
        LoaderOptions options = safeOptions();
        options.setCodePointLimit(100_000);
        return new ExecutionDocument(fieldsOf(normalized.substring(4, end), options), normalized.substring(end + 5));
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
        return fieldsOf(yaml, safeOptions());
    }

    /** 중복 키와 컬렉션 별칭을 거부하는 호출별 YAML 옵션을 만든다. */
    private static LoaderOptions safeOptions() {
        LoaderOptions options = new LoaderOptions();
        options.setAllowDuplicateKeys(false);
        options.setMaxAliasesForCollections(0);
        return options;
    }

    /** 지정된 안전 옵션으로 YAML 객체를 읽으며 기존 diff의 빈 맵 처리도 유지한다. */
    private static Map<String, Object> fieldsOf(String yaml, LoaderOptions options) {
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
