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
class CandidateSnapshotIntegrationTest {
    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired SnapshotService snapshots;

    @BeforeEach void reset() {
        jdbc.update("delete from orders");
        jdbc.update("update products set stock_quantity=10 where sku='CAMP-LANTERN-LED'");
    }

    Map<String,String> baselineFiles() {
        var files=new TreeMap<String,String>();
        snapshots.capture().files().forEach((path,bytes)->files.put(path,new String(bytes,StandardCharsets.UTF_8)));
        return files;
    }
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
