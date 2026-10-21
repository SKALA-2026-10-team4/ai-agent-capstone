package com.capstone4.skillops.proposal;

import java.util.List;

import tools.jackson.databind.PropertyNamingStrategies;
import tools.jackson.databind.annotation.JsonNaming;

/**
 * 운영자가 읽는 제안서 본문. 저장할 때 {@link ProposalDocumentWriter} 가 한 번 만들고 제안서와 함께 보관한다.
 * 화면은 이 모양을 그대로 그린다.
 *
 * @param toneWarnings LLM 이 쓴 글에서 {@link ToneCheck} 가 찾은 표현. 화면에는 보여 주지 않는다
 */
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public record ProposalDocument(String title, String summary, List<Section> sections, List<ToneWarning> toneWarnings) {

    /**
     * 목차의 한 절, 또는 수정안 절 안의 후보 하나(parts).
     *
     * @param quote       따로 인용해 보여 줄 글(변경 의도). 없으면 null
     * @param items       글머리표 목록(의도 확인 조건)
     * @param candidateNo 후보 한 칸이면 그 후보 번호, 아니면 null
     */
    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record Section(String key, String heading, List<String> paragraphs, String quote, List<String> items,
            List<Fact> facts, List<Section> parts, Integer candidateNo) {
    }

    /** "이름: 값" 한 줄 */
    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record Fact(String label, String value) {
    }

    /** where 는 candidates[후보 번호].explanation 모양이다 */
    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record ToneWarning(String where, String phrase) {
    }
}
