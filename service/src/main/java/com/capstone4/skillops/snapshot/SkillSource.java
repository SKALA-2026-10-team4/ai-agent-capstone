package com.capstone4.skillops.snapshot;

/**
 * service/src/main/java/com/capstone4/skillops/snapshot/DependencyAnalyzer.java 에 넘기는 SKILL.md 하나다. path 와
 * fileHash 는 결과 노드에 그대로 실리고, text 는 frontmatter 를 읽을 원문이다.
 * service/src/main/java/com/capstone4/skillops/snapshot/DependencyGraphService.java 의 fetch 는 주문 Agent manifest 의 경로와
 * 해시를 그대로 넣어 만들고, service/src/main/java/com/capstone4/skillops/workspace/WorkspaceService.java 의 analyze 는 연습 공간
 * 원문으로 fromText 를 불러 만든다.
 */
public record SkillSource(String path, String fileHash, String text) {

    /** manifest 해시가 없는 글(연습 공간 원문 등)에서 fileHash 를 Sha256.hex 로 직접 계산해 만든다. */
    public static SkillSource fromText(String path, String text) {
        return new SkillSource(path, Sha256.hex(text), text);
    }
}
