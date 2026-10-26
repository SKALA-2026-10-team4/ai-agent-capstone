package com.capstone4.skillops.proposal;

import static com.capstone4.skillops.proposal.ProposalDocumentWriterTest.CREATED;
import static com.capstone4.skillops.proposal.ProposalDocumentWriterTest.allTexts;
import static com.capstone4.skillops.proposal.ProposalDocumentWriterTest.candidates;
import static com.capstone4.skillops.proposal.ProposalDocumentWriterTest.fixture;
import static com.capstone4.skillops.proposal.ProposalDocumentWriterTest.section;
import static com.capstone4.skillops.proposal.ProposalDocumentWriterTest.withoutExplanations;
import static org.assertj.core.api.Assertions.assertThat;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.capstone4.skillops.proposal.ProposalDocument.Fact;
import com.capstone4.skillops.proposal.ProposalDocument.Section;

/**
 * service/src/main/java/com/capstone4/skillops/proposal/ProposalDocumentWriter.java 가 행동 변경 진단의 새 칸을 본문에 옮기는지
 * 보호한다(docs/plans/2026-10-26-behavior-edit-root-fix.md 2.3, 2.5, 2.7 절). 원인 종류 incomplete_edit 의 문장, 담당자 기대 결과
 * (intent.expectations)의 글머리표와 출처 문장, encode_rule 후보 이름, 되돌리기·근거 불일치 문장, 서비스 재판정 절(server_overrides)을
 * 확인하고, 새 문장 틀도 ToneCheck 의 금지 표현을 쓰지 않는지 본다. 장면은 B-01(선입금 주문만 결제)이다.
 */
class ProposalDocumentBehaviorEditTest {

    /** F1 픽스처를 B-01 모양으로 바꾼다. 오류 없이 의도만 실현되지 않았고, 2번 후보는 되돌리기이며 근거가 맞지 않는다. */
    @SuppressWarnings("unchecked")
    static Map<String, Object> behaviorEdit() {
        Map<String, Object> proposal = fixture("f1-proposed.json");
        List<Map<String, Object>> expectations = List.of(
                expectation("order.payment_method == 'card'", "order.final_status in ['HOLD', 'REJECTED']", "payment.status == null"),
                expectation(null, "order.final_status != 'FAILED'"));
        Map<String, Object> ownerIntent = new LinkedHashMap<>();
        ownerIntent.put("text", "선입금 주문만 결제한다");
        ownerIntent.put("expectations", expectations);
        ((Map<String, Object>) proposal.get("trigger")).put("intent", ownerIntent);
        proposal.put("failure", null);
        Map<String, Object> root = (Map<String, Object>) proposal.get("root_cause");
        root.put("skill", "payment");
        root.put("kind", "incomplete_edit");
        root.put("repro_scenario_ids", List.of("g-01", "g-03"));
        Map<String, Object> intent = new LinkedHashMap<>();
        intent.put("text", "선입금 주문만 결제한다");
        intent.put("source", "owner");
        intent.put("checks", List.of());
        intent.put("expectations", expectations);
        proposal.put("intent", intent);
        Map<String, Object> first = ProposalDocumentWriterTest.candidate(proposal, 0);
        first.put("kind", "encode_rule");
        Map<String, Object> second = ProposalDocumentWriterTest.candidate(proposal, 1);
        second.put("is_revert", true);
        second.put("weakens_contract", true);
        ((Map<String, Object>) second.get("results")).put("evidence_matches", false);
        proposal.put("server_overrides", List.of(
                override(2, "results.evidence_matches", null, false),
                override(2, "results.keeps_old_orders", Map.of("passed", 3, "total", 3), Map.of("passed", 2, "total", 3)),
                override(null, "status", "no_valid_fix", "proposed")));
        return proposal;
    }

    static Map<String, Object> expectation(String scope, String... checks) {
        Map<String, Object> e = new LinkedHashMap<>();
        e.put("scope", scope);
        e.put("checks", List.of(checks));
        return e;
    }

    static Map<String, Object> override(Integer candidateNo, String field, Object agentValue, Object serverValue) {
        Map<String, Object> entry = new LinkedHashMap<>();
        entry.put("candidate_no", candidateNo);
        entry.put("field", field);
        entry.put("agent_value", agentValue);
        entry.put("server_value", serverValue);
        return entry;
    }

    static ProposalDocument write() {
        return ProposalDocumentWriter.write("p_1", behaviorEdit(), CREATED);
    }

    @Test
    void incompleteEditExplainsThatTheIntentWasNotRealized() {
        ProposalDocument doc = write();

        assertThat(doc.title()).isEqualTo("payment 수정 제안서");
        assertThat(doc.summary()).startsWith(
                "Skill 수정본으로 기준 주문을 시험했더니 오류 없이 끝났지만, 담당자의 의도대로 처리되지 않은 주문이 있었습니다. "
                        + "원인은 payment Skill의 수정이 담당자의 의도를 실행 규칙으로 담지 못한 데 있습니다.");
        assertThat(section(doc, "what_happened").paragraphs().getFirst()).endsWith(
                "주문은 오류 없이 끝났지만 담당자의 의도와 다르게 처리된 주문이 있었습니다.");
        assertThat(section(doc, "what_happened").facts()).contains(new Fact("의도대로 처리되지 않은 주문", "g-01, g-03"));
        Section cause = section(doc, "cause");
        assertThat(cause.paragraphs()).containsExactly(
                "payment Skill에서 아래 부분이 바뀌었습니다.",
                "SkillOps는 이 수정본으로 기준 주문을 돌렸습니다. 주문은 오류 없이 끝났지만 담당자의 의도대로 처리되지 않은 주문이 있었고, "
                        + "그래서 이 수정이 의도를 다 담지 못했다고 판단했습니다.",
                "본문에 적은 정책은 주문 Agent가 그대로 실행하지 않습니다. 의도대로 처리하려면 Skill의 실행 규칙(rules)에 정책을 옮겨야 합니다.");
        assertThat(cause.facts()).contains(new Fact("의도대로 처리되지 않은 주문", "g-01, g-03"));
    }

    @Test
    void intentSectionListsTheOwnerExpectations() {
        Section intent = section(write(), "intent");

        assertThat(intent.quote()).isEqualTo("선입금 주문만 결제한다");
        assertThat(intent.items()).containsExactly(
                "조건 order.payment_method == 'card' 에 맞는 주문: order.final_status in ['HOLD', 'REJECTED'], payment.status == null",
                "모든 주문: order.final_status != 'FAILED'");
        assertThat(intent.paragraphs()).containsExactly(
                "SkillOps는 이번 변경의 의도를 아래와 같이 이해했습니다.",
                "아래 기대 결과는 담당자가 수정본을 저장할 때 직접 적었습니다.",
                "수정안을 시험할 때 아래 조건이 지켜지는지도 함께 확인했습니다.",
                "이 해석이 실제 의도와 다르면 승인하지 말고 거절해 주세요.");
    }

    @Test
    void inferredExpectationsAreLabelled() {
        Map<String, Object> proposal = behaviorEdit();
        ProposalRules.map(proposal.get("intent")).put("source", "inferred");

        assertThat(section(ProposalDocumentWriter.write("p_1", proposal, CREATED), "intent").paragraphs()).contains(
                "담당자가 기대 결과를 적지 않아 SkillOps가 의도 문장과 바뀐 내용으로 기대 결과를 만들었습니다.");
    }

    @Test
    void candidateCardsExplainEncodeRuleRevertAndEvidence() {
        Section candidates = candidates(write());

        assertThat(candidates.paragraphs()).contains("실패했던 주문과 기준 주문 3건을 돌렸고, 의도 확인 조건도 함께 확인했습니다.");
        Section first = candidates.parts().get(0);
        assertThat(first.heading()).isEqualTo("후보 1번 · 업무 정책을 실행 규칙으로 옮기기");
        assertThat(first.paragraphs()).startsWith("의도대로 처리되지 않던 주문이 이제 의도대로 처리됐습니다.");
        Section second = candidates.parts().get(1);
        assertThat(second.paragraphs()).startsWith(
                "제안서의 수정 내용이 연습 공간에서 시험한 수정과 맞지 않아 이 시험 결과를 쓸 수 없습니다.");
        assertThat(second.paragraphs()).contains(
                "이 수정안은 담당자가 바꾼 Skill을 수정 전으로 되돌립니다. 담당자 의도가 있는 진단에서는 쓸 수 없습니다.",
                "이 수정안은 실행 규칙이나, 규칙과 의도가 쓰는 입력을 지웁니다. 담당자 의도가 있는 진단에서는 쓸 수 없습니다.");
    }

    /** 담당자 의도가 없는 진단에서는 되돌리기가 허용되므로 되돌리기 문장을 넣지 않는다. */
    @Test
    void revertSentenceNeedsAnOwnerIntent() {
        Map<String, Object> proposal = behaviorEdit();
        ProposalRules.map(proposal.get("trigger")).remove("intent");

        Section second = candidates(ProposalDocumentWriter.write("p_1", proposal, CREATED)).parts().get(1);

        assertThat(second.paragraphs()).noneMatch(p -> p.contains("되돌립니다") || p.contains("지웁니다"));
    }

    @Test
    void serverOverridesGetTheirOwnSectionBeforeTheDecision() {
        ProposalDocument doc = write();

        assertThat(doc.sections()).extracting(Section::key).containsExactly("overview", "what_happened", "cause", "intent",
                "candidates", "server_overrides", "decision", "reference");
        Section overrides = section(doc, "server_overrides");
        assertThat(overrides.heading()).isEqualTo("서비스 재판정");
        assertThat(overrides.facts()).containsExactly(
                new Fact("후보 2번 · 근거 일치", "Agent 없음 → 서비스 거짓"),
                new Fact("후보 2번 · 기준 주문 통과", "Agent 3/3 → 서비스 2/3"),
                new Fact("제안서 상태", "Agent no_valid_fix → 서비스 proposed"));
    }

    /** server_overrides 가 비어 있으면 서비스 재판정 절을 넣지 않는다. */
    @Test
    void emptyOverridesAddNoSection() {
        Map<String, Object> proposal = behaviorEdit();
        proposal.put("server_overrides", List.of());

        assertThat(ProposalDocumentWriter.write("p_1", proposal, CREATED).sections()).extracting(Section::key)
                .doesNotContain("server_overrides");
    }

    @Test
    void newTemplatesNeverUseBannedPhrases() {
        ProposalDocument doc = ProposalDocumentWriter.write("p_1", withoutExplanations(behaviorEdit()), CREATED);

        allTexts(doc).forEach(text -> assertThat(ToneCheck.findIn(text)).as(text).isEmpty());
        assertThat(doc.toneWarnings()).isEmpty();
    }
}
