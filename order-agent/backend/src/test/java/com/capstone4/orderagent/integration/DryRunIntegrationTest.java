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

/**
 * 모의 실행 API(order-agent/backend/src/main/java/com/capstone4/orderagent/controller/DryRunController.java 와
 * order-agent/backend/src/main/java/com/capstone4/orderagent/service/DryRunService.java 의 run)를 보호한다.
 * baseline snapshot 으로 SHIPPED 가 되지만 재고는 DRY_RUN_CLEANUP 단계로 되돌아오는지, 실행이 dry_run 으로 기록돼 실패 목록에 나오지 않는지,
 * 모의 실행 뒤의 live 주문은 여전히 live 이고 재고를 쓰는지, 없는 snapshot 은 주문을 만들기 전에 404 인지 본다.
 * service/src/main/java/com/capstone4/skillops/scenario/HarnessFlowRunner.java 가 기대는 동작이다.
 * ORDER_DEMO_INTEGRATION_TESTS=true 일 때만 돌고, 그때는 order-agent/compose.yml 의 db-test(호스트 포트 5434)가 떠 있어야 한다.
 */
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
    // 모의 실행과 live 주문이 함께 쓰는 주문 본문이다. 랜턴 2개라 live 로 처리되면 재고가 10 에서 8 이 된다.
    static final String ORDER="""
        {"customer":"c_1001","items":[{"sku":"CAMP-LANTERN-LED","qty":2}],"payment_method":"card",
         "shipping":{"country":"KR","express":false}}""";

    /** 매 테스트마다 주문을 지우고 랜턴 재고를 10 으로 되돌린다. */
    @BeforeEach void reset() {
        jdbc.update("delete from orders");
        jdbc.update("update products set stock_quantity=10 where sku='CAMP-LANTERN-LED'");
    }

    /** snapshot 과 ORDER 로 POST /api/dry-runs 를 부르고, 기대한 HTTP 상태인지 본 뒤 응답 JSON 을 읽는다. */
    Map<String,Object> dryRun(UUID snapshot,int status) throws Exception {
        String body="{\"snapshot_id\":\"%s\",\"order\":%s}".formatted(snapshot,ORDER);
        return Json.object(mvc.perform(post("/api/dry-runs").contentType(MediaType.APPLICATION_JSON).content(body))
            .andExpect(status().is(status)).andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8));
    }
    /** GET 응답이 200 인지 보고 본문 JSON 을 읽는다. */
    Map<String,Object> read(String path) throws Exception {
        return Json.object(mvc.perform(get(path)).andExpect(status().isOk()).andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8));
    }
    /** 응답 본문에서 key 의 목록을 꺼낸다. */
    List<Map<String,Object>> items(Map<String,Object> body,String key) { return (List<Map<String,Object>>)body.get(key); }
    /** GET /api/products 응답에서 랜턴의 지금 재고를 읽는다. 재고를 DB 가 아니라 공개 API 로 확인하려고 쓴다. */
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
        // 재고는 Skill 실행이 실제로 예약했다가 마지막 정리 단계(DRY_RUN_CLEANUP)가 되돌린 것이다.
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
