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
 * 후보 snapshot 발행 API(order-agent/backend/src/main/java/com/capstone4/orderagent/controller/EvidenceController.java 의 publishCandidate,
 * POST /api/snapshots/candidates)와 SnapshotService.publish 를 보호한다. 후보 파일 묶음이 바이트 그대로 저장되고 metadata 의 source 가
 * candidate 로 바뀌는지, 그 snapshot 으로 주문이 SHIPPED 되는지, skills 폴더 밖 경로를 INVALID_BUNDLE_PATH 로 거절하는지 본다.
 * service/src/main/java/com/capstone4/skillops/scenario/OrderAgentRunClient.java 의 publishCandidate 가 기대는 동작이다.
 * ORDER_DEMO_INTEGRATION_TESTS=true 일 때만 돌고, 그때는 order-agent/compose.yml 의 db-test(호스트 포트 5434)가 떠 있어야 한다.
 */
@SpringBootTest(properties={"spring.datasource.url=jdbc:postgresql://localhost:5434/order_agent_test",
    "spring.datasource.username=order_test","spring.datasource.password=order_test_local"})
@AutoConfigureMockMvc
@EnabledIfEnvironmentVariable(named="ORDER_DEMO_INTEGRATION_TESTS",matches="true")
@SuppressWarnings("unchecked")
class CandidateSnapshotIntegrationTest {
    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired SnapshotService snapshots;

    /** 매 테스트마다 주문을 지우고 랜턴 재고를 10 으로 되돌린다. */
    @BeforeEach void reset() {
        jdbc.update("delete from orders");
        jdbc.update("update products set stock_quantity=10 where sku='CAMP-LANTERN-LED'");
    }

    /** 게시 번들을 고정해 경로별 텍스트로 꺼낸다. 후보 요청 본문의 files 로 그대로 쓴다. */
    Map<String,String> baselineFiles() {
        var files=new TreeMap<String,String>();
        snapshots.capture().files().forEach((path,bytes)->files.put(path,new String(bytes,StandardCharsets.UTF_8)));
        return files;
    }
    /** 파일 묶음을 metadata(round 1)와 함께 후보로 발행하고, 기대한 HTTP 상태인지 본 뒤 응답 본문을 돌려준다. */
    String publish(Object files,int status) throws Exception {
        return mvc.perform(post("/api/snapshots/candidates").contentType(MediaType.APPLICATION_JSON)
            .content(Json.write(Map.of("files",files,"metadata",Map.of("round",1)))))
            .andExpect(status().is(status)).andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
    }

    @Test void publishesCandidateFiles() throws Exception {
        var files=baselineFiles();
        var manifest=Json.object(publish(files,200));
        var paths=((List<Map<String,Object>>)manifest.get("files")).stream().map(f->(String)f.get("path")).collect(java.util.stream.Collectors.toSet());
        assertThat(paths).isEqualTo(files.keySet());
        assertThat((Map<String,Object>)manifest.get("metadata")).containsEntry("source","candidate").containsEntry("round",1);
        assertThat(manifest).containsKeys("snapshot_id","hash","created_at");
        var stored=snapshots.get(UUID.fromString((String)manifest.get("snapshot_id")));
        assertThat(stored.hash()).isEqualTo(manifest.get("hash"));
        files.forEach((path,text)->assertThat(stored.files().get(path)).isEqualTo(text.getBytes(StandardCharsets.UTF_8)));
    }
    @Test void orderRunsWithCandidateSnapshot() throws Exception {
        var manifest=Json.object(publish(baselineFiles(),200));
        String body="""
            {"customer":"c_1001","items":[{"sku":"CAMP-LANTERN-LED","qty":1}],"payment_method":"card",
             "shipping":{"country":"KR","express":false},"snapshot_id":"%s"}
            """.formatted(manifest.get("snapshot_id"));
        mvc.perform(post("/api/orders").header("Idempotency-Key",UUID.randomUUID().toString())
            .contentType(MediaType.APPLICATION_JSON).content(body))
            .andExpect(status().isCreated()).andExpect(jsonPath("status").value("SHIPPED"));
    }
    @Test void rejectsPathOutsideSkills() throws Exception {
        var error=Json.object(publish(Map.of("../x","a"),400));
        assertThat(error).containsEntry("code","INVALID_BUNDLE_PATH");
        var outside=Json.object(publish(Map.of("other/SKILL.md","a"),400));
        assertThat(outside).containsEntry("code","INVALID_BUNDLE_PATH");
    }
}
