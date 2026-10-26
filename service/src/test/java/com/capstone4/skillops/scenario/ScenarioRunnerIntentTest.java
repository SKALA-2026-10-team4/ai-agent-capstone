package com.capstone4.skillops.scenario;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.capstone4.skillops.workspace.WorkspaceService;

/**
 * service/src/main/java/com/capstone4/skillops/scenario/ScenarioRunner.java 의 intent_expectations 채점 규칙을 보호한다
 * (docs/plans/2026-10-26-behavior-edit-root-fix.md 2.3, 2.4 절). 장면은 B-01(선입금 주문만 결제)과 B-03(10만 원 이상 보류)이다.
 * scope 에 맞는 기준 주문이 파일의 expect 대신 의도 checks 와 불변식으로 채점되는지, scope 가 null 인 checks 가 모든 주문에 붙는지,
 * 재현 주문에 맞는 checks 가 더해지는지, order.total 이 상품 단가 × 수량이고 단가는 한 번만 읽는지, 장부에 intent_scoped,
 * checks_hash, intent_matches(채점에 붙은 expectation 의 자리)가 남는지, 잘못된 intent_expectations 를 아무것도 돌리기 전에 거부하고
 * 연습 공간을 고정하지 않는지, 고정한 뒤 원문을 다시 읽는지 본다.
 * 기준 주문은 임시 폴더에 쓰고, 실행기는 결제 방법이 card 인 주문을 HOLD(결제 단계가 아무것도 내놓지 않음)로, 나머지를 SHIPPED 로
 * 끝낸 것처럼 답하는 가짜다.
 */
class ScenarioRunnerIntentTest {

    static final String EXECUTION = "5bc11dca-57c1-4517-950c-a0aec8ed67e2";
    static final List<IntentExpectation> PREPAID_ONLY = List.of(
            new IntentExpectation("order.payment_method == 'card'",
                    List.of("order.final_status in ['HOLD', 'REJECTED']", "payment.status == null")),
            new IntentExpectation("order.payment_method == 'prepaid'", List.of("order.final_status == 'SHIPPED'")));

    @TempDir
    Path dir;
    WorkspaceService workspaces = mock(WorkspaceService.class);
    OrderAgentRunClient orderAgent = mock(OrderAgentRunClient.class);
    TestFixtures.InMemoryRunLedger ledger = new TestFixtures.InMemoryRunLedger();
    List<FlowRunner.DryRunRequest> requests = new ArrayList<>();
    ScenarioRunner runner;

    /** card 면 HOLD, 아니면 SHIPPED 로 끝난 것처럼 답하는 재현 지원 실행기다. 실행 번호는 r_1 부터 매긴다. */
    FlowRunner harness = new FlowRunner() {
        @Override
        public RunOutcome runDryRun(DryRunRequest request) {
            requests.add(request);
            String runId = "r_" + requests.size();
            if ("card".equals(request.order().get("payment_method"))) {
                return new RunOutcome(runId, "HOLD", List.of(
                        new RunOutcome.Step("order-intake", "ok", Map.of(), Map.of("order.id", "o_1"), null),
                        new RunOutcome.Step("payment", "ok", Map.of(), Map.of(), null)));
            }
            return new RunOutcome(runId, "SHIPPED", List.of(
                    new RunOutcome.Step("order-intake", "ok", Map.of(), Map.of("order.id", "o_1"), null),
                    new RunOutcome.Step("payment", "ok", Map.of(), Map.of("payment.status", "PAID"), null),
                    new RunOutcome.Step("shipping-reserve", "ok", Map.of(), Map.of("shipping.id", "sh_1"), null)));
        }

        @Override
        public boolean supportsReplay() {
            return true;
        }
    };

    @BeforeEach
    void setUp() throws Exception {
        Path golden = Files.createDirectories(dir.resolve("golden"));
        Files.writeString(golden.resolve("g-01.json"), scenario("g-01", "c_1001", "card", "KR", 1));
        Files.writeString(golden.resolve("g-04.json"), scenario("g-04", "c_1001", "prepaid", "KR", 1));
        Files.writeString(golden.resolve("g-05.json"), scenario("g-05", "c_1001", "card", "US", 1));
        Files.writeString(golden.resolve("g-06.json"), scenario("g-06", "c_1001", "card", "KR", 4));
        when(workspaces.skillsOf("w_i")).thenReturn(Map.of());
        when(orderAgent.unitPrices()).thenReturn(Map.of("CAMP-LANTERN-LED", new BigDecimal("30000")));
        when(orderAgent.originalOrder(EXECUTION)).thenReturn(Map.of("customer", "c_declined",
                "items", List.of(Map.of("sku", "CAMP-LANTERN-LED", "qty", 1)), "payment_method", "card",
                "shipping", Map.of("country", "KR", "express", false)));
        runner = new ScenarioRunner(workspaces, new ScenarioCatalog(dir.toString(), TestFixtures.JSON), harness,
                new ScenarioGrader(), orderAgent, ledger);
    }

    /** 랜턴 qty 개를 주문하고 SHIPPED 와 결제 완료를 기대하는 기준 주문 파일이다. */
    static String scenario(String id, String customer, String paymentMethod, String country, int qty) {
        return """
                {"id": "%s", "order": {"customer": "%s", "items": [{"sku": "CAMP-LANTERN-LED", "qty": %d}],
                 "payment_method": "%s", "shipping": {"country": "%s", "express": false}},
                 "expect": {"final_status": "SHIPPED", "assertions": ["payment.status == 'PAID'"]}}
                """.formatted(id, customer, qty, paymentMethod, country);
    }

    ScenarioRunReport.ScenarioResult resultOf(ScenarioRunReport report, String scenarioId) {
        return report.results().stream().filter(r -> r.scenarioId().equals(scenarioId)).findFirst().orElseThrow();
    }

    @Test
    void scopedGoldensAreGradedByTheIntentInsteadOfTheirFile() {
        assertThat(runner.run("w_i", List.of("g-01", "g-04"), null, null, 1).allPassed()).isFalse();

        var report = runner.run("w_i", List.of("g-01", "g-04"), null, null, PREPAID_ONLY, 1);

        assertThat(report.allPassed()).isTrue();
        assertThat(report.results()).extracting(ScenarioRunReport.ScenarioResult::intentScoped).containsExactly(true, true);
        assertThat(ledger.runsOf("w_i")).filteredOn(run -> run.checksHash() != null).hasSize(2)
                .allSatisfy(run -> {
                    assertThat(run.intentScoped()).isTrue();
                    assertThat(run.checksHash()).isEqualTo(IntentPlan.intentHash(PREPAID_ONLY)).startsWith("i:");
                });
        List<Integer> canonical = IntentPlan.canonicalIndices(PREPAID_ONLY);
        assertThat(intentMatchesOf("g-01")).containsExactly(List.of(canonical.get(0)));
        assertThat(intentMatchesOf("g-04")).containsExactly(List.of(canonical.get(1)));
        verify(workspaces, times(2)).freeze("w_i");
    }

    /** 의도와 함께 돌린 이 시나리오의 실행들이 장부에 남긴 intent_matches 다. */
    List<List<Integer>> intentMatchesOf(String scenarioId) {
        return ledger.runsOf("w_i").stream().filter(run -> run.scenarioId().equals(scenarioId) && run.checksHash() != null)
                .map(RunLedger.Run::intentMatches).toList();
    }

    /** scope 에 맞지 않는 기준 주문은 파일의 expect 로 채점하고 intent_scoped 는 거짓이다. */
    @Test
    void goldensOutsideTheScopeKeepTheirFile() {
        var countryOnly = List.of(new IntentExpectation("order.shipping.country != 'KR'",
                List.of("order.final_status == 'REJECTED'")));

        var report = runner.run("w_i", List.of("g-04", "g-05"), null, null, countryOnly, 1);

        assertThat(resultOf(report, "g-04").intentScoped()).isFalse();
        assertThat(resultOf(report, "g-04").passed()).isTrue();
        assertThat(resultOf(report, "g-05").intentScoped()).isTrue();
        assertThat(resultOf(report, "g-05").failures()).singleElement().satisfies(f -> assertThat(f.reason())
                .isEqualTo("확인 조건 실패: order.final_status == 'REJECTED' (실제 'HOLD')"));
    }

    /** scope 가 null 인 checks 는 scope 에 든 주문과 안 든 주문 모두에 더한다. 그 checks 만으로는 intent_scoped 가 아니다. */
    @Test
    void unscopedChecksApplyToEveryScenario() {
        var everywhere = List.of(new IntentExpectation(null, List.of("order.shipments == 1")),
                new IntentExpectation("order.payment_method == 'card'", List.of("order.final_status == 'HOLD'")));

        var report = runner.run("w_i", List.of("g-01", "g-04"), null, null, everywhere, 1);

        assertThat(resultOf(report, "g-04").passed()).isTrue();
        assertThat(resultOf(report, "g-04").intentScoped()).isFalse();
        assertThat(resultOf(report, "g-01").intentScoped()).isTrue();
        assertThat(resultOf(report, "g-01").failures()).singleElement().satisfies(f -> assertThat(f.reason())
                .isEqualTo("확인 조건 실패: order.shipments == 1 (실제 0)"));
        // scope 가 null 인 expectation 은 모든 실행의 intent_matches 에 들어가고, scope 가 맞을 때만 그 expectation 이 더해진다.
        List<Integer> canonical = IntentPlan.canonicalIndices(everywhere);
        assertThat(intentMatchesOf("g-04")).containsExactly(List.of(canonical.get(0)));
        assertThat(intentMatchesOf("g-01")).containsExactly(canonical.stream().sorted().toList());
    }

    /** order.total 은 상품 단가 × 수량이며 단가는 run_scenarios 한 번에 한 번만 읽는다. order.total 을 쓰지 않으면 읽지 않는다. */
    @Test
    void orderTotalComesFromUnitPricesReadOnce() {
        var highAmount = List.of(new IntentExpectation("order.total >= 100000", List.of("order.final_status == 'HOLD'")));

        var report = runner.run("w_i", List.of("g-01", "g-06"), null, null, highAmount, 2);

        assertThat(resultOf(report, "g-01").intentScoped()).isFalse();
        assertThat(resultOf(report, "g-06").intentScoped()).isTrue();
        assertThat(resultOf(report, "g-06").passed()).isTrue();
        verify(orderAgent, times(1)).unitPrices();

        runner.run("w_i", List.of("g-01"), null, null, PREPAID_ONLY, 1);
        verify(orderAgent, times(1)).unitPrices();
    }

    /** 재현 주문은 불변식에 원래 주문의 처음 값으로 맞는 checks 를 더한다. extra_checks 는 그 뒤에 붙는다. */
    @Test
    void replaysAddMatchingChecksAndExtraChecksStillApply() {
        var report = runner.run("w_i", List.of("f-" + EXECUTION), null, List.of("order.id != null", "notify.sent == true"),
                PREPAID_ONLY, 1);

        var replay = report.results().getFirst();
        assertThat(replay.intentScoped()).isTrue();
        assertThat(replay.failures()).singleElement().satisfies(f -> assertThat(f.reason())
                .isEqualTo("확인 조건 실패: notify.sent == true (실제 null)"));
        assertThat(ledger.runsOf("w_i")).singleElement()
                .satisfies(run -> assertThat(run.checksHash()).isEqualTo(IntentPlan.intentHash(PREPAID_ONLY)));
    }

    /** 의도가 없으면 extra_checks 만의 지문(x:)을, 둘 다 없으면 null 을 장부에 남긴다. */
    @Test
    void checksHashWithoutIntent() {
        runner.run("w_i", List.of("g-04"), null, List.of("payment.status == 'PAID'"), null, 1);
        runner.run("w_i", List.of("g-04"), null, null, List.of(), 1);

        assertThat(ledger.runsOf("w_i")).extracting(RunLedger.Run::checksHash)
                .satisfiesExactlyInAnyOrder(hash -> assertThat(hash).startsWith("x:"), hash -> assertThat(hash).isNull());
        assertThat(ledger.runsOf("w_i")).allSatisfy(run -> {
            assertThat(run.intentScoped()).isFalse();
            assertThat(run.intentMatches()).isNull();
        });
    }

    /** scope 에 맞지 않는 기준 주문도 의도와 함께 돌렸으면 intent_matches 는 null 이 아니라 빈 목록이다. */
    @Test
    void goldensOutsideEveryScopeRecordEmptyMatches() {
        runner.run("w_i", List.of("g-04"), null, null, PREPAID_ONLY.subList(0, 1), 1);

        assertThat(ledger.runsOf("w_i")).singleElement().satisfies(run -> assertThat(run.intentMatches()).isEmpty());
    }

    /**
     * 연습 공간을 고정한 뒤 원문을 다시 읽어 돌린다. 검사 단계에서 읽은 뒤 고정 전에 끝난 apply_patch 가 있어도 시험은 고정된 원문으로 돈다.
     * 같은 연습 공간의 apply 와 freeze 는 WorkspaceService 가 잠금으로 차례로 세운다.
     */
    @Test
    void skillsAreReadAgainAfterFreezing() {
        var stale = Map.of("payment", new com.capstone4.skillops.workspace.WorkspaceSkill(1, "old"));
        var frozen = Map.of("payment", new com.capstone4.skillops.workspace.WorkspaceSkill(2, "new"));
        when(workspaces.skillsOf("w_i")).thenReturn(stale, frozen);

        runner.run("w_i", List.of("g-04"), null, null, null, 1);

        var order = org.mockito.Mockito.inOrder(workspaces);
        order.verify(workspaces).skillsOf("w_i");
        order.verify(workspaces).freeze("w_i");
        order.verify(workspaces).skillsOf("w_i");
        assertThat(requests).singleElement().satisfies(request -> assertThat(request.skills()).isEqualTo(frozen));
    }

    /** 잘못된 intent_expectations 는 연습 공간을 찾기 전에 INVALID_INPUT 으로 거부하고, 아무것도 돌리거나 고정하지 않는다. */
    @Test
    void invalidIntentExpectationsAreRejectedBeforeAnything() {
        List<IntentExpectation> tooMany = Collections.nCopies(11, new IntentExpectation(null, List.of("x == 1")));
        assertRejected(tooMany, "intent_expectations 는 10개까지 넣을 수 있습니다 (받은 개수 11)");
        assertRejected(List.of(new IntentExpectation(null, List.of())),
                "intent_expectations 의 1번째 항목에 checks 를 1~10개 넣으세요 (받은 개수 0)");
        assertRejected(List.of(new IntentExpectation(null, Collections.nCopies(11, "x == 1"))),
                "intent_expectations 의 1번째 항목에 checks 를 1~10개 넣으세요 (받은 개수 11)");
        assertRejected(Collections.singletonList(null), "intent_expectations 의 1번째 항목에 checks 를 1~10개 넣으세요 (받은 개수 0)");
        assertRejected(List.of(PREPAID_ONLY.getFirst(), new IntentExpectation("order.total >= 'big'", List.of("x == 1"))),
                "intent_expectations 의 2번째 scope: 확인 조건을 읽을 수 없습니다: order.total >= 'big' (예: payment.status == 'PAID')");
        assertRejected(List.of(new IntentExpectation(null, List.of("order.final_status = 'HOLD'"))),
                "intent_expectations 의 1번째 checks: 확인 조건을 읽을 수 없습니다: order.final_status = 'HOLD' (예: payment.status == 'PAID')");
        verify(workspaces, never()).skillsOf(any());
        verify(workspaces, never()).freeze(any());
        assertThat(requests).isEmpty();
    }

    /** extra_checks 문법은 intent_expectations 보다 먼저 본다. */
    @Test
    void extraChecksAreCheckedBeforeIntentExpectations() {
        assertThatThrownBy(() -> runner.run("w_i", List.of("g-01"), null, List.of("bad"),
                List.of(new IntentExpectation(null, List.of())), 1)).hasMessageStartingWith("확인 조건을 읽을 수 없습니다: bad");
    }

    /** 시험 주문 번호가 틀려 아무것도 돌리지 못하면 연습 공간을 고정하지 않는다. */
    @Test
    void workspaceIsNotFrozenWhenNothingRuns() {
        assertThatThrownBy(() -> runner.run("w_i", List.of("g-99"), null, null, PREPAID_ONLY, 1))
                .isInstanceOf(IllegalArgumentException.class);
        verify(workspaces, never()).freeze(any());
    }

    /** 의도의 scope 가 같고 순서와 앞뒤 공백만 다르면 지문이 같다. 내용이 다르면 지문도 다르다. */
    @Test
    void intentHashIgnoresOrderAndSpacing() {
        var reordered = List.of(
                new IntentExpectation(" order.payment_method == 'prepaid' ", List.of("order.final_status == 'SHIPPED'")),
                new IntentExpectation("order.payment_method == 'card'",
                        List.of("payment.status == null", "order.final_status in ['HOLD', 'REJECTED']")));

        assertThat(IntentPlan.intentHash(reordered)).isEqualTo(IntentPlan.intentHash(PREPAID_ONLY));
        assertThat(IntentPlan.intentHash(PREPAID_ONLY.subList(0, 1))).isNotEqualTo(IntentPlan.intentHash(PREPAID_ONLY));
        assertThat(IntentPlan.intentHash(List.of())).isNull();
        assertThat(IntentPlan.intentHash(null)).isNull();
        // 지문이 같은 두 목록은 같은 expectation 이 같은 자리를 받는다. 장부 intent_matches 를 다른 순서의 목록과 맞춰 볼 수 있다.
        List<Integer> original = IntentPlan.canonicalIndices(PREPAID_ONLY);
        assertThat(IntentPlan.canonicalIndices(reordered)).containsExactly(original.get(1), original.get(0));
        assertThat(original).containsExactlyInAnyOrder(0, 1);
        assertThat(IntentPlan.canonicalIndices(null)).isEmpty();
    }

    /** scope 문맥은 주문의 처음 값이다. 단가를 모르는 상품이 있으면 order.total 은 null 이다. */
    @Test
    void scopeContextUsesTheInitialOrderValues() {
        Map<String, Object> order = Map.of("customer", "c_1001", "payment_method", "card",
                "items", List.of(Map.of("sku", "A", "qty", 2), Map.of("sku", "B", "qty", 1)),
                "shipping", Map.of("country", "US", "express", true));

        Map<String, Object> context = IntentPlan.scopeContextOf(order, true,
                Map.of("A", new BigDecimal("1000"), "B", new BigDecimal("500")));

        assertThat(Check.parse("order.total == 2500").holds(context)).isTrue();
        assertThat(Check.parse("order.shipping.country == 'US'").holds(context)).isTrue();
        assertThat(Check.parse("order.shipping.express == true").holds(context)).isTrue();
        assertThat(Check.parse("order.customer == 'c_1001'").holds(context)).isTrue();
        assertThat(IntentPlan.scopeContextOf(order, true, Map.of("A", BigDecimal.ONE))).containsEntry("order.total", null);
        assertThat(IntentPlan.scopeContextOf(order, false, Map.of())).doesNotContainKey("order.total");
    }

    void assertRejected(List<IntentExpectation> expectations, String message) {
        assertThatThrownBy(() -> runner.run("w_i", List.of("g-01"), null, null, expectations, 1))
                .isInstanceOf(IllegalArgumentException.class).hasMessage(message);
    }
}
