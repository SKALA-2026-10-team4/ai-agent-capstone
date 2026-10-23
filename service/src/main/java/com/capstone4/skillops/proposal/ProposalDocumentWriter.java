package com.capstone4.skillops.proposal;

import static com.capstone4.skillops.proposal.ProposalRules.bool;
import static com.capstone4.skillops.proposal.ProposalRules.integer;
import static com.capstone4.skillops.proposal.ProposalRules.map;
import static com.capstone4.skillops.proposal.ProposalRules.maps;
import static com.capstone4.skillops.proposal.ProposalRules.passes;
import static com.capstone4.skillops.proposal.ProposalRules.str;
import static com.capstone4.skillops.proposal.ProposalRules.strings;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

import com.capstone4.skillops.proposal.ProposalDocument.Fact;
import com.capstone4.skillops.proposal.ProposalDocument.Section;
import com.capstone4.skillops.proposal.ProposalDocument.ToneWarning;
import com.capstone4.skillops.proposal.ProposalRules.OldOrders;

/**
 * SkillOps Agent 가 보낸 제안서(JSON 그대로의 Map)로 운영자가 읽을 본문(ProposalDocument)을 만든다.
 * service/src/main/java/com/capstone4/skillops/proposal/ProposalService.java 가 제안서를 저장할 때 write 를 한 번 불러 결과를
 * proposals.document 칸에 보관하고, 화면(frontend/src/components/ProposalDocument.vue)이 그 본문을 그대로 그린다.
 *
 * 목차와 문장 틀은 이 클래스에 고정되어 있다. 목차는 개요, 발생 경위, 원인, 변경 의도(intent 가 있을 때만), 수정안과 시험 결과,
 * 추천과 결정 요청, 참고 정보 순서다. LLM 이 만든 값은 고치지 않고 옮겨 적기만 한다. 그런 값은 변경 의도 절의 인용 글(intent.text)과
 * 의도 확인 조건(intent.checks), 후보 카드의 AI 설명(explanation)이다. 금지 표현 검사는 후보 설명만 보고, 찾은 표현은 tone_warnings 에 남긴다.
 * 문장은 능동문으로 쓰고, 변수 바로 뒤에는 받침에 따라 바뀌는 조사를 붙이지 않는다("payment 단계에서", "후보 1번의").
 *
 * 문장 틀은 service/src/main/java/com/capstone4/skillops/proposal/ToneCheck.java 의 금지 표현에 걸리지 않아야 한다.
 * service/src/test/java/com/capstone4/skillops/proposal/ProposalDocumentWriterTest.java 와
 * service/src/test/java/com/capstone4/skillops/proposal/ProposalDocumentWriterGoldenTest.java 가 문장 전체를 고정하므로,
 * 문장을 고치면 골든 파일(service/src/test/resources/proposal/golden)도 함께 고친다.
 *
 * 튜닝 지점은 다음과 같다. 목차 순서와 조건부 절은 Writer 의 document 에, 시험 범위 판정은 coverageOf 에 있다.
 * 개요 네 문장은 summarySentences 가 순서대로 모으는 summaryOccurrence, summaryCause, summaryTrials, summaryRequest 에 있다.
 * 후보 카드 문장은 candidateParagraphs 와, trialParagraphs 가 부르는 네 문장 메서드에 있다. 결정 요청 문장은 proposedDecision 과
 * noFixDecision 에 있다.
 * 시각 형식과 시간대는 WHEN, 후보 종류 이름은 KIND, 금지 표현 목록은 ToneCheck 의 PHRASES 에 있다.
 */
public final class ProposalDocumentWriter {

    /** 본문에 쓰는 시각 형식이다. Asia/Seoul 시간대의 "2026년 10월 21일 14시 3분" 모양이며, 발생 시각과 제안서를 만든 시각에 쓴다. */
    static final DateTimeFormatter WHEN = DateTimeFormatter.ofPattern("yyyy년 M월 d일 H시 m분")
            .withZone(ZoneId.of("Asia/Seoul"));

    /**
     * 후보 종류(kind)를 운영자 말로 바꾼 이름이다. 키는 skillops-agent/skillops_agent/state.py 의 Candidate.kind 타입에 적힌 세 값과
     * 같아야 한다. 표에 없는 종류는 받은 이름을 그대로 쓰므로, 종류를 더하면 이 표에도 이름을 더한다.
     */
    static final Map<String, String> KIND = Map.of(
            "same_skill", "바뀐 Skill만 고치기",
            "linked_skills", "주고받는 값까지 함께 고치기",
            "run_order", "실행 순서 다시 짜기");

    /** 실패 정보에 단계 이름이 없을 때 단계 이름 자리에 넣는 말이다. 문장은 "확인되지 않은 단계에서"가 된다. */
    private static final String UNKNOWN_STEP = "확인되지 않은";

    /** 정적 메서드 write 만 쓰는 클래스라서 인스턴스를 만들지 않는다. */
    private ProposalDocumentWriter() {
    }

    /**
     * 제안서 하나의 본문을 만든다. proposalId 와 createdAt 은 참고 정보 절에 쓰고, failure.at 을 읽을 수 없으면 발생 시각 대신 createdAt 을 쓴다.
     * ProposalService 의 documentOf 가 저장 직전에 부르고, 테스트도 직접 부른다. 계산한 값을 한곳에 모으려고 호출마다 새 Writer 를 만든다.
     */
    public static ProposalDocument write(String proposalId, Map<String, Object> proposal, Instant createdAt) {
        return new Writer(proposalId, proposal, createdAt).document();
    }

    /**
     * 본문 하나를 만드는 동안 쓰는 계산 상태를 담는다. 생성자가 여러 절이 함께 쓰는 값(합격 수, 시험한 수, 시험 범위, 발생 시각,
     * 멈춘 단계)을 미리 계산하고, 절마다 있는 메서드가 그 값으로 문장을 고른다. write 가 호출마다 하나씩 만들고 document 를 부른 뒤 버린다.
     */
    private static final class Writer {

        /**
         * 수정안을 어디까지 시험했는지 나타낸다. 후보 없음, 하나도 시험하지 못함, 일부만 시험함, 모두 시험함의 네 가지다.
         * 개요 셋째 문장(summaryTrials)과 수정안 절 소개(candidatesIntro)가 같은 판정으로 문장을 고르므로 두 곳이 서로 다른 말을 하지 않는다.
         */
        private enum TrialCoverage {
            NO_CANDIDATES, NONE_TESTED, SOME_TESTED, ALL_TESTED
        }

        private final String proposalId;
        private final Map<String, Object> proposal;
        private final Instant createdAt;
        /** status 가 proposed 인지 나타낸다. 제목, 개요, 결정 요청 절의 문장이 이 값으로 갈린다. */
        private final boolean proposed;
        private final Map<String, Object> trigger;
        /**
         * 진단이 Skill 수정본 저장(trigger.type 이 skill_saved)으로 시작했는지 나타낸다. 아니면 주문 실패로 시작한 진단으로 보고 문장을 고른다.
         * trigger 는 SkillOps Agent 가 받은 진단 요청(skillops-agent/skillops_agent/main.py 의 DiagnoseRequest)을 그대로 옮긴 것이다.
         */
        private final boolean savedTrigger;
        /** 제안서의 failure 묶음이다. 실패한 실행 기록이 없으면 null 이다. */
        private final Map<String, Object> failure;
        /** 제안서의 root_cause 묶음이다. 원인을 하나로 확정하지 못했으면 null 이다. */
        private final Map<String, Object> root;
        /** 제안서의 intent 묶음이다. null 이면 변경 의도 절을 넣지 않는다. */
        private final Map<String, Object> intent;
        /** 후보 목록이다. Map 이 아닌 원소와 null 은 ProposalRules 의 maps 가 미리 뺐다. */
        private final List<Map<String, Object>> candidates;
        /** 모든 확인을 통과한 후보 수다. ProposalRules 의 passes 로 다시 센다. */
        private final long passing;
        /** 원문에 적용해 실제로 시험한 후보 수다. 적용에 실패했거나 시험 결과가 없는 후보는 빼고 센다. */
        private final long tested;
        /** 후보 수와 시험한 후보 수로 정한 시험 범위다. */
        private final TrialCoverage coverage;
        /** 의도 확인 조건(intent.checks)이 하나라도 있었는지 나타낸다. 없으면 의도를 확인했다고 쓰지 않는다. */
        private final boolean hasChecks;
        /** 실패 시각을 WHEN 형식으로 바꾼 글이다. failure.at 을 읽지 못하면 제안서를 만든 시각을 쓴다. */
        private final String when;
        /** 주문이 멈춘 단계(failure.skill)의 이름이다. 모르면 UNKNOWN_STEP 을 쓴다. */
        private final String failedStep;

        /**
         * 제안서에서 여러 절이 함께 쓰는 값을 한 번에 꺼내고 계산한다.
         * 합격과 시험 여부의 판정 기준은 service/src/main/java/com/capstone4/skillops/proposal/ProposalRules.java 의 passes 와 tested 에 있다.
         */
        Writer(String proposalId, Map<String, Object> proposal, Instant createdAt) {
            this.proposalId = proposalId;
            this.proposal = proposal;
            this.createdAt = createdAt;
            this.proposed = ProposalStatus.PROPOSED.equals(str(proposal.get("status")));
            this.trigger = map(proposal.get("trigger"));
            this.savedTrigger = trigger != null && "skill_saved".equals(str(trigger.get("type")));
            this.failure = map(proposal.get("failure"));
            this.root = map(proposal.get("root_cause"));
            this.intent = map(proposal.get("intent"));
            this.candidates = maps(proposal.get("candidates"));
            this.passing = candidates.stream().filter(ProposalRules::passes).count();
            this.tested = candidates.stream().filter(ProposalRules::tested).count();
            this.coverage = coverageOf(candidates.size(), tested);
            this.hasChecks = intent != null && !strings(intent.get("checks")).isEmpty();
            this.when = WHEN.format(failedAt());
            String step = failure == null ? null : str(failure.get("skill"));
            this.failedStep = step == null ? UNKNOWN_STEP : step;
        }

        /**
         * 후보 수와 시험한 후보 수로 시험 범위를 정한다. 후보가 없는지, 하나도 시험하지 못했는지, 일부만 시험했는지, 모두 시험했는지를
         * 이 순서로 본다. 시험 범위를 나누는 기준을 바꾸려면 이 메서드와 TrialCoverage 를 함께 고친다.
         */
        private static TrialCoverage coverageOf(int candidateCount, long tested) {
            if (candidateCount == 0) {
                return TrialCoverage.NO_CANDIDATES;
            }
            if (tested == 0) {
                return TrialCoverage.NONE_TESTED;
            }
            return tested < candidateCount ? TrialCoverage.SOME_TESTED : TrialCoverage.ALL_TESTED;
        }

        /** 실패 시각을 돌려준다. failure.at 이 없거나 ISO-8601 시각으로 읽을 수 없으면 제안서를 만든 시각을 쓴다. */
        private Instant failedAt() {
            String at = failure == null ? null : str(failure.get("at"));
            if (at != null) {
                try {
                    return Instant.parse(at);
                } catch (DateTimeParseException e) {
                    // 시각을 읽을 수 없으면 제안서를 만든 시각을 쓴다.
                }
            }
            return createdAt;
        }

        /**
         * 본문 전체를 목차 순서대로 만든다. 목차 순서를 바꾸거나 절을 더하려면 이 메서드를 고친다.
         * 변경 의도 절은 intent 가 있을 때만 넣는다. 이 절이 없으면 화면(frontend/src/views/ProposalView.vue)에 의도 확인 체크가 나오지 않아
         * 운영자가 승인할 수 없다. 개요 절의 문단과 본문의 summary 는 같은 글이다.
         */
        ProposalDocument document() {
            String summary = String.join(" ", summarySentences());
            List<Section> sections = new ArrayList<>();
            sections.add(overviewSection(summary));
            sections.add(whatHappenedSection());
            sections.add(causeSection());
            if (intent != null) {
                sections.add(intentSection());
            }
            sections.add(candidatesSection());
            sections.add(decisionSection());
            sections.add(referenceSection());
            return new ProposalDocument(title(), summary, List.copyOf(sections), toneWarnings());
        }

        /**
         * 본문 제목을 정한다. 원인 Skill 을 모르면 일반 제목을 쓰고, 알면 status 가 proposed 일 때 수정 제안서, 아닐 때 진단 결과라고 쓴다.
         * 제목은 목록 화면과 상세 화면 머리에 나온다.
         */
        private String title() {
            String rootSkill = root == null ? null : str(root.get("skill"));
            if (rootSkill == null) {
                return "Skill 변경 진단 결과";
            }
            return proposed ? rootSkill + " 수정 제안서" : rootSkill + " 진단 결과";
        }

        /**
         * 개요의 네 문장을 순서대로 모은다. 일어난 일, 원인, 시험 범위, 운영자에게 바라는 일 순서다.
         * 개요 문장을 더하거나 순서를 바꾸려면 이 목록을 고친다.
         */
        private List<String> summarySentences() {
            return List.of(summaryOccurrence(), summaryCause(), summaryTrials(), summaryRequest());
        }

        /**
         * 개요 첫 문장으로 언제 어느 단계에서 주문이 멈췄는지 쓴다. 진단이 Skill 저장으로 시작했는지, 주문 실패로 시작했는지에 따라
         * 문장을 고른다. 실패 기록이 없으면 실패한 실행 기록 없이 진단이 끝났다고 쓴다.
         */
        private String summaryOccurrence() {
            if (failure == null) {
                return "실패한 실행 기록 없이 진단이 끝났습니다.";
            }
            if (savedTrigger) {
                return when + "에 Skill 수정본으로 기준 주문을 시험했더니 " + failedStep + " 단계에서 주문이 멈췄습니다.";
            }
            return orderStoppedSentence();
        }

        /**
         * 주문 실패로 시작한 진단에서 언제 어느 단계에서 주문이 멈췄는지 쓴 문장이다.
         * 개요(summaryOccurrence)와 발생 경위(whatHappenedParagraphs)가 같은 문장을 쓰도록 한곳에 두었다.
         */
        private String orderStoppedSentence() {
            return when + "에 들어온 주문이 " + failedStep + " 단계에서 멈췄습니다.";
        }

        /**
         * 개요 둘째 문장으로 원인을 쓴다. 원인 Skill 이름은 str 로 거르지 않고 받은 값 그대로 이어 붙이므로, 이름이 없으면 null 이,
         * 빈 글자면 빈 칸이 그대로 문장에 들어간다(골든 파일 root_skill_blank 가 고정한다).
         */
        private String summaryCause() {
            if (root != null) {
                return "원인은 " + root.get("skill") + " Skill의 변경에 있습니다. 이 변경만 되돌려 다시 돌렸더니 같은 주문이 끝까지 처리됐습니다.";
            }
            return "바뀐 부분을 하나씩 되돌려 시험했지만 원인을 하나로 확정하지 못했습니다.";
        }

        /**
         * 개요 셋째 문장으로 수정안을 몇 개 만들어 몇 개를 시험했고 몇 개가 통과했는지 쓴다.
         * 문장은 시험 범위(coverage)로 고르고, 모두 시험한 경우에는 status 가 proposed 인지에 따라 다시 고른다.
         */
        private String summaryTrials() {
            return switch (coverage) {
                case NO_CANDIDATES -> "시험할 수정안은 만들지 못했습니다.";
                case NONE_TESTED -> nothingTested();
                case SOME_TESTED -> "SkillOps가 수정안 " + candidates.size() + "개를 만들었고, 그중 " + tested + "개를 실제 주문 흐름에서 시험했습니다. "
                        + (passing > 0 ? "모든 확인을 통과한 안은 " + passing + "개입니다." : "모든 확인을 통과한 안은 없습니다.");
                case ALL_TESTED -> proposed
                        ? "SkillOps가 수정안 " + candidates.size() + "개를 실제 주문 흐름에서 시험했고, 그중 " + passing
                                + "개가 모든 확인을 통과했습니다."
                        : "SkillOps가 수정안 " + candidates.size() + "개를 시험했지만 모든 확인을 통과한 안은 없습니다.";
            };
        }

        /**
         * 개요 마지막 문장으로 운영자에게 바라는 일을 쓴다. status 가 proposed 이면 추천 후보의 승인 여부를 정해 달라고 하고,
         * 아니면 담당자가 직접 고쳐야 한다고 쓴다. 추천 번호는 받은 값 그대로 쓴다.
         */
        private String summaryRequest() {
            return proposed ? "아래 내용을 확인한 뒤 후보 " + proposal.get("recommended_candidate_no") + "번의 승인 여부를 정해 주세요."
                    : "담당자가 Skill을 직접 고쳐야 합니다.";
        }

        /** 후보는 있지만 하나도 원문에 적용하지 못해 시험하지 못했다는 문장이다. 개요와 수정안 절 소개가 같은 문장을 쓴다. */
        private String nothingTested() {
            return "SkillOps가 수정안 " + candidates.size() + "개를 만들었지만 지금 Skill 원문에 적용할 수 있는 안이 없어 시험하지 못했습니다.";
        }

        /** 개요 절을 만든다. 개요 네 문장을 이은 한 문단만 담는다. */
        private static Section overviewSection(String summary) {
            return section("overview", "개요", List.of(summary), List.of());
        }

        /** 발생 경위 절을 만든다. 받지 못한 값(failure.missing_inputs)은 문단과 사실 두 곳에서 쓰므로 한 번만 꺼내 넘긴다. */
        private Section whatHappenedSection() {
            List<String> missing = failure == null ? List.of() : strings(failure.get("missing_inputs"));
            return section("what_happened", "발생 경위", whatHappenedParagraphs(missing), whatHappenedFacts(missing));
        }

        /**
         * 발생 경위 문단을 만든다. 시작 계기에 따라 어떻게 멈췄는지 쓰고, 받지 못한 값이 있으면 앞 단계에서 값이 넘어오지 않았다는
         * 문장을 덧붙인다.
         */
        private List<String> whatHappenedParagraphs(List<String> missing) {
            List<String> paragraphs = new ArrayList<>();
            if (failure == null) {
                paragraphs.add("실패한 실행 기록을 찾지 못했습니다.");
            } else if (savedTrigger) {
                paragraphs.add("담당자가 Skill 편집 화면에서 수정본을 저장했습니다. 저장 직후 SkillOps가 이 수정본으로 기준 주문을 모의 실행했고, "
                        + when + "에 " + failedStep + " 단계에서 주문이 멈췄습니다.");
            } else {
                paragraphs.add(orderStoppedSentence() + " 주문 Agent가 이 실패를 기록했고, "
                        + "SkillOps가 그 기록으로 진단을 시작했습니다.");
            }
            if (!missing.isEmpty()) {
                paragraphs.add("이 단계가 받아야 할 값이 앞 단계에서 넘어오지 않았습니다.");
            }
            return paragraphs;
        }

        /**
         * 발생 경위의 사실 목록을 만든다. 시작 계기, 실패 정보(멈춘 단계, 오류 종류, 받지 못한 값, 오류 메시지, 실패한 실행),
         * 수정 전후 Skill 세트 순서다. 값이 없는 줄은 add 가 뺀다.
         */
        private List<Fact> whatHappenedFacts(List<String> missing) {
            List<Fact> facts = new ArrayList<>();
            add(facts, "시작 계기", savedTrigger ? "Skill 수정본 저장" : "주문 실패");
            if (failure != null) {
                add(facts, "멈춘 단계", str(failure.get("skill")));
                add(facts, "오류 종류", categoryLabel(str(failure.get("category"))));
                add(facts, "받지 못한 값", missing.isEmpty() ? null : String.join(", ", missing));
                add(facts, "오류 메시지", str(failure.get("message")));
                add(facts, "실패한 실행", str(failure.get("execution_id")));
            }
            add(facts, "수정 전 Skill 세트", str(proposal.get("baseline_snapshot_id")));
            add(facts, "수정 후 Skill 세트", str(proposal.get("snapshot_id")));
            return facts;
        }

        /** 원인 절을 만든다. root_cause 가 없으면 원인을 확정하지 못했다는 절을 대신 넣는다. */
        private Section causeSection() {
            if (root == null) {
                return unknownCause();
            }
            String rootSkill = str(root.get("skill"));
            return section("cause", "원인", causeParagraphs(rootSkill), causeFacts());
        }

        /** 원인을 하나로 확정하지 못했을 때 넣는 원인 절이다. 바뀐 부분을 하나씩 되돌려 봐도 주문이 끝까지 처리되지 않았다고 설명한다. */
        private static Section unknownCause() {
            return section("cause", "원인", List.of(
                    "SkillOps가 바뀐 부분을 하나씩 되돌려 실패한 주문을 다시 돌렸지만, 주문이 끝까지 처리된 경우가 없었습니다. "
                            + "그래서 원인을 하나로 확정하지 못했습니다."),
                    List.of());
        }

        /**
         * 원인 문단을 만든다. 멈춘 단계와 원인 Skill 이 다르면 둘을 구분하는 문장을 먼저 넣는다.
         * 이어서 바뀐 부분을 가리키는 문장과, 그 변경만 되돌리자 실패한 주문이 끝까지 처리됐다는 확정 이유를 쓴다.
         */
        private List<String> causeParagraphs(String rootSkill) {
            List<String> paragraphs = new ArrayList<>();
            if (failure != null && str(failure.get("skill")) != null && !failedStep.equals(rootSkill)) {
                paragraphs.add("주문은 " + failedStep + " 단계에서 멈췄지만 원인은 " + rootSkill + " Skill에 있습니다.");
            }
            paragraphs.add(rootSkill + " Skill에서 아래 부분이 바뀌었습니다.");
            paragraphs.add("SkillOps는 이 변경만 되돌린 연습 공간에서 실패한 주문을 다시 돌렸습니다. "
                    + "그러자 주문이 끝까지 처리됐고, 그래서 이 변경을 원인으로 확정했습니다.");
            return paragraphs;
        }

        /**
         * 원인 절의 사실 목록을 만든다. 바뀌기 전, 바뀐 뒤, 원인 확인 실행 순서다.
         * before 와 after 는 skillops-agent/skillops_agent/nodes.py 의 proposal_payload 가 원인 변경(diff_skill 이 돌려준 hunk)에서
         * 옮겨 적은 값이다.
         */
        private List<Fact> causeFacts() {
            List<Fact> facts = new ArrayList<>();
            add(facts, "바뀌기 전", str(root.get("before")));
            add(facts, "바뀐 뒤", str(root.get("after")));
            add(facts, "원인 확인 실행", joined(root.get("proof_run_ids")));
            return facts;
        }

        /**
         * 변경 의도 절을 만든다. intent.text 를 인용 글로, intent.checks 를 글머리표로 보여 주고, 해석이 틀리면 거절해 달라고 안내한다.
         * 조건이 없으면 의도를 확인할 조건을 만들지 못했다고 쓴다.
         */
        private Section intentSection() {
            List<String> checks = strings(intent.get("checks"));
            List<String> paragraphs = List.of(
                    "SkillOps는 이번 변경의 의도를 아래와 같이 이해했습니다.",
                    checks.isEmpty() ? "의도를 확인할 조건은 만들지 못했습니다." : "수정안을 시험할 때 아래 조건이 지켜지는지도 함께 확인했습니다.",
                    "이 해석이 실제 의도와 다르면 승인하지 말고 거절해 주세요.");
            return quotedSection("intent", "변경 의도", paragraphs, str(intent.get("text")), checks);
        }

        /** 수정안과 시험 결과 절을 만든다. 소개 문단 뒤에 후보마다 카드(candidatePart)를 하나씩 품는다. */
        private Section candidatesSection() {
            return sectionWithParts("candidates", "수정안과 시험 결과", candidatesIntro(),
                    candidates.stream().map(this::candidatePart).toList());
        }

        /** 수정안 절의 소개 문단을 만든다. 문장은 시험 범위(coverage)로 고르며, summaryTrials 와 같은 판정을 쓰므로 두 문장이 어긋나지 않는다. */
        private List<String> candidatesIntro() {
            return switch (coverage) {
                case NO_CANDIDATES -> List.of("시험할 수정안을 만들지 못했습니다.");
                case NONE_TESTED -> List.of(nothingTested());
                case SOME_TESTED -> trialsIntro("SkillOps가 수정안 " + candidates.size() + "개를 만들었고, 원문에 적용할 수 있는 " + tested
                        + "개를 각각 따로 마련한 연습 공간에서 시험했습니다.");
                case ALL_TESTED -> trialsIntro(
                        "SkillOps가 수정안 " + candidates.size() + "개를 만들어 각각 따로 마련한 연습 공간에서 시험했습니다.");
            };
        }

        /** 시험 방법을 소개한 문장 뒤에, 기준 주문 수를 알면 무엇을 돌렸는지 덧붙인다. 의도 확인 조건이 있었을 때만 의도도 확인했다고 쓴다. */
        private List<String> trialsIntro(String howTested) {
            List<String> paragraphs = new ArrayList<>();
            paragraphs.add(howTested);
            firstOldOrdersTotal().ifPresent(total -> paragraphs.add(hasChecks
                    ? "실패했던 주문과 기준 주문 " + total + "건을 돌렸고, 의도 확인 조건도 함께 확인했습니다."
                    : "실패했던 주문과 기준 주문 " + total + "건을 돌렸습니다."));
            return paragraphs;
        }

        /**
         * results.keeps_old_orders.total 이 있는 첫 후보의 기준 주문 수를 돌려준다.
         * 적용에 실패한 후보라도 total 이 있으면 그 값을 쓴다(골든 파일 first_total_from_errored 가 고정한다).
         */
        private Optional<Integer> firstOldOrdersTotal() {
            // 모든 후보가 같은 기준 주문(skillops-agent/skillops_agent/config.py 의 GOLDEN_IDS)으로 시험하므로 처음 찾은 값을 쓴다.
            return candidates.stream().map(c -> ProposalRules.oldOrders(map(c.get("results"))).total())
                    .filter(Objects::nonNull).findFirst();
        }

        /** 후보 하나의 카드를 만든다. 제목, 시험 결과 문단, 사실 목록, 후보 번호를 담는다. */
        private Section candidatePart(Map<String, Object> candidate) {
            Integer no = integer(candidate.get("candidate_no"));
            String heading = candidateHeading(no, str(candidate.get("kind")));
            String error = str(candidate.get("validation_error"));
            return candidateCard(heading, candidateParagraphs(error, map(candidate.get("results"))),
                    candidateFacts(candidate, error), no);
        }

        /**
         * 후보 카드 제목을 만든다. 종류는 KIND 의 이름으로 바꾸고, 모르는 종류는 받은 이름을 그대로 쓴다.
         * kind 가 없거나 공백뿐이면 KIND(Map.of)의 getOrDefault 가 NullPointerException 을 던지며,
         * ProposalDocumentWriterGoldenTest 의 candidateWithoutKindThrowsNullPointerException 이 이 동작을 고정한다.
         */
        private static String candidateHeading(Integer no, String kind) {
            return "후보 " + no + "번 · " + KIND.getOrDefault(kind, kind);
        }

        /**
         * 후보 카드 문단을 고른다. 원문에 적용하지 못한 후보와 시험 결과가 없는 후보는 한 문장으로 끝내고,
         * 시험한 후보는 기준별 결과를 쓴다.
         */
        private List<String> candidateParagraphs(String error, Map<String, Object> results) {
            if (error != null) {
                return List.of("이 수정안은 지금 Skill 원문과 맞지 않아 적용하지 못했고, 그래서 시험하지 않았습니다.");
            }
            if (results == null) {
                return List.of("이 수정안은 시험하지 못했습니다.");
            }
            return trialParagraphs(results);
        }

        /**
         * 시험한 후보의 기준별 결과 문단을 만든다. 실패 해결, 기준 주문, 의도, Skill 사이 연결 순서이며, 기준 주문 문장은 total 을 알 때만 넣는다.
         * 필수 정보 결과가 있으면 해당 문장도 덧붙이며 합격 조건(ProposalRules의 passes)과 같은 칸을 읽는다.
         */
        private List<String> trialParagraphs(Map<String, Object> results) {
            List<String> paragraphs = new ArrayList<>();
            paragraphs.add(fixesFailureSentence(results));
            OldOrders oldOrders = ProposalRules.oldOrders(results);
            if (oldOrders.total() != null) {
                paragraphs.add(oldOrdersSentence(oldOrders));
            }
            paragraphs.add(intentSentence(results));
            paragraphs.add(linksSentence(results));
            if (results.containsKey("metadata_valid")) {
                paragraphs.add(metadataSentence(results));
            }
            return paragraphs;
        }

        /** 필수 정보 검사 결과를 연결 검사와 별도 문장으로 설명한다. */
        private static String metadataSentence(Map<String, Object> results) {
            return bool(results.get("metadata_valid")) ? "Skill의 필수 정보와 본문이 검사 기준을 충족합니다."
                    : "Skill의 필수 정보 또는 본문에 오류가 있어 이 후보를 사용할 수 없습니다.";
        }

        /** 실패했던 주문을 다시 돌렸을 때 끝까지 처리됐는지(results.fixes_failure) 쓴다. */
        private static String fixesFailureSentence(Map<String, Object> results) {
            return bool(results.get("fixes_failure")) ? "실패했던 주문을 다시 돌렸더니 끝까지 처리됐습니다."
                    : "실패했던 주문을 다시 돌렸지만 여전히 멈췄습니다.";
        }

        /**
         * 기준 주문이 이전과 같게 처리됐는지(results.keeps_old_orders) 쓴다. total 이 있을 때만 부른다.
         * passed 가 없으면 통과한 주문을 0건으로 보고 다르게 처리된 건수를 센다.
         */
        private static String oldOrdersSentence(OldOrders oldOrders) {
            Integer passed = oldOrders.passed();
            Integer total = oldOrders.total();
            return total.equals(passed) ? "기준 주문 " + total + "건도 모두 이전과 같게 처리됐습니다."
                    : "기준 주문 " + total + "건 가운데 " + (total - (passed == null ? 0 : passed))
                            + "건이 이전과 다르게 처리됐습니다.";
        }

        /** 의도 확인 조건이 지켜졌는지(results.keeps_intent) 쓴다. 조건이 없었으면 keeps_intent 값과 상관없이 확인하지 않았다고 쓴다. */
        private String intentSentence(Map<String, Object> results) {
            if (!hasChecks) {
                return "의도 확인 조건이 없어 의도는 따로 확인하지 않았습니다.";
            }
            return bool(results.get("keeps_intent")) ? "의도 확인 조건도 모두 지켜졌습니다."
                    : "의도 확인 조건 가운데 지켜지지 않은 것이 있습니다.";
        }

        /** 고친 뒤에도 Skill 사이 입력과 출력이 이어지는지(results.no_broken_links) 쓴다. */
        private static String linksSentence(Map<String, Object> results) {
            return bool(results.get("no_broken_links")) ? "고친 뒤에도 Skill 사이의 입력과 출력이 모두 이어집니다."
                    : "고친 뒤에 입력을 받지 못하는 Skill이 남습니다.";
        }

        /**
         * 후보 카드의 사실 목록을 만든다. 판정, 고치는 Skill, 적용하지 못한 이유, AI 설명, 근거 실행 순서다.
         * AI 설명은 LLM 이 쓴 글을 고치지 않고 그대로 옮긴다.
         */
        private static List<Fact> candidateFacts(Map<String, Object> candidate, String error) {
            List<Fact> facts = new ArrayList<>();
            add(facts, "판정", verdict(candidate));
            add(facts, "고치는 Skill", String.join(", ",
                    maps(candidate.get("patches")).stream().map(p -> str(p.get("skill"))).filter(Objects::nonNull).toList()));
            add(facts, "적용하지 못한 이유", error);
            add(facts, "AI 설명", str(candidate.get("explanation")));
            add(facts, "근거 실행", joined(candidate.get("evidence_run_ids")));
            return facts;
        }

        /**
         * 후보의 판정 글을 만든다. 합격하면 적용 가능이라 쓰고 순위가 있으면 순위를 덧붙이며, 아니면 적용 불가라 쓴다.
         * 합격 여부는 Agent 가 보낸 valid 가 아니라 ProposalRules 의 passes 로 다시 계산한 값이다.
         */
        private static String verdict(Map<String, Object> candidate) {
            Integer rank = integer(candidate.get("rank"));
            return passes(candidate) ? (rank == null ? "적용 가능" : "적용 가능 · " + rank + "순위") : "적용 불가";
        }

        /** 추천과 결정 요청 절을 만든다. status 가 proposed 인지에 따라 문단을 고른다. */
        private Section decisionSection() {
            return section("decision", "추천과 결정 요청", proposed ? proposedDecision() : noFixDecision(), List.of());
        }

        /**
         * 합격 후보가 있을 때의 결정 요청 문단이다. 추천 후보, 추천 이유, 승인하면 남는 것, 거절 안내 순서다.
         * 추천 이유 문장은 skillops-agent/skillops_agent/nodes.py 의 _rank_key 순위 기준(연결이 맞는 안, 고치는 Skill 수가 적은 안)을
         * 풀어 쓴 것이므로 순위 기준을 바꾸면 이 문장도 고친다. 운영 반영이 아직 연결되지 않았다는 문장은 promote_workspace 를 연결하면 고친다.
         */
        private List<String> proposedDecision() {
            return List.of(
                    "추천 후보는 " + proposal.get("recommended_candidate_no") + "번입니다.",
                    passing == 1 ? "모든 확인을 통과한 수정안이 이 후보 하나뿐입니다."
                            : "모든 확인을 통과한 수정안 가운데 Skill 사이 연결이 맞고 고치는 Skill 수가 가장 적은 안입니다.",
                    "운영자가 의도 해석을 확인하고 승인하면 승인 기록과 승인 번호가 남습니다. "
                            + "다만 운영 Skill에 반영하는 단계는 아직 연결되지 않았습니다. "
                            + "그래서 승인한 뒤에도 운영 주문은 지금 Skill로 처리됩니다.",
                    "의도 해석이 틀렸거나 수정안이 맞지 않으면 거절해 주세요. "
                            + "거절한 뒤에도 후보 내용을 Skill 편집 화면의 초안으로 가져와 직접 고칠 수 있습니다.");
        }

        /** 합격 후보가 없을 때의 결정 요청 문단이다. 승인할 안이 없다는 안내, 직접 고친 뒤 다시 저장하는 방법, 거절해서 닫는 방법 순서다. */
        private static List<String> noFixDecision() {
            return List.of(
                    "모든 확인을 통과한 수정안이 없어 승인할 안이 없습니다.",
                    "담당자가 Skill 편집 화면에서 직접 고친 뒤 다시 저장해 주세요. 저장하면 SkillOps가 같은 검증을 다시 시작합니다.",
                    "이 제안서는 확인한 뒤 거절해서 닫을 수 있습니다.");
        }

        /** 참고 정보 절을 만든다. 제안서 번호, 진단 번호, 변경 번호, 제안서를 만든 시각을 사실 목록으로 담는다. */
        private Section referenceSection() {
            List<Fact> facts = new ArrayList<>();
            add(facts, "제안서 번호", proposalId);
            add(facts, "진단 번호", str(proposal.get("thread_id")));
            add(facts, "변경 번호", trigger == null ? null : str(trigger.get("change_id")));
            add(facts, "제안서를 만든 시각", WHEN.format(createdAt));
            return section("reference", "참고 정보", List.of(), facts);
        }

        /**
         * 후보 설명(explanation)마다 ToneCheck 의 findIn 으로 금지 표현을 찾아 위치와 함께 모은다. 글은 고치지 않는다.
         * 금지 표현을 검사할 글을 늘리려면 이 메서드를 고친다.
         */
        private List<ToneWarning> toneWarnings() {
            List<ToneWarning> warnings = new ArrayList<>();
            for (Map<String, Object> candidate : candidates) {
                String where = "candidates[" + integer(candidate.get("candidate_no")) + "].explanation";
                ToneCheck.findIn(str(candidate.get("explanation")))
                        .forEach(phrase -> warnings.add(new ToneWarning(where, phrase)));
            }
            return List.copyOf(warnings);
        }

        /** 문단과 사실만 있는 절을 만든다. */
        private static Section section(String key, String heading, List<String> paragraphs, List<Fact> facts) {
            return newSection(key, heading, paragraphs, null, List.of(), facts, List.of(), null);
        }

        /** 인용 글과 글머리표가 있는 절을 만든다. 변경 의도 절이 쓴다. */
        private static Section quotedSection(String key, String heading, List<String> paragraphs, String quote,
                List<String> items) {
            return newSection(key, heading, paragraphs, quote, items, List.of(), List.of(), null);
        }

        /** 후보 카드를 품은 절을 만든다. 수정안과 시험 결과 절이 쓴다. */
        private static Section sectionWithParts(String key, String heading, List<String> paragraphs,
                List<Section> parts) {
            return newSection(key, heading, paragraphs, null, List.of(), List.of(), parts, null);
        }

        /** 후보 하나의 카드를 만든다. 모든 카드의 key 는 candidate 이고, 카드끼리는 candidateNo 로 구분한다. */
        private static Section candidateCard(String heading, List<String> paragraphs, List<Fact> facts,
                Integer candidateNo) {
            return newSection("candidate", heading, paragraphs, null, List.of(), facts, List.of(), candidateNo);
        }

        /** Section 을 만드는 유일한 곳이다. 목록은 List.copyOf 로 만든 고칠 수 없는 사본으로 담는다. */
        private static Section newSection(String key, String heading, List<String> paragraphs, String quote,
                List<String> items, List<Fact> facts, List<Section> parts, Integer candidateNo) {
            return new Section(key, heading, List.copyOf(paragraphs), quote, List.copyOf(items), List.copyOf(facts),
                    List.copyOf(parts), candidateNo);
        }

        /** 오류 종류 이름을 그대로 쓰되, CONTRACT_VIOLATION 이면 운영자가 알아보도록 Skill 사이 입력값 불일치라는 설명을 덧붙인다. */
        private static String categoryLabel(String category) {
            return "CONTRACT_VIOLATION".equals(category) ? category + " (Skill 사이 입력값 불일치)" : category;
        }

        /**
         * 실행 번호 목록을 쉼표와 공백(", ")으로 이은 글로 만든다. 목록이 비면 null 을 돌려줘 add 가 그 줄을 빼게 한다.
         * frontend/src/components/ProposalFacts.vue 의 runLinks 가 같은 구분자로 나눠 실행 화면 링크를 만들므로, 구분자를 바꾸면 화면도 고친다.
         */
        private static String joined(Object values) {
            List<String> ids = strings(values);
            return ids.isEmpty() ? null : String.join(", ", ids);
        }

        /** 사실 한 줄을 목록에 더한다. 값이 없거나 빈 글자면 그 줄을 넣지 않으므로 화면에 빈 칸이 생기지 않는다. */
        private static void add(List<Fact> facts, String label, String value) {
            if (value != null && !value.isBlank()) {
                facts.add(new Fact(label, value));
            }
        }
    }
}
