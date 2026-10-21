package com.capstone4.skillops.proposal;

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

import com.capstone4.skillops.proposal.ProposalDocument.Fact;
import com.capstone4.skillops.proposal.ProposalDocument.Section;
import com.capstone4.skillops.proposal.ProposalDocument.ToneWarning;

/**
 * B 가 보낸 제안서(JSON 그대로)로 운영자가 읽을 본문을 만든다. 목차와 문장 틀은 정해져 있고, LLM 이 쓴 글은 후보의 "AI 설명" 한 칸뿐이다.
 * 문장은 능동문으로 쓰고, 변수 바로 뒤에는 받침에 따라 바뀌는 조사를 붙이지 않는다("payment 단계에서", "후보 1번의").
 */
public final class ProposalDocumentWriter {

    static final DateTimeFormatter WHEN = DateTimeFormatter.ofPattern("yyyy년 M월 d일 H시 m분")
            .withZone(ZoneId.of("Asia/Seoul"));

    static final Map<String, String> KIND = Map.of(
            "same_skill", "바뀐 Skill만 고치기",
            "linked_skills", "주고받는 값까지 함께 고치기",
            "run_order", "실행 순서 다시 짜기");

    private static final String UNKNOWN_STEP = "확인되지 않은";

    private ProposalDocumentWriter() {
    }

    public static ProposalDocument write(String proposalId, Map<String, Object> proposal, Instant createdAt) {
        return new Writer(proposalId, proposal, createdAt).document();
    }

    private static final class Writer {

        private final String proposalId;
        private final Map<String, Object> proposal;
        private final Instant createdAt;
        private final boolean proposed;
        private final boolean savedTrigger;
        private final Map<String, Object> failure;
        private final Map<String, Object> root;
        private final Map<String, Object> intent;
        private final List<Map<String, Object>> candidates;
        private final long passing;
        private final String when;
        private final String failedStep;

        Writer(String proposalId, Map<String, Object> proposal, Instant createdAt) {
            this.proposalId = proposalId;
            this.proposal = proposal;
            this.createdAt = createdAt;
            this.proposed = "proposed".equals(str(proposal.get("status")));
            Map<String, Object> trigger = map(proposal.get("trigger"));
            this.savedTrigger = trigger != null && "skill_saved".equals(str(trigger.get("type")));
            this.failure = map(proposal.get("failure"));
            this.root = map(proposal.get("root_cause"));
            this.intent = map(proposal.get("intent"));
            this.candidates = maps(proposal.get("candidates"));
            this.passing = candidates.stream().filter(ProposalRules::passes).count();
            this.when = WHEN.format(failedAt());
            String step = failure == null ? null : str(failure.get("skill"));
            this.failedStep = step == null ? UNKNOWN_STEP : step;
        }

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

        ProposalDocument document() {
            String summary = String.join(" ", summarySentences());
            List<Section> sections = new ArrayList<>();
            sections.add(section("overview", "개요", List.of(summary), List.of()));
            sections.add(whatHappened());
            sections.add(cause());
            if (intent != null) {
                sections.add(intentSection());
            }
            sections.add(candidatesSection());
            sections.add(decision());
            sections.add(reference());
            return new ProposalDocument(title(), summary, List.copyOf(sections), toneWarnings());
        }

        private String title() {
            String rootSkill = root == null ? null : str(root.get("skill"));
            if (rootSkill == null) {
                return "Skill 변경 진단 결과";
            }
            return proposed ? rootSkill + " 수정 제안서" : rootSkill + " 진단 결과";
        }

        private List<String> summarySentences() {
            List<String> out = new ArrayList<>();
            if (failure == null) {
                out.add("실패한 실행 기록 없이 진단이 끝났습니다.");
            } else if (savedTrigger) {
                out.add(when + "에 Skill 수정본으로 기준 주문을 시험했더니 " + failedStep + " 단계에서 주문이 멈췄습니다.");
            } else {
                out.add(when + "에 들어온 주문이 " + failedStep + " 단계에서 멈췄습니다.");
            }
            if (root != null) {
                out.add("원인은 " + root.get("skill") + " Skill의 변경에 있습니다. 이 변경만 되돌려 다시 돌렸더니 같은 주문이 끝까지 처리됐습니다.");
            } else {
                out.add("바뀐 부분을 하나씩 되돌려 시험했지만 원인을 하나로 확정하지 못했습니다.");
            }
            if (candidates.isEmpty()) {
                out.add("시험할 수정안은 만들지 못했습니다.");
            } else if (proposed) {
                out.add("SkillOps가 수정안 " + candidates.size() + "개를 실제 주문 흐름에서 시험했고, 그중 " + passing
                        + "개가 모든 확인을 통과했습니다.");
            } else {
                out.add("SkillOps가 수정안 " + candidates.size() + "개를 시험했지만 모든 확인을 통과한 안은 없습니다.");
            }
            out.add(proposed ? "아래 내용을 확인한 뒤 후보 " + proposal.get("recommended_candidate_no") + "번의 승인 여부를 정해 주세요."
                    : "담당자가 Skill을 직접 고쳐야 합니다.");
            return out;
        }

        private Section whatHappened() {
            List<String> paragraphs = new ArrayList<>();
            if (failure == null) {
                paragraphs.add("실패한 실행 기록을 찾지 못했습니다.");
            } else if (savedTrigger) {
                paragraphs.add("담당자가 Skill 편집 화면에서 수정본을 저장했습니다. 저장 직후 SkillOps가 이 수정본으로 기준 주문을 모의 실행했고, "
                        + when + "에 " + failedStep + " 단계에서 주문이 멈췄습니다.");
            } else {
                paragraphs.add(when + "에 들어온 주문이 " + failedStep + " 단계에서 멈췄습니다. 주문 Agent가 이 실패를 기록했고, "
                        + "SkillOps가 그 기록으로 진단을 시작했습니다.");
            }
            List<String> missing = failure == null ? List.of() : strings(failure.get("missing_inputs"));
            if (!missing.isEmpty()) {
                paragraphs.add("이 단계가 받아야 할 값이 앞 단계에서 넘어오지 않았습니다.");
            }
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
            return section("what_happened", "발생 경위", paragraphs, facts);
        }

        private Section cause() {
            if (root == null) {
                return section("cause", "원인", List.of(
                        "SkillOps가 바뀐 부분을 하나씩 되돌려 실패한 주문을 다시 돌렸지만, 주문이 끝까지 처리된 경우가 없었습니다. "
                                + "그래서 원인을 하나로 확정하지 못했습니다."),
                        List.of());
            }
            String rootSkill = str(root.get("skill"));
            List<String> paragraphs = new ArrayList<>();
            if (failure != null && str(failure.get("skill")) != null && !failedStep.equals(rootSkill)) {
                paragraphs.add("주문은 " + failedStep + " 단계에서 멈췄지만 원인은 " + rootSkill + " Skill에 있습니다.");
            }
            paragraphs.add(rootSkill + " Skill에서 아래 부분이 바뀌었습니다.");
            paragraphs.add("SkillOps는 이 변경만 되돌린 연습 공간에서 실패한 주문을 다시 돌렸습니다. "
                    + "그러자 주문이 끝까지 처리됐고, 그래서 이 변경을 원인으로 확정했습니다.");
            List<Fact> facts = new ArrayList<>();
            add(facts, "바뀌기 전", str(root.get("before")));
            add(facts, "바뀐 뒤", str(root.get("after")));
            add(facts, "원인 확인 실행", joined(root.get("proof_run_ids")));
            return section("cause", "원인", paragraphs, facts);
        }

        private Section intentSection() {
            List<String> checks = strings(intent.get("checks"));
            List<String> paragraphs = List.of(
                    "SkillOps는 이번 변경의 의도를 아래와 같이 이해했습니다.",
                    checks.isEmpty() ? "의도를 확인할 조건은 만들지 못했습니다." : "수정안을 시험할 때 아래 조건이 지켜지는지도 함께 확인했습니다.",
                    "이 해석이 실제 의도와 다르면 승인하지 말고 거절해 주세요.");
            return new Section("intent", "변경 의도", paragraphs, str(intent.get("text")), checks, List.of(), List.of(),
                    null);
        }

        private Section candidatesSection() {
            List<String> paragraphs = new ArrayList<>();
            if (candidates.isEmpty()) {
                paragraphs.add("시험할 수정안을 만들지 못했습니다.");
            } else {
                paragraphs.add("SkillOps가 수정안 " + candidates.size() + "개를 만들어 각각 따로 마련한 연습 공간에서 시험했습니다.");
                // 기준 주문 수는 처음으로 시험한 후보의 값을 쓴다(모든 후보가 같은 기준 주문으로 시험한다).
                candidates.stream().map(c -> map(c.get("results"))).filter(Objects::nonNull)
                        .map(r -> map(r.get("keeps_old_orders"))).filter(Objects::nonNull)
                        .map(o -> integer(o.get("total"))).filter(Objects::nonNull).findFirst()
                        .ifPresent(total -> paragraphs.add("실패했던 주문과 기준 주문 " + total + "건을 돌렸고, 의도 확인 조건도 함께 확인했습니다."));
            }
            List<Section> parts = candidates.stream().map(this::candidatePart).toList();
            return new Section("candidates", "수정안과 시험 결과", List.copyOf(paragraphs), null, List.of(), List.of(), parts,
                    null);
        }

        private Section candidatePart(Map<String, Object> candidate) {
            Integer no = integer(candidate.get("candidate_no"));
            String kind = str(candidate.get("kind"));
            String heading = "후보 " + no + "번 · " + KIND.getOrDefault(kind, kind);
            String error = str(candidate.get("validation_error"));
            Map<String, Object> results = map(candidate.get("results"));
            List<String> paragraphs = new ArrayList<>();
            if (error != null) {
                paragraphs.add("이 수정안은 지금 Skill 원문과 맞지 않아 적용하지 못했고, 그래서 시험하지 않았습니다.");
            } else if (results == null) {
                paragraphs.add("이 수정안은 시험하지 못했습니다.");
            } else {
                paragraphs.add(ProposalRules.bool(results.get("fixes_failure")) ? "실패했던 주문을 다시 돌렸더니 끝까지 처리됐습니다."
                        : "실패했던 주문을 다시 돌렸지만 여전히 멈췄습니다.");
                Map<String, Object> oldOrders = map(results.get("keeps_old_orders"));
                Integer passed = oldOrders == null ? null : integer(oldOrders.get("passed"));
                Integer total = oldOrders == null ? null : integer(oldOrders.get("total"));
                if (total != null) {
                    paragraphs.add(total.equals(passed) ? "기준 주문 " + total + "건도 모두 이전과 같게 처리됐습니다."
                            : "기준 주문 " + total + "건 가운데 " + (total - (passed == null ? 0 : passed))
                                    + "건이 이전과 다르게 처리됐습니다.");
                }
                paragraphs.add(ProposalRules.bool(results.get("keeps_intent")) ? "의도 확인 조건도 모두 지켜졌습니다."
                        : "의도 확인 조건 가운데 지켜지지 않은 것이 있습니다.");
                paragraphs.add(ProposalRules.bool(results.get("no_broken_links")) ? "고친 뒤에도 Skill 사이의 입력과 출력이 모두 이어집니다."
                        : "고친 뒤에 입력을 받지 못하는 Skill이 남습니다.");
            }
            List<Fact> facts = new ArrayList<>();
            Integer rank = integer(candidate.get("rank"));
            add(facts, "판정", passes(candidate) ? (rank == null ? "적용 가능" : "적용 가능 · " + rank + "순위") : "적용 불가");
            add(facts, "고치는 Skill", String.join(", ",
                    maps(candidate.get("patches")).stream().map(p -> str(p.get("skill"))).filter(Objects::nonNull).toList()));
            add(facts, "적용하지 못한 이유", error);
            add(facts, "AI 설명", str(candidate.get("explanation")));
            add(facts, "근거 실행", joined(candidate.get("evidence_run_ids")));
            return new Section("candidate", heading, List.copyOf(paragraphs), null, List.of(), List.copyOf(facts),
                    List.of(), no);
        }

        private Section decision() {
            List<String> paragraphs = new ArrayList<>();
            if (proposed) {
                paragraphs.add("추천 후보는 " + proposal.get("recommended_candidate_no") + "번입니다.");
                paragraphs.add(passing == 1 ? "모든 확인을 통과한 수정안이 이 후보 하나뿐입니다."
                        : "모든 확인을 통과한 수정안 가운데 Skill 사이 연결이 맞고 고치는 Skill 수가 가장 적은 안입니다.");
                paragraphs.add("운영자가 의도 해석을 확인하고 승인하면 승인 기록과 승인 번호가 남습니다. "
                        + "다만 운영 Skill에 반영하는 단계는 아직 연결되지 않았습니다. "
                        + "그래서 승인한 뒤에도 운영 주문은 지금 Skill로 처리됩니다.");
                paragraphs.add("의도 해석이 틀렸거나 수정안이 맞지 않으면 거절해 주세요. "
                        + "거절한 뒤에도 후보 내용을 Skill 편집 화면의 초안으로 가져와 직접 고칠 수 있습니다.");
            } else {
                paragraphs.add("모든 확인을 통과한 수정안이 없어 승인할 안이 없습니다.");
                paragraphs.add("담당자가 Skill 편집 화면에서 직접 고친 뒤 다시 저장해 주세요. 저장하면 SkillOps가 같은 검증을 다시 시작합니다.");
                paragraphs.add("이 제안서는 확인한 뒤 거절해서 닫을 수 있습니다.");
            }
            return section("decision", "추천과 결정 요청", paragraphs, List.of());
        }

        private Section reference() {
            Map<String, Object> trigger = map(proposal.get("trigger"));
            List<Fact> facts = new ArrayList<>();
            add(facts, "제안서 번호", proposalId);
            add(facts, "진단 번호", str(proposal.get("thread_id")));
            add(facts, "변경 번호", trigger == null ? null : str(trigger.get("change_id")));
            add(facts, "제안서를 만든 시각", WHEN.format(createdAt));
            return section("reference", "참고 정보", List.of(), facts);
        }

        private List<ToneWarning> toneWarnings() {
            List<ToneWarning> warnings = new ArrayList<>();
            for (Map<String, Object> candidate : candidates) {
                String where = "candidates[" + integer(candidate.get("candidate_no")) + "].explanation";
                ToneCheck.findIn(str(candidate.get("explanation")))
                        .forEach(phrase -> warnings.add(new ToneWarning(where, phrase)));
            }
            return List.copyOf(warnings);
        }

        private static Section section(String key, String heading, List<String> paragraphs, List<Fact> facts) {
            return new Section(key, heading, List.copyOf(paragraphs), null, List.of(), List.copyOf(facts), List.of(), null);
        }

        private static String categoryLabel(String category) {
            return "CONTRACT_VIOLATION".equals(category) ? category + " (Skill 사이 입력값 불일치)" : category;
        }

        private static String joined(Object values) {
            List<String> ids = strings(values);
            return ids.isEmpty() ? null : String.join(", ", ids);
        }

        /** 값이 없거나 비어 있으면 그 줄을 넣지 않는다 */
        private static void add(List<Fact> facts, String label, String value) {
            if (value != null && !value.isBlank()) {
                facts.add(new Fact(label, value));
            }
        }
    }
}
