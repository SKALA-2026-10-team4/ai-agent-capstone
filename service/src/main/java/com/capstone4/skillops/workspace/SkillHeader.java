package com.capstone4.skillops.workspace;

import java.util.Arrays;
import java.util.List;

/**
 * SKILL.md 맨 위 칸(--- 사이)에서 name, version, inputs, outputs, steps 다섯 값만 읽는 간이 파서다.
 * 계약 1(docs/contracts/skill-frontmatter.md) 모양을 따른다. YAML 라이브러리 없이 'key: value' 줄과 '[a, b]' 한 줄 목록만
 * 읽으므로, 여러 줄 목록('- 항목')으로 쓴 값은 빈 목록이 된다. 그래서 YAML 로 읽는 C-1 분석기
 * (service/src/main/java/com/capstone4/skillops/snapshot/DependencyAnalyzer.java)와 같은 SKILL.md 를 다르게 읽을 수 있다.
 *
 * 쓰는 곳은 버전 확인과 가짜 실행기다. service/src/main/java/com/capstone4/skillops/workspace/WorkspaceService.java 와
 * service/src/main/java/com/capstone4/skillops/workspace/LiveFolderSkillVersionStore.java 의 versionOf 가 version 을,
 * service/src/main/java/com/capstone4/skillops/scenario/ContractOnlyFlowRunner.java 가 steps, inputs, outputs 를 읽는다.
 * name 값을 쓰는 production 코드는 지금 없고, service/src/test/java/com/capstone4/skillops/workspace/SkillHeaderTest.java
 * 만 이 값을 확인한다. 설계상 정식 레지스트리(계약 일관성 검사)는 A 의 registry 패키지 몫이지만 지금 그 패키지에는 package-info.java 만 있고,
 * apply_patch 의 연결 검사는 C-1 의 DependencyAnalyzer 가 한다. record 의 accessor 는 읽은 값을 그대로 돌려준다.
 *
 * @param version 맨 위 칸에 버전이 없거나 숫자가 아니면 null 이다
 * @param steps   order-flow 에만 있는 실행 순서다. 다른 skill 은 빈 목록이다
 */
public record SkillHeader(String name, Integer version, List<String> inputs, List<String> outputs, List<String> steps) {

    /**
     * SKILL.md 원문 첫 줄이 '---' 이고 뒤에 닫는 '---' 줄이 있으면 그 사이의 'key: value' 줄을 읽는다.
     * name, version, inputs, outputs, steps 만 읽고 다른 키와 콜론 없는 줄은 넘긴다. 값 뒤에 공백을 두고 붙인 '#' 주석은
     * 떼고 읽는다. 맨 위 칸을 찾지 못하면 IllegalArgumentException 을 던진다.
     */
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

    /** 두 번째 줄부터 맨 위 칸을 닫는 '---' 줄을 찾아 그 위치를 돌려준다. 없으면 -1 이다. */
    private static int indexOfClosing(List<String> lines) {
        for (int i = 1; i < lines.size(); i++) {
            if (lines.get(i).strip().equals("---")) {
                return i;
            }
        }
        return -1;
    }

    /**
     * {@code [a, b]} 처럼 대괄호로 감싼 한 줄 목록을 항목 목록으로 바꾼다. 항목 앞뒤 공백을 떼고 빈 항목은 버린다.
     * 대괄호로 감싸지 않은 값은 빈 목록으로 본다.
     */
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
