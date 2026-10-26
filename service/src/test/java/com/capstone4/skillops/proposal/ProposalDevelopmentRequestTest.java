package com.capstone4.skillops.proposal;

import static com.capstone4.skillops.proposal.ProposalDocumentWriterTest.CREATED;
import static com.capstone4.skillops.proposal.ProposalDocumentWriterTest.allTexts;
import static com.capstone4.skillops.proposal.ProposalDocumentWriterTest.candidates;
import static com.capstone4.skillops.proposal.ProposalDocumentWriterTest.fixture;
import static com.capstone4.skillops.proposal.ProposalDocumentWriterTest.section;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;

import java.time.Clock;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Consumer;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import com.capstone4.skillops.proposal.ProposalDocument.Fact;
import com.capstone4.skillops.proposal.ProposalDocument.Section;
import com.capstone4.skillops.proposal.ProposalDocument.ToneWarning;
import com.capstone4.skillops.proposal.ProposalService.SavedProposal;
import com.capstone4.skillops.scenario.TestFixtures.InMemoryRunLedger;

/**
 * 개발 요청서(development_request)를 담은 제안서의 저장과 본문을 보호한다(docs/plans/2026-10-26-behavior-edit-root-fix.md 2.11 절).
 * 장면은 담당자가 shipping-reserve 에 어떤 업무 도구도 만들지 않는 값(shipping.weather)을 넣은 사용자 실험 1 이다
 * (service/src/test/resources/proposal/needs-tool.json).
 * 저장 쪽은 service/src/main/java/com/capstone4/skillops/proposal/ProposalValidator.java 의 형식 검사 메시지 전체와 순서, 근거 실행
 * 대조, status 처리(Agent 의 proposed 는 서버 값으로 덮고, 서버가 proposed 로 정하면 거부)와 저장된 payload 에 개발 요청서가 그대로
 * 남는지를 본다. 본문 쪽은 service/src/main/java/com/capstone4/skillops/proposal/ProposalDocumentWriter.java 의 개요, 개발 요청 절,
 * 임시 대안 절, 결정 요청 문장과 절 순서, 문장 틀이 ToneCheck 에 걸리지 않는지, Agent 가 쓴 제목과 요약의 금지 표현이 tone_warnings 에
 * 남는지를 본다. 본문 전체 글자는 ProposalDocumentWriterGoldenTest 의 needs_tool 골든 파일이 고정한다.
 */
class ProposalDevelopmentRequestTest {

    /** 원인 실험에서 수정본 그대로 돌려 떨어진 실행이다. */
    static final String CONTROL_RUN = "r_d0000000000000000000000000000001";
    /** 원인 실험에서 변경을 되돌려(임시 대안을 넣어) 돌려 통과한 실행이다. */
    static final String ABLATION_RUN = "r_d0000000000000000000000000000002";
    /** 장부에 없는 실행 번호다. */
    static final String UNKNOWN_RUN = "r_ffffffffffffffffffffffffffffffff";
    /** needs-tool.json 의 재현 시나리오다. */
    static final String REPRO = "f-55555555-5555-4555-8555-555555555555";

    ProposalRepository repository = mock(ProposalRepository.class);
    ProposalTestBench bench = new ProposalTestBench().withF1();
    InMemoryRunLedger ledger = bench.ledger;
    ProposalService service = new ProposalService(repository, ledger, bench.judge(),
            Clock.fixed(CREATED, ZoneOffset.UTC));

    @BeforeEach
    void setUp() {
        given(repository.save(any(Proposal.class))).willAnswer(call -> call.getArgument(0));
        given(repository.findByThreadId(anyString())).willReturn(Optional.empty());
        ledger.record("w_ablation_weather", REPRO, CONTROL_RUN, false);
        ledger.record("w_ablation_weather", REPRO, ABLATION_RUN, true);
    }

    /** needs-tool.json 을 새로 읽어 change 로 고친 제안서를 돌려준다. */
    static Map<String, Object> needsTool(Consumer<Map<String, Object>> change) {
        Map<String, Object> proposal = fixture("needs-tool.json");
        change.accept(proposal);
        return proposal;
    }

    /** 제안서의 development_request 묶음을 꺼낸다. */
    static Map<String, Object> request(Map<String, Object> proposal) {
        return ProposalRules.map(proposal.get("development_request"));
    }

    /** development_request 의 index 번째(0부터) 빈틈을 꺼낸다. */
    static Map<String, Object> gap(Map<String, Object> proposal, int index) {
        return ProposalRules.maps(request(proposal).get("gaps")).get(index);
    }

    /** save 가 정확히 이 메시지의 IllegalArgumentException 으로 거부하고 아무것도 저장하지 않았는지 본다. */
    void assertRejected(Map<String, Object> proposal, String message) {
        assertThatThrownBy(() -> service.save(proposal)).isExactlyInstanceOf(IllegalArgumentException.class)
                .hasMessage(message);
        then(repository).should(never()).save(any());
    }

    /** 저장소의 save 에 넘어간 제안서를 꺼낸다. */
    Proposal captured() {
        ArgumentCaptor<Proposal> saved = ArgumentCaptor.forClass(Proposal.class);
        then(repository).should().save(saved.capture());
        return saved.getValue();
    }

    /** needs-tool.json 으로 본문을 만든다. */
    static ProposalDocument write(Map<String, Object> proposal) {
        return ProposalDocumentWriter.write("p_1", proposal, CREATED);
    }

    // ── 저장 ────────────────────────────────────────────────────────────

    /** 형식이 맞고 근거 실행이 장부에 있으면 no_valid_fix 로 저장하고, 개발 요청서를 받은 그대로 payload 에 남긴다. */
    @Test
    void validRequestIsStoredUnchangedInThePayload() {
        Map<String, Object> input = needsTool(p -> {
        });

        SavedProposal saved = service.save(input);

        assertThat(saved.status()).isEqualTo("no_valid_fix");
        assertThat(saved.validCandidateNos()).isEmpty();
        Proposal stored = captured();
        assertThat(stored.getStatus()).isEqualTo("no_valid_fix");
        assertThat(stored.getPayload().get("development_request")).isEqualTo(fixture("needs-tool.json").get("development_request"))
                .isNotSameAs(input.get("development_request"));
        assertThat(stored.getPayload()).containsEntry("server_overrides", List.of());
        assertThat(stored.getDocument()).containsEntry("summary", write(stored.getPayload()).summary());
    }

    /** 값이 null 인 development_request 는 없는 것으로 본다. 합격 후보가 있는 F1 은 그대로 proposed 다. */
    @Test
    void nullRequestIsTreatedAsAbsent() {
        Map<String, Object> f1 = fixture("f1-proposed.json");
        f1.put("development_request", null);

        assertThat(service.save(f1).status()).isEqualTo("proposed");
    }

    /**
     * Agent 가 개발 요청서와 함께 proposed 를 보내도 거부하지 않는다. 다른 판정 칸처럼 합격 후보 수로 서버가 status 를 정하고
     * server_overrides 에 남기므로 저장된 status 는 no_valid_fix 다.
     */
    @Test
    void agentProposedIsOverriddenToNoValidFix() {
        SavedProposal saved = service.save(needsTool(p -> p.put("status", "proposed")));

        assertThat(saved.status()).isEqualTo("no_valid_fix");
        Map<String, Object> override = new LinkedHashMap<>();
        override.put("candidate_no", null);
        override.put("field", "status");
        override.put("agent_value", "proposed");
        override.put("server_value", "no_valid_fix");
        assertThat(captured().getPayload()).containsEntry("server_overrides", List.of(override));
    }

    /** 서버가 합격 후보를 찾아 proposed 로 정했는데 개발 요청서가 있으면 두 판단이 어긋나므로 거부한다. */
    @Test
    void serverProposedWithARequestIsRejected() {
        Map<String, Object> f1 = fixture("f1-proposed.json");
        f1.put("development_request", request(fixture("needs-tool.json")));

        assertRejected(f1, "development_request 가 있으면 합격 후보가 없어야 합니다 (서비스가 다시 계산한 합격 후보 [1])");
    }

    /** 형식 검사 메시지 전체다. kind, title, gaps, options, interim, interim.proof_run_ids, evidence_run_ids 순서로 본다. */
    @Test
    void formatMessages() {
        assertRejected(needsTool(p -> p.put("development_request", "needs a tool")), "development_request 가 객체가 아닙니다");
        assertRejected(needsTool(p -> request(p).put("kind", "needs_skill")),
                "development_request.kind 는 needs_tool 이어야 합니다 (받은 값 needs_skill)");
        assertRejected(needsTool(p -> request(p).remove("kind")),
                "development_request.kind 는 needs_tool 이어야 합니다 (받은 값 null)");
        assertRejected(needsTool(p -> request(p).put("title", " ")), "development_request.title 이 없습니다");
        assertRejected(needsTool(p -> request(p).put("gaps", List.of())), "development_request.gaps 가 비어 있습니다");
        assertRejected(needsTool(p -> request(p).remove("gaps")), "development_request.gaps 가 비어 있습니다");
        assertRejected(needsTool(p -> gap(p, 1).remove("key")), "development_request.gaps 의 2번째 항목에 key 가 없습니다");
        assertRejected(needsTool(p -> gap(p, 0).put("needed_by", "")),
                "development_request.gaps 의 1번째 항목에 needed_by 가 없습니다");
        assertRejected(needsTool(p -> request(p).put("gaps", List.of("shipping.weather"))),
                "development_request.gaps 의 1번째 항목에 key 가 없습니다");
        assertRejected(needsTool(p -> gap(p, 0).put("field", "depends_on")),
                "development_request.gaps 의 1번째 항목의 field 는 inputs, rules, outputs 가운데 하나여야 합니다 (받은 값 depends_on)");
        assertRejected(needsTool(p -> gap(p, 1).put("kind", "unknown")),
                "development_request.gaps 의 2번째 항목의 kind 는 missing_value 나 tool_output_missing 이어야 합니다 (받은 값 unknown)");
        assertRejected(needsTool(p -> request(p).remove("options")), "development_request.options 가 목록이 아닙니다");
        assertRejected(needsTool(p -> ProposalRules.maps(request(p).get("options")).get(1).put("type", "rewrite_skill")),
                "development_request.options 의 2번째 항목의 type 은 extend_tool 이나 new_tool 이어야 합니다 (받은 값 rewrite_skill)");
        assertRejected(needsTool(p -> request(p).put("interim", "remove the input")),
                "development_request.interim 이 객체가 아닙니다");
    }

    /** 임시 대안과 개발 요청의 근거 실행은 어느 연습 공간에서든 이 서비스가 돌린 실행이어야 한다. */
    @Test
    void evidenceRunsMustBeInTheLedger() {
        assertRejected(needsTool(p -> ProposalRules.map(request(p).get("interim")).put("proof_run_ids", List.of(UNKNOWN_RUN))),
                "development_request.interim.proof_run_ids 의 실행 " + UNKNOWN_RUN + " 을 찾을 수 없습니다");
        assertRejected(needsTool(p -> request(p).put("evidence_run_ids", List.of(CONTROL_RUN, UNKNOWN_RUN))),
                "development_request.evidence_run_ids 의 실행 " + UNKNOWN_RUN + " 을 찾을 수 없습니다");
    }

    /** 방법 목록은 비어도 되고, 임시 대안은 없거나 null 이어도 되며, 근거 실행 칸도 없어도 된다. */
    @Test
    void optionalPartsMayBeEmpty() {
        SavedProposal saved = service.save(needsTool(p -> {
            request(p).put("options", List.of());
            request(p).put("interim", null);
            request(p).remove("evidence_run_ids");
            request(p).remove("change_points");
        }));

        assertThat(saved.status()).isEqualTo("no_valid_fix");
    }

    /** 개발 요청서는 후보 검사 다음에 본다. 두 규칙을 함께 어기면 후보 메시지가 먼저 나온다. */
    @Test
    void candidatesAreCheckedBeforeTheRequest() {
        assertRejected(needsTool(p -> {
            Map<String, Object> candidate = new LinkedHashMap<>();
            candidate.put("candidate_no", 2);
            p.put("candidates", List.of(candidate));
            request(p).put("kind", "bogus");
        }), "candidate_no 는 1부터 빠짐없이 매겨야 합니다 (1번째 후보의 값 2)");
    }

    // ── 본문 ────────────────────────────────────────────────────────────

    /** 개요는 개발 요청서를 만들었다고 끝맺고, 후보를 일부러 만들지 않았다고 쓴다. Skill 을 직접 고치라고 쓰지 않는다. */
    @Test
    void summaryEndsWithTheDevelopmentRequest() {
        ProposalDocument doc = write(fixture("needs-tool.json"));

        assertThat(doc.title()).isEqualTo("shipping-reserve 진단 결과");
        assertThat(doc.summary()).isEqualTo(
                "2026년 10월 26일 10시 20분에 Skill 수정본으로 기준 주문을 시험했더니 shipping-reserve 단계에서 주문이 멈췄습니다. "
                        + "원인은 shipping-reserve Skill의 변경에 있습니다. 이 변경만 되돌려 다시 돌렸더니 같은 주문이 끝까지 처리됐습니다. "
                        + "필요한 값을 만드는 업무 도구가 없어 SkillOps는 수정안을 만들지 않았습니다. "
                        + "SKILL.md 수정만으로는 해결할 수 없어 개발 요청서를 만들었습니다.");
        assertThat(allTexts(doc)).noneMatch(text -> text.contains("직접 고쳐야"));
    }

    @Test
    void sectionsPlaceTheRequestAndInterimBeforeTheCandidates() {
        assertThat(write(fixture("needs-tool.json")).sections()).extracting(Section::key, Section::heading).containsExactly(
                org.assertj.core.groups.Tuple.tuple("overview", "개요"),
                org.assertj.core.groups.Tuple.tuple("what_happened", "발생 경위"),
                org.assertj.core.groups.Tuple.tuple("cause", "원인"),
                org.assertj.core.groups.Tuple.tuple("development_request", "개발 요청"),
                org.assertj.core.groups.Tuple.tuple("interim", "임시 대안"),
                org.assertj.core.groups.Tuple.tuple("candidates", "수정안과 시험 결과"),
                org.assertj.core.groups.Tuple.tuple("decision", "추천과 결정 요청"),
                org.assertj.core.groups.Tuple.tuple("reference", "참고 정보"));
    }

    @Test
    void requestSectionListsValuesSkillsOptionsChangePointsAndEvidence() {
        Section s = section(write(fixture("needs-tool.json")), "development_request");

        assertThat(s.paragraphs()).containsExactly(
                "담당자 수정이 쓰는 값 가운데 주문이 들어올 때 받는 값에도 없고 어떤 업무 도구도 만들지 않는 값이 있습니다.",
                "이런 값은 SKILL.md를 고쳐서는 만들 수 없으므로 업무 도구를 넓히거나 새로 만들어야 합니다.",
                "SkillOps가 제안하는 개발 방법은 아래와 같습니다.",
                "새 도구 이름은 SkillOps가 붙인 예시이므로 개발할 때 바꿔도 됩니다.");
        assertThat(s.items()).containsExactly(
                "기존 도구 넓히기 · 도구: shipping.reserve · 단계: shipping-reserve · 더 내놓을 값: shipping.weather",
                "새 도구 만들기 · 예시 이름: weather.lookup · 실행 위치: shipping-reserve 단계 앞 · "
                        + "받을 수 있는 값: order.shipping, payment.status, inventory.reserved · 내놓을 값: shipping.weather");
        assertThat(s.facts()).containsExactly(
                new Fact("요청 제목", "shipping-reserve 단계가 쓰는 shipping.weather 값을 만드는 도구 필요"),
                new Fact("필요한 값", "shipping.weather"),
                new Fact("필요로 하는 Skill", "shipping-reserve"),
                new Fact("바꿀 곳", "order-agent/backend/src/main/java/com/capstone4/orderagent/harness/ToolCatalog.java, "
                        + "order-agent/backend/src/main/java/com/capstone4/orderagent/service/OrderToolService.java"),
                new Fact("근거 실행", CONTROL_RUN + ", " + ABLATION_RUN));
        assertThat(s.quote()).isNull();
        assertThat(s.parts()).isEmpty();
    }

    @Test
    void interimSectionQuotesThePatchAndNamesTheProof() {
        Map<String, Object> proposal = fixture("needs-tool.json");
        Section s = section(write(proposal), "interim");

        assertThat(s.paragraphs()).containsExactly(
                "업무 도구가 생기기 전까지 쓸 수 있는 임시 대안입니다.",
                "shipping-reserve 의 inputs 와 rules 에서 shipping.weather 를 빼면 주문이 끝까지 처리됩니다. "
                        + "비 오는 날 보류 정책은 도구가 생길 때까지 지켜지지 않습니다.",
                "SkillOps는 원인 실험에서 이 변경을 넣고 주문을 다시 돌렸고, 그 실행을 근거로 남겼습니다.",
                "임시 대안은 자동으로 들어가지 않습니다. 쓰려면 담당자가 확인한 뒤 Skill 편집 화면에서 직접 넣어 주세요.");
        assertThat(s.quote()).isEqualTo(ProposalRules.map(request(proposal).get("interim")).get("patch"));
        assertThat(s.facts()).containsExactly(new Fact("근거 실행", ABLATION_RUN));
        assertThat(s.items()).isEmpty();
    }

    @Test
    void decisionAsksForTheToolThenAResave() {
        ProposalDocument doc = write(fixture("needs-tool.json"));

        assertThat(section(doc, "decision").paragraphs()).containsExactly(
                "SKILL.md를 고치는 것만으로는 해결할 수 없어 승인할 수정안이 없습니다.",
                "개발 요청 절의 내용으로 업무 도구를 넓히거나 새로 만든 뒤 Skill 수정본을 다시 저장해 주세요. "
                        + "저장하면 SkillOps가 같은 검증을 다시 시작합니다.",
                "도구가 생기기 전까지는 임시 대안 절의 변경을 담당자가 Skill 편집 화면에서 직접 넣어 쓸 수 있습니다.",
                "이 제안서는 확인한 뒤 거절해서 닫을 수 있습니다.");
        assertThat(candidates(doc).paragraphs()).containsExactly("필요한 값을 만드는 업무 도구가 없어 SkillOps는 수정안을 만들지 않았습니다.");
    }

    /** 임시 대안이 없으면 임시 대안 절과 결정 요청의 임시 대안 문장을 넣지 않는다. 빈 방법 목록과 출력 빈틈도 글로 남긴다. */
    @Test
    void minimalRequestOmitsInterimAndExplainsMissingOutputs() {
        ProposalDocument doc = write(needsTool(p -> {
            request(p).remove("interim");
            request(p).put("options", List.of());
            request(p).remove("change_points");
            request(p).remove("evidence_run_ids");
            gap(p, 1).put("kind", "tool_output_missing");
            gap(p, 1).put("field", "outputs");
            gap(p, 1).put("key", "shipping.rain_hold");
        }));

        assertThat(doc.sections()).extracting(Section::key).doesNotContain("interim");
        Section s = section(doc, "development_request");
        assertThat(s.paragraphs()).containsExactly(
                "담당자 수정이 쓰는 값 가운데 주문이 들어올 때 받는 값에도 없고 어떤 업무 도구도 만들지 않는 값이 있습니다. "
                        + "담당자 수정이 내놓겠다고 적은 값 가운데 그 단계의 업무 도구도, 다른 업무 도구도 만들지 않는 값이 있습니다.",
                "이런 값은 SKILL.md를 고쳐서는 만들 수 없으므로 업무 도구를 넓히거나 새로 만들어야 합니다.",
                "SkillOps가 제안할 개발 방법은 찾지 못했습니다.");
        assertThat(s.items()).isEmpty();
        assertThat(s.facts()).extracting(Fact::label).containsExactly("요청 제목", "필요한 값", "필요로 하는 Skill");
        assertThat(s.facts()).contains(new Fact("필요한 값", "shipping.weather, shipping.rain_hold"));
        assertThat(section(doc, "decision").paragraphs()).noneMatch(p -> p.contains("임시 대안"));
    }

    /** 후보가 있는 no_valid_fix 에 개발 요청서가 붙어도 시험 문장은 그대로 쓰고 개요의 마지막 문장만 개발 요청서로 바뀐다. */
    @Test
    void testedCandidatesKeepTheirTrialSentence() {
        Map<String, Object> proposal = fixture("f1-proposed.json");
        ProposalRules.maps(proposal.get("candidates")).forEach(c -> ProposalRules.map(c.get("results")).put("fixes_failure", false));
        proposal.put("status", "no_valid_fix");
        proposal.put("development_request", request(fixture("needs-tool.json")));

        assertThat(write(proposal).summary()).endsWith(
                "SkillOps가 수정안 2개를 시험했지만 모든 확인을 통과한 안은 없습니다. SKILL.md 수정만으로는 해결할 수 없어 개발 요청서를 만들었습니다.");
    }

    /** 새 문장 틀은 ToneCheck 의 금지 표현에 걸리지 않는다. */
    @Test
    void templatesNeverUseBannedPhrases() {
        ProposalDocument doc = write(fixture("needs-tool.json"));

        allTexts(doc).forEach(text -> assertThat(ToneCheck.findIn(text)).as(text).isEmpty());
        assertThat(doc.toneWarnings()).isEmpty();
    }

    /** Agent 가 쓴 제목과 임시 대안 요약의 금지 표현은 고치지 않고 tone_warnings 에만 남긴다. */
    @Test
    void agentTextPhrasesAreRecordedNotRewritten() {
        Map<String, Object> proposal = needsTool(p -> {
            request(p).put("title", "날씨 값에 대한 도구 필요");
            ProposalRules.map(request(p).get("interim")).put("summary", "입력을 빼는 것을 통해 주문이 처리됩니다.");
        });

        ProposalDocument doc = write(proposal);

        assertThat(doc.toneWarnings()).containsExactly(new ToneWarning("development_request.title", "에 대한"),
                new ToneWarning("development_request.interim.summary", "을 통해"));
        assertThat(section(doc, "development_request").facts()).contains(new Fact("요청 제목", "날씨 값에 대한 도구 필요"));
        assertThat(section(doc, "interim").paragraphs()).contains("입력을 빼는 것을 통해 주문이 처리됩니다.");
    }

    /** 개발 요청서가 없는 no_valid_fix 는 Skill 을 고치면 된다고 단정하지 않고, 승인할 안이 없다는 사실과 볼 곳만 쓴다. */
    @Test
    void genericNoValidFixPointsToTheTrialsWithoutClaimingASkillEditWorks() {
        Map<String, Object> tested = fixture("f1-proposed.json");
        ProposalRules.maps(tested.get("candidates")).forEach(c -> ProposalRules.map(c.get("results")).put("fixes_failure", false));
        tested.put("status", "no_valid_fix");
        List<String> summaries = new ArrayList<>(List.of(write(tested).summary(), write(fixture("no-root-cause.json")).summary()));

        assertThat(summaries.get(0)).endsWith("승인할 수 있는 안이 없습니다. 아래 시험 결과에서 통과하지 못한 확인을 보고 고칠 방법을 정해 주세요.");
        assertThat(summaries.get(1)).endsWith("승인할 수 있는 안이 없습니다. 아래 원인과 발생 경위를 보고 고칠 방법을 정해 주세요.");
        assertThat(summaries).noneMatch(summary -> summary.contains("직접 고쳐야"));
        summaries.forEach(summary -> assertThat(ToneCheck.findIn(summary)).as(summary).isEmpty());
    }
}
