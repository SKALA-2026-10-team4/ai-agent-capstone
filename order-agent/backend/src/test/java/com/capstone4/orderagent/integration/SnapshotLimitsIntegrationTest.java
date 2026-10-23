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

/**
 * snapshot 후보 발행(POST /api/snapshots/candidates)과 수정 저장(POST /api/snapshots/edits)의 크기, 개수, 경로 한도와 검사 순서를 고정하는 특성 테스트다.
 * order-agent/backend/src/main/java/com/capstone4/orderagent/workspace/SnapshotService.java 의 MAX_FILES, MAX_FILE_BYTES, MAX_BUNDLE_BYTES,
 * MAX_EDIT_CHANGES, MAX_PATH_LENGTH, EDITABLE_SKILL_PATH 와 publish, saveEdits, validatePath 를 보호하므로 그 상수를 바꾸면 기대값도 함께 고친다.
 * ORDER_DEMO_INTEGRATION_TESTS=true 일 때만 돌고, 그때는 order-agent/compose.yml 의 db-test(호스트 포트 5434)가 떠 있어야 한다.
 */
@SpringBootTest(properties={"spring.datasource.url=jdbc:postgresql://localhost:5434/order_agent_test",
    "spring.datasource.username=order_test","spring.datasource.password=order_test_local"})
@AutoConfigureMockMvc
@EnabledIfEnvironmentVariable(named="ORDER_DEMO_INTEGRATION_TESTS",matches="true")
class SnapshotLimitsIntegrationTest {
    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired SnapshotService snapshots;

    /** 후보 파일 묶음을 발행하고 '상태 오류코드' 를 돌려준다. */
    String publish(Map<String,String> files) throws Exception {
        return outcome(post("/api/snapshots/candidates"),Json.write(Map.of("files",files)));
    }
    /** 수정 요청 본문을 저장하고 '상태 오류코드' 를 돌려준다. */
    String saveEdits(String body) throws Exception {return outcome(post("/api/snapshots/edits"),body);}
    /** 기준 snapshot id, 기준 해시, 변경 목록으로 수정 저장 요청 본문 JSON 을 만든다. */
    String edits(UUID baseline,String hash,Map<String,String> changes){
        return Json.write(Map.of("baseline_snapshot_id",baseline,"baseline_hash",hash,"changes",changes));
    }
    /** 요청을 보내고 HTTP 상태와 오류 code 를 한 줄('201 -' 처럼 오류가 없으면 '-')로 돌려준다. 성공과 실패를 같은 모양으로 비교하려고 쓴다. */
    private String outcome(org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder request,String body) throws Exception {
        var response=mvc.perform(request.contentType(MediaType.APPLICATION_JSON).content(body)).andReturn().getResponse();
        var json=Json.object(response.getContentAsString(StandardCharsets.UTF_8));
        return response.getStatus()+" "+json.getOrDefault("code","-");
    }
    /** count 개의 Skill 폴더마다 bytes 바이트짜리 SKILL.md 를 하나씩 담은 파일 묶음을 만든다. 폴더 이름은 s000 부터 번호순이다. */
    static Map<String,String> sized(int count,int bytes){
        var files=new TreeMap<String,String>();
        for(int i=0;i<count;i++)files.put("skills/s%03d/SKILL.md".formatted(i),"a".repeat(bytes));
        return files;
    }

    /** 파일은 1~256개, 파일당 1,000,000바이트, 합계 5,000,000바이트까지 발행된다. */
    @Test void candidateBundleSizeLimits() throws Exception {
        assertThat(publish(sized(1,1_000_000))).isEqualTo("200 -");
        assertThat(publish(sized(1,1_000_001))).isEqualTo("400 INVALID_BUNDLE_SIZE");
        assertThat(publish(sized(256,1))).isEqualTo("200 -");
        assertThat(publish(sized(257,1))).isEqualTo("400 INVALID_BUNDLE_SIZE");
        assertThat(publish(Map.of())).isEqualTo("400 INVALID_BUNDLE_SIZE");
        assertThat(publish(sized(5,1_000_000))).isEqualTo("200 -");
        var over=sized(5,1_000_000);over.put("skills/z/SKILL.md","a");
        assertThat(publish(over)).isEqualTo("400 INVALID_BUNDLE_SIZE");
    }
    /** 경로는 skills/ 로 시작하고 500자 이하이며 빈·점 세그먼트, 역슬래시, NUL 이 없어야 한다. */
    @Test void candidatePathRules() throws Exception {
        assertThat(publish(Map.of("skills/"+"p".repeat(493),"a"))).isEqualTo("200 -");
        assertThat(publish(Map.of("skills/"+"p".repeat(494),"a"))).isEqualTo("400 INVALID_BUNDLE_PATH");
        for(String bad:List.of("skill/a.md","skills/./a.md","skills/../a.md","skills//a.md","skills/a/","skills\\a.md","skills/a\\b.md","skills/a\u0000.md","/skills/a.md"))
            assertThat(publish(Map.of(bad,"a"))).as(bad).isEqualTo("400 INVALID_BUNDLE_PATH");
    }
    /** 파일 순서대로 경로를 먼저, 그다음 크기를 검사하고 합계는 마지막에 본다. */
    @Test void candidateChecksRunPerFileInPathOrder() throws Exception {
        var sizeFirst=new TreeMap<String,String>();
        sizeFirst.put("skills/a/SKILL.md","a".repeat(1_000_001));sizeFirst.put("skills/b/../c.md","a");
        assertThat(publish(sizeFirst)).isEqualTo("400 INVALID_BUNDLE_SIZE");
        var pathFirst=new TreeMap<String,String>();
        pathFirst.put("skills/a/SKILL.md","a");pathFirst.put("skills/b\\c.md","a".repeat(1_000_001));
        assertThat(publish(pathFirst)).isEqualTo("400 INVALID_BUNDLE_PATH");
        var totalLast=sized(5,1_000_000);totalLast.put("skills/zz/../x.md","a");
        assertThat(publish(totalLast)).isEqualTo("400 INVALID_BUNDLE_PATH");
    }
    /** 수정 저장은 1~64개 변경, skills 폴더 바로 아래 소문자나 숫자로 시작하는 이름 폴더의 SKILL.md 경로, 파일당 1,000,000바이트까지 받는다. */
    @Test void editLimits() throws Exception {
        var baseline=snapshots.capture();
        assertThat(saveEdits(edits(baseline.id(),baseline.hash(),sized(64,1)))).isEqualTo("201 -");
        assertThat(saveEdits(edits(baseline.id(),baseline.hash(),sized(65,1)))).isEqualTo("400 INVALID_SKILL_EDITS");
        assertThat(saveEdits(edits(UUID.randomUUID(),baseline.hash(),sized(65,1)))).isEqualTo("400 INVALID_SKILL_EDITS");
        assertThat(saveEdits(edits(UUID.randomUUID(),baseline.hash(),sized(1,1)))).isEqualTo("404 SNAPSHOT_NOT_FOUND");
        assertThat(saveEdits(edits(baseline.id(),baseline.hash(),Map.of("skills/9lives/SKILL.md","x")))).isEqualTo("201 -");
        for(String bad:List.of("skills/Payment/SKILL.md","skills/payment/resources/x.md","skills/-x/SKILL.md","skills/payment/skill.md","other/payment/SKILL.md"))
            assertThat(saveEdits(edits(baseline.id(),baseline.hash(),Map.of(bad,"x")))).as(bad).isEqualTo("400 INVALID_SKILL_EDITS");
        assertThat(saveEdits(edits(baseline.id(),baseline.hash(),Map.of("skills/payment/SKILL.md","a".repeat(1_000_000))))).isEqualTo("201 -");
        assertThat(saveEdits(edits(baseline.id(),baseline.hash(),Map.of("skills/payment/SKILL.md","a".repeat(1_000_001))))).isEqualTo("400 INVALID_BUNDLE_SIZE");
        assertThat(saveEdits("""
            {"baseline_snapshot_id":"%s","baseline_hash":"%s","changes":{"skills/payment/SKILL.md":null}}
            """.formatted(baseline.id(),baseline.hash()))).isEqualTo("400 INVALID_SKILL_EDITS");
        assertThat(saveEdits("""
            {"baseline_hash":"%s","changes":{"skills/payment/SKILL.md":"x"}}
            """.formatted(baseline.hash()))).isEqualTo("400 INVALID_SKILL_EDITS");
        assertThat(saveEdits("""
            {"baseline_snapshot_id":"%s","baseline_hash":"%s","changes":{"skills/Bad/SKILL.md":"%s"}}
            """.formatted(baseline.id(),baseline.hash(),"a".repeat(1_000_001)))).isEqualTo("400 INVALID_SKILL_EDITS");
    }
    /** 변경 목록에는 실제로 바뀐 경로만 정렬해 남고, 같은 내용의 경로는 빠진다. */
    @Test void onlyActuallyChangedPathsAreRecorded() throws Exception {
        var baseline=snapshots.capture();
        String payment=new String(baseline.files().get("skills/payment/SKILL.md"),StandardCharsets.UTF_8);
        var changes=new LinkedHashMap<String,String>();
        changes.put("skills/payment/SKILL.md",payment);changes.put("skills/zeta/SKILL.md","새 파일");changes.put("skills/alpha/SKILL.md","새 파일");
        var response=Json.object(mvc.perform(post("/api/snapshots/edits").contentType(MediaType.APPLICATION_JSON)
            .content(edits(baseline.id(),baseline.hash(),changes))).andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8));
        assertThat(response.get("changed_paths")).isEqualTo(List.of("skills/alpha/SKILL.md","skills/zeta/SKILL.md"));
        var saved=snapshots.get(UUID.fromString((String)response.get("snapshot_id")));
        assertThat(saved.files()).hasSize(baseline.files().size()+2);
        assertThat(saved.files().get("skills/payment/SKILL.md")).isEqualTo(baseline.files().get("skills/payment/SKILL.md"));
    }
}
