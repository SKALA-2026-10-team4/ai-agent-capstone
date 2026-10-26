package com.capstone4.orderagent.harness;

import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;
import com.capstone4.orderagent.entity.enums.ProcessingStepType;
import com.capstone4.orderagent.service.*;
import com.capstone4.orderagent.trace.TraceService;
import com.capstone4.orderagent.workspace.*;
import org.junit.jupiter.api.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * order-agent/backend/src/main/java/com/capstone4/orderagent/harness/OrderHarness.java 의 실행 규칙(rules) 경로를 DB 없이 고정한다.
 * 게시 번들(order-agent/skills)의 Skill 여섯 개에 규칙만 끼워 넣은 snapshot 을 SnapshotService mock 으로 주고, SkillLoader, SkillExecutor,
 * FixedDecisionProvider, ToolDispatcher 는 실제 객체를 쓴다. 업무 효과(OrderToolService)와 trace 기록(TraceService)은 mock 이며
 * 둘의 호출을 한 줄씩 timeline 에 모아 순서를 비교한다. 규칙이 매치된 단계는 결정 요청과 도구 호출 없이 RULE_MATCHED, stopByRule,
 * SKILL_FINISHED, BUSINESS_STOP(rule_reason 포함), 실행 종료 순서로 끝나야 하고, 매치되지 않으면 기존 흐름 그대로 끝나야 한다.
 * 도구가 업무 종료를 돌려준 경로(결제 거절)의 BUSINESS_STOP 에는 rule_reason 이 없어야 한다.
 * DB 를 쓰는 같은 경로의 기대 줄은 order-agent/backend/src/test/java/com/capstone4/orderagent/integration/TraceContractGolden.java 의 RULE_HOLD 와
 * RULE_REJECT_AT_INTAKE 에 있다.
 */
@SuppressWarnings("unchecked")
class OrderHarnessRuleTest {
    static final List<String> STEPS=List.of("order-flow","order-intake","inventory-check","payment","shipping-reserve","notify");
    final UUID order=UUID.randomUUID(),execution=UUID.randomUUID();
    final SnapshotService snapshots=mock(SnapshotService.class);
    final OrderToolService business=mock(OrderToolService.class);
    final OrderLifecycleService lifecycle=mock(OrderLifecycleService.class);
    final TraceService trace=mock(TraceService.class);
    final SkillExecutor decisions=new SkillExecutor(new FixedDecisionProvider(),trace,1000);
    // trace 이벤트와 업무 호출을 일어난 순서대로 적은 줄이다. 이벤트는 KIND:skill_id, 업무 호출은 대문자 동사로 시작한다.
    final List<String> timeline=new ArrayList<>();
    // trace 이벤트의 data 를 KIND:skill_id 마다 마지막 값으로 모은다.
    final Map<String,Map<String,Object>> data=new HashMap<>();
    // 이 도구는 정상 결과 대신 결제 거절(HOLD) 결과를 돌려준다. null 이면 모든 도구가 정상 결과다.
    String declinedTool;

    /** trace 와 업무 mock 이 호출을 timeline 에 적고, 도구는 정상 결과를, 규칙 종료는 then 에 맞는 상태를 돌려주게 한다. */
    @BeforeEach void record(){
        when(trace.begin(any(),anyString(),anyString(),anyString())).thenReturn(execution);
        doAnswer(inv->{
            String line=inv.getArgument(1)+(inv.getArgument(2)==null?"":":"+inv.getArgument(2));
            timeline.add(line);data.put(line,(Map<String,Object>)inv.getArgument(3));return null;
        }).when(trace).event(any(),anyString(),any(),anyMap());
        doAnswer(inv->timeline.add("MODEL_RESPONSE:"+inv.getArgument(1))).when(trace).modelResult(any(),any(),any(),any(),any());
        doAnswer(inv->timeline.add("FINISH "+inv.getArgument(1)+" "+inv.getArgument(2)+" "+inv.getArgument(3)))
            .when(trace).finish(any(),anyString(),any(),any(),anyLong());
        when(business.execute(any(),any(),anyString(),anyString(),anyMap())).thenAnswer(inv->{
            timeline.add("EXECUTE "+inv.getArgument(3));return toolResult(inv.getArgument(3));
        });
        when(business.stopByRule(any(),any(),anyString(),any(),anyString(),anyString())).thenAnswer(inv->{
            timeline.add("STOP_BY_RULE "+inv.getArgument(2)+" "+inv.getArgument(3)+" "+inv.getArgument(4)+" "+inv.getArgument(5));
            return inv.getArgument(4).equals("hold")?"HOLD":"REJECTED";
        });
        doAnswer(inv->timeline.add("COMPLETE")).when(business).complete(any(),any(),anyLong());
    }
    @AfterEach void close(){decisions.close();}

    /** payment 의 hold 규칙이 카드 주문에 매치되면 결정 요청과 결제 도구 없이 HOLD 로 끝나고, 규칙 종료가 PAYMENT 단계로 기록된다. */
    @Test void holdRuleAtPaymentStopsBeforeDecisionAndTool(){
        run("card","KR",Map.of("payment",rules("order.payment_method != 'prepaid'","hold","PREPAID_ONLY")));
        assertThat(timeline).endsWith(
            "SKILL_FINISHED:inventory-check","SKILL_LOADED:payment","SKILL_STARTED:payment","RULE_MATCHED:payment",
            "STOP_BY_RULE payment PAYMENT hold PREPAID_ONLY","SKILL_FINISHED:payment","BUSINESS_STOP:payment","FINISH COMPLETED HOLD null");
        assertThat(timeline).doesNotContain("DECISION_INPUT:payment","EXECUTE payment.charge","COMPLETE");
        var matched=data.get("RULE_MATCHED:payment");
        assertThat(new ArrayList<>(matched.keySet())).containsExactly("skill_id","rule_index","when","then","reason");
        assertThat(matched).isEqualTo(Map.of("skill_id","payment","rule_index",0,"when","order.payment_method != 'prepaid'","then","hold","reason","PREPAID_ONLY"));
        var finished=data.get("SKILL_FINISHED:payment");
        assertThat(new ArrayList<>(finished.keySet())).containsExactly("final_status","business_stop","rule_reason","step_no","version","status","output","duration_ms");
        assertThat(finished).containsEntry("final_status","HOLD").containsEntry("business_stop",true).containsEntry("rule_reason","PREPAID_ONLY")
            .containsEntry("step_no",3).containsEntry("status","ok").containsEntry("output",Map.of());
        assertThat(data.get("BUSINESS_STOP:payment")).isEqualTo(Map.of("final_status","HOLD","rule_reason","PREPAID_ONLY"));
        verify(lifecycle,never()).fail(any(),any(),any());
        verify(business,never()).compensateUnpaid(any(),any(),any());
    }
    /** order-intake 의 규칙은 order.shipping 하위 값으로 평가되고, 위에서부터 처음 참인 규칙 하나만 적용돼 도구 없이 REJECTED 로 끝난다. */
    @Test void firstMatchingRuleAtIntakeRejectsWithoutAnyTool(){
        run("card","US",Map.of("order-intake",rules(
            "order.customer == 'c_vip'","hold","VIP_REVIEW",
            "order.shipping.country != 'KR'","reject","DOMESTIC_ONLY",
            "order.payment_method == 'card'","hold","CARD_REVIEW")));
        assertThat(timeline).endsWith("SKILL_STARTED:order-intake","RULE_MATCHED:order-intake",
            "STOP_BY_RULE order-intake INTAKE reject DOMESTIC_ONLY","SKILL_FINISHED:order-intake","BUSINESS_STOP:order-intake",
            "FINISH COMPLETED REJECTED null");
        assertThat(timeline).noneMatch(line->line.startsWith("EXECUTE ")||line.startsWith("DECISION_INPUT:order-intake"));
        assertThat(data.get("RULE_MATCHED:order-intake")).containsEntry("rule_index",1).containsEntry("reason","DOMESTIC_ONLY");
        assertThat(data.get("SKILL_FINISHED:order-intake")).containsEntry("step_no",1).containsEntry("final_status","REJECTED");
        assertThat(data.get("BUSINESS_STOP:order-intake")).isEqualTo(Map.of("final_status","REJECTED","rule_reason","DOMESTIC_ONLY"));
    }
    /** 규칙이 있어도 매치되지 않으면 기존과 같이 다섯 도구를 모두 부르고 SHIPPED 확정으로 끝난다. */
    @Test void unmatchedRuleKeepsTheNormalFlow(){
        run("prepaid","KR",Map.of("payment",rules("order.payment_method != 'prepaid'","hold","PREPAID_ONLY")));
        assertThat(timeline).filteredOn(line->line.startsWith("EXECUTE ")).containsExactly(
            "EXECUTE order.inspect","EXECUTE inventory.reserve","EXECUTE payment.charge","EXECUTE shipping.reserve","EXECUTE notify.send");
        assertThat(timeline).endsWith("SKILL_FINISHED:notify","COMPLETE");
        assertThat(timeline).noneMatch(line->line.startsWith("RULE_MATCHED")||line.startsWith("STOP_BY_RULE")||line.startsWith("BUSINESS_STOP"));
        assertThat(data.get("SKILL_FINISHED:payment")).doesNotContainKey("rule_reason").containsEntry("output",Map.of("payment.id","pay_1","payment.status","PAID"));
    }
    /** 결제 뒤 단계의 규칙은 결제 도구를 실행한 다음 그 단계에서 멈추고, 규칙 종료는 SHIPPING 단계로 기록된다(결제 상태는 OrderToolService 가 그대로 둔다). */
    @Test void ruleAfterPaymentStopsAtThatStepAfterThePaymentTool(){
        run("card","KR",Map.of("shipping-reserve",rules("payment.status == 'PAID'","hold","MANUAL_SHIPPING_REVIEW")));
        assertThat(timeline).filteredOn(line->line.startsWith("EXECUTE ")).containsExactly(
            "EXECUTE order.inspect","EXECUTE inventory.reserve","EXECUTE payment.charge");
        assertThat(timeline).endsWith("SKILL_FINISHED:payment","SKILL_LOADED:shipping-reserve","SKILL_STARTED:shipping-reserve",
            "RULE_MATCHED:shipping-reserve","STOP_BY_RULE shipping-reserve SHIPPING hold MANUAL_SHIPPING_REVIEW",
            "SKILL_FINISHED:shipping-reserve","BUSINESS_STOP:shipping-reserve","FINISH COMPLETED HOLD null");
        assertThat(data.get("SKILL_FINISHED:shipping-reserve")).containsEntry("step_no",4);
        assertThat(data.get("BUSINESS_STOP:shipping-reserve")).isEqualTo(Map.of("final_status","HOLD","rule_reason","MANUAL_SHIPPING_REVIEW"));
    }
    /** 규칙 없이 결제 도구가 거절을 돌려준 업무 종료의 BUSINESS_STOP 은 예전 모양 그대로 final_status 만 담고 rule_reason 은 없다. */
    @Test void toolBusinessStopKeepsBusinessStopWithoutRuleReason(){
        declinedTool="payment.charge";
        when(business.status(order)).thenReturn("HOLD");
        run("card","KR",Map.of());
        assertThat(timeline).filteredOn(line->line.startsWith("EXECUTE ")).containsExactly(
            "EXECUTE order.inspect","EXECUTE inventory.reserve","EXECUTE payment.charge");
        assertThat(timeline).endsWith("SKILL_FINISHED:payment","BUSINESS_STOP:payment","FINISH COMPLETED HOLD null");
        assertThat(timeline).noneMatch(line->line.startsWith("RULE_MATCHED")||line.startsWith("STOP_BY_RULE"));
        assertThat(data.get("SKILL_FINISHED:payment")).doesNotContainKey("rule_reason").containsEntry("business_stop",true);
        assertThat(data.get("BUSINESS_STOP:payment")).isEqualTo(Map.of("final_status","HOLD"));
    }
    /** 선언하지 않은 키를 쓰는 규칙은 flow 검증에서 RULE_KEY_NOT_INPUT 계약 오류로 멈추고 업무 도구는 하나도 부르지 않는다. */
    @Test void undeclaredRuleKeyFailsFlowValidationBeforeAnyTool(){
        var failure=catchThrowable(()->run("card","KR",Map.of("payment",rules("order.customer == 'c_declined'","hold","DECLINED_BEFORE"))));
        assertThat(failure).isInstanceOfSatisfying(AgentException.class,e->{
            assertThat(e.category()).isEqualTo("CONTRACT_VIOLATION");
            assertThat(e.details()).containsEntry("reason","RULE_KEY_NOT_INPUT").containsEntry("skill_id","payment");
        });
        assertThat(timeline).noneMatch(line->line.startsWith("EXECUTE ")||line.startsWith("FLOW_VALIDATED")||line.startsWith("RULE_MATCHED"));
        assertThat(timeline).contains("EXECUTION_ERROR:payment");
        verify(lifecycle).fail(order,ProcessingStepType.PAYMENT,"CONTRACT_VIOLATION");
    }

    /** 결제 방식과 배송 국가로 랜턴 1개 주문의 처음 컨텍스트를 만들고, 규칙을 끼워 넣은 게시 번들로 주문 하나를 실행한다. */
    void run(String method,String country,Map<String,String> rulesBySkill){
        var context=new LinkedHashMap<String,Object>();
        context.put("order.id",order.toString());context.put("order.customer","c_1001");
        context.put("order.items",List.of(Map.of("sku","CAMP-LANTERN-LED","qty",1)));context.put("order.payment_method",method);
        context.put("order.shipping",Map.of("country",country,"express",false));context.put("order.total",30000L);
        when(business.initial(order)).thenReturn(new OrderToolService.Initial(context,null,false));
        when(snapshots.capture()).thenReturn(new SnapshotRepository.Stored(UUID.randomUUID(),"snapshot-hash",Map.of(),Instant.now(),bundle(rulesBySkill)));
        new OrderHarness(snapshots,new SkillLoader(),decisions,new ToolDispatcher(business,trace),business,lifecycle,trace,
            45000,12,10000,"test-model").execute(order);
    }
    /** order-agent/skills 의 SKILL.md 여섯 개를 읽고, 지정한 Skill 의 front matter 끝(닫는 --- 앞)에 rules 블록을 넣는다. */
    static SortedMap<String,byte[]> bundle(Map<String,String> rulesBySkill){
        var files=new TreeMap<String,byte[]>();
        for(String id:STEPS){
            String text;
            try{text=Files.readString(Path.of("../skills",id,"SKILL.md"),StandardCharsets.UTF_8).replace("\r\n","\n");}
            catch(java.io.IOException e){throw new IllegalStateException(e);}
            if(rulesBySkill.containsKey(id)){
                int close=text.indexOf("\n---\n",4);
                text=text.substring(0,close+1)+rulesBySkill.get(id)+text.substring(close+1);
            }
            files.put(SkillLoader.skillPath(id),text.getBytes(StandardCharsets.UTF_8));
        }
        return files;
    }
    /** when, then, reason 세 값씩 받아 rules YAML 블록을 만든다. */
    static String rules(String... parts){
        var yaml=new StringBuilder("rules:\n");
        for(int i=0;i<parts.length;i+=3)
            yaml.append("  - when: \"").append(parts[i]).append("\"\n    then: ").append(parts[i+1]).append("\n    reason: ").append(parts[i+2]).append('\n');
        return yaml.toString();
    }
    /** 도구 이름마다 정상 결과(context, final_status PROCESSING)를 만든다. declinedTool 이면 결제 거절 결과(final_status HOLD)다. */
    Map<String,Object> toolResult(String tool){
        if(tool.equals(declinedTool))return Map.of("context",Map.of("payment.status","DECLINED"),"final_status","HOLD");
        Map<String,Object> context=switch(tool){
            case "order.inspect" -> Map.of("order.id",order.toString(),"order.total",30000L);
            case "inventory.reserve" -> Map.of("inventory.available",true,"inventory.reservable_quantity",10,"inventory.reserved",true);
            case "payment.charge" -> Map.of("payment.status","PAID","payment.id","pay_1");
            case "shipping.reserve" -> Map.of("shipping.id","ship_1","shipping.eta","2026-10-28");
            default -> Map.of("notify.sent",true,"notify.channel","mock");
        };
        return Map.of("context",context,"final_status","PROCESSING");
    }
}
