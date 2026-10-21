package com.capstone4.orderagent.integration;

import java.nio.charset.StandardCharsets;
import java.util.*;
import com.capstone4.orderagent.harness.Json;
import com.capstone4.orderagent.workspace.SnapshotService;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
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
class DryRunIntegrationTest {
    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired SnapshotService snapshots;
    static final String LANTERN="CAMP-LANTERN-LED";
    static final String ORDER="""
        {"customer":"c_1001","items":[{"sku":"CAMP-LANTERN-LED","qty":2}],"payment_method":"card",
         "shipping":{"country":"KR","express":false}}""";

    @BeforeEach void reset() {
        jdbc.update("delete from orders");
        jdbc.update("update products set stock_quantity=10 where sku='CAMP-LANTERN-LED'");
    }

    Map<String,Object> dryRun(UUID snapshot,int status) throws Exception {
        String body="{\"snapshot_id\":\"%s\",\"order\":%s}".formatted(snapshot,ORDER);
        return Json.object(mvc.perform(post("/api/dry-runs").contentType(MediaType.APPLICATION_JSON).content(body))
            .andExpect(status().is(status)).andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8));
    }
    Map<String,Object> read(String path) throws Exception {
        return Json.object(mvc.perform(get(path)).andExpect(status().isOk()).andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8));
    }
    List<Map<String,Object>> items(Map<String,Object> body,String key) { return (List<Map<String,Object>>)body.get(key); }
    int lanternStock() throws Exception {
        var lantern=items(read("/api/products"),"items").stream().filter(p->p.get("sku").equals(LANTERN)).findFirst().orElseThrow();
        return ((Number)lantern.get("stock_quantity")).intValue();
    }

    @Test void dryRunShipsWithBaseline() throws Exception {
        var result=dryRun(snapshots.demo("baseline").id(),200);
        assertThat(result).containsEntry("final_status","SHIPPED").containsEntry("completion","COMPLETED")
            .containsEntry("run_id","r_"+((String)result.get("execution_id")).replace("-",""));
        var trace=read("/api/executions/"+result.get("execution_id")+"/trace");
        assertThat(trace).containsEntry("mode","dry_run").containsEntry("order_id",result.get("order_id"));
    }
    @Test void dryRunLeavesStockUnchanged() throws Exception {
        int before=lanternStock();
        var result=dryRun(snapshots.demo("baseline").id(),200);
        assertThat(result).containsEntry("final_status","SHIPPED");
        assertThat(lanternStock()).isEqualTo(before);
        // Stock was really reserved by the skill run and then returned by the cleanup step.
        var steps=items(read("/api/orders/"+result.get("order_id")),"steps");
        assertThat(steps).anyMatch(s->s.get("step").equals("INVENTORY")&&s.get("status").equals("SUCCESS"));
        assertThat(steps.getLast()).containsEntry("step","RELEASE").containsEntry("status","SUCCESS").containsEntry("result_code","DRY_RUN_CLEANUP");
    }
    @Test void dryRunFailureIsNotListedAsFailed() throws Exception {
        var result=dryRun(snapshots.demo("broken-contract").id(),200);
        assertThat(result).containsEntry("final_status","FAILED").containsEntry("completion","FAILED");
        assertThat(read("/api/executions/"+result.get("execution_id")+"/trace")).containsEntry("mode","dry_run");
        assertThat(items(read("/api/executions/failed"),"items")).noneMatch(f->f.get("execution_id").equals(result.get("execution_id")));
        assertThat(lanternStock()).isEqualTo(10);
    }
    @Test void liveOrderStillLive() throws Exception {
        dryRun(snapshots.demo("baseline").id(),200);
        var order=Json.object(mvc.perform(post("/api/orders").header("Idempotency-Key",UUID.randomUUID().toString())
            .contentType(MediaType.APPLICATION_JSON).content(ORDER))
            .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8));
        assertThat(order).containsEntry("status","SHIPPED");
        assertThat(read("/api/executions/"+order.get("execution_id")+"/trace")).containsEntry("mode","live");
        assertThat(lanternStock()).isEqualTo(8);
    }
    @Test void unknownSnapshotFailsBeforeAnyOrderIsCreated() throws Exception {
        assertThat(dryRun(UUID.randomUUID(),404)).containsEntry("code","SNAPSHOT_NOT_FOUND");
        assertThat(jdbc.queryForObject("select count(*) from orders",Integer.class)).isZero();
    }
}
