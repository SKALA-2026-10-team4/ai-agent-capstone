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
 * order-agent/backend/src/main/java/com/capstone4/orderagent/controller/EvidenceController.java 의 시연 fixture 고정, snapshot 과 파일 조회,
 * 후보 발행, 수정 저장, 전달 상태 API 의 응답 모양을 고정하는 특성 테스트다. 그 아래의 SnapshotService(demo, saveEdits)와
 * SkillChangeDispatcher.status 의 동작도 함께 보호한다. 응답 키는 frontend/src/api/orderAgent.js, frontend/src/api/diagnoses.js,
 * order-agent/frontend/src/api/evidence.js, service/src/main/java/com/capstone4/skillops/snapshot/ 의 SnapshotManifest 와 SnapshotFile,
 * service/src/main/java/com/capstone4/skillops/scenario/OrderAgentRunClient.java 의 publishCandidate 가 읽는다.
 * ORDER_DEMO_INTEGRATION_TESTS=true 일 때만 돌고, 그때는 order-agent/compose.yml 의 db-test(호스트 포트 5434)가 떠 있어야 한다.
 */
@SpringBootTest(properties={"spring.datasource.url=jdbc:postgresql://localhost:5434/order_agent_test",
    "spring.datasource.username=order_test","spring.datasource.password=order_test_local"})
@AutoConfigureMockMvc
@EnabledIfEnvironmentVariable(named="ORDER_DEMO_INTEGRATION_TESTS",matches="true")
@SuppressWarnings("unchecked")
class EvidenceApiContractIntegrationTest {
    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired SnapshotService snapshots;
    // 시연 fixture 가 바꾸는 유일한 파일이자 수정 저장 시험에 쓰는 snapshot 안 경로다.
    static final String INVENTORY="skills/inventory-check/SKILL.md";

    /** JSON 을 보내고 기대한 HTTP 상태인지 본 뒤 응답 본문을 키 순서대로 읽는다. */
    Map<String,Object> send(String path,Object body,int status) throws Exception {
        return Json.object(mvc.perform(post(path).contentType(MediaType.APPLICATION_JSON).content(Json.write(body)))
            .andExpect(status().is(status)).andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8));
    }
    /** GET 응답이 200 인지 보고 본문 JSON 을 키 순서대로 읽는다. */
    Map<String,Object> read(String path) throws Exception {
        return Json.object(mvc.perform(get(path)).andExpect(status().isOk()).andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8));
    }
    /** snapshot 안 파일 하나를 UTF-8 텍스트로 읽는다. */
    String text(UUID snapshot,String path){return new String(snapshots.get(snapshot).files().get(path),StandardCharsets.UTF_8);}

    /** fixture 이름이 없거나 모르면 UNKNOWN_FIXTURE 이고, 세 fixture 는 inventory-check 만 정해진 방식으로 바꾼다. */
    @Test void demoFixturesRewriteOnlyInventoryCheck() throws Exception {
        assertThat(send("/api/snapshots",Map.of(),400)).containsEntry("code","UNKNOWN_FIXTURE");
        assertThat(send("/api/snapshots",Map.of("fixture","nope"),400)).containsEntry("code","UNKNOWN_FIXTURE");
        var bundle=snapshots.capture();
        String original=new String(bundle.files().get(INVENTORY),StandardCharsets.UTF_8);
        var expected=Map.of("baseline",original,
            "broken-contract",original.replace("inventory.available","inventory.reservable_quantity").replace(", inventory.reserved",""),
            "repaired",original.replace("inventory.available","inventory.reservable_quantity")+"\n예약 가능 수량이 주문 수량 이상이면 inventory.reserved를 true로 기록한다.\n");
        for(var fixture:List.of("baseline","broken-contract","repaired")){
            var manifest=send("/api/snapshots",Map.of("fixture",fixture),200);
            assertThat(manifest.keySet()).containsExactlyInAnyOrder("snapshot_id","hash","created_at","metadata","files");
            assertThat(manifest.get("metadata")).isEqualTo(Map.of("source","demo-fixture","fixture",fixture));
            UUID id=UUID.fromString((String)manifest.get("snapshot_id"));
            assertThat(text(id,INVENTORY)).as(fixture).isEqualTo(expected.get(fixture));
            bundle.files().forEach((path,bytes)->{if(!path.equals(INVENTORY))assertThat(snapshots.get(id).files().get(path)).isEqualTo(bytes);});
            assertThat(snapshots.get(id).files().keySet()).isEqualTo(bundle.files().keySet());
        }
    }
    /** manifest 는 경로 순 파일 목록(path, hash, size)을, 파일 조회는 해시·크기·인코딩·본문을 돌려준다. */
    @Test void manifestAndFileResponses() throws Exception {
        var snapshot=snapshots.capture();
        var manifest=read("/api/snapshots/"+snapshot.id());
        assertThat(manifest.keySet()).containsExactlyInAnyOrder("snapshot_id","hash","created_at","metadata","files");
        assertThat(manifest).containsEntry("snapshot_id",snapshot.id().toString()).containsEntry("hash",snapshot.hash())
            .containsEntry("created_at",snapshot.createdAt().toString());
        assertThat(manifest.get("metadata")).isEqualTo(Map.of("source","published-bundle","release","camping-v1"));
        var files=(List<Map<String,Object>>)manifest.get("files");
        assertThat(files).extracting(f->f.get("path")).containsExactlyElementsOf(snapshot.files().keySet());
        for(var file:files){
            assertThat(file.keySet()).containsExactlyInAnyOrder("path","hash","size");
            byte[] bytes=snapshot.files().get((String)file.get("path"));
            assertThat(file).containsEntry("hash",Json.hash(bytes)).containsEntry("size",bytes.length);
        }
        var file=read("/api/snapshots/"+snapshot.id()+"/files?path="+INVENTORY);
        assertThat(file.keySet()).containsExactlyInAnyOrder("snapshot_id","path","hash","size","encoding","content");
        byte[] bytes=snapshot.files().get(INVENTORY);
        assertThat(file).containsEntry("snapshot_id",snapshot.id().toString()).containsEntry("path",INVENTORY)
            .containsEntry("hash",Json.hash(bytes)).containsEntry("size",bytes.length).containsEntry("encoding","utf-8")
            .containsEntry("content",new String(bytes,StandardCharsets.UTF_8));
        mvc.perform(get("/api/snapshots/"+snapshot.id()+"/files").param("path","skills/none/SKILL.md"))
            .andExpect(status().isNotFound()).andExpect(jsonPath("code").value("SNAPSHOT_FILE_NOT_FOUND"))
            .andExpect(jsonPath("message").value("실행 자료를 확인할 수 없습니다: SNAPSHOT_FILE_NOT_FOUND"));
    }
    /** 후보 발행은 요청 metadata 를 보존하되 source 를 candidate 로 덮어쓴다. */
    @Test void candidateMetadataKeepsRequestValuesButForcesSource() throws Exception {
        var manifest=send("/api/snapshots/candidates",Map.of("files",Map.of("skills/a/SKILL.md","x"),
            "metadata",Map.of("source","manual","round",2,"note","n")),200);
        assertThat(manifest.get("metadata")).isEqualTo(Map.of("source","candidate","round",2,"note","n"));
        var withoutMetadata=send("/api/snapshots/candidates",Map.of("files",Map.of("skills/a/SKILL.md","x")),200);
        assertThat(withoutMetadata.get("metadata")).isEqualTo(Map.of("source","candidate"));
        assertThat(send("/api/snapshots/candidates",Map.of(),400)).containsEntry("code","INVALID_BUNDLE_SIZE");
    }
    /** 수정 저장 응답은 manifest 뒤에 네 키를 순서대로 붙이고, outbox 와 전달 상태를 같은 변경 id 로 남긴다. */
    @Test void savedEditResponseOutboxAndDeliveryStatus() throws Exception {
        var baseline=snapshots.capture();
        String original=new String(baseline.files().get(INVENTORY),StandardCharsets.UTF_8);
        var response=send("/api/snapshots/edits",Map.of("baseline_snapshot_id",baseline.id(),"baseline_hash",baseline.hash(),
            "changes",Map.of(INVENTORY,original+"\n추가 문장\n")),201);
        var keys=new ArrayList<>(response.keySet());
        assertThat(keys).hasSize(9);
        assertThat(keys.subList(0,5)).containsExactlyInAnyOrder("snapshot_id","hash","created_at","metadata","files");
        assertThat(keys.subList(5,9)).containsExactly("baseline_snapshot_id","change_id","changed_paths","validation_status");
        String change=(String)response.get("change_id");
        assertThat(response).containsEntry("baseline_snapshot_id",baseline.id().toString()).containsEntry("changed_paths",List.of(INVENTORY))
            .containsEntry("validation_status","QUEUED");
        assertThat(response.get("metadata")).isEqualTo(Map.of("source","user-edit","change_id",change,"baseline_snapshot_id",baseline.id().toString(),
            "baseline_hash",baseline.hash(),"changed_paths",List.of(INVENTORY),"validation_status","QUEUED"));
        var row=jdbc.queryForMap("select snapshot_id,payload,thread_id,attempts,last_error from skill_change_events where change_id=?",UUID.fromString(change));
        assertThat(row.get("snapshot_id").toString()).isEqualTo(response.get("snapshot_id"));
        assertThat(Json.object((String)row.get("payload"))).isEqualTo(Map.of("type","skill_saved","change_id",change,
            "baseline_snapshot_id",baseline.id().toString(),"candidate_snapshot_id",response.get("snapshot_id"),"changed_paths",List.of(INVENTORY)));
        assertThat(row.get("thread_id")).isNull();assertThat(row.get("attempts")).isEqualTo(0);assertThat(row.get("last_error")).isNull();
        var pending=read("/api/skill-changes/"+change);
        assertThat(pending.keySet()).containsExactly("change_id","snapshot_id","thread_id","attempts","last_error","delivery_status");
        assertThat(pending).containsEntry("change_id",change).containsEntry("snapshot_id",response.get("snapshot_id"))
            .containsEntry("attempts",0).containsEntry("delivery_status","PENDING");
        UUID thread=UUID.randomUUID();
        jdbc.update("update skill_change_events set thread_id=?,attempts=2,last_error='x' where change_id=?",thread,UUID.fromString(change));
        assertThat(read("/api/skill-changes/"+change)).containsEntry("thread_id",thread.toString()).containsEntry("attempts",2)
            .containsEntry("last_error","x").containsEntry("delivery_status","ACCEPTED");
        mvc.perform(get("/api/skill-changes/"+UUID.randomUUID())).andExpect(status().isNotFound())
            .andExpect(jsonPath("code").value("SKILL_CHANGE_NOT_FOUND"));
    }
}
