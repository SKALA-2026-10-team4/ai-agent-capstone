package com.capstone4.skillops.proposal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.capstone4.skillops.scenario.IntentExpectation;
import com.capstone4.skillops.scenario.IntentPlan;
import com.capstone4.skillops.snapshot.OrderAgentException;
import com.capstone4.skillops.workspace.AppliedPatch;
import com.capstone4.skillops.workspace.ApplyCheck;
import com.capstone4.skillops.workspace.WorkspaceEvidence;

/**
 * service/src/main/java/com/capstone4/skillops/proposal/ProposalJudge.java 의 재판정 규칙을 보호한다
 * (docs/plans/2026-10-26-behavior-edit-root-fix.md 2.6, 2.7, 2.10 절).
 * 의도와 기준본을 제안서 칸이 아니라 주문 Agent 의 snapshot metadata 에서 읽는지(V3-1), 의도가 있는 요청은 그 expectations 로 채점한
 * 실행만 세는지, keeps_intent 가 빈 의도·되돌리기·계약 약화·시험되지 않은 의도·맞은 실행이 없는 scoped expectation 을 거짓으로 보는지
 * (V3-4), metadata 나 기준본을 읽지 못하면 저장을 거부하는지, 의도가 없는 요청은 is_revert·weakens_contract 를 보지 않는지,
 * patch·snapshot·고정 여부가 어긋나거나 patches 가 없으면 evidence_matches 가 거짓인지, 재현 근거(V3-5), 계약 약화의 정의(V3-7),
 * min-repeat 와 repro_scenario_ids, golden-ids 설정을 확인한다.
 * 장면은 B-01(선입금 주문만 결제)이다. 담당자가 payment 본문에 정책 문장만 더했고, 후보는 rules 를 더하는 encode_rule 이다.
 */
class ProposalJudgeTest {

    static final String SNAPSHOT = ProposalTestBench.SNAPSHOT;
    static final String BASELINE = ProposalTestBench.BASELINE;
    static final String HEAD = "---\nname: payment\nversion: 1\nowner: team\ndescription: 결제\n";
    static final String INPUTS = "inputs: [order.id, order.total, order.payment_method]\noutputs: [payment.status]\ndepends_on: []\n";
    static final String RULES = "rules:\n  - when: \"order.payment_method != 'prepaid'\"\n    then: hold\n    reason: PREPAID_ONLY\n";
    /** RULES 뒤에 붙이는 둘째 규칙이다. */
    static final String BIG_ORDER_RULE = "  - when: \"order.total >= 100000\"\n    then: hold\n    reason: BIG_ORDER\n";
    static final String BODY = "---\n결제한다.\n선입금 주문만 결제한다.\n";
    /** 담당자가 고치기 전 payment 원문이다. */
    static final String BASELINE_PAYMENT = HEAD + INPUTS + "---\n결제한다.\n";
    /** 담당자가 본문에 정책 문장만 더한 payment 원문이다. 후보 연습 공간의 시작 원문이다. */
    static final String OWNER_PAYMENT = HEAD + INPUTS + BODY;
    /** 정책을 rules 로 옮긴 후보를 넣은 payment 원문이다. */
    static final String ENCODED_PAYMENT = HEAD + INPUTS + RULES + BODY;
    static final String PATCH = "@@ -8,0 +9,4 @@\n+rules:\n";

    static final List<IntentExpectation> EXPECTATIONS = List.of(
            new IntentExpectation("order.payment_method == 'card'",
                    List.of("order.final_status in ['HOLD', 'REJECTED']", "payment.status == null")),
            new IntentExpectation("order.payment_method == 'prepaid'", List.of("order.final_status == 'SHIPPED'")));
    static final String HASH = IntentPlan.intentHash(EXPECTATIONS);
    /** 장부 intent_matches 에 남는 카드 expectation 의 자리다. */
    static final int CARD = IntentPlan.canonicalIndices(EXPECTATIONS).get(0);
    /** 장부 intent_matches 에 남는 선입금 expectation 의 자리다. */
    static final int PREPAID = IntentPlan.canonicalIndices(EXPECTATIONS).get(1);

    ProposalTestBench bench = new ProposalTestBench();
    ProposalJudge judge = bench.judge();

    @BeforeEach
    void setUp() {
        bench.baseline = Map.of("payment", sha256(BASELINE_PAYMENT));
        bench.metadata.put(SNAPSHOT, ProposalTestBench.userEdit(ownerIntent(expectationMaps(EXPECTATIONS))));
        bench.workspaces.put("w_i1", workspace(OWNER_PAYMENT, ENCODED_PAYMENT));
        resetLedger(HASH);
    }

    /** 장부를 비우고 원인 근거 실행과 w_i1 의 후보 실행을 다시 남긴다. hash 가 null 이면 의도 없이 돌린 실행이다. */
    void resetLedger(String hash) {
        bench.ledger.clear();
        recordProof();
        recordIntentRuns("w_i1", hash);
    }

    /** 수정본 사전 검증에서 의도와 함께 돌린 g-01 이 떨어진 실행이다. 제안서의 root_cause.proof_run_ids 가 가리킨다. */
    void recordProof() {
        bench.ledger.record("w_pre", "g-01", "r_pre_0", false, true, HASH, List.of(CARD));
    }

    /**
     * 의도로 채점한 g-01~g-03 실행을 두 번씩 통과로 남긴다. g-01 은 카드, g-02 는 선입금 주문이라 각자의 scope 에 들고, g-03 은
     * 어느 scope 에도 들지 않는다고 본다. hash 가 null 이면 의도 없이 돌린 실행이라 intent_scoped 는 거짓, intent_matches 는 null 이다.
     */
    void recordIntentRuns(String workspaceId, String hash) {
        int n = 0;
        for (String id : ProposalTestBench.GOLDEN) {
            List<Integer> matches = hash == null ? null : switch (id) {
                case "g-01" -> List.of(CARD);
                case "g-02" -> List.of(PREPAID);
                default -> List.of();
            };
            for (int i = 0; i < 2; i++) {
                bench.ledger.record(workspaceId, id, "r_" + workspaceId + "_" + (n++), true,
                        matches != null && !matches.isEmpty(), hash, matches);
            }
        }
    }

    /** 시작 원문과 지금 원문으로 고정된 후보 연습 공간 기록을 만든다. 넣은 수정은 PATCH 하나다. */
    static WorkspaceEvidence workspace(String start, String now) {
        return new WorkspaceEvidence("w_i1", SNAPSHOT, true, List.of(new AppliedPatch("payment", 1, PATCH)),
                new ApplyCheck(0, 0, 0, 0), Map.of("payment", start), Map.of("payment", now));
    }

    /** 주문 Agent metadata 의 intent 칸이다. expectations 가 null 이면 그 칸을 만들지 않는다. */
    static Map<String, Object> ownerIntent(List<Map<String, Object>> expectations) {
        Map<String, Object> intent = new LinkedHashMap<>();
        intent.put("text", "선입금 주문만 결제한다");
        if (expectations != null) {
            intent.put("expectations", expectations);
        }
        return intent;
    }

    /**
     * B-01 모양의 제안서다. Agent 가 trigger.intent 와 intent 에 담당자 의도를 옮겨 적었고 원인은 incomplete_edit, 재현 주문은 g-01,
     * 원인 근거는 수정본 사전 검증에서 떨어진 g-01 실행이다. 재판정은 trigger 를 보지 않고 snapshot metadata 를 본다.
     */
    static Map<String, Object> proposal() {
        Map<String, Object> intent = ownerIntent(expectationMaps(EXPECTATIONS));
        Map<String, Object> trigger = new LinkedHashMap<>();
        trigger.put("type", "skill_saved");
        trigger.put("baseline_snapshot_id", BASELINE);
        trigger.put("intent", intent);
        Map<String, Object> root = new LinkedHashMap<>();
        root.put("skill", "payment");
        root.put("kind", "incomplete_edit");
        root.put("proof_run_ids", List.of("r_pre_0"));
        root.put("repro_scenario_ids", List.of("g-01"));
        Map<String, Object> proposalIntent = new LinkedHashMap<>(intent);
        proposalIntent.put("source", "owner");
        proposalIntent.put("checks", List.of());
        Map<String, Object> proposal = new LinkedHashMap<>();
        proposal.put("trigger", trigger);
        proposal.put("baseline_snapshot_id", BASELINE);
        proposal.put("snapshot_id", SNAPSHOT);
        proposal.put("root_cause", root);
        proposal.put("intent", proposalIntent);
        proposal.put("candidates", new ArrayList<>(List.of(candidate(1, "w_i1"))));
        proposal.put("recommended_candidate_no", 1);
        proposal.put("status", "proposed");
        return proposal;
    }

    /** Agent 가 모두 통과라고 적은 encode_rule 후보다. */
    static Map<String, Object> candidate(int no, String workspaceId) {
        Map<String, Object> patch = new LinkedHashMap<>();
        patch.put("skill", "payment");
        patch.put("base_version", 1);
        patch.put("patch", PATCH);
        Map<String, Object> results = new LinkedHashMap<>();
        results.put("fixes_failure", true);
        results.put("keeps_old_orders", new LinkedHashMap<>(Map.of("passed", 3, "total", 3)));
        results.put("keeps_intent", true);
        results.put("no_broken_links", true);
        results.put("metadata_valid", true);
        results.put("flow_valid", true);
        Map<String, Object> candidate = new LinkedHashMap<>();
        candidate.put("candidate_no", no);
        candidate.put("kind", "encode_rule");
        candidate.put("workspace_id", workspaceId);
        candidate.put("patches", List.of(patch));
        candidate.put("results", results);
        candidate.put("valid", true);
        candidate.put("evidence_run_ids", List.of("r_" + workspaceId + "_0"));
        candidate.put("is_revert", false);
        candidate.put("weakens_contract", false);
        return candidate;
    }

    /** expectation 목록을 JSON 모양의 목록으로 바꾼다. */
    static List<Map<String, Object>> expectationMaps(List<IntentExpectation> expectations) {
        return expectations.stream().map(e -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("scope", e.scope());
            m.put("checks", e.checks());
            return m;
        }).toList();
    }

    static Map<String, Object> first(ProposalJudge.Judgement judged) {
        return ProposalRules.maps(judged.payload().get("candidates")).getFirst();
    }

    static Map<String, Object> results(ProposalJudge.Judgement judged) {
        return ProposalRules.map(first(judged).get("results"));
    }

    static List<String> fields(ProposalJudge.Judgement judged) {
        return judged.overrides().stream().map(o -> (String) o.get("field")).toList();
    }

    /** 연습 공간 시작 원문 start 에서 후보가 now 로 바꿨을 때 서버가 계산한 weakens_contract 다. */
    Object weakens(String start, String now) {
        bench.workspaces.put("w_i1", workspace(start, now));
        return first(judge.judge(proposal())).get("weakens_contract");
    }

    @Test
    void encodeRuleCandidateWithIntentGradedRunsPasses() {
        Map<String, Object> input = proposal();
        ProposalJudge.Judgement judged = judge.judge(input);

        assertThat(judged.validCandidateNos()).containsExactly(1);
        assertThat(judged.status()).isEqualTo("proposed");
        assertThat(judged.overrides()).isEmpty();
        assertThat(results(judged)).containsEntry("keeps_intent", true).containsEntry("evidence_matches", true)
                .containsEntry("keeps_old_orders", Map.of("passed", 3, "total", 3));
        assertThat(first(judged)).containsEntry("is_revert", false).containsEntry("weakens_contract", false);
        assertThat(input).isEqualTo(proposal()).doesNotContainKey("server_overrides");
    }

    /** 의도가 있는 요청은 그 expectations 로 채점한 실행만 센다. 조건 없이 돌린 실행은 근거가 되지 않는다. */
    @Test
    void runsWithoutTheIntentDoNotCountForIntentRequests() {
        resetLedger(null);

        ProposalJudge.Judgement judged = judge.judge(proposal());

        assertThat(judged.validCandidateNos()).isEmpty();
        assertThat(results(judged)).containsEntry("fixes_failure", false).containsEntry("keeps_intent", false)
                .containsEntry("keeps_old_orders", Map.of("passed", 0, "total", 3));
        assertThat(fields(judged)).containsExactly("results.fixes_failure", "results.keeps_old_orders",
                "results.keeps_intent", "valid", "status", "recommended_candidate_no");
    }

    /** 담당자가 바꾼 SKILL.md 를 기준본과 같게 되돌린 후보는 의도가 있는 요청에서 keeps_intent 가 거짓이다. */
    @Test
    void revertingTheOwnerEditFailsKeepsIntent() {
        bench.workspaces.put("w_i1", workspace(OWNER_PAYMENT, BASELINE_PAYMENT));

        ProposalJudge.Judgement judged = judge.judge(proposal());

        assertThat(first(judged)).containsEntry("is_revert", true).containsEntry("valid", false);
        assertThat(results(judged)).containsEntry("keeps_intent", false);
        assertThat(judged.overrides()).first().isEqualTo(override(1, "is_revert", false, true));
        assertThat(fields(judged)).contains("results.keeps_intent", "valid", "status");
    }

    /**
     * 의도와 기준본은 snapshot metadata 에서 읽는다(V3-1). Agent 가 trigger.intent 를 비우고 기준본 번호를 지우거나 자기 자신으로 적어도
     * 되돌리기 후보는 떨어진다.
     */
    @Test
    void forgedProposalFieldsDoNotHideTheOwnerIntent() {
        bench.workspaces.put("w_i1", workspace(OWNER_PAYMENT, BASELINE_PAYMENT));
        for (Object forgedBaseline : new Object[] { null, SNAPSHOT }) {
            Map<String, Object> proposal = proposal();
            ProposalRules.map(proposal.get("trigger")).put("intent", null);
            ProposalRules.map(proposal.get("trigger")).put("baseline_snapshot_id", forgedBaseline);
            proposal.put("baseline_snapshot_id", forgedBaseline);
            proposal.remove("intent");

            ProposalJudge.Judgement judged = judge.judge(proposal);

            assertThat(first(judged)).as("baseline %s", forgedBaseline).containsEntry("is_revert", true)
                    .containsEntry("valid", false);
            assertThat(results(judged)).containsEntry("keeps_intent", false);
            assertThat(judged.validCandidateNos()).isEmpty();
        }
    }

    /** metadata 에 의도가 없으면 Agent 가 trigger.intent 를 적어도 의도 요청이 아니다. 의도 없는 실행으로 판정한다. */
    @Test
    void triggerIntentWithoutMetadataIntentIsNotAnIntentRequest() {
        bench.metadata.put(SNAPSHOT, ProposalTestBench.userEdit(null));
        resetLedger(null);
        bench.workspaces.put("w_i1", workspace(OWNER_PAYMENT, BASELINE_PAYMENT));

        ProposalJudge.Judgement judged = judge.judge(proposal());

        assertThat(judged.validCandidateNos()).containsExactly(1);
        assertThat(first(judged)).containsEntry("is_revert", false);
    }

    /** 진단한 snapshot 의 metadata 를 주문 Agent 에서 읽지 못하면 Agent 값으로 물러서지 않고 저장을 거부한다. */
    @Test
    void unreadableMetadataRejectsTheJudgement() {
        bench.metadata.clear();

        assertThatThrownBy(() -> judge.judge(proposal())).isInstanceOf(OrderAgentException.class)
                .satisfies(e -> assertThat(((OrderAgentException) e).code()).isEqualTo("SNAPSHOT_NOT_FOUND"));
    }

    /** 시험한 후보가 없으면 판정할 것이 없으므로 metadata 를 읽지 않는다. */
    @Test
    void metadataIsNotReadWithoutTestedCandidates() {
        bench.metadata.clear();
        Map<String, Object> proposal = proposal();
        ProposalRules.maps(proposal.get("candidates")).getFirst().put("validation_error", "patch does not apply");

        assertThat(judge.judge(proposal).status()).isEqualTo("no_valid_fix");
    }

    /** 기준본을 주문 Agent 에서 읽지 못하면 Agent 의 is_revert 를 믿지 않고 저장을 거부한다. */
    @Test
    void unreadableBaselineRejectsTheJudgement() {
        bench.baseline = null;

        assertThatThrownBy(() -> judge.judge(proposal())).isInstanceOf(OrderAgentException.class)
                .satisfies(e -> assertThat(((OrderAgentException) e).code()).isEqualTo("ORDER_AGENT_UNAVAILABLE"));
    }

    /** metadata 에 기준본 번호가 없거나 자기 자신이면 is_revert 를 계산할 수 없으므로 keeps_intent 를 거짓으로 둔다. */
    @Test
    void metadataWithoutBaselineFailsKeepsIntent() {
        for (Object baselineId : new Object[] { null, SNAPSHOT }) {
            Map<String, Object> metadata = ProposalTestBench.userEdit(ownerIntent(expectationMaps(EXPECTATIONS)));
            metadata.put("baseline_snapshot_id", baselineId);
            bench.metadata.put(SNAPSHOT, metadata);

            ProposalJudge.Judgement judged = judge.judge(proposal());

            assertThat(results(judged)).as("baseline %s", baselineId).containsEntry("keeps_intent", false);
            assertThat(first(judged)).containsEntry("is_revert", false).containsEntry("valid", false);
            assertThat(fields(judged)).doesNotContain("is_revert").contains("results.keeps_intent");
        }
    }

    /** rules 를 지우거나 scope 와 rules 가 쓰는 input 을 지운 후보는 계약을 약하게 만든다. */
    @Test
    void removingRulesOrUsedInputsWeakensTheContract() {
        assertThat(weakens(ENCODED_PAYMENT, OWNER_PAYMENT)).isEqualTo(true);
        assertThat(first(judge.judge(proposal()))).containsEntry("valid", false);

        String withoutMethod = OWNER_PAYMENT.replace("order.total, order.payment_method]", "order.total]");
        assertThat(weakens(OWNER_PAYMENT, withoutMethod)).isEqualTo(true);

        String withoutTotal = ENCODED_PAYMENT.replace("order.total, order.payment_method]", "order.payment_method]");
        assertThat(weakens(ENCODED_PAYMENT, withoutTotal)).isEqualTo(false);
    }

    /**
     * 계약 약화의 정의(V3-7)다. 규칙을 지우거나(같은 when 의 규칙이 둘이면 하나만 지워도) 규칙의 when 을 바꾸거나, 쓰이는 키를 덮던 input 을
     * 지우면(다른 input 이 여전히 덮어도) 약화다. 규칙을 더하거나 순서를 바꾸거나 then·reason 만 바꾸는 것은 약화가 아니다.
     */
    @Test
    void weakeningDefinitionCases() {
        String whenChanged = ENCODED_PAYMENT.replace("order.payment_method != 'prepaid'", "order.payment_method == 'card'");
        assertThat(weakens(ENCODED_PAYMENT, whenChanged)).as("when 변경").isEqualTo(true);

        String duplicated = HEAD + INPUTS + RULES + RULES.replace("rules:\n", "").replace("hold", "reject") + BODY;
        assertThat(weakens(duplicated, ENCODED_PAYMENT)).as("같은 when 규칙 하나 삭제").isEqualTo(true);

        String coveredTwice = HEAD + INPUTS.replace("order.total, order.payment_method]", "order.total, order, order.payment_method]")
                + RULES + BODY;
        String stillCovered = coveredTwice.replace("order, order.payment_method]", "order]");
        assertThat(weakens(coveredTwice, stillCovered)).as("다른 input 이 덮어도 쓰이는 키의 input 삭제").isEqualTo(true);

        String twoRules = HEAD + INPUTS + RULES + BIG_ORDER_RULE + BODY;
        assertThat(weakens(ENCODED_PAYMENT, twoRules)).as("규칙 추가").isEqualTo(false);
        String swapped = HEAD + INPUTS + "rules:\n" + BIG_ORDER_RULE + RULES.replace("rules:\n", "") + BODY;
        assertThat(weakens(twoRules, swapped)).as("규칙 순서 변경").isEqualTo(false);
        String thenChanged = ENCODED_PAYMENT.replace("then: hold", "then: reject").replace("PREPAID_ONLY", "CARD_BLOCKED");
        assertThat(weakens(ENCODED_PAYMENT, thenChanged)).as("then·reason 만 변경").isEqualTo(false);
        assertThat(weakens(twoRules, ENCODED_PAYMENT)).as("규칙 삭제").isEqualTo(true);
    }

    /** 의도가 있는데 기대 결과가 하나도 없으면 keeps_intent 는 거짓이다. */
    @Test
    void emptyExpectationsFailKeepsIntent() {
        resetLedger(null);
        bench.metadata.put(SNAPSHOT, ProposalTestBench.userEdit(ownerIntent(List.of())));
        Map<String, Object> proposal = proposal();
        ProposalRules.map(proposal.get("intent")).put("expectations", List.of());

        ProposalJudge.Judgement judged = judge.judge(proposal);

        assertThat(results(judged)).containsEntry("fixes_failure", true).containsEntry("keeps_intent", false);
        assertThat(judged.validCandidateNos()).isEmpty();
    }

    /** 담당자가 기대 결과를 주지 않았으면 제안서의 intent.expectations(inferred)로 채점한 실행을 센다. */
    @Test
    void inferredExpectationsAreUsedWhenTheOwnerGaveNone() {
        bench.metadata.put(SNAPSHOT, ProposalTestBench.userEdit(ownerIntent(null)));

        assertThat(judge.judge(proposal()).validCandidateNos()).containsExactly(1);
    }

    /** 담당자의 기대 결과가 있으면 Agent 가 제안서에 다른 기대 결과를 적어도 담당자의 것으로 판정한다. */
    @Test
    void ownerExpectationsWinOverTheProposalCopy() {
        Map<String, Object> proposal = proposal();
        ProposalRules.map(proposal.get("intent")).put("expectations",
                List.of(Map.of("checks", List.of("order.final_status != null"))));

        assertThat(judge.judge(proposal).validCandidateNos()).containsExactly(1);
    }

    /** scope 에 든 실행이 하나도 없으면 의도를 시험하지 않은 것이라 keeps_intent 가 거짓이다. */
    @Test
    void intentThatWasNeverExercisedFailsKeepsIntent() {
        bench.ledger.clear();
        recordProof();
        int n = 0;
        for (String id : ProposalTestBench.GOLDEN) {
            for (int i = 0; i < 2; i++) {
                bench.ledger.record("w_i1", id, "r_w_i1_" + (n++), true, false, HASH, List.of());
            }
        }

        assertThat(results(judge.judge(proposal()))).containsEntry("keeps_intent", false);
    }

    /**
     * scope 가 있는 expectation 마다 그 expectation 이 붙은 실행이 하나 이상 있어야 한다(V3-4). 카드 주문만 돌리고 선입금 주문을 하나도
     * 돌리지 않았으면 선입금 expectation 은 시험되지 않았으므로 keeps_intent 가 거짓이다. intent_matches 가 없는 옛 줄도 맞지 않는다.
     */
    @Test
    void scopedExpectationWithoutMatchingRunFailsKeepsIntent() {
        bench.ledger.clear();
        recordProof();
        for (String id : ProposalTestBench.GOLDEN) {
            for (int i = 0; i < 2; i++) {
                List<Integer> matches = id.equals("g-02") ? null : List.of(CARD);
                bench.ledger.record("w_i1", id, "r_w_i1_" + id + i, true, true, HASH, matches);
            }
        }

        ProposalJudge.Judgement judged = judge.judge(proposal());

        assertThat(results(judged)).containsEntry("fixes_failure", true).containsEntry("keeps_intent", false)
                .containsEntry("keeps_old_orders", Map.of("passed", 3, "total", 3));
        assertThat(judged.validCandidateNos()).isEmpty();
    }

    /** scope 가 null 인 expectation 은 모든 실행에 붙으므로, 그 의도로 채점한 실행이 하나라도 있으면 시험된 것이다. */
    @Test
    void unscopedExpectationIsTestedWheneverAnIntentRunExists() {
        List<IntentExpectation> always = List.of(new IntentExpectation(null, List.of("order.final_status != null")));
        String hash = IntentPlan.intentHash(always);
        bench.metadata.put(SNAPSHOT, ProposalTestBench.userEdit(ownerIntent(expectationMaps(always))));
        bench.ledger.clear();
        recordProof();
        recordIntentRuns("w_i1", hash);

        ProposalJudge.Judgement judged = judge.judge(proposal());

        assertThat(results(judged)).containsEntry("keeps_intent", true);
        assertThat(judged.validCandidateNos()).containsExactly(1);
    }

    /** 장부의 intent_matches 는 지문의 정렬 순서 자리라서, 담당자가 expectations 를 다른 순서로 적었어도 같은 expectation 에 맞는다. */
    @Test
    void intentMatchesDoNotDependOnTheExpectationOrder() {
        bench.metadata.put(SNAPSHOT, ProposalTestBench.userEdit(ownerIntent(expectationMaps(EXPECTATIONS.reversed()))));

        assertThat(results(judge.judge(proposal()))).containsEntry("keeps_intent", true);
    }

    /** metadata 에 의도가 없는 요청은 Agent 의 keeps_intent 를 쓰고 is_revert·weakens_contract 를 불합격 사유로 보지 않는다. */
    @Test
    void requestsWithoutIntentIgnoreRevertAndWeakening() {
        bench.metadata.put(SNAPSHOT, ProposalTestBench.userEdit(null));
        resetLedger(null);
        bench.workspaces.put("w_i1", workspace(OWNER_PAYMENT, BASELINE_PAYMENT));
        Map<String, Object> proposal = proposal();
        Map<String, Object> candidate = ProposalRules.maps(proposal.get("candidates")).getFirst();
        candidate.put("is_revert", true);
        candidate.put("weakens_contract", true);

        ProposalJudge.Judgement judged = judge.judge(proposal);

        assertThat(judged.validCandidateNos()).containsExactly(1);
        assertThat(first(judged)).containsEntry("is_revert", true).containsEntry("weakens_contract", true);
        assertThat(judged.overrides()).isEmpty();
    }

    /** 시나리오 하나가 min-repeat 번보다 적게 돌았으면 통과로 세지 않는다. */
    @Test
    void scenariosRunFewerThanMinRepeatDoNotCount() {
        bench.ledger.clear();
        recordProof();
        bench.ledger.record("w_i1", "g-01", "r_w_i1_0", true, true, HASH, List.of(CARD));
        bench.ledger.record("w_i1", "g-01", "r_w_i1_1", true, true, HASH, List.of(CARD));
        bench.ledger.record("w_i1", "g-02", "r_w_i1_2", true, true, HASH, List.of(PREPAID));
        bench.ledger.record("w_i1", "g-02", "r_w_i1_3", true, true, HASH, List.of(PREPAID));
        bench.ledger.record("w_i1", "g-03", "r_w_i1_4", true, false, HASH, List.of());

        assertThat(results(judge.judge(proposal()))).containsEntry("keeps_old_orders", Map.of("passed", 2, "total", 3));
    }

    /** fixes_failure 는 root_cause.repro_scenario_ids 를 재현 시나리오로 쓴다. 근거는 있어도 후보 연습 공간에서 돌리지 않았으면 거짓이다. */
    @Test
    void reproScenarioIdsDriveFixesFailure() {
        bench.ledger.record("w_pre", "g-09", "r_pre_9", false, false, HASH, List.of());
        Map<String, Object> proposal = proposal();
        ProposalRules.map(proposal.get("root_cause")).put("repro_scenario_ids", List.of("g-01", "g-09"));
        ProposalRules.map(proposal.get("root_cause")).put("proof_run_ids", List.of("r_pre_0", "r_pre_9"));

        ProposalJudge.Judgement judged = judge.judge(proposal);

        assertThat(results(judged)).containsEntry("fixes_failure", false);
        assertThat(fields(judged)).doesNotContain("root_cause.repro_scenario_ids");
    }

    /**
     * 재현 시나리오마다 원인 근거 실행 가운데 그 시나리오로 돌아 떨어진 실행이 있어야 한다(V3-5). 통과한 실행, 다른 시나리오의 실행,
     * 장부에 없는 실행은 근거가 아니다. 근거가 없으면 fixes_failure 가 거짓이고 server_overrides 맨 앞에 남는다.
     */
    @Test
    void reproWithoutFailingProofRunFailsFixesFailure() {
        bench.ledger.record("w_pre", "g-01", "r_pre_0", true, true, HASH, List.of(CARD));
        bench.ledger.record("w_pre", "g-02", "r_pre_1", false, true, HASH, List.of(PREPAID));
        Map<String, Object> proposal = proposal();
        ProposalRules.map(proposal.get("root_cause")).put("proof_run_ids", List.of("r_pre_0", "r_pre_1", "r_unknown"));

        ProposalJudge.Judgement judged = judge.judge(proposal);

        assertThat(results(judged)).containsEntry("fixes_failure", false);
        assertThat(judged.validCandidateNos()).isEmpty();
        assertThat(judged.overrides()).first()
                .isEqualTo(override(null, "root_cause.repro_scenario_ids", List.of("g-01"), List.of()));
        assertThat(fields(judged)).contains("results.fixes_failure", "valid", "status");
    }

    /** patch 내용·순서, 시작 snapshot, 고정 여부 가운데 하나라도 어긋나면 evidence_matches 가 거짓이고 후보는 떨어진다. */
    @Test
    void evidenceMustMatchTheWorkspaceRecord() {
        List<WorkspaceEvidence> broken = List.of(
                new WorkspaceEvidence("w_i1", SNAPSHOT, true,
                        List.of(new AppliedPatch("payment", 1, PATCH + "+x\n")), new ApplyCheck(0, 0, 0, 0),
                        Map.of("payment", OWNER_PAYMENT), Map.of("payment", ENCODED_PAYMENT)),
                new WorkspaceEvidence("w_i1", SNAPSHOT, true,
                        List.of(new AppliedPatch("payment", 2, PATCH)), new ApplyCheck(0, 0, 0, 0),
                        Map.of("payment", OWNER_PAYMENT), Map.of("payment", ENCODED_PAYMENT)),
                new WorkspaceEvidence("w_i1", BASELINE, true, List.of(new AppliedPatch("payment", 1, PATCH)),
                        new ApplyCheck(0, 0, 0, 0), Map.of("payment", OWNER_PAYMENT), Map.of("payment", ENCODED_PAYMENT)),
                new WorkspaceEvidence("w_i1", null, true, List.of(new AppliedPatch("payment", 1, PATCH)),
                        new ApplyCheck(0, 0, 0, 0), Map.of("payment", OWNER_PAYMENT), Map.of("payment", ENCODED_PAYMENT)),
                new WorkspaceEvidence("w_i1", SNAPSHOT, false, List.of(new AppliedPatch("payment", 1, PATCH)),
                        new ApplyCheck(0, 0, 0, 0), Map.of("payment", OWNER_PAYMENT), Map.of("payment", ENCODED_PAYMENT)),
                new WorkspaceEvidence("w_i1", SNAPSHOT, true, null, null, null, Map.of()));
        for (WorkspaceEvidence workspace : broken) {
            bench.workspaces.put("w_i1", workspace);

            ProposalJudge.Judgement judged = judge.judge(proposal());

            assertThat(results(judged)).as(workspace.toString()).containsEntry("evidence_matches", false);
            assertThat(judged.validCandidateNos()).as(workspace.toString()).isEmpty();
            assertThat(judged.overrides()).first().isEqualTo(override(1, "results.evidence_matches", null, false));
        }
    }

    /** patches 가 없는 후보는 담당자 수정본을 그대로 시험한 것이라 수정안이 아니다. 연습 공간에도 넣은 수정이 없어도 떨어진다. */
    @Test
    void candidateWithoutPatchesIsInvalid() {
        bench.workspaces.put("w_i1", new WorkspaceEvidence("w_i1", SNAPSHOT, true, List.of(), null,
                Map.of("payment", OWNER_PAYMENT), Map.of("payment", OWNER_PAYMENT)));
        Map<String, Object> proposal = proposal();
        ProposalRules.maps(proposal.get("candidates")).getFirst().put("patches", List.of());

        ProposalJudge.Judgement judged = judge.judge(proposal);

        assertThat(results(judged)).containsEntry("evidence_matches", false);
        assertThat(first(judged)).containsEntry("valid", false);
        assertThat(judged.validCandidateNos()).isEmpty();
    }

    /** 연결·필수 정보·구조 판정은 마지막 apply_patch 결과의 오류 수로 다시 계산한다. */
    @Test
    void linkAndMetadataVerdictsComeFromTheLastApply() {
        bench.workspaces.put("w_i1", new WorkspaceEvidence("w_i1", SNAPSHOT, true,
                List.of(new AppliedPatch("payment", 1, PATCH)), new ApplyCheck(0, 1, 2, 3), Map.of("payment", OWNER_PAYMENT),
                Map.of("payment", ENCODED_PAYMENT)));

        ProposalJudge.Judgement judged = judge.judge(proposal());

        assertThat(results(judged)).containsEntry("no_broken_links", false).containsEntry("metadata_valid", false)
                .containsEntry("flow_valid", false);
        assertThat(fields(judged)).startsWith("results.no_broken_links", "results.metadata_valid", "results.flow_valid");
    }

    /** 추천 후보가 떨어지면 합격 후보 가운데 rank 가 가장 작은 후보를 추천한다. */
    @Test
    void recommendationMovesToTheBestRankedPassingCandidate() {
        bench.workspaces.put("w_i2", new WorkspaceEvidence("w_i2", SNAPSHOT, true,
                List.of(new AppliedPatch("payment", 1, PATCH)), new ApplyCheck(0, 0, 0, 0), Map.of("payment", OWNER_PAYMENT),
                Map.of("payment", ENCODED_PAYMENT)));
        bench.workspaces.put("w_i3", bench.workspaces.get("w_i2"));
        recordIntentRuns("w_i2", HASH);
        recordIntentRuns("w_i3", HASH);
        Map<String, Object> proposal = proposal();
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> candidates = (List<Map<String, Object>>) proposal.get("candidates");
        candidates.add(candidate(2, "w_i2"));
        candidates.add(candidate(3, "w_i3"));
        candidates.get(1).put("rank", 2);
        candidates.get(2).put("rank", 1);
        bench.workspaces.remove("w_i1");

        ProposalJudge.Judgement judged = judge.judge(proposal);

        assertThat(judged.validCandidateNos()).containsExactly(2, 3);
        assertThat(judged.payload()).containsEntry("recommended_candidate_no", 3);
        assertThat(judged.overrides()).last().isEqualTo(override(null, "recommended_candidate_no", 1, 3));
    }

    /** golden-ids 설정은 비우면 기준 주문 폴더 전부이고, 적으면 폴더에 있는 번호여야 한다. min-repeat 는 1 이상이어야 한다. */
    @Test
    void settingsAreValidatedAtStartup() {
        Set<String> catalog = new TreeSet<>(List.of("g-01", "g-02", "g-03"));
        assertThat(ProposalJudge.goldenIdsOf("", catalog)).containsExactly("g-01", "g-02", "g-03");
        assertThat(ProposalJudge.goldenIdsOf(" g-01 , g-02 ,", catalog)).containsExactly("g-01", "g-02");
        assertThatThrownBy(() -> ProposalJudge.goldenIdsOf("g-01,g-09", catalog)).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("g-09");
        assertThatThrownBy(() -> new ProposalJudge(bench.ledger, id -> Optional.empty(), id -> Map.of(), id -> Map.of(),
                ProposalTestBench.GOLDEN, 0)).isInstanceOf(IllegalStateException.class).hasMessageContaining("min-repeat");
    }

    static Map<String, Object> override(Integer candidateNo, String field, Object agentValue, Object serverValue) {
        Map<String, Object> entry = new LinkedHashMap<>();
        entry.put("candidate_no", candidateNo);
        entry.put("field", field);
        entry.put("agent_value", agentValue);
        entry.put("server_value", serverValue);
        return entry;
    }

    static String sha256(String text) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
