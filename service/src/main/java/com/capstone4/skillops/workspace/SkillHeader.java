package com.capstone4.skillops.workspace;

import java.util.Arrays;
import java.util.List;

/**
 * SKILL.md 맨 위 칸(--- 사이)에서 C-2 가 쓰는 값만 읽는다. 계약 1(docs/contracts/skill-frontmatter.md) 모양을 따른다.
 * 정식 레지스트리(계약 일관성 검사)는 A 의 registry/ 가 맡는다. 여기서는 버전 확인과 가짜 실행기에만 쓴다.
 *
 * @param version 맨 위 칸에 버전이 없으면 null
 * @param steps   order-flow 에만 있는 실행 순서. 다른 skill 은 빈 목록
 */
public record SkillHeader(String name, Integer version, List<String> inputs, List<String> outputs, List<String> steps) {

    public static SkillHeader parse(String fullText) {
        List<String> lines = fullText.lines().toList();
        int end = lines.isEmpty() || !lines.getFirst().strip().equals("---") ? -1 : indexOfClosing(lines);
        if (end < 0) {
            throw new IllegalArgumentException("SKILL.md 맨 위 칸(--- 사이)을 찾을 수 없습니다");
        }
        String name = null;
        Integer version = null;
        List<String> inputs = List.of();
        List<String> outputs = List.of();
        List<String> steps = List.of();
        for (String line : lines.subList(1, end)) {
            int colon = line.indexOf(':');
            if (colon < 0) {
                continue;
            }
            String key = line.substring(0, colon).strip();
            String value = line.substring(colon + 1).replaceFirst("\\s+#.*$", "").strip();
            switch (key) {
                case "name" -> name = value;
                case "version" -> version = value.matches("\\d+") ? Integer.valueOf(value) : null;
                case "inputs" -> inputs = list(value);
                case "outputs" -> outputs = list(value);
                case "steps" -> steps = list(value);
                default -> {
                }
            }
        }
        return new SkillHeader(name, version, inputs, outputs, steps);
    }

    private static int indexOfClosing(List<String> lines) {
        for (int i = 1; i < lines.size(); i++) {
            if (lines.get(i).strip().equals("---")) {
                return i;
            }
        }
        return -1;
    }

    /** {@code [a, b]} → [a, b]. 대괄호가 없으면 빈 목록. */
    private static List<String> list(String value) {
        if (!value.startsWith("[") || !value.endsWith("]")) {
            return List.of();
        }
        return Arrays.stream(value.substring(1, value.length() - 1).split(","))
                .map(String::strip)
                .filter(s -> !s.isEmpty())
                .toList();
    }
}
