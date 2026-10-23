package com.capstone4.skillops.snapshot;

import java.util.List;

import tools.jackson.databind.PropertyNamingStrategies;
import tools.jackson.databind.annotation.JsonNaming;

/**
 * 한 skill 파일을 두 snapshot 사이에서 비교한 결과 record 다.
 * service/src/main/java/com/capstone4/skillops/snapshot/SkillDiffer.java 의 diff 가 만들고,
 * service/src/main/java/com/capstone4/skillops/snapshot/SkillDiffService.java 가 칸을 DiffSkillResult 로 옮긴다. 해시가 같아
 * 파일을 읽지 않았을 때는 SkillDiffService 의 compareSkillMd 가 unchanged 결과를 직접 만든다.
 *
 * @param status             modified, added, removed, unchanged 가운데 하나다.
 * @param frontmatterChanges 값이 달라진 frontmatter 키만 담는다(inputs, outputs, depends_on, steps 등).
 * @param hunks              본문과 frontmatter 의 줄 단위 변경 덩어리를 파일 위에서부터 순서대로 담는다.
 * @param frontmatterParseError 어느 쪽이든 frontmatter YAML 을 읽지 못하면 그 이유다. 이때 frontmatterChanges 는
 *                              비어 있고 hunks 만 믿을 수 있다. 정상이면 null 이다.
 */
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public record SkillFileDiff(String status, List<FrontmatterChange> frontmatterChanges, List<Hunk> hunks,
        String frontmatterParseError) {

    // 변경 상태 값이다. diff_skill 의 status 와 related_files[].status 계약이다.
    static final String ADDED = "added";
    static final String REMOVED = "removed";
    static final String MODIFIED = "modified";
    static final String UNCHANGED = "unchanged";

    /**
     * 두 쪽 값(글 또는 해시)으로 변경 상태를 정한다. before 가 없으면 after 도 없어도 added 다. SkillDiffer.diff 는 글로,
     * SkillDiffService 의 relatedFiles 는 해시로 부른다. frontend/src/utils/snapshotDiff.js 의 fileStatus 가 같은 규칙으로 화면의
     * 파일 상태를 정한다.
     */
    static String statusOf(String before, String after) {
        return before == null ? ADDED : after == null ? REMOVED : before.equals(after) ? UNCHANGED : MODIFIED;
    }

    /**
     * 값이 달라진 frontmatter 키 하나다. before 와 after 는 YAML 값 그대로이고 키가 없던 쪽은 null 이다. added 는 after 가
     * 목록일 때 before 에 없던 항목, removed 는 before 가 목록일 때 after 에서 빠진 항목이며 목록이 아니면 빈 목록이다.
     */
    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record FrontmatterChange(String field, Object before, Object after, List<String> added, List<String> removed) {
    }

    /**
     * 줄 단위 변경 덩어리 하나다.
     *
     * @param index  표시 순서(0부터)다. 영구 식별자로 쓰지 않는다.
     * @param hunkId 두 파일 해시와 변경 위치, 내용으로 만든 고정 ID 다. 다시 조회해도 같은 덩어리를 가리킨다.
     * @param part   frontmatter 나 body 다.
     * @param header 덩어리의 '@@' 머리줄이다.
     * @param before 문맥 줄은 빼고 지워진 줄만 담는다.
     * @param after  문맥 줄은 빼고 추가된 줄만 담는다.
     * @param diff   이 덩어리 하나만 담은 unified diff 다. 바뀌기 전 파일에 단독으로 적용된다.
     */
    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record Hunk(int index, String hunkId, String part, String header, String before, String after, String diff) {
    }
}
