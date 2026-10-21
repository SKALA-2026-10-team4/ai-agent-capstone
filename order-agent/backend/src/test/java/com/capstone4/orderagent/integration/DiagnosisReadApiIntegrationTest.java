package com.capstone4.orderagent.integration;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.sql.Timestamp;
import java.util.*;
import com.capstone4.orderagent.dto.order.CreateOrderRequest;
import com.capstone4.orderagent.service.OrderService;
import com.capstone4.orderagent.workspace.SnapshotService;
import com.capstone4.orderagent.trace.TraceService;
import com.capstone4.orderagent.harness.Json;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties={"spring.datasource.url=jdbc:postgresql://localhost:5434/order_agent_test",
    "spring.datasource.username=order_test","spring.datasource.password=order_test_local"})
@AutoConfigureMockMvc
@EnabledIfEnvironmentVariable(named="ORDER_DEMO_INTEGRATION_TESTS",matches="true")
@SuppressWarnings("unchecked")
class DiagnosisReadApiIntegrationTest {
    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired SnapshotService snapshots;
    @Autowired OrderService orders;
    @Autowired TraceService traces;

    @BeforeEach void reset() {
        jdbc.update("delete from orders");
        jdbc.update("update products set stock_quantity=10 where sku='CAMP-LANTERN-LED'");
    }

    CreateOrderRequest input(UUID snapshot, String customer, String sku) {
        return new CreateOrderRequest(customer,List.of(new CreateOrderRequest.Item(sku,1)),"card",
            new CreateOrderRequest.Shipping("KR",false),snapshot);
    }
    Map<String,Object> read(String path) throws Exception {
        return Json.object(mvc.perform(get(path)).andExpect(status().isOk()).andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8));
    }
    @SuppressWarnings("unchecked") List<Map<String,Object>> items(Map<String,Object> body,String key) {
        return (List<Map<String,Object>>)body.get(key);
    }
    @Test void emptyAndNormalBusinessRejectionsAreNotExecutionFailures() throws Exception {
        mvc.perform(get("/api/executions/failed")).andExpect(status().isOk()).andExpect(content().json("{\"items\":[]}"));
        orders.createOrder(UUID.randomUUID(),input(null,"c_1001","CAMP-MUG-STEEL"));
        orders.createOrder(UUID.randomUUID(),input(null,"c_declined","CAMP-LANTERN-LED"));
        assertThat(items(read("/api/executions/failed"),"items")).isEmpty();
    }
    @Test void allLiveFailuresAreReturnedInFailureTimeOrderIncludingMissingSnapshots() throws Exception {
        var order=orders.createOrder(UUID.randomUUID(),input(null,"c_1001","CAMP-LANTERN-LED")).order();
        UUID newest=null;
        for(int i=0;i<26;i++) {
            UUID id=UUID.randomUUID();newest=id;
            insertExecution(id,order.orderId(),"live",i==25?"INTERRUPTED":"FAILED",Instant.parse("2026-01-01T00:00:00Z").plusSeconds(i));
        }
        insertExecution(UUID.randomUUID(),order.orderId(),"dry_run","FAILED",Instant.parse("2027-01-01T00:00:00Z"));
        insertExecution(UUID.randomUUID(),order.orderId(),"dry-run","FAILED",Instant.parse("2027-01-01T00:00:01Z"));
        var result=items(read("/api/executions/failed"),"items");
        assertThat(result).hasSize(26);
        assertThat(result.getFirst().get("execution_id")).isEqualTo(newest.toString());
        assertThat(result.getFirst().get("completion")).isEqualTo("INTERRUPTED");
        assertThat(result.getFirst()).containsEntry("snapshot_id",null).containsEntry("snapshot_hash",null);
        assertThat((Map<String,Object>)result.getFirst().get("error")).containsKey("category");
        // Read APIs do not modify legacy traces or order/inventory state.
        assertThat(items(traces.get(newest),"events")).isEmpty();
        assertThat(jdbc.queryForObject("select stock_quantity from products where sku='CAMP-LANTERN-LED'",Integer.class)).isEqualTo(9);
    }
    void insertExecution(UUID id,UUID order,String mode,String completion,Instant at) {
        jdbc.update("""
            insert into agent_executions(id,order_id,agent_id,mode,model,completion,final_status,error_category,started_at,finished_at)
            values (?,?,'camping-order-agent',?,'fixed-policy-v1',?,'FAILED','EXECUTION_INTERRUPTED',?,?)
            """,id,order,mode,completion,Timestamp.from(at.minusSeconds(1)),Timestamp.from(at));
    }
    @Test void contractFailureKeepsExactEvidenceWithoutInventingExecutedSteps() throws Exception {
        var snapshot=snapshots.demo("broken-contract");
        assertThatThrownBy(()->orders.createOrder(UUID.randomUUID(),input(snapshot.id(),"c_1001","CAMP-LANTERN-LED"))).isInstanceOf(RuntimeException.class);
        var failures=items(read("/api/executions/failed"),"items");
        assertThat(failures).hasSize(1);
        var failure=failures.getFirst();
        assertThat(failure.get("snapshot_hash")).isEqualTo(snapshot.hash());
        assertThat((Map<String,Object>)failure.get("error"))
            .containsEntry("category","CONTRACT_VIOLATION").containsEntry("skill_id","payment")
            .containsEntry("phase","static_validation").containsEntry("missing_inputs",List.of("inventory.reserved"));
        var trace=read("/api/executions/"+failure.get("execution_id")+"/trace");
        assertThat(items(trace,"events")).noneMatch(e->Set.of("TOOL_REQUEST","SKILL_STARTED","SKILL_FINISHED").contains(e.get("kind")));
        assertThat(jdbc.queryForObject("select stock_quantity from products where sku='CAMP-LANTERN-LED'",Integer.class)).isEqualTo(10);
    }
    @Test void successfulExecutionRecordsActualStepInputsOutputsAndMeasuredDuration() throws Exception {
        var order=orders.createOrder(UUID.randomUUID(),input(null,"c_1001","CAMP-LANTERN-LED")).order();
        var trace=read("/api/executions/"+order.executionId()+"/trace");
        var events=items(trace,"events");
        var starts=events.stream().filter(e->e.get("kind").equals("SKILL_STARTED")).toList();
        var ends=events.stream().filter(e->e.get("kind").equals("SKILL_FINISHED")).toList();
        assertThat(starts).hasSize(5);assertThat(ends).hasSize(5);
        var payment=starts.stream().filter(e->e.get("skill_id").equals("payment")).findFirst().orElseThrow();
        var data=(Map<String,Object>)payment.get("data");
        assertThat((Map<String,Object>)data.get("input")).containsEntry("order.total",30000).containsEntry("inventory.reserved",true);
        for(var end:ends) {
            var values=(Map<String,Object>)end.get("data");
            assertThat(values.get("status")).isEqualTo("ok");
            assertThat(((Number)values.get("duration_ms")).longValue()).isGreaterThanOrEqualTo(0);
            assertThat((Map<?,?>)values.get("output")).isNotEmpty();
        }
        mvc.perform(get("/api/traces/"+trace.get("run_id"))).andExpect(status().isOk()).andExpect(jsonPath("execution_id").value(order.executionId().toString()));
    }
    @Test void skillMetadataAndOriginalBytesShareTheSnapshotAndManifestHashes() throws Exception {
        var baseline=snapshots.capture();
        var bytes=new TreeMap<>(baseline.files());
        String path="skills/payment/SKILL.md";
        bytes.put(path,new String(bytes.get(path),StandardCharsets.UTF_8).replace("\n","\r\n").getBytes(StandardCharsets.UTF_8));
        var snapshot=snapshots.publish(bytes,Map.of("source","read-api-test"));
        var list=read("/api/snapshots/"+snapshot.id()+"/skills");
        assertThat(list).containsEntry("snapshot_hash",snapshot.hash()).containsEntry("partial",false);
        assertThat(items(list,"items")).extracting(e->e.get("skill_id"))
            .containsExactly("inventory-check","notify","order-flow","order-intake","payment","shipping-reserve");
        var skill=read("/api/snapshots/"+snapshot.id()+"/skills/payment");
        var raw=read("/api/snapshots/"+snapshot.id()+"/files?path="+path);
        assertThat(skill).containsEntry("owner","주문운영팀").containsEntry("content",new String(bytes.get(path),StandardCharsets.UTF_8))
            .containsEntry("hash",raw.get("hash")).containsEntry("snapshot_hash",snapshot.hash());
        assertThat((List<String>)skill.get("inputs")).contains("inventory.reserved");
        var manifest=read("/api/snapshots/"+snapshot.id());
        assertThat(items(manifest,"files")).anyMatch(f->f.get("path").equals(path)&&f.get("hash").equals(skill.get("hash")));
        assertThat(read("/api/snapshots/"+snapshot.id()+"/files?path=skills/inventory-check/resources/policy.md").get("encoding")).isEqualTo("utf-8");
    }
    @Test void malformedSkillIsReportedAsPartialButOriginalFileRemainsReadable() throws Exception {
        var baseline=snapshots.capture();var files=new TreeMap<>(baseline.files());
        files.put("skills/payment/SKILL.md","---\nname: payment\nname: duplicate\n---\nbody".getBytes(StandardCharsets.UTF_8));
        var snapshot=snapshots.publish(files,Map.of("source","invalid-metadata"));
        var list=read("/api/snapshots/"+snapshot.id()+"/skills");
        assertThat(list.get("partial")).isEqualTo(true);assertThat(items(list,"items")).hasSize(5);
        assertThat(items(list,"parse_errors")).hasSize(1);
        mvc.perform(get("/api/snapshots/"+snapshot.id()+"/skills/payment")).andExpect(status().isBadRequest()).andExpect(jsonPath("code").value("SKILL_METADATA_INVALID"));
        mvc.perform(get("/api/snapshots/"+snapshot.id()+"/files").param("path","skills/payment/SKILL.md")).andExpect(status().isOk());
    }
    @Test void invalidUtf8AndMissingRequiredMetadataAreNotSilentlyNormalized() throws Exception {
        var baseline=snapshots.capture();var files=new TreeMap<>(baseline.files());
        files.put("skills/payment/SKILL.md",new byte[]{(byte)0xc3,(byte)0x28});
        String inventory=new String(files.get("skills/inventory-check/SKILL.md"),StandardCharsets.UTF_8).replace("owner: 주문운영팀\n","");
        files.put("skills/inventory-check/SKILL.md",inventory.getBytes(StandardCharsets.UTF_8));
        var snapshot=snapshots.publish(files,Map.of("source","invalid-encoding"));
        var list=read("/api/snapshots/"+snapshot.id()+"/skills");
        assertThat(list.get("partial")).isEqualTo(true);assertThat(items(list,"parse_errors")).hasSize(2);
        mvc.perform(get("/api/snapshots/"+snapshot.id()+"/files").param("path","skills/payment/SKILL.md"))
            .andExpect(status().isOk()).andExpect(jsonPath("encoding").value("base64"));
    }
    @Test void missingInvalidAndTamperedResourcesHaveExplicitHttpErrors() throws Exception {
        var snapshot=snapshots.capture();
        mvc.perform(get("/api/executions/"+UUID.randomUUID()+"/trace")).andExpect(status().isNotFound()).andExpect(jsonPath("code").value("EXECUTION_NOT_FOUND"));
        mvc.perform(get("/api/executions/not-a-uuid/trace")).andExpect(status().isBadRequest());
        mvc.perform(get("/api/snapshots/"+UUID.randomUUID()+"/skills")).andExpect(status().isNotFound()).andExpect(jsonPath("code").value("SNAPSHOT_NOT_FOUND"));
        mvc.perform(get("/api/snapshots/"+snapshot.id()+"/skills/unknown")).andExpect(status().isNotFound()).andExpect(jsonPath("code").value("SKILL_NOT_FOUND"));
        mvc.perform(get("/api/snapshots/"+snapshot.id()+"/skills/bad_name")).andExpect(status().isBadRequest()).andExpect(jsonPath("code").value("INVALID_SKILL_NAME"));
        mvc.perform(get("/api/snapshots/"+snapshot.id()+"/files").param("path","../payment/SKILL.md")).andExpect(status().isNotFound());
        mvc.perform(get("/api/snapshots/"+snapshot.id()+"/files")).andExpect(status().isBadRequest()).andExpect(jsonPath("code").value("VALIDATION_ERROR"));
        jdbc.update("update snapshot_files set content=? where snapshot_id=? and path=?",new byte[]{1},snapshot.id(),"skills/payment/SKILL.md");
        for(String suffix:List.of("","/skills","/skills/payment","/files?path=skills/payment/SKILL.md")) {
            mvc.perform(get("/api/snapshots/"+snapshot.id()+suffix)).andExpect(status().isBadRequest()).andExpect(jsonPath("code").value("SNAPSHOT_TAMPERED"));
        }
    }
}
