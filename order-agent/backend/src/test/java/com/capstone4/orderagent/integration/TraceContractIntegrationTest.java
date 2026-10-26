package com.capstone4.orderagent.integration;

import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.*;
import com.capstone4.orderagent.adapter.ShippingMockAdapter;
import com.capstone4.orderagent.dto.order.CreateOrderRequest;
import com.capstone4.orderagent.dto.order.OrderResponse;
import com.capstone4.orderagent.exception.BusinessException;
import com.capstone4.orderagent.harness.*;
import com.capstone4.orderagent.service.*;
import com.capstone4.orderagent.trace.TraceService;
import com.capstone4.orderagent.workspace.SnapshotService;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * 주문 Agent trace 의 실행 경로별 이벤트 순서와 data, 실행 요약, 주문 단계 기록, 재고를 고정하는 특성 테스트다. 리팩토링이 관찰 가능한 동작을
 * 바꾸지 않았는지 확인하려고 만들었으며, 기대 줄은 order-agent/backend/src/test/java/com/capstone4/orderagent/integration/TraceContractGolden.java 에 있다.
 * 보호 대상은 order-agent/backend/src/main/java/com/capstone4/orderagent/harness/ 의 OrderHarness, SkillExecutor, ToolDispatcher 와
 * order-agent/backend/src/main/java/com/capstone4/orderagent/service/ 의 OrderToolService, OrderLifecycleService, DryRunService,
 * order-agent/backend/src/main/java/com/capstone4/orderagent/trace/ 의 TraceService, ExecutionRepository, 그리고 trace 조회 API 다.
 * 이 trace 는 service/src/main/java/com/capstone4/skillops/scenario/OrderAgentTraceConverter.java, frontend/src/utils/eventView.js,
 * skillops-agent/skillops_agent/contracts.py 가 읽는 계약이다. 모델 응답은 FixedDecisionProvider 를 spy 로 바꿔 주입하고,
 * 업무 도구 실패는 ShippingMockAdapter spy 에 예외를 넣어 만든다.
 * ORDER_DEMO_INTEGRATION_TESTS=true 일 때만 돌고, 그때는 order-agent/compose.yml 의 db-test(호스트 포트 5434)가 떠 있어야 한다.
 */
@SpringBootTest(properties={"order-agent.skill-change-dispatch-enabled=false","spring.datasource.url=jdbc:postgresql://localhost:5434/order_agent_test",
    "spring.datasource.username=order_test","spring.datasource.password=order_test_local"})
@AutoConfigureMockMvc
@EnabledIfEnvironmentVariable(named="ORDER_DEMO_INTEGRATION_TESTS",matches="true")
@SuppressWarnings("unchecked")
class TraceContractIntegrationTest {
    @Autowired SnapshotService snapshots;
    @Autowired OrderService orders;
    @Autowired OrderProcessingService processor;
    @Autowired OrderLifecycleService lifecycle;
    @Autowired OrderToolService tools;
    @Autowired TraceService traces;
    @Autowired JdbcTemplate jdbc;
    @Autowired MockMvc mvc;
    @MockitoSpyBean FixedDecisionProvider decisions;
    @MockitoSpyBean ShippingMockAdapter shipping;
    static final String LANTERN="CAMP-LANTERN-LED";
    // 루트 flow 의 snapshot 안 경로와, 그 파일에서 바꿔 끼울 원래 steps 줄이다. publishEdited 가 이 줄을 다른 단계 목록으로 바꾼다.
    static final String FLOW="skills/order-flow/SKILL.md";
    static final String FLOW_STEPS="steps: [order-intake, inventory-check, payment, shipping-reserve, notify]";
    /** 값의 키 순서가 계약인 data 하위 Map 이름이다. 나머지 Map 은 Map.of 라 순서를 비교하지 않는다. */
    static final Set<String> ORDERED=Set.of("arguments","context","input","declared_output","output");

    /** 한 번의 주문 실행 결과(주문 응답, trace, 던져진 예외)를 묶는다. */
    record Run(OrderResponse order,Map<String,Object> trace,RuntimeException failure){}

    /** 매 테스트마다 주문을 지우고 상품 재고를 시드 값으로 되돌린다. */
    @BeforeEach void reset(){
        jdbc.update("delete from orders");
        jdbc.update("update products set stock_quantity = case sku when 'CAMP-LANTERN-LED' then 10 when 'CAMP-CHAIR-FOLD' then 5 else 0 end");
    }

    /** 정상 주문은 시작 9개, 단계마다 8개, 종료 1개 이벤트를 남기고 SHIPPED 가 된다. */
    @Test void shippedOrder(){
        assertRun("shipped",order(input(null)),TraceContractGolden.SHIPPED);
    }
    /** 재고 부족은 inventory-check 에서 업무 중단(REJECTED)으로 끝나고 이후 단계는 SKIPPED 다. */
    @Test void outOfStockStopsAtInventory(){
        assertRun("out-of-stock",order(input(null,"c_1001","CAMP-MUG-STEEL")),TraceContractGolden.OUT_OF_STOCK);
    }
    /** 결제 거절은 payment 도구 안에서 예약을 해제하고 HOLD 로 업무 중단한다. */
    @Test void paymentDeclineHoldsAndReleases(){
        assertRun("payment-declined",order(input(null,"c_declined",LANTERN)),TraceContractGolden.PAYMENT_DECLINED);
    }
    /** payment 의 hold 실행 규칙이 카드 주문에 매치되면 결제 도구 없이 예약을 되돌리고 HOLD 로 업무 종료한다. 결제는 시도하지 않은 상태로 남는다. */
    @Test void ruleHoldAtPaymentReleasesAndStops(){
        UUID snapshot=publishEdited("skills/payment/SKILL.md","depends_on: [inventory-check]\n",
            "depends_on: [inventory-check]\nrules:\n  - when: \"order.payment_method != 'prepaid'\"\n    then: hold\n    reason: PREPAID_ONLY\n",Map.of());
        assertRun("rule-hold",order(input(snapshot)),TraceContractGolden.RULE_HOLD);
    }
    /** order-intake 의 reject 실행 규칙은 해외 배송 주문을 업무 도구 없이 REJECTED 로 끝내고 업무 단계를 모두 SKIPPED 로 둔다. */
    @Test void ruleRejectAtIntakeStopsBeforeAnyTool(){
        UUID snapshot=publishEdited("skills/order-intake/SKILL.md","depends_on: []\n",
            "depends_on: []\nrules:\n  - when: \"order.shipping.country != 'KR'\"\n    then: reject\n    reason: DOMESTIC_ONLY\n",Map.of());
        var overseas=new CreateOrderRequest("c_1001",List.of(new CreateOrderRequest.Item(LANTERN,1)),"card",new CreateOrderRequest.Shipping("US",false),snapshot);
        assertRun("rule-reject-at-intake",order(overseas),TraceContractGolden.RULE_REJECT_AT_INTAKE);
    }
    /** 정적 계약 위반은 도구를 부르기 전에 실패하고 위반 단계를 FAILED 로 기록한다. */
    @Test void staticContractViolationFailsBeforeTools(){
        assertRun("static-contract-violation",order(input(snapshots.demo("broken-contract").id())),TraceContractGolden.STATIC_CONTRACT_VIOLATION);
    }
    /** 선언했지만 만들지 않은 출력은 실행 중 MISSING_SKILL_OUTPUT 으로 실패하고 미결제 예약을 되돌린다. */
    @Test void runtimeMissingOutputCompensatesReservation(){
        UUID snapshot=publishEdited("skills/inventory-check/SKILL.md","outputs: [inventory.available, inventory.reserved]",
            "outputs: [inventory.available, inventory.reserved, inventory.reservation_reference]",Map.of());
        assertRun("runtime-missing-output",order(input(snapshot)),TraceContractGolden.RUNTIME_MISSING_OUTPUT);
    }
    /** 명시적 미선택(none)은 실행이 COMPLETED, 주문이 REJECTED 이고 error_category 는 NO_SELECTION 이다. */
    @Test void explicitNoSelection(){
        selectReplies("{\"skill\":\"none\"}");
        assertRun("no-selection",order(input(null)),TraceContractGolden.NO_SELECTION);
    }
    /** 없는 Skill 선택은 SKILL_LOAD_REJECTED 를 남기고 UNKNOWN_SKILL 로 실패한다. */
    @Test void unknownSkillSelection(){
        selectReplies("{\"skill\":\"does-not-exist\"}");
        assertRun("unknown-skill",order(input(null)),TraceContractGolden.UNKNOWN_SKILL);
    }
    /** order-flow 가 아닌 Skill 을 엔트리로 고르면 ROOT_SKILL_MISMATCH 다. */
    @Test void rootSkillMismatch(){
        selectReplies("{\"skill\":\"payment\"}");
        assertRun("root-mismatch",order(input(null)),TraceContractGolden.ROOT_MISMATCH);
    }
    /** 도구 바인딩이 없는 단계는 FLOW_VALIDATED 전에 UNSUPPORTED_SKILL 로 실패한다. */
    @Test void unsupportedStepHasNoToolBinding(){
        UUID snapshot=publishEdited(FLOW,FLOW_STEPS,"steps: [order-intake, inventory-check, payment, shipping-reserve, notify, gift-wrap]",
            Map.of("skills/gift-wrap/SKILL.md","""
                ---
                name: gift-wrap
                description: 선물 포장 여부를 기록한다
                inputs: [order.id]
                outputs: [gift.wrapped]
                depends_on: []
                owner: 주문운영팀
                version: 1
                ---
                # 선물 포장 여부를 기록한다

                선물 포장 요청을 확인합니다.
                """));
        assertRun("unsupported-step",order(input(snapshot)),TraceContractGolden.UNSUPPORTED_STEP);
    }
    /** 단계에 묶이지 않은 도구 요청은 TOOL_NOT_ALLOWED 로 실패하고 미결제 예약을 되돌린다. */
    @Test void toolNotAllowedForStep(){
        toolReplies("payment",new DecisionProvider.Reply("{\"tool\":\"shipping.reserve\",\"arguments\":{\"order_id\":\"x\",\"country\":\"KR\"}}",null,null));
        assertRun("tool-not-allowed",order(input(null)),TraceContractGolden.TOOL_NOT_ALLOWED);
    }
    /** 엔트리 선택의 skill 이 문자열이 아니면 원문을 JSON 으로 남기고 MODEL_FORMAT_ERROR 다. */
    @Test void selectionThatIsNotAString(){
        selectReplies("{\"skill\":123}");
        assertRun("select-not-string",order(input(null)),TraceContractGolden.SELECT_NOT_STRING);
    }
    /** 엔트리 선택 응답이 JSON 이 아니면 결정 오류를 남기고 재시도 없이 MODEL_FORMAT_ERROR 다. */
    @Test void selectionThatIsNotJson(){
        selectReplies("not json");
        assertRun("select-not-json",order(input(null)),TraceContractGolden.SELECT_NOT_JSON);
    }
    /** 관측 토큰 한도를 넘긴 단계 결정은 응답 기록 뒤 TOKEN_LIMIT_EXCEEDED 로 실패한다. */
    @Test void tokenLimitDuringStepDecision(){
        toolReplies("payment",new DecisionProvider.Reply("{}",10001L,0L));
        assertRun("token-limit",order(input(null)),TraceContractGolden.TOKEN_LIMIT);
    }
    /** 도구 응답의 arguments 가 객체가 아니면 도구를 부르지 않고 MODEL_FORMAT_ERROR 다. */
    @Test void toolDecisionWithMalformedArguments(){
        toolReplies("payment",new DecisionProvider.Reply("{\"tool\":\"payment.charge\",\"arguments\":\"oops\"}",null,null));
        assertRun("tool-format-error",order(input(null)),TraceContractGolden.TOOL_FORMAT_ERROR);
    }
    /** 서버 계산과 다른 도구 인자는 TOOL_ERROR 이벤트와 INVALID_TOOL_ARGUMENTS 로 실패한다. */
    @Test void invalidToolArguments(){
        toolReplies("payment",new DecisionProvider.Reply("{\"tool\":\"payment.charge\",\"arguments\":{\"order_id\":\"wrong-order\",\"amount\":1,\"method\":\"card\"}}",null,null));
        assertRun("invalid-tool-arguments",order(input(null)),TraceContractGolden.INVALID_TOOL_ARGUMENTS);
    }
    /** 업무 Mock 의 일반 예외는 TOOL_ERROR 로 바뀌고 결제된 주문은 예약을 유지한다. */
    @Test void businessToolFailureAfterPayment(){
        doThrow(new IllegalStateException("injected failure")).when(shipping).reserve(any(UUID.class),anyString(),anyBoolean(),anyBoolean(),anyBoolean());
        assertRun("business-tool-failure",order(input(null)),TraceContractGolden.BUSINESS_TOOL_FAILURE);
    }
    /** 결정 제공자의 일반 예외는 결정 오류 없이 EXECUTION_ERROR 로 기록된다. */
    @Test void decisionProviderCrash(){
        doThrow(new IllegalStateException("provider crashed")).when(decisions).decide(argThat(q->q.skill()!=null&&q.skill().id().equals("payment")));
        assertRun("decision-provider-crash",order(input(null)),TraceContractGolden.DECISION_PROVIDER_CRASH);
    }
    /** notify 가 빠진 flow 는 배송까지 확정한 뒤 INCOMPLETE_FLOW 로 실패한다. */
    @Test void incompleteFlowWithoutNotify(){
        UUID snapshot=publishEdited(FLOW,FLOW_STEPS,"steps: [order-intake, inventory-check, payment, shipping-reserve]",Map.of());
        assertRun("incomplete-flow",order(input(snapshot)),TraceContractGolden.INCOMPLETE_FLOW);
    }
    /** flow 단계에 다른 flow 를 넣으면 NESTED_FLOW_NOT_SUPPORTED 계약 위반이다. */
    @Test void nestedFlowStep(){
        UUID snapshot=publishEdited(FLOW,FLOW_STEPS,"steps: [order-intake, sub-flow, notify]",Map.of("skills/sub-flow/SKILL.md","""
            ---
            name: sub-flow
            description: 하위 처리 순서
            steps: [order-intake]
            owner: 주문운영팀
            version: 1
            ---
            # 하위 처리 순서

            order-intake 를 실행합니다.
            """));
        assertRun("nested-flow",order(input(snapshot)),TraceContractGolden.NESTED_FLOW);
    }
    /** 같은 단계를 두 번 적은 flow 는 INVALID_FLOW 다. */
    @Test void duplicateFlowSteps(){
        UUID snapshot=publishEdited(FLOW,FLOW_STEPS,"steps: [order-intake, order-intake]",Map.of());
        assertRun("duplicate-flow-steps",order(input(snapshot)),TraceContractGolden.DUPLICATE_FLOW_STEPS);
    }
    /** 형식이 깨진 Skill 이 하나라도 있으면 후보 제시 전에 INVALID_SKILL_FORMAT 으로 실패한다. */
    @Test void malformedCandidateSkill(){
        UUID snapshot=publishEdited(null,null,null,Map.of("skills/broken-skill/SKILL.md","front matter 가 없는 파일"));
        assertRun("invalid-candidate",order(input(snapshot)),TraceContractGolden.INVALID_CANDIDATE);
    }
    /** 실행 시점에 snapshot 이 변조돼 있으면 SNAPSHOT_FIXED 없이 SNAPSHOT_TAMPERED 로 실패한다. */
    @Test void snapshotTamperedBeforeExecution(){
        var baseline=snapshots.capture();
        var source=orders.createOrder(UUID.randomUUID(),input(baseline.id())).order();
        UUID pending=pendingOrder(source.orderId(),"NOT_ATTEMPTED",false,false,baseline.id());
        jdbc.update("update snapshot_files set content=? where snapshot_id=? and path=?","tampered".getBytes(StandardCharsets.UTF_8),baseline.id(),"skills/payment/SKILL.md");
        RuntimeException failure=null;
        try{processor.process(pending);}catch(RuntimeException e){failure=e;}
        var order=orders.getOrder(pending);
        assertRun("snapshot-tampered",new Run(order,traces.get(order.executionId()),failure),TraceContractGolden.SNAPSHOT_TAMPERED);
    }
    /** 모의 실행은 dry_run 으로 기록하고 끝에 예약 재고를 DRY_RUN_CLEANUP 으로 되돌린다. */
    @Test void dryRunShipsAndCleansUp() throws Exception {
        var result=dryRun(snapshots.demo("baseline").id());
        assertThat(result.keySet()).containsExactly("execution_id","run_id","order_id","final_status","completion");
        assertRun("dry-run-shipped",dryRunResult(result),TraceContractGolden.DRY_RUN_SHIPPED);
    }
    /** 실패한 모의 실행도 200 결과이고 주문 단계는 실행이 남긴 그대로다. */
    @Test void dryRunFailureIsAResult() throws Exception {
        var result=dryRun(snapshots.demo("broken-contract").id());
        assertThat(result.keySet()).containsExactly("execution_id","run_id","order_id","final_status","completion");
        assertRun("dry-run-failed",dryRunResult(result),TraceContractGolden.DRY_RUN_FAILED);
    }

    /** 재시작 복구는 미결제 예약만 되돌리고 RUNNING 실행을 INTERRUPTED 로 닫는다. */
    @Test void restartRecoveryInterruptsRunningExecutionsAndReleasesOnlyUnpaidReservations(){
        var source=orders.createOrder(UUID.randomUUID(),input(null)).order();
        UUID unpaid=pendingOrder(source.orderId(),"NOT_ATTEMPTED",true,true,null);
        UUID paid=pendingOrder(source.orderId(),"PAID",true,true,null);
        UUID untraced=pendingOrder(source.orderId(),"NOT_ATTEMPTED",true,false,null);
        jdbc.update("update products set stock_quantity=stock_quantity-2 where sku=?",LANTERN);
        lifecycle.recoverInterrupted();
        assertThat(stock(LANTERN)).isEqualTo(9);
        var interrupted=orders.getOrder(unpaid);
        var trace=traces.get(interrupted.executionId());
        assertThat(((Number)trace.get("elapsed_ms")).longValue()).isBetween(4000L,600000L);
        assertThat(trace.get("finished_at")).isNotNull();
        assertRun("recovery-unpaid",new Run(interrupted,trace,null),TraceContractGolden.RECOVERY_UNPAID);
        var kept=orders.getOrder(paid);
        assertThat(steps(kept)).containsExactly("RECOVERY FAILED PROCESS_INTERRUPTED 이전 실행이 중단되었습니다. 확정된 결과는 보존하고 미결제 예약은 해제했습니다.");
        assertThat(render(traces.get(kept.executionId()))).containsExactly("EXECUTION_INTERRUPTED {\"category\":\"PROCESS_INTERRUPTED\"}");
        assertThat(summary(traces.get(kept.executionId()))).isEqualTo(summary(trace));
        assertThat(jdbc.queryForObject("select inventory_reserved from orders where id=?",Boolean.class,paid)).isTrue();
        var released=orders.getOrder(untraced);
        assertThat(released.executionId()).isNull();
        assertThat(steps(released)).containsExactly(
            "RELEASE SUCCESS PROCESS_INTERRUPTED 중단된 실행의 미결제 재고 예약을 복원했습니다.",
            "RECOVERY FAILED PROCESS_INTERRUPTED 이전 실행이 중단되었습니다. 확정된 결과는 보존하고 미결제 예약은 해제했습니다.");
        assertThat(jdbc.queryForObject("select inventory_reserved from orders where id=?",Boolean.class,untraced)).isFalse();
        assertThat(orders.getOrder(source.orderId()).status().name()).isEqualTo("SHIPPED");
        assertThat(orders.getOrder(source.orderId()).steps()).hasSize(5);
    }

    /** 도구 트랜잭션은 주문 잠금, RUNNING 확인, 멱등 재생, 주문 상태 확인, 서버 인자 대조 순서로 검사한다. */
    @Test void toolServiceChecksRunningReplayStatusAndArgumentsInOrder(){
        var source=orders.createOrder(UUID.randomUUID(),input(null)).order();
        UUID pending=pendingOrder(source.orderId(),"NOT_ATTEMPTED",false,false,null);
        UUID execution=traces.begin(pending,"live","fixed","fixed-policy-v1");
        var args=Map.<String,Object>of("order_id",pending.toString());
        var first=tools.execute(execution,pending,"order-intake","order.inspect",args);
        var replay=tools.execute(execution,pending,"order-intake","order.inspect",args);
        assertThat(Json.hash(replay)).isEqualTo(Json.hash(first));
        assertThat(first.keySet()).containsExactlyInAnyOrder("context","final_status");
        assertThat(keys(first.get("context"))).containsExactly(
            "order.id","order.customer","order.items","order.payment_method","order.shipping","order.total");
        assertThatThrownBy(()->tools.execute(execution,pending,"order-intake","order.inspect",Map.of("order_id","other")))
            .isInstanceOf(AgentException.class).hasMessage("IDEMPOTENCY_CONFLICT");
        assertThatThrownBy(()->tools.execute(execution,pending,"inventory-check","inventory.reserve",args))
            .isInstanceOf(AgentException.class).hasMessage("INVALID_TOOL_ARGUMENTS");
        assertThatThrownBy(()->tools.execute(execution,pending,"gift-wrap","gift.wrap",args))
            .isInstanceOf(AgentException.class).hasMessage("UNKNOWN_TOOL");
        jdbc.update("update orders set status='HOLD' where id=?",pending);
        var items=List.of(Map.of("sku",LANTERN,"qty",1));
        assertThatThrownBy(()->tools.execute(execution,pending,"inventory-check","inventory.reserve",Map.of("order_id",pending.toString(),"items",items)))
            .isInstanceOf(AgentException.class).hasMessage("ORDER_NOT_PROCESSING");
        assertThat(Json.hash(tools.execute(execution,pending,"order-intake","order.inspect",args))).isEqualTo(Json.hash(first));
        jdbc.update("update agent_executions set completion='FAILED' where id=?",execution);
        assertThatThrownBy(()->tools.execute(execution,pending,"order-intake","order.inspect",args))
            .isInstanceOf(AgentException.class).hasMessage("EXECUTION_NOT_RUNNING");
        var order=orders.getOrder(pending);
        assertRun("tool-replay",new Run(order,traces.get(execution),null),TraceContractGolden.TOOL_REPLAY);
    }

    /** trace·최근 실행·실패 목록 응답의 키 순서와 값 타입을 고정한다. */
    @Test void traceRecentAndFailedListsKeepKeyOrderAndTypes() throws Exception {
        var shipped=orders.createOrder(UUID.randomUUID(),input(null)).order();
        var trace=traces.get(shipped.executionId());
        var keys=List.of("execution_id","run_id","order_id","snapshot_id","snapshot_hash","agent_id","mode","model_mode","model",
            "completion","raw_selection","resolved_skill_id","error_category","final_status","api_calls","input_tokens","output_tokens",
            "elapsed_ms","started_at","finished_at");
        var withEvents=new ArrayList<>(keys);withEvents.add("events");
        assertThat(trace.keySet()).containsExactlyElementsOf(withEvents);
        assertThat(trace.get("execution_id")).isEqualTo(shipped.executionId());
        assertThat(trace.get("order_id")).isEqualTo(shipped.orderId());
        assertThat(trace.get("snapshot_id")).isInstanceOf(UUID.class);
        assertThat(trace.get("run_id")).isEqualTo("r_"+shipped.executionId().toString().replace("-",""));
        assertThat(trace.get("api_calls")).isInstanceOf(Integer.class).isEqualTo(0);
        assertThat(trace.get("elapsed_ms")).isInstanceOf(Long.class);
        assertThat(trace.get("input_tokens")).isNull();
        assertThat(trace.get("output_tokens")).isNull();
        assertThat(Instant.parse((String)trace.get("started_at"))).isBeforeOrEqualTo(Instant.parse((String)trace.get("finished_at")));
        var events=events(trace);
        for(int i=0;i<events.size();i++){
            assertThat(events.get(i).keySet()).containsExactly("sequence","kind","skill_id","data","at");
            assertThat(events.get(i).get("sequence")).isEqualTo(i+1);
            assertThat(Instant.parse((String)events.get(i).get("at"))).isNotNull();
        }
        var http=read("/api/executions/"+shipped.executionId()+"/trace");
        assertThat(http.keySet()).containsExactlyElementsOf(withEvents);
        assertThat(((List<Map<String,Object>>)http.get("events")).getFirst().keySet()).containsExactly("sequence","kind","skill_id","data","at");

        UUID newest=null;
        for(int i=0;i<21;i++){
            newest=UUID.randomUUID();
            jdbc.update("""
                insert into agent_executions(id,order_id,agent_id,mode,model,completion,started_at)
                values (?,?,'camping-order-agent','live','fixed-policy-v1','RUNNING',?)
                """,newest,shipped.orderId(),Timestamp.from(Instant.parse("2030-01-01T00:00:00Z").plusSeconds(i)));
        }
        var recent=items(read("/api/executions"));
        assertThat(recent).hasSize(20);
        assertThat(recent.getFirst().get("execution_id")).isEqualTo(newest.toString());
        assertThat(recent).allSatisfy(item->assertThat(item.keySet()).containsExactlyElementsOf(keys));
        assertThat(recent).noneMatch(item->item.get("execution_id").equals(shipped.executionId().toString()));

        assertThatThrownBy(()->orders.createOrder(UUID.randomUUID(),input(snapshots.demo("broken-contract").id())))
            .isInstanceOf(BusinessException.class);
        var failed=items(read("/api/executions/failed"));
        assertThat(failed).hasSize(1);
        assertThat(failed.getFirst().keySet()).containsExactly("execution_id","order_id","snapshot_id","snapshot_hash",
            "completion","final_status","started_at","finished_at","error");
        assertThat(((Map<String,Object>)failed.getFirst().get("error")).keySet().iterator().next()).isEqualTo("category");
    }

    /** 이전 run_id 별칭은 대문자 16진도 받고 형식이 다르면 EXECUTION_NOT_FOUND 404 다. */
    @Test void legacyRunIdAcceptsUppercaseHexAndRejectsOtherShapes() throws Exception {
        var shipped=orders.createOrder(UUID.randomUUID(),input(null)).order();
        String hex=shipped.executionId().toString().replace("-","");
        mvc.perform(get("/api/traces/r_"+hex.toUpperCase())).andExpect(status().isOk())
            .andExpect(jsonPath("execution_id").value(shipped.executionId().toString()))
            .andExpect(jsonPath("run_id").value("r_"+hex));
        for(String bad:List.of("r_"+hex.substring(1),hex,"R_"+hex,"r_"+hex.substring(1)+"g","r_"+hex+"0",
                "r_"+UUID.randomUUID().toString().replace("-",""))){
            mvc.perform(get("/api/traces/"+bad)).andExpect(status().isNotFound()).andExpect(jsonPath("code").value("EXECUTION_NOT_FOUND"));
        }
    }

    /** 랜턴 1개를 c_1001 이 주문하는 기본 요청을 만든다. */
    CreateOrderRequest input(UUID snapshot){return input(snapshot,"c_1001",LANTERN);}
    /** 한 품목 1개짜리 국내 일반 배송 카드 주문 요청을 만든다. */
    CreateOrderRequest input(UUID snapshot,String customer,String sku){
        return new CreateOrderRequest(customer,List.of(new CreateOrderRequest.Item(sku,1)),"card",new CreateOrderRequest.Shipping("KR",false),snapshot);
    }
    /** 주문을 만들고, 실패해도 요청 키로 주문과 trace 를 다시 읽는다. */
    Run order(CreateOrderRequest request){
        UUID key=UUID.randomUUID();RuntimeException failure=null;
        try{orders.createOrder(key,request);}catch(RuntimeException e){failure=e;}
        var order=orders.getByRequestId(key);
        return new Run(order,traces.get(order.executionId()),failure);
    }
    /** 게시 번들을 고정한 뒤 파일 하나를 고치고 파일을 더해 새 snapshot 으로 발행한다. */
    UUID publishEdited(String path,String from,String to,Map<String,String> additions){
        var files=new TreeMap<>(snapshots.capture().files());
        if(path!=null){
            String original=new String(files.get(path),StandardCharsets.UTF_8);
            String edited=original.replace(from,to);
            assertThat(edited).isNotEqualTo(original);
            files.put(path,edited.getBytes(StandardCharsets.UTF_8));
        }
        additions.forEach((p,text)->files.put(p,text.getBytes(StandardCharsets.UTF_8)));
        return snapshots.publish(files,Map.of("source","trace-contract")).id();
    }
    /** 엔트리 선택 질문에 고정 제공자가 내놓을 응답 원문을 바꾼다. 토큰 수는 관측하지 않은 값(null)으로 둔다. */
    void selectReplies(String raw){
        doReturn(new DecisionProvider.Reply(raw,null,null)).when(decisions).decide(argThat(q->"select".equals(q.purpose())));
    }
    /** 지정한 단계 Skill 의 도구 질문에 고정 제공자가 내놓을 응답을 바꾼다. */
    void toolReplies(String skill,DecisionProvider.Reply reply){
        doReturn(reply).when(decisions).decide(argThat(q->q.skill()!=null&&q.skill().id().equals(skill)));
    }
    /** 이미 처리된 주문을 복사해 처리 중(PROCESSING) 주문 행을 만들고, running 이면 RUNNING 실행 행을 연결한다. */
    UUID pendingOrder(UUID source,String paymentStatus,boolean reserved,boolean running,UUID snapshot){
        UUID id=UUID.randomUUID();
        jdbc.update("""
            insert into orders (id, request_id, request_hash, customer, payment_method, shipping_country, shipping_express,
              total_amount, status, payment_status, notification_status, created_at, updated_at, version,
              inventory_reserved, requested_snapshot_id)
            select ?, ?, request_hash, customer, payment_method, shipping_country, shipping_express,
              total_amount, 'PROCESSING', ?, 'NOT_ATTEMPTED', created_at, updated_at, 0, ?, ? from orders where id=?
            """,id,UUID.randomUUID(),paymentStatus,reserved,snapshot,source);
        jdbc.update("""
            insert into order_items (order_id, product_id, sku, product_name, unit_price, quantity, line_total)
            select ?, product_id, sku, product_name, unit_price, quantity, line_total from order_items where order_id=?
            """,id,source);
        if(running){
            UUID execution=UUID.randomUUID();
            jdbc.update("""
                insert into agent_executions(id,order_id,agent_id,mode,model_mode,model,completion,started_at)
                values (?,?,'camping-order-agent','live','fixed','fixed-policy-v1','RUNNING',?)
                """,execution,id,Timestamp.from(Instant.now().minusSeconds(5)));
            jdbc.update("update orders set execution_id=? where id=?",execution,id);
        }
        return id;
    }
    /** POST /api/dry-runs 로 랜턴 1개 모의 주문을 실행하고 응답 JSON 을 키 순서대로 읽는다. */
    Map<String,Object> dryRun(UUID snapshot) throws Exception {
        String body="""
            {"snapshot_id":"%s","order":{"customer":"c_1001","items":[{"sku":"CAMP-LANTERN-LED","qty":1}],
             "payment_method":"card","shipping":{"country":"KR","express":false}}}
            """.formatted(snapshot);
        return Json.object(mvc.perform(post("/api/dry-runs").contentType(MediaType.APPLICATION_JSON).content(body))
            .andExpect(status().isOk()).andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8));
    }
    /** 모의 실행 응답이 가리키는 주문과 trace 를 읽는다. */
    Run dryRunResult(Map<String,Object> result){
        var order=orders.getOrder(UUID.fromString((String)result.get("order_id")));
        var trace=traces.get(UUID.fromString((String)result.get("execution_id")));
        assertThat(result.get("final_status")).isEqualTo(trace.get("final_status"));
        assertThat(result.get("completion")).isEqualTo(trace.get("completion"));
        assertThat(result.get("run_id")).isEqualTo(trace.get("run_id"));
        return new Run(order,trace,null);
    }
    /** GET 응답 JSON 을 키 순서를 보존해 읽는다. */
    Map<String,Object> read(String path) throws Exception {
        return Json.object(mvc.perform(get(path)).andExpect(status().isOk()).andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8));
    }
    /** 목록 응답의 items 를 꺼낸다. */
    List<Map<String,Object>> items(Map<String,Object> body){return (List<Map<String,Object>>)body.get("items");}
    /** trace 의 events 를 꺼낸다. */
    List<Map<String,Object>> events(Map<String,Object> trace){return (List<Map<String,Object>>)trace.get("events");}
    /** Map 의 키를 순서대로 문자열 목록으로 바꾼다. */
    static List<String> keys(Object map){return ((Map<?,?>)map).keySet().stream().map(Object::toString).toList();}
    /** 상품의 지금 재고를 DB 에서 읽는다. */
    int stock(String sku){return jdbc.queryForObject("select stock_quantity from products where sku=?",Integer.class,sku);}

    /** 실행 하나를 기대 줄과 비교하고, 순서가 계약인 이벤트 키도 함께 확인한다. data 없는 기대 이벤트 줄은 KIND:skill_id 만 비교한다. */
    void assertRun(String name,Run run,String golden){
        assertOrderedKeys(run.trace());
        var expected=golden.lines().toList();
        var actual=describe(run);
        var comparable=new ArrayList<String>();
        boolean inEvents=false;
        for(int i=0;i<actual.size();i++){
            String line=actual.get(i);
            if(inEvents&&i<expected.size()&&!expected.get(i).contains(" "))line=line.split(" ",2)[0];
            if(line.equals("events:"))inEvents=true;
            comparable.add(line);
        }
        assertThat(comparable).as(name).containsExactlyElementsOf(expected);
    }
    /** 실행 하나를 요약·예외·주문 상태·재고·주문 단계·이벤트 줄로 펼친다. */
    List<String> describe(Run run){
        var order=run.order();var lines=new ArrayList<String>();
        lines.add("summary: "+summary(run.trace()));
        lines.add("failure: "+failure(run.failure()));
        lines.add("order: "+order.status()+" "+order.resultCode()+" payment="+order.payment().status()
            +" shipment="+(order.shipment()!=null)+" notification="+order.notification().status());
        lines.add("stock: lantern="+stock(LANTERN)+" chair="+stock("CAMP-CHAIR-FOLD")+" mug="+stock("CAMP-MUG-STEEL"));
        lines.add("steps:");
        lines.addAll(steps(order));
        lines.add("events:");
        lines.addAll(render(run.trace()));
        return lines;
    }
    /** 주문 단계를 '단계 상태 결과코드 메시지' 줄로 바꾼다. */
    List<String> steps(OrderResponse order){
        return order.steps().stream().map(s->s.step()+" "+s.status()+" "+s.resultCode()+" "+s.message()).toList();
    }
    /** trace 요약 컬럼 가운데 실행마다 같은 값만 한 줄로 만든다. */
    String summary(Map<String,Object> trace){
        var parts=new ArrayList<String>();
        for(String key:List.of("completion","final_status","error_category","raw_selection","resolved_skill_id","agent_id","mode",
                "model_mode","model","api_calls","input_tokens","output_tokens"))parts.add(key+"="+trace.get(key));
        return String.join(" ",parts);
    }
    /** 던져진 예외를 종류와 코드로 요약한다. */
    String failure(RuntimeException failure){
        if(failure==null)return "none";
        if(failure instanceof BusinessException b)return "BusinessException "+b.getCode()+" order_id="+(b.getOrderId()!=null);
        if(failure instanceof AgentException a)return "AgentException "+a.category();
        return failure.getClass().getSimpleName();
    }
    /** trace 의 이벤트를 'KIND:skill_id data' 줄로 바꾼다. data 는 키를 정렬하고 실행마다 달라지는 값을 감춘다. */
    List<String> render(Map<String,Object> trace){
        var canonical=new Canonical(trace);
        return events(trace).stream().map(e->e.get("kind")+(e.get("skill_id")==null?"":":"+e.get("skill_id"))+" "
            +Json.write(canonical.data((Map<?,?>)e.get("data")))).toList();
    }
    /** 순서가 계약인 이벤트 data 키(단계 종료, 오류, 실행 종료, 선택, 모델 응답)를 확인한다. */
    void assertOrderedKeys(Map<String,Object> trace){
        for(var event:events(trace)){
            var keys=keys(event.get("data"));
            switch((String)event.get("kind")){
                case "SKILL_FINISHED" -> assertThat(keys.subList(keys.size()-5,keys.size())).containsExactly("step_no","version","status","output","duration_ms");
                case "EXECUTION_ERROR" -> assertThat(keys.getFirst()).isEqualTo("category");
                case "EXECUTION_FINISHED" -> {
                    if(keys.size()==3)assertThat(keys).containsExactly("completion","final_status","error_category");
                    else assertThat(keys).containsExactlyInAnyOrder("completion","final_status");
                }
                case "SKILL_SELECTED","SKILL_RESOLVED" -> assertThat(keys).containsExactly("raw_name","resolved_skill_id");
                case "MODEL_RESPONSE" -> assertThat(keys).containsExactly("raw_response","input_tokens","output_tokens");
                default -> {}
            }
        }
    }

    /** 비교용 data 를 만든다. 실행마다 달라지는 식별자·해시·시간과 프롬프트 문구는 감춘다. */
    final class Canonical {
        private final Map<String,String> masks=new LinkedHashMap<>();
        private final Map<String,String> descriptions=new HashMap<>();
        /** trace 의 실행·주문·snapshot 식별자와 snapshot 파일 해시를 가림 표에 넣는다. */
        Canonical(Map<String,Object> trace){
            masks.put(String.valueOf(trace.get("execution_id")),"<execution>");
            masks.put(String.valueOf(trace.get("order_id")),"<order>");
            if(trace.get("snapshot_id") instanceof UUID snapshot){
                masks.put(snapshot.toString(),"<snapshot>");
                masks.put((String)trace.get("snapshot_hash"),"<snapshot-hash>");
                snapshots.get(snapshot).files().forEach((path,bytes)->{
                    String id=path.matches("skills/[^/]+/SKILL\\.md")?path.split("/")[1]:path;
                    masks.put(Json.hash(bytes),"<hash:"+id+">");
                    String text=new String(bytes,StandardCharsets.UTF_8);
                    text.lines().filter(line->line.startsWith("description: ")).findFirst()
                        .ifPresent(line->descriptions.put(id,line.substring("description: ".length())));
                });
            }
        }
        /** 이벤트 data 최상위 Map 을 바꾼다. 도구 결정의 inputs 는 선언 순서를 지킨다. */
        Object data(Map<?,?> data){return map(data,false,"tool".equals(data.get("purpose")));}
        /** Map 하나를 바꾼다. ordered 면 원래 키 순서를 지키고, 아니면 키를 정렬해 Map.of 처럼 순서가 정해지지 않은 값도 비교할 수 있게 한다. */
        private Object map(Map<?,?> source,boolean ordered,boolean toolInputs){
            Map<String,Object> result=ordered?new LinkedHashMap<>():new TreeMap<>();
            source.forEach((key,value)->result.put(key.toString(),entry(key.toString(),value,source,toolInputs)));
            return result;
        }
        /**
         * 키 하나의 값을 바꾼다. 경과 시간은 음수가 아닌지만 보고 자리표시자로, 배송 예정일은 날짜 모양만 보고 자리표시자로 바꾼다.
         * 도구 정의가 ToolCatalog.definition 과 같으면 이름만 남기고, 모델 응답 원문은 JSON 이면 정규화해 json: 을 앞에 붙이고
         * 아니면 식별자만 가린 글자 그대로 둔다.
         * 후보의 description 이 snapshot 의 front matter 와 같으면 자리표시자로 바꾼다. 나머지는 value 로 바꾸되, 키가 ORDERED 에 있거나
         * 도구 결정의 inputs 이면 키 순서를 지킨다.
         */
        private Object entry(String key,Object value,Map<?,?> parent,boolean toolInputs){
            if(key.equals("duration_ms")||key.equals("elapsed_ms")){
                assertThat(((Number)value).longValue()).isNotNegative();return "<ms>";
            }
            if(key.equals("shipping.eta")){assertThat((String)value).matches("\\d{4}-\\d{2}-\\d{2}");return "<eta>";}
            if(key.equals("tool_definition")&&value instanceof Map<?,?> definition&&!definition.isEmpty()
                    &&definition.equals(ToolCatalog.definition((String)definition.get("name"))))return "<definition:"+definition.get("name")+">";
            if(key.equals("raw_response")&&value instanceof String raw){
                try{return "json:"+Json.write(value(Json.object(raw),false));}catch(RuntimeException e){return text(raw);}
            }
            if(key.equals("description")&&parent.get("skill_id") instanceof String id&&value!=null&&value.equals(descriptions.get(id)))
                return "<description:"+id+">";
            return value(value,ORDERED.contains(key)||(key.equals("inputs")&&toolInputs));
        }
        /** 값의 종류에 따라 Map 은 map 으로, 목록은 원소마다, 문자열은 text 로 바꾸고 나머지는 그대로 둔다. 목록 원소의 Map 은 키를 정렬한다. */
        private Object value(Object value,boolean ordered){
            if(value instanceof Map<?,?> map)return map(map,ordered,false);
            if(value instanceof List<?> list)return list.stream().map(item->value(item,false)).toList();
            if(value instanceof String text)return text(text);
            return value;
        }
        /** 문자열 안의 식별자와 해시를 가림 표의 자리표시자로 바꾼다. */
        private String text(String value){
            for(var mask:masks.entrySet())value=value.replace(mask.getKey(),mask.getValue());
            return value;
        }
    }
}
