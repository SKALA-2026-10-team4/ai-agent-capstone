package com.capstone4.orderagent.integration;

import java.util.*;
import java.nio.charset.StandardCharsets;
import com.capstone4.orderagent.dto.order.CreateOrderRequest;
import com.capstone4.orderagent.harness.*;
import com.capstone4.orderagent.service.OrderService;
import com.capstone4.orderagent.trace.TraceService;
import com.capstone4.orderagent.workspace.SnapshotService;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties={"spring.datasource.url=jdbc:postgresql://localhost:5434/order_agent_test",
    "spring.datasource.username=order_test","spring.datasource.password=order_test_local"})
@AutoConfigureMockMvc
@EnabledIfEnvironmentVariable(named="ORDER_DEMO_INTEGRATION_TESTS",matches="true")
class AgentEvidenceIntegrationTest {
    @Autowired SnapshotService snapshots;
    @Autowired OrderService orders;
    @Autowired TraceService traces;
    @Autowired JdbcTemplate jdbc;
    @Autowired MockMvc mvc;
    @MockitoSpyBean FixedDecisionProvider decisions;
    static final String LANTERN="CAMP-LANTERN-LED";
    @BeforeEach void reset(){jdbc.update("delete from orders");jdbc.update("update products set stock_quantity=10 where sku=?",LANTERN);}
    CreateOrderRequest input(UUID snapshot){return new CreateOrderRequest("c_1001",List.of(new CreateOrderRequest.Item(LANTERN,1)),"card",new CreateOrderRequest.Shipping("KR",false),snapshot);}
    int stock(){return jdbc.queryForObject("select stock_quantity from products where sku=?",Integer.class,LANTERN);}
    UUID execution(){return jdbc.queryForObject("select id from agent_executions order by started_at desc limit 1",UUID.class);}
    @SuppressWarnings("unchecked") List<Map<String,Object>> events(Map<String,Object> trace){return (List<Map<String,Object>>)trace.get("events");}

    @Test void normalExecutionActuallyLoadsSixSkillsAndSharesSnapshotReferences() throws Exception {
        var result=orders.createOrder(UUID.randomUUID(),input(null)).order();
        assertThat(result.executionId()).isNotNull();
        var trace=traces.get(result.executionId());
        assertThat(trace.get("resolved_skill_id")).isEqualTo("order-flow");
        assertThat(trace.get("model_mode")).isEqualTo("fixed");
        assertThat(trace.get("mode")).isEqualTo("live");
        assertThat(trace.get("api_calls")).isEqualTo(0);
        assertThat(trace.get("input_tokens")).isNull();
        assertThat(events(trace).stream().filter(e->e.get("kind").equals("SKILL_LOADED")).map(e->e.get("skill_id")).toList())
            .containsExactly("order-flow","order-intake","inventory-check","payment","shipping-reserve","notify");
        UUID snapshot=(UUID)trace.get("snapshot_id");
        mvc.perform(get("/api/executions/"+result.executionId()+"/trace")).andExpect(status().isOk()).andExpect(jsonPath("completion").value("COMPLETED"));
        mvc.perform(get("/api/traces/"+trace.get("run_id"))).andExpect(status().isOk()).andExpect(jsonPath("execution_id").value(result.executionId().toString()));
        mvc.perform(get("/api/snapshots/"+snapshot+"/files").param("path","skills/inventory-check/resources/policy.md"))
            .andExpect(status().isOk()).andExpect(jsonPath("encoding").value("utf-8"));
        assertThat(snapshots.get(snapshot).files()).hasSize(7);
    }

    @ParameterizedTest(name="S1: {0}, stock={1} → {2}")
    @CsvSource({"c_1001,10,SHIPPED,9", "c_1001,0,REJECTED,0", "c_declined,10,HOLD,10"})
    void descriptionOnlyEditPreservesBusinessResultsAndLoadsEditedBytes(
            String customer,int initialStock,String expectedStatus,int remainingStock) throws Exception {
        String path="skills/inventory-check/SKILL.md";
        var baseline=snapshots.capture();
        var files=new TreeMap<>(baseline.files());
        String original=new String(files.get(path),StandardCharsets.UTF_8);
        String edited=original.replace("description: 상품 전체 재고를 확인하고 예약한다",
            "description: 주문에 포함된 모든 상품의 재고를 확인한 뒤 전체 수량을 예약한다");
        assertThat(edited).isNotEqualTo(original);
        files.put(path,edited.getBytes(StandardCharsets.UTF_8));
        var candidate=snapshots.publish(files,Map.of("source","s1-description-edit",
            "baseline_snapshot_id",baseline.id().toString()));
        assertThat(candidate.hash()).isNotEqualTo(baseline.hash());
        assertThat(candidate.files().keySet()).isEqualTo(baseline.files().keySet());
        baseline.files().forEach((p,bytes)->{
            if(!p.equals(path))assertThat(candidate.files().get(p)).isEqualTo(bytes);
        });

        for(var snapshot:List.of(baseline,candidate)){
            jdbc.update("update products set stock_quantity=? where sku=?",initialStock,LANTERN);
            var request=new CreateOrderRequest(customer,List.of(new CreateOrderRequest.Item(LANTERN,1)),
                "card",new CreateOrderRequest.Shipping("KR",false),snapshot.id());
            var order=orders.createOrder(UUID.randomUUID(),request).order();
            assertThat(order.status().name()).isEqualTo(expectedStatus);
            assertThat(order.totalAmount()).isEqualTo(30000);
            assertThat(stock()).isEqualTo(remainingStock);
            assertThat(order.payment().status().name()).isEqualTo(
                expectedStatus.equals("SHIPPED")?"PAID":expectedStatus.equals("HOLD")?"DECLINED":"NOT_ATTEMPTED");
            if(expectedStatus.equals("SHIPPED"))assertThat(order.shipment()).isNotNull();
            else assertThat(order.shipment()).isNull();
            assertThat(order.notification().status().name()).isEqualTo(
                expectedStatus.equals("SHIPPED")?"SENT":"NOT_ATTEMPTED");

            var trace=traces.get(order.executionId());
            assertThat(trace.get("snapshot_id")).isEqualTo(snapshot.id());
            assertThat(trace.get("completion")).isEqualTo("COMPLETED");
            assertThat(trace.get("error_category")).isNull();
            assertThat(events(trace).stream().filter(e->"TOOL_REQUEST".equals(e.get("kind")))
                .map(e->((Map<?,?>)e.get("data")).get("tool")).toList()).isEqualTo(
                    expectedStatus.equals("SHIPPED")
                        ?List.of("order.inspect","inventory.reserve","payment.charge","shipping.reserve","notify.send")
                        :expectedStatus.equals("HOLD")?List.of("order.inspect","inventory.reserve","payment.charge","inventory.release")
                        :List.of("order.inspect","inventory.reserve"));
            var loaded=events(trace).stream().filter(e->"SKILL_LOADED".equals(e.get("kind"))
                &&"inventory-check".equals(e.get("skill_id"))).findFirst().orElseThrow();
            assertThat(((Map<?,?>)loaded.get("data")).get("hash"))
                .isEqualTo(Json.hash(snapshot.files().get(path)));
            if(!expectedStatus.equals("SHIPPED")){
                assertThat(events(trace).stream().filter(e->"TOOL_REQUEST".equals(e.get("kind")))
                    .map(e->e.get("skill_id"))).doesNotContain("shipping-reserve","notify");
            }
        }
        mvc.perform(get("/api/snapshots/"+candidate.id()+"/files").param("path",path))
            .andExpect(status().isOk()).andExpect(jsonPath("content").value(edited));
        assertThat(snapshots.get(baseline.id()).files().get(path)).isEqualTo(original.getBytes(StandardCharsets.UTF_8));
        verify(decisions).decide(argThat(q->q.skill()!=null&&q.skill().id().equals("inventory-check")
            &&q.skill().hash().equals(Json.hash(candidate.files().get(path)))));
    }
    @Test void deletedInventoryOutputFailsAtPaymentBeforeBusinessToolsAndRestorationPasses() throws Exception {
        String path="skills/inventory-check/SKILL.md";
        var baseline=snapshots.capture();
        String original=new String(baseline.files().get(path),StandardCharsets.UTF_8);
        String edited=original.replace("outputs: [inventory.available, inventory.reserved]",
            "outputs: [inventory.available]");
        assertThat(edited).isNotEqualTo(original);
        var files=new TreeMap<>(baseline.files());
        files.put(path,edited.getBytes(StandardCharsets.UTF_8));
        var broken=snapshots.publish(files,Map.of("source","s2-output-deletion",
            "baseline_snapshot_id",baseline.id().toString(),"changed_paths",List.of(path)));
        baseline.files().forEach((p,bytes)->{
            if(!p.equals(path))assertThat(broken.files().get(p)).isEqualTo(bytes);
        });
        var control=orders.createOrder(UUID.randomUUID(),input(baseline.id())).order();
        assertThat(control.status().name()).isEqualTo("SHIPPED");
        jdbc.update("update products set stock_quantity=10 where sku=?",LANTERN);

        UUID requestId=UUID.randomUUID();
        assertThatThrownBy(()->orders.createOrder(requestId,input(broken.id())))
            .isInstanceOf(com.capstone4.orderagent.exception.BusinessException.class);
        UUID failedExecution=jdbc.queryForObject("select execution_id from orders where request_id=?",UUID.class,requestId);
        var trace=traces.get(failedExecution);
        assertThat(trace.get("snapshot_id")).isEqualTo(broken.id());
        assertThat(trace.get("completion")).isEqualTo("FAILED");
        assertThat(trace.get("error_category")).isEqualTo("CONTRACT_VIOLATION");
        var error=events(trace).stream().filter(e->"EXECUTION_ERROR".equals(e.get("kind")))
            .findFirst().orElseThrow();
        assertThat(error.get("skill_id")).isEqualTo("payment");
        var details=(Map<?,?>)error.get("data");
        assertThat(details.get("phase")).isEqualTo("static_validation");
        assertThat(details.get("missing_inputs")).isEqualTo(List.of("inventory.reserved"));
        assertThat(events(trace).stream().map(e->e.get("kind")))
            .doesNotContain("TOOL_REQUEST","TOOL_RESULT","SKILL_STARTED");
        assertThat(stock()).isEqualTo(10);
        assertThat(jdbc.queryForObject("select payment_status from orders where request_id=?",
            String.class,requestId)).isEqualTo("NOT_ATTEMPTED");
        assertThat(jdbc.queryForObject("select status from orders where request_id=?",
            String.class,requestId)).isEqualTo("FAILED");
        mvc.perform(get("/api/executions/"+failedExecution+"/trace"))
            .andExpect(status().isOk()).andExpect(jsonPath("error_category").value("CONTRACT_VIOLATION"));
        mvc.perform(get("/api/snapshots/"+broken.id()+"/files").param("path",path))
            .andExpect(status().isOk()).andExpect(jsonPath("content").value(edited));

        // 수동 복원으로 원인을 비교한다. SkillOps가 생성한 수정안은 아니다.
        var restoredFiles=new TreeMap<>(broken.files());
        restoredFiles.put(path,original.getBytes(StandardCharsets.UTF_8));
        var restored=snapshots.publish(restoredFiles,Map.of("source","s2-output-restoration",
            "broken_snapshot_id",broken.id().toString()));
        var repairedOrder=orders.createOrder(UUID.randomUUID(),input(restored.id())).order();
        assertThat(repairedOrder.status().name()).isEqualTo("SHIPPED");
        assertThat(stock()).isEqualTo(9);
        assertThat(snapshots.get(broken.id()).files().get(path)).isEqualTo(edited.getBytes(StandardCharsets.UTF_8));
        assertThat(snapshots.get(baseline.id()).files().get(path)).isEqualTo(original.getBytes(StandardCharsets.UTF_8));
    }

    @Test void shippingBeforePaymentFailsStaticValidationAndRestoredFlowPasses() throws Exception {
        String path="skills/order-flow/SKILL.md";
        var baseline=snapshots.capture();
        String original=new String(baseline.files().get(path),StandardCharsets.UTF_8);
        String edited=original.replace("steps: [order-intake, inventory-check, payment, shipping-reserve, notify]",
            "steps: [order-intake, inventory-check, shipping-reserve, payment, notify]");
        assertThat(edited).isNotEqualTo(original);
        var files=new TreeMap<>(baseline.files());
        files.put(path,edited.getBytes(StandardCharsets.UTF_8));
        var broken=snapshots.publish(files,Map.of("source","s3-shipping-before-payment",
            "baseline_snapshot_id",baseline.id().toString(),"changed_paths",List.of(path)));
        assertThat(broken.hash()).isNotEqualTo(baseline.hash());
        assertThat(broken.files().keySet()).isEqualTo(baseline.files().keySet());
        baseline.files().forEach((p,bytes)->{
            if(!p.equals(path))assertThat(broken.files().get(p)).isEqualTo(bytes);
        });
        var control=orders.createOrder(UUID.randomUUID(),input(baseline.id())).order();
        assertThat(control.status().name()).isEqualTo("SHIPPED");
        jdbc.update("update products set stock_quantity=10 where sku=?",LANTERN);

        UUID requestId=UUID.randomUUID();
        assertThatThrownBy(()->orders.createOrder(requestId,input(broken.id())))
            .isInstanceOf(com.capstone4.orderagent.exception.BusinessException.class);
        UUID failedExecution=jdbc.queryForObject("select execution_id from orders where request_id=?",UUID.class,requestId);
        var trace=traces.get(failedExecution);
        assertThat(trace.get("snapshot_id")).isEqualTo(broken.id());
        assertThat(trace.get("completion")).isEqualTo("FAILED");
        assertThat(trace.get("error_category")).isEqualTo("CONTRACT_VIOLATION");
        var error=events(trace).stream().filter(e->"EXECUTION_ERROR".equals(e.get("kind")))
            .findFirst().orElseThrow();
        assertThat(error.get("skill_id")).isEqualTo("shipping-reserve");
        var details=(Map<?,?>)error.get("data");
        assertThat(details.get("phase")).isEqualTo("static_validation");
        assertThat(details.get("missing_inputs")).isEqualTo(List.of("payment.id","payment.status"));
        assertThat(details.get("missing_dependencies")).isEqualTo(List.of("payment"));
        assertThat(events(trace).stream().map(e->e.get("kind")))
            .doesNotContain("TOOL_REQUEST","TOOL_RESULT","SKILL_STARTED","FLOW_VALIDATED");
        var loaded=events(trace).stream().filter(e->"SKILL_LOADED".equals(e.get("kind"))
            &&"order-flow".equals(e.get("skill_id"))).findFirst().orElseThrow();
        assertThat(((Map<?,?>)loaded.get("data")).get("hash")).isEqualTo(Json.hash(broken.files().get(path)));
        var failed=orders.getByRequestId(requestId);
        assertThat(failed.status().name()).isEqualTo("FAILED");
        assertThat(failed.payment().status().name()).isEqualTo("NOT_ATTEMPTED");
        assertThat(failed.shipment()).isNull();
        assertThat(failed.notification().status().name()).isEqualTo("NOT_ATTEMPTED");
        assertThat(stock()).isEqualTo(10);
        mvc.perform(get("/api/executions/"+failedExecution+"/trace"))
            .andExpect(status().isOk()).andExpect(jsonPath("error_category").value("CONTRACT_VIOLATION"));
        mvc.perform(get("/api/snapshots/"+broken.id()+"/files").param("path",path))
            .andExpect(status().isOk()).andExpect(jsonPath("content").value(edited));

        // 실제 예약은 결제 뒤로 수동 복원한다. 배송 가능 조회 도구 추가는 이번 범위가 아니다.
        var restoredFiles=new TreeMap<>(broken.files());
        restoredFiles.put(path,original.getBytes(StandardCharsets.UTF_8));
        var restored=snapshots.publish(restoredFiles,Map.of("source","s3-flow-restoration",
            "broken_snapshot_id",broken.id().toString()));
        var repaired=orders.createOrder(UUID.randomUUID(),input(restored.id())).order();
        assertThat(repaired.status().name()).isEqualTo("SHIPPED");
        assertThat(repaired.payment().status().name()).isEqualTo("PAID");
        assertThat(repaired.shipment()).isNotNull();
        assertThat(repaired.notification().status().name()).isEqualTo("SENT");
        assertThat(stock()).isEqualTo(9);
        assertThat(events(traces.get(repaired.executionId())).stream()
            .filter(e->"TOOL_REQUEST".equals(e.get("kind")))
            .map(e->((Map<?,?>)e.get("data")).get("tool")).toList())
            .isEqualTo(List.of("order.inspect","inventory.reserve","payment.charge","shipping.reserve","notify.send"));
        assertThat(snapshots.get(broken.id()).files().get(path)).isEqualTo(edited.getBytes(StandardCharsets.UTF_8));
        assertThat(snapshots.get(baseline.id()).files().get(path)).isEqualTo(original.getBytes(StandardCharsets.UTF_8));
    }

    @Test void brokenContractFailsBeforeBusinessToolsAndRepairedSnapshotPasses(){
        var broken=snapshots.demo("broken-contract");
        assertThatThrownBy(()->orders.createOrder(UUID.randomUUID(),input(broken.id()))).isInstanceOf(RuntimeException.class);
        var trace=traces.get(execution());
        assertThat(trace.get("error_category")).isEqualTo("CONTRACT_VIOLATION");
        var failure=events(trace).stream().filter(e->e.get("kind").equals("EXECUTION_ERROR")).findFirst().orElseThrow();
        assertThat(failure.get("skill_id")).isEqualTo("payment");
        assertThat(((Map<?,?>)failure.get("data")).get("skill_id")).isEqualTo("payment");
        assertThat(((Map<?,?>)failure.get("data")).get("missing_inputs")).isEqualTo(List.of("inventory.reserved"));
        assertThat(((Map<?,?>)failure.get("data")).get("phase")).isEqualTo("static_validation");
        assertThat(events(trace).stream().anyMatch(e->e.get("kind").equals("TOOL_REQUEST"))).isFalse();
        assertThat(stock()).isEqualTo(10);
        var repaired=snapshots.demo("repaired");
        var order=orders.createOrder(UUID.randomUUID(),input(repaired.id())).order();
        assertThat(order.status().name()).isEqualTo("SHIPPED");
        assertThat(snapshots.get(broken.id()).hash()).isEqualTo(broken.hash());
        assertThat(snapshots.get(repaired.id()).hash()).isNotEqualTo(broken.hash());
    }
    @Test void snapshotsPreserveMetadataRelatedBytesAndNeverOverwritePreviousCandidate(){
        var original=snapshots.capture();var files=new TreeMap<>(original.files());
        files.put("skills/inventory-check/resources/policy.md","changed resource".getBytes(StandardCharsets.UTF_8));
        var candidate=snapshots.publish(files,Map.of("source","test-candidate","proposal_id","p_fixture"));
        assertThat(candidate.metadata().get("proposal_id")).isEqualTo("p_fixture");
        assertThat(snapshots.get(original.id()).hash()).isEqualTo(original.hash());
        assertThat(candidate.hash()).isNotEqualTo(original.hash());
        files.put("skills/order-flow/SKILL.md",new byte[0]);
        assertThat(snapshots.get(candidate.id()).files().get("skills/order-flow/SKILL.md")).isNotEmpty();
    }
    @Test void tamperedSnapshotsAreRejectedAndNeverUsed(){
        var snapshot=snapshots.capture();
        jdbc.update("update snapshot_files set content=? where snapshot_id=? and path=?","tampered".getBytes(StandardCharsets.UTF_8),snapshot.id(),"skills/payment/SKILL.md");
        assertThatThrownBy(()->snapshots.get(snapshot.id())).isInstanceOf(AgentException.class).hasMessage("SNAPSHOT_TAMPERED");
        assertThatThrownBy(()->orders.createOrder(UUID.randomUUID(),input(snapshot.id()))).isInstanceOf(AgentException.class);
        assertThat(stock()).isEqualTo(10);
    }
    @Test void unknownSkillRetainsOriginalNameAndRefusesLoad(){
        doReturn(new DecisionProvider.Reply("{\"skill\":\"does-not-exist\"}",null,null)).when(decisions).decide(argThat(q->q.purpose().equals("select")));
        assertThatThrownBy(()->orders.createOrder(UUID.randomUUID(),input(null))).isInstanceOf(RuntimeException.class);
        var trace=traces.get(execution());
        assertThat(trace.get("raw_selection")).isEqualTo("does-not-exist");assertThat(trace.get("resolved_skill_id")).isNull();
        assertThat(trace.get("error_category")).isEqualTo("UNKNOWN_SKILL");assertThat(stock()).isEqualTo(10);
    }
    @Test void explicitNoSelectionIsNotAnUnknownSkill(){
        doReturn(new DecisionProvider.Reply("{\"skill\":\"none\"}",null,null)).when(decisions).decide(argThat(q->q.purpose().equals("select")));
        var result=orders.createOrder(UUID.randomUUID(),input(null)).order();
        assertThat(result.status().name()).isEqualTo("REJECTED");assertThat(result.resultCode()).isEqualTo("NO_SELECTION");
        assertThat(traces.get(result.executionId()).get("completion")).isEqualTo("COMPLETED");assertThat(stock()).isEqualTo(10);
    }
    @Test void invalidModelArgumentsNeverChargeAndCompensateCommittedReservation(){
        doReturn(new DecisionProvider.Reply("{\"tool\":\"payment.charge\",\"arguments\":{\"order_id\":\"wrong-order\",\"amount\":1,\"method\":\"card\"}}",null,null))
            .when(decisions).decide(argThat(q->q.skill()!=null&&q.skill().id().equals("payment")));
        assertThatThrownBy(()->orders.createOrder(UUID.randomUUID(),input(null))).isInstanceOf(RuntimeException.class);
        assertThat(traces.get(execution()).get("error_category")).isEqualTo("INVALID_TOOL_ARGUMENTS");assertThat(stock()).isEqualTo(10);
        var failure=events(traces.get(execution())).stream().filter(e->e.get("kind").equals("EXECUTION_ERROR")).findFirst().orElseThrow();
        assertThat(failure.get("skill_id")).isEqualTo("payment");
        var step=events(traces.get(execution())).stream().filter(e->e.get("kind").equals("SKILL_FINISHED")&&"payment".equals(e.get("skill_id"))).findFirst().orElseThrow();
        assertThat(((Map<?,?>)step.get("data")).get("status")).isEqualTo("error");
        assertThat(((Map<?,?>)step.get("data")).get("output")).isEqualTo(Map.of());
        assertThat(jdbc.queryForObject("select payment_status from orders",String.class)).isEqualTo("NOT_ATTEMPTED");
        assertThat(jdbc.queryForObject("select count(*) from order_processing_steps where step='RELEASE'",Integer.class)).isEqualTo(1);
    }
    @Test void incompleteFlowIsNeverMarkedShipped(){
        var baseline=snapshots.capture();var files=new TreeMap<>(baseline.files());
        String flow=new String(files.get("skills/order-flow/SKILL.md"),StandardCharsets.UTF_8).replace(", notify]","]");
        files.put("skills/order-flow/SKILL.md",flow.getBytes(StandardCharsets.UTF_8));
        var candidate=snapshots.publish(files,Map.of("source","missing-notify"));
        assertThatThrownBy(()->orders.createOrder(UUID.randomUUID(),input(candidate.id()))).isInstanceOf(RuntimeException.class);
        assertThat(traces.get(execution()).get("error_category")).isEqualTo("INCOMPLETE_FLOW");
        assertThat(jdbc.queryForObject("select status from orders",String.class)).isEqualTo("FAILED");
        assertThat(jdbc.queryForObject("select payment_status from orders",String.class)).isEqualTo("PAID");
    }
}
