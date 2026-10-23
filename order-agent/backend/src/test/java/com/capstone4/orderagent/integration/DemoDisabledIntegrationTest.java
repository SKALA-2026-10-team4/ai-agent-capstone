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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * order-agent/backend/src/main/java/com/capstone4/orderagent/controller/DemoGuard.java 의 경계를 보호하는 특성 테스트다.
 * order-agent.demo-enabled=false 이면 시연 전용 쓰기 API(fixture 고정, 후보 발행, 모의 실행)만 SNAPSHOT_NOT_FOUND 404 로 감추고,
 * 아무 주문도 만들지 않으며, 조회와 주문과 수정 저장은 그대로 동작하는 현재 동작을 고정한다.
 * ORDER_DEMO_INTEGRATION_TESTS=true 일 때만 돌고, 그때는 order-agent/compose.yml 의 db-test(호스트 포트 5434)가 떠 있어야 한다.
 */
@SpringBootTest(properties={"order-agent.demo-enabled=false","spring.datasource.url=jdbc:postgresql://localhost:5434/order_agent_test",
    "spring.datasource.username=order_test","spring.datasource.password=order_test_local"})
@AutoConfigureMockMvc
@EnabledIfEnvironmentVariable(named="ORDER_DEMO_INTEGRATION_TESTS",matches="true")
class DemoDisabledIntegrationTest {
    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired SnapshotService snapshots;
    // 모의 실행 본문의 order 와 주문 요청에 함께 쓰는 랜턴 1개 주문 본문이다.
    static final String ORDER="""
        {"customer":"c_1001","items":[{"sku":"CAMP-LANTERN-LED","qty":1}],"payment_method":"card","shipping":{"country":"KR","express":false}}""";

    /** 매 테스트마다 주문을 지우고 상품 재고를 시드 값으로 되돌린다. */
    @BeforeEach void reset(){
        jdbc.update("delete from orders");
        jdbc.update("update products set stock_quantity = case sku when 'CAMP-LANTERN-LED' then 10 when 'CAMP-CHAIR-FOLD' then 5 else 0 end");
    }

    /** fixture·후보 발행·모의 실행은 요청 내용과 무관하게 404 SNAPSHOT_NOT_FOUND 다. */
    @Test void demoOnlyWritesAreHidden() throws Exception {
        for(var request:List.of(
                post("/api/snapshots").content("{\"fixture\":\"baseline\"}"),
                post("/api/snapshots").content("{}"),
                post("/api/snapshots/candidates").content("{\"files\":{\"skills/a/SKILL.md\":\"x\"}}"),
                post("/api/snapshots/candidates").content("{}"),
                post("/api/dry-runs").content("{\"snapshot_id\":\""+UUID.randomUUID()+"\",\"order\":"+ORDER+"}"))){
            mvc.perform(request.contentType(MediaType.APPLICATION_JSON)).andExpect(status().isNotFound())
                .andExpect(jsonPath("code").value("SNAPSHOT_NOT_FOUND"))
                .andExpect(jsonPath("message").value("실행 자료를 확인할 수 없습니다: SNAPSHOT_NOT_FOUND"));
        }
        org.assertj.core.api.Assertions.assertThat(jdbc.queryForObject("select count(*) from orders",Integer.class)).isZero();
    }
    /** 모의 실행 요청 형식 검사는 demo 가드보다 먼저 400 으로 끝난다. */
    @Test void dryRunValidationRunsBeforeTheGuard() throws Exception {
        mvc.perform(post("/api/dry-runs").contentType(MediaType.APPLICATION_JSON).content("{\"order\":"+ORDER+"}"))
            .andExpect(status().isBadRequest()).andExpect(jsonPath("code").value("VALIDATION_ERROR"));
    }
    /** 조회, 주문, 수정 저장은 demo 설정과 무관하게 동작한다. */
    @Test void readsOrdersAndEditsStayAvailable() throws Exception {
        mvc.perform(get("/api/executions")).andExpect(status().isOk());
        mvc.perform(post("/api/orders").header("Idempotency-Key",UUID.randomUUID().toString()).contentType(MediaType.APPLICATION_JSON).content(ORDER))
            .andExpect(status().isCreated()).andExpect(jsonPath("status").value("SHIPPED"));
        var baseline=snapshots.capture();
        String path="skills/payment/SKILL.md";
        String edited=new String(baseline.files().get(path),StandardCharsets.UTF_8)+"\n추가\n";
        mvc.perform(post("/api/snapshots/edits").contentType(MediaType.APPLICATION_JSON)
            .content(Json.write(Map.of("baseline_snapshot_id",baseline.id(),"baseline_hash",baseline.hash(),"changes",Map.of(path,edited)))))
            .andExpect(status().isCreated()).andExpect(jsonPath("validation_status").value("QUEUED"));
    }
}
