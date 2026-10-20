package com.capstone4.skillops.snapshot;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;

/**
 * SKILL.md 맨 위 '---' 사이의 YAML 을 읽는다. 주문 Agent SkillLoader 와 같은 안전 옵션
 * (SafeConstructor, 중복 키 금지, alias 금지)을 쓴다. YAML 이 깨지면 YAMLException 을 그대로 던진다.
 */
final class Frontmatter {

    private static final Pattern BLOCK = Pattern.compile("\\A---\\R(.*?)\\R---(\\R|\\z)", Pattern.DOTALL);

    private Frontmatter() {
    }

    /** frontmatter 가 없거나 YAML 이 맵이 아니면 빈 맵. */
    static Map<String, Object> parse(String text) {
        if (text == null) {
            return Map.of();
        }
        Matcher m = BLOCK.matcher(text);
        if (!m.find()) {
            return Map.of();
        }
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
}
