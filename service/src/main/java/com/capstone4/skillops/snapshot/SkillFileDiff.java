package com.capstone4.skillops.snapshot;

import java.util.List;

import tools.jackson.databind.PropertyNamingStrategies;
import tools.jackson.databind.annotation.JsonNaming;

/**
 * 한 skill 파일의 두 snapshot 간 차이.
 *
 * @param status             modified · added · removed · unchanged
 * @param frontmatterChanges 값이 달라진 frontmatter 키만 (inputs·outputs·depends_on·steps 등)
 * @param hunks              본문·frontmatter 의 줄 단위 변경 덩어리, 파일 위에서부터 순서대로
 * @param frontmatterParseError 어느 쪽이든 frontmatter YAML 을 읽지 못하면 그 이유. 이때 frontmatterChanges 는
 *                              비어 있고 hunks 만 믿을 수 있다. 정상이면 null
 */
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public record SkillFileDiff(String status, List<FrontmatterChange> frontmatterChanges, List<Hunk> hunks,
        String frontmatterParseError) {

    /** 목록 값이면 added·removed 를, 아니면 빈 목록을 채운다. */
    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record FrontmatterChange(String field, Object before, Object after, List<String> added, List<String> removed) {
    }

    /**
     * @param index  표시 순서(0부터). 영구 식별자로 쓰지 않는다
     * @param hunkId 두 파일 해시와 변경 위치·내용으로 만든 고정 ID. 다시 조회해도 같은 덩어리를 가리킨다
     * @param part   frontmatter · body
     * @param before 지워진 줄만 (문맥 줄 제외)
     * @param after  추가된 줄만 (문맥 줄 제외)
     * @param diff   이 덩어리 하나만 담은 unified diff. 바뀌기 전 파일에 단독으로 적용된다
     */
    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record Hunk(int index, String hunkId, String part, String header, String before, String after, String diff) {
    }
}
