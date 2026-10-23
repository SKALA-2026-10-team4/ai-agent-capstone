package com.capstone4.skillops.proposal;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.capstone4.skillops.proposal.ProposalDocument.Fact;
import com.capstone4.skillops.proposal.ProposalDocument.Section;
import com.capstone4.skillops.proposal.ProposalDocument.ToneWarning;

import tools.jackson.databind.json.JsonMapper;

/**
 * service/src/main/java/com/capstone4/skillops/proposal/ProposalDocumentWriter.java 가 만드는 본문의 문장과 구조를 보호하는 단위 테스트다.
 * F1 픽스처(service/src/test/resources/proposal/f1-proposed.json)로 개요 네 문장, 목차 순서와 key, 절마다의 문단과 사실,
 * 후보 카드의 판정, 결정 요청, 참고 정보를 확인하고, 원인을 모르는 제안서(service/src/test/resources/proposal/no-root-cause.json)도
 * 읽을 수 있는 본문이 되는지 본다.
 * 문장 틀이 ToneCheck 의 금지 표현을 쓰지 않는지, LLM 설명의 금지 표현은 고치지 않고 tone_warnings 에만 남는지도 확인한다.
 * 시험하지 않은 후보나 의도 확인 조건이 없을 때 시험했다거나 확인했다고 쓰지 않는지도 본다.
 * fixture 는 service/src/test/java/com/capstone4/skillops/proposal/ 의 ProposalServiceTest, ProposalCharacterizationTest,
 * ProposalDocumentWriterGoldenTest 도 가져다 쓰고, CREATED 는 ProposalDocumentWriterGoldenTest 가 가져다 쓴다.
 */
class ProposalDocumentWriterTest {

    static final JsonMapper JSON = JsonMapper.builder().build();
    /** 제안서를 만든 시각으로 넣는 고정 시각이다. 한국 시각으로 2026년 10월 21일 14시 10분이다. */
    static final Instant CREATED = Instant.parse("2026-10-21T05:10:00Z");

    /** service/src/test/resources/proposal 아래의 제안서 픽스처를 새 Map 으로 읽는다. 테스트가 고쳐도 다른 테스트에 번지지 않는다. */
    @SuppressWarnings("unchecked")
    static Map<String, Object> fixture(String name) {
        return JSON.readValue(Path.of("src/test/resources/proposal/" + name).toFile(), Map.class);
    }

    /** F1 픽스처로 본문을 만든다. */
    static ProposalDocument write() {
        return ProposalDocumentWriter.write("p_1", fixture("f1-proposed.json"), CREATED);
    }

    /** 새 검사 결과를 받은 제안서만 필수 정보의 통과·실패 문장을 추가한다. */
    @Test
    void explainsMetadataResultSeparatelyFromSkillLinks() {
        for (boolean metadataValid : List.of(true, false)) {
            Map<String, Object> proposal = fixture("f1-proposed.json");
            Map<String, Object> candidate = ProposalRules.maps(proposal.get("candidates")).getFirst();
            ProposalRules.map(candidate.get("results")).put("metadata_valid", metadataValid);
            candidate.put("valid", metadataValid);
            if (!metadataValid) {
                proposal.put("recommended_candidate_no", null);
                proposal.put("status", "no_valid_fix");
            }

            ProposalDocument document = ProposalDocumentWriter.write("p_1", proposal, CREATED);

            assertThat(candidates(document).parts().getFirst().paragraphs()).contains(metadataValid
                    ? "Skill의 필수 정보와 본문이 검사 기준을 충족합니다."
                    : "Skill의 필수 정보 또는 본문에 오류가 있어 이 후보를 사용할 수 없습니다.");
        }
    }

    /** flow 구조 검사 결과를 받은 후보는 운영자에게 통과·실패 이유를 보여 준다. */
    @Test
    void explainsFlowStructureResultSeparately() {
        for (boolean flowValid : List.of(true, false)) {
            Map<String, Object> proposal = fixture("f1-proposed.json");
            Map<String, Object> candidate = ProposalRules.maps(proposal.get("candidates")).getFirst();
            ProposalRules.map(candidate.get("results")).put("flow_valid", flowValid);
            candidate.put("valid", flowValid);
            if (!flowValid) {
                proposal.put("recommended_candidate_no", null);
                proposal.put("status", "no_valid_fix");
            }

            ProposalDocument document = ProposalDocumentWriter.write("p_1", proposal, CREATED);

            assertThat(candidates(document).parts().getFirst().paragraphs()).contains(flowValid
                    ? "실행 순서의 구조와 단계 선언을 모두 확인했습니다."
                    : "실행 순서의 구조 또는 단계 선언에 오류가 있어 이 후보를 사용할 수 없습니다.");
        }
    }

    /** 본문에서 key 가 같은 절을 찾는다. 없으면 테스트가 실패한다. */
    static Section section(ProposalDocument doc, String key) {
        return doc.sections().stream().filter(s -> s.key().equals(key)).findFirst().orElseThrow();
    }

    /** 수정안과 시험 결과 절을 찾는다. */
    static Section candidates(ProposalDocument doc) {
        return section(doc, "candidates");
    }

    /** 후보 설명(explanation)을 모두 지운 제안서 사본을 만든다. 문장 틀만 금지 표현 검사에 걸리게 하려는 것이다. */
    @SuppressWarnings("unchecked")
    static Map<String, Object> withoutExplanations(Map<String, Object> proposal) {
        Map<String, Object> copy = JSON.readValue(JSON.writeValueAsString(proposal), Map.class);
        ((List<Map<String, Object>>) copy.get("candidates")).forEach(c -> c.put("explanation", null));
        return copy;
    }

    /** 본문에 나오는 모든 글(제목, 개요, 절 제목, 문단, 인용, 글머리표, 사실, 후보 카드)을 모은다. */
    static List<String> allTexts(ProposalDocument doc) {
        List<String> texts = new ArrayList<>(List.of(doc.title(), doc.summary()));
        doc.sections().forEach(s -> collect(s, texts));
        return texts;
    }

    /** 절 하나와 그 안의 후보 카드에 있는 글을 texts 에 더한다. */
    static void collect(Section s, List<String> texts) {
        texts.add(s.heading());
        texts.addAll(s.paragraphs());
        if (s.quote() != null) {
            texts.add(s.quote());
        }
        texts.addAll(s.items());
        s.facts().forEach(f -> {
            texts.add(f.label());
            texts.add(f.value());
        });
        s.parts().forEach(p -> collect(p, texts));
    }

    @Test
    void summaryFollowsTheFixedSentences() {
        ProposalDocument doc = write();
        assertThat(doc.title()).isEqualTo("inventory-check 수정 제안서");
        assertThat(doc.summary()).isEqualTo(
                "2026년 10월 21일 14시 3분에 Skill 수정본으로 기준 주문을 시험했더니 payment 단계에서 주문이 멈췄습니다. "
                        + "원인은 inventory-check Skill의 변경에 있습니다. 이 변경만 되돌려 다시 돌렸더니 같은 주문이 끝까지 처리됐습니다. "
                        + "SkillOps가 수정안 2개를 실제 주문 흐름에서 시험했고, 그중 1개가 모든 확인을 통과했습니다. "
                        + "아래 내용을 확인한 뒤 후보 1번의 승인 여부를 정해 주세요.");
    }

    @Test
    void sectionsFollowTheTableOfContents() {
        assertThat(write().sections()).extracting(Section::heading)
                .containsExactly("개요", "발생 경위", "원인", "변경 의도", "수정안과 시험 결과", "추천과 결정 요청", "참고 정보");
        assertThat(write().sections()).extracting(Section::key)
                .containsExactly("overview", "what_happened", "cause", "intent", "candidates", "decision", "reference");
    }

    @Test
    void whatHappenedListsWhenWhereAndWhichValue() {
        Section s = section(write(), "what_happened");
        assertThat(s.paragraphs()).containsExactly(
                "담당자가 Skill 편집 화면에서 수정본을 저장했습니다. 저장 직후 SkillOps가 이 수정본으로 기준 주문을 모의 실행했고, "
                        + "2026년 10월 21일 14시 3분에 payment 단계에서 주문이 멈췄습니다.",
                "이 단계가 받아야 할 값이 앞 단계에서 넘어오지 않았습니다.");
        assertThat(s.facts()).containsExactly(
                new Fact("시작 계기", "Skill 수정본 저장"),
                new Fact("멈춘 단계", "payment"),
                new Fact("오류 종류", "CONTRACT_VIOLATION (Skill 사이 입력값 불일치)"),
                new Fact("받지 못한 값", "inventory.reserved"),
                new Fact("오류 메시지", "required input inventory.reserved not found"),
                new Fact("실패한 실행", "33333333-3333-4333-8333-333333333333"),
                new Fact("수정 전 Skill 세트", "11111111-1111-4111-8111-111111111111"),
                new Fact("수정 후 Skill 세트", "22222222-2222-4222-8222-222222222222"));
    }

    @Test
    void causeSeparatesWhereItStoppedFromWhatChanged() {
        Section s = section(write(), "cause");
        assertThat(s.paragraphs()).containsExactly(
                "주문은 payment 단계에서 멈췄지만 원인은 inventory-check Skill에 있습니다.",
                "inventory-check Skill에서 아래 부분이 바뀌었습니다.",
                "SkillOps는 이 변경만 되돌린 연습 공간에서 실패한 주문을 다시 돌렸습니다. "
                        + "그러자 주문이 끝까지 처리됐고, 그래서 이 변경을 원인으로 확정했습니다.");
        assertThat(s.facts()).containsExactly(
                new Fact("바뀌기 전", "outputs: [inventory.available, inventory.reserved]"),
                new Fact("바뀐 뒤", "outputs: [inventory.available]"),
                new Fact("원인 확인 실행", "r_a0000000000000000000000000000001, r_a0000000000000000000000000000002"));
    }

    @Test
    void intentQuotesTheTextAndListsChecks() {
        Section s = section(write(), "intent");
        assertThat(s.quote()).isEqualTo("예약 가능 수량을 계속 내놓고 싶다.");
        assertThat(s.items()).containsExactly("inventory.reservable_quantity != null");
        assertThat(s.paragraphs()).containsExactly(
                "SkillOps는 이번 변경의 의도를 아래와 같이 이해했습니다.",
                "수정안을 시험할 때 아래 조건이 지켜지는지도 함께 확인했습니다.",
                "이 해석이 실제 의도와 다르면 승인하지 말고 거절해 주세요.");
    }

    @Test
    void passingCandidateIsJudgedApplicable() {
        Section first = candidates(write()).parts().get(0);
        assertThat(first.heading()).isEqualTo("후보 1번 · 바뀐 Skill만 고치기");
        assertThat(first.candidateNo()).isEqualTo(1);
        assertThat(first.paragraphs()).containsExactly(
                "실패했던 주문을 다시 돌렸더니 끝까지 처리됐습니다.",
                "기준 주문 3건도 모두 이전과 같게 처리됐습니다.",
                "의도 확인 조건도 모두 지켜졌습니다.",
                "고친 뒤에도 Skill 사이의 입력과 출력이 모두 이어집니다.");
        assertThat(first.facts()).containsExactly(
                new Fact("판정", "적용 가능 · 1순위"),
                new Fact("고치는 Skill", "inventory-check"),
                new Fact("AI 설명", "재고 확인 Skill이 예약 여부를 다시 내놓게 했습니다."),
                new Fact("근거 실행", "r_c1000000000000000000000000000001, r_c1000000000000000000000000000002"));
    }

    @Test
    void failedCandidateExplainsEachCriterion() {
        Section second = candidates(write()).parts().get(1);
        assertThat(second.heading()).isEqualTo("후보 2번 · 주고받는 값까지 함께 고치기");
        assertThat(second.candidateNo()).isEqualTo(2);
        assertThat(second.paragraphs()).containsExactly(
                "실패했던 주문을 다시 돌렸더니 끝까지 처리됐습니다.",
                "기준 주문 3건 가운데 1건이 이전과 다르게 처리됐습니다.",
                "의도 확인 조건도 모두 지켜졌습니다.",
                "고친 뒤에 입력을 받지 못하는 Skill이 남습니다.");
        assertThat(second.facts()).contains(new Fact("판정", "적용 불가"),
                new Fact("고치는 Skill", "payment, shipping-reserve"));
    }

    @Test
    void candidatesSectionIntroducesTheTrials() {
        assertThat(candidates(write()).paragraphs()).containsExactly(
                "SkillOps가 수정안 2개를 만들어 각각 따로 마련한 연습 공간에서 시험했습니다.",
                "실패했던 주문과 기준 주문 3건을 돌렸고, 의도 확인 조건도 함께 확인했습니다.");
    }

    @Test
    void decisionNamesTheRecommendationAndWhatApprovalDoes() {
        assertThat(section(write(), "decision").paragraphs()).containsExactly(
                "추천 후보는 1번입니다.",
                "모든 확인을 통과한 수정안이 이 후보 하나뿐입니다.",
                "운영자가 의도 해석을 확인하고 승인하면 승인 기록과 승인 번호가 남습니다. "
                        + "다만 운영 Skill에 반영하는 단계는 아직 연결되지 않았습니다. "
                        + "그래서 승인한 뒤에도 운영 주문은 지금 Skill로 처리됩니다.",
                "의도 해석이 틀렸거나 수정안이 맞지 않으면 거절해 주세요. "
                        + "거절한 뒤에도 후보 내용을 Skill 편집 화면의 초안으로 가져와 직접 고칠 수 있습니다.");
    }

    @Test
    void referenceCarriesIdsAndCreationTime() {
        assertThat(section(write(), "reference").facts()).containsExactly(
                new Fact("제안서 번호", "p_1"),
                new Fact("진단 번호", "5b0c2f3e-8f0a-4a57-9a3c-1c2d3e4f5a6b"),
                new Fact("변경 번호", "c_20261021_01"),
                new Fact("제안서를 만든 시각", "2026년 10월 21일 14시 10분"));
    }

    @Test
    void noRootCauseStillProducesAReadableDocument() {
        ProposalDocument doc = ProposalDocumentWriter.write("p_2", fixture("no-root-cause.json"), CREATED);
        assertThat(doc.title()).isEqualTo("Skill 변경 진단 결과");
        assertThat(doc.summary()).isEqualTo(
                "2026년 10월 21일 15시 30분에 들어온 주문이 payment 단계에서 멈췄습니다. "
                        + "바뀐 부분을 하나씩 되돌려 시험했지만 원인을 하나로 확정하지 못했습니다. "
                        + "시험할 수정안은 만들지 못했습니다. 담당자가 Skill을 직접 고쳐야 합니다.");
        assertThat(doc.sections()).extracting(Section::key).doesNotContain("intent");
        assertThat(section(doc, "decision").paragraphs()).containsExactly(
                "모든 확인을 통과한 수정안이 없어 승인할 안이 없습니다.",
                "담당자가 Skill 편집 화면에서 직접 고친 뒤 다시 저장해 주세요. 저장하면 SkillOps가 같은 검증을 다시 시작합니다.",
                "이 제안서는 확인한 뒤 거절해서 닫을 수 있습니다.");
        assertThat(candidates(doc).paragraphs()).containsExactly("시험할 수정안을 만들지 못했습니다.");
    }

    @Test
    void templatesNeverUseBannedPhrases() {
        for (String name : List.of("f1-proposed.json", "no-root-cause.json")) {
            ProposalDocument doc = ProposalDocumentWriter.write("p_1", withoutExplanations(fixture(name)), CREATED);
            allTexts(doc).forEach(text -> assertThat(ToneCheck.findIn(text)).as(text).isEmpty());
            assertThat(doc.toneWarnings()).isEmpty();
        }
    }

    @Test
    void explanationPhrasesAreRecordedNotRewritten() {
        ProposalDocument doc = write();
        assertThat(doc.toneWarnings()).containsExactly(new ToneWarning("candidates[2].explanation", "을 통해"));
        assertThat(candidates(doc).parts().get(1).facts())
                .contains(new Fact("AI 설명", "이 기능을 통해 연결을 맞췄습니다."));
    }

    // ── 시험하지 않은 것을 시험했다고 쓰지 않는다(최종 리뷰) ─────────────────

    /** 2번 후보를 원문에 적용하지 못해 시험하지 않은 후보로 바꾼 F1 제안서다. */
    @SuppressWarnings("unchecked")
    static Map<String, Object> withUntestedSecondCandidate() {
        Map<String, Object> proposal = fixture("f1-proposed.json");
        Map<String, Object> second = ((List<Map<String, Object>>) proposal.get("candidates")).get(1);
        second.put("validation_error", "patch context does not match");
        second.put("results", null);
        second.put("evidence_run_ids", List.of());
        return proposal;
    }

    /** 의도 확인 조건(intent.checks)을 비운 F1 제안서다. */
    @SuppressWarnings("unchecked")
    static Map<String, Object> withoutIntentChecks() {
        Map<String, Object> proposal = fixture("f1-proposed.json");
        ((Map<String, Object>) proposal.get("intent")).put("checks", List.of());
        return proposal;
    }

    @Test
    void untestedCandidateIsNotCountedAsTried() {
        ProposalDocument doc = ProposalDocumentWriter.write("p_1", withUntestedSecondCandidate(), CREATED);
        assertThat(doc.summary()).contains(
                "SkillOps가 수정안 2개를 만들었고, 그중 1개를 실제 주문 흐름에서 시험했습니다. 모든 확인을 통과한 안은 1개입니다.");
        assertThat(candidates(doc).paragraphs().get(0)).isEqualTo(
                "SkillOps가 수정안 2개를 만들었고, 원문에 적용할 수 있는 1개를 각각 따로 마련한 연습 공간에서 시험했습니다.");
    }

    @Test
    void nothingTestedIsSaidPlainly() {
        Map<String, Object> proposal = withUntestedSecondCandidate();
        Map<String, Object> first = candidate(proposal, 0);
        first.put("validation_error", "patch context does not match");
        first.put("results", null);
        proposal.put("status", "no_valid_fix");
        ProposalDocument doc = ProposalDocumentWriter.write("p_1", proposal, CREATED);
        assertThat(doc.summary()).contains(
                "SkillOps가 수정안 2개를 만들었지만 지금 Skill 원문에 적용할 수 있는 안이 없어 시험하지 못했습니다.");
        assertThat(candidates(doc).paragraphs()).containsExactly(
                "SkillOps가 수정안 2개를 만들었지만 지금 Skill 원문에 적용할 수 있는 안이 없어 시험하지 못했습니다.");
    }

    @Test
    void noIntentChecksAreNotClaimed() {
        ProposalDocument doc = ProposalDocumentWriter.write("p_1", withoutIntentChecks(), CREATED);
        assertThat(candidates(doc).paragraphs()).containsExactly(
                "SkillOps가 수정안 2개를 만들어 각각 따로 마련한 연습 공간에서 시험했습니다.",
                "실패했던 주문과 기준 주문 3건을 돌렸습니다.");
        assertThat(candidates(doc).parts().get(0).paragraphs()).contains("의도 확인 조건이 없어 의도는 따로 확인하지 않았습니다.")
                .doesNotContain("의도 확인 조건도 모두 지켜졌습니다.");
    }

    /** 제안서의 index 번째 후보를 꺼낸다. index 는 0부터 센다. */
    @SuppressWarnings("unchecked")
    static Map<String, Object> candidate(Map<String, Object> proposal, int index) {
        return ((List<Map<String, Object>>) proposal.get("candidates")).get(index);
    }
}
