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
