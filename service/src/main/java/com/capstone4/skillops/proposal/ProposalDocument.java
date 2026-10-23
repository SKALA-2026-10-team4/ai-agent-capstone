package com.capstone4.skillops.proposal;

import java.util.List;

import tools.jackson.databind.PropertyNamingStrategies;
import tools.jackson.databind.annotation.JsonNaming;

/**
 * 운영자가 읽는 제안서 본문이다. 제목, 개요 한 문단, 목차 순서의 절 목록, 말투 경고로 이루어진다.
 * 저장할 때 service/src/main/java/com/capstone4/skillops/proposal/ProposalDocumentWriter.java 가 한 번 만들고,
 * service/src/main/java/com/capstone4/skillops/proposal/ProposalService.java 가 snake_case 키의 Map 으로 바꿔 proposals.document 칸에 보관한다.
 * 화면은 이 모양을 그대로 그린다. frontend/src/views/ProposalView.vue 가 title 을 화면 머리에 쓰고,
 * frontend/src/components/ProposalDocument.vue 가 sections 를 그린다. 목록 응답(service/src/main/java/com/capstone4/skillops/api/ProposalController.java)은
 * title 과 summary 를 꺼내 쓴다. JSON 키 이름은 화면과 맞춘 약속이므로 칸 이름을 바꾸면 화면도 함께 고친다.
 *
 * @param toneWarnings 후보 설명(explanation)에서 ToneCheck 가 찾은 금지 표현 목록이다. 기록으로만 남기고 화면에는 보여 주지 않는다
 */
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public record ProposalDocument(String title, String summary, List<Section> sections, List<ToneWarning> toneWarnings) {

    /**
     * 목차의 절 하나이거나, 수정안 절 안에 들어가는 후보 카드 하나(parts 의 원소)다.
     * key 는 절의 종류다. frontend/src/components/ProposalDocument.vue 가 key 가 intent 와 candidates 인 절 아래에 slot 을 두고,
     * frontend/src/views/ProposalView.vue 가 그 자리에 의도 확인 체크와 고른 후보의 패치 비교를 넣는다. 그래서 key 값을 바꾸면 화면도 고친다.
     * ProposalDocumentWriter 는 목록 칸을 비어 있어도 null 이 아닌 빈 목록으로 채운다. 화면이 목록의 length 를 바로 읽기 때문이다.
     *
     * @param quote       따로 인용해 보여 줄 글이다. 변경 의도 절의 intent.text 가 들어가고, 없으면 null 이다
     * @param items       글머리표 목록이다. 변경 의도 절의 의도 확인 조건이 들어간다
     * @param parts       수정안 절이 품는 후보 카드 목록이다. 다른 절에서는 빈 목록이다
     * @param candidateNo 후보 카드이면 그 후보 번호이고, 아니면 null 이다. 화면은 이 번호로 승인할 후보를 고른다
     */
    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record Section(String key, String heading, List<String> paragraphs, String quote, List<String> items,
            List<Fact> facts, List<Section> parts, Integer candidateNo) {
    }

    /**
     * 이름과 값이 한 쌍인 사실 한 줄이다. 화면은 label 을 이름 칸에, value 를 값 칸에 보여 준다.
     * 값이 실행 번호 목록이면 frontend/src/components/ProposalFacts.vue 가 번호마다 실행 화면 링크로 바꾼다.
     */
    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record Fact(String label, String value) {
    }

    /** 금지 표현 하나가 어디서 나왔는지 남긴다. where 는 candidates[후보 번호].explanation 모양이고 phrase 는 찾은 표현이다. */
    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record ToneWarning(String where, String phrase) {
    }
}
