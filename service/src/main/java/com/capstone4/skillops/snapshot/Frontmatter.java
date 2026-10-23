package com.capstone4.skillops.snapshot;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;

/**
 * SKILL.md 맨 위 '---' 줄 사이의 YAML(frontmatter)을 읽는 C-1 파서다. 주문 Agent SkillLoader
 * (order-agent/backend/src/main/java/com/capstone4/orderagent/harness/SkillLoader.java 의 parseMetadata)와 같은 안전 옵션인
 * SafeConstructor, 중복 키 금지, 컬렉션 alias 금지를 쓴다. 주문 Agent 가 더 두는 글자 수 한도(setCodePointLimit)는 두지 않는다.
 * YAML 이 깨지면 YAMLException 을 그대로 던지고, 호출처가 그 예외를 parse_error, frontmatter_parse_error 칸이나 INVALID_FLOW
 * 오류로 바꾼다.
 * service/src/main/java/com/capstone4/skillops/snapshot/SkillDiffer.java 가 frontmatter 키 변화와 덩어리 위치(part)를 계산할 때,
 * service/src/main/java/com/capstone4/skillops/snapshot/DependencyAnalyzer.java 가 steps 와 단계 선언을 읽을 때 부른다.
 * C-2 연습 공간은 줄 단위로 읽는 별도 파서 service/src/main/java/com/capstone4/skillops/workspace/SkillHeader.java 를 쓴다.
 */
final class Frontmatter {

    /**
     * 글 첫 줄의 '---' 부터 그 뒤에 처음 나오는 '---' 만 있는 줄까지를 잡고, 그 사이 글을 그룹 1 로 꺼내는 정규식이다.
     * 여는 줄과 닫는 줄 사이에 줄이 하나 이상 있어야 하므로 둘째 줄의 '---' 는 닫는 줄이 되지 못하고, '---' 두 줄만 있는
     * 빈 블록은 잡지 않는다. 줄바꿈은 LF 와 CRLF 를 모두 받고 닫는 줄은 글 끝에 와도 된다. 여는 줄이나 닫는 줄의 '---' 뒤에
     * 다른 글자가 붙으면 구분 줄로 보지 않는다.
     */
    private static final Pattern BLOCK = Pattern.compile("\\A---\\R(.*?)\\R---(\\R|\\z)", Pattern.DOTALL);

    /** 정적 메서드만 있는 도구 클래스라서 객체를 만들지 못하게 막는다. */
    private Frontmatter() {
    }

    /**
     * text 의 frontmatter 를 읽어 키를 문자열로 바꾼 맵으로 돌려준다. 키 순서는 파일에 적힌 순서다.
     * text 가 null 이거나 frontmatter 가 없거나 YAML 이 맵이 아니면 빈 맵을 돌려준다. YAML 이 깨졌거나 중복 키, 컬렉션 alias,
     * 안전하지 않은 태그가 있으면 YAMLException 을 던진다.
     */
    static Map<String, Object> parse(String text) {
        if (text == null) {
            return Map.of();
        }
        Matcher m = BLOCK.matcher(text);
        if (!m.find()) {
            return Map.of();
        }
        // 주문 Agent SkillLoader 와 같은 안전 옵션이다. Yaml 은 스레드에 안전하지 않으므로 호출마다 새로 만든다.
        LoaderOptions options = new LoaderOptions();
        options.setAllowDuplicateKeys(false);
        options.setMaxAliasesForCollections(0);
        Object loaded = new Yaml(new SafeConstructor(options)).load(m.group(1));
        if (!(loaded instanceof Map<?, ?> map)) {
            return Map.of();
        }
        Map<String, Object> result = new LinkedHashMap<>();
        map.forEach((k, v) -> result.put(String.valueOf(k), v));
        return result;
    }

    /**
     * frontmatter 를 닫는 '---' 줄의 번호(1부터)를 돌려준다. 첫 줄이 '---' 가 아니거나 닫는 줄이 없으면 0 이다.
     * YAML 내용은 보지 않고, BLOCK 과 달리 둘째 줄부터 닫는 줄을 찾으므로 '---' 두 줄만 있는 빈 블록에도 2 를 돌려준다.
     * SkillDiffer 가 덩어리의 part 를 frontmatter 와 body 로 가르는 기준으로 쓴다.
     */
    static int closingLine(String text) {
        if (text == null) {
            return 0;
        }
        List<String> lines = text.lines().toList();
        if (lines.isEmpty() || !lines.get(0).equals("---")) {
            return 0;
        }
        for (int i = 1; i < lines.size(); i++) {
            if (lines.get(i).equals("---")) {
                return i + 1;
            }
        }
        return 0;
    }
}
