package com.capstone4.orderagent.controller;

import java.util.*;
import com.capstone4.orderagent.harness.Json;
import com.capstone4.orderagent.harness.ToolCatalog;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * order-agent/backend/src/main/java/com/capstone4/orderagent/controller/ToolController.java 의 GET /api/tools 응답 모양을 DB 없이 고정한다.
 * SkillOps Agent 가 개발 요청서 탐지(docs/plans/2026-10-26-behavior-edit-root-fix.md 2.11 절)에 이 응답을 그대로 읽으므로
 * 키 이름, 도구별 produces, 처음 입력 키가 바뀌면 이 테스트와 order-agent/docs/diagnosis-read-api.md 10절을 함께 고친다.
 * produces 가 실제 출력 키와 같은지는 order-agent/backend/src/test/java/com/capstone4/orderagent/service/OrderToolServiceProducesTest.java 가 본다.
 */
@SuppressWarnings("unchecked")
class ToolControllerTest {
    final MockMvc mvc=MockMvcBuilders.standaloneSetup(new ToolController()).build();

    /** 응답 JSON 을 Map 으로 읽는다. */
    Map<String,Object> response() throws Exception {
        String body=mvc.perform(get("/api/tools")).andExpect(status().isOk())
            .andReturn().getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8);
        return Json.object(body);
    }

    /** 최상위 키는 initial_input_keys, tools 두 개이고 처음 입력 키는 flow 정적 검증이 쓰는 다섯 키다. */
    @Test void topLevelShapeAndInitialInputKeys() throws Exception {
        mvc.perform(get("/api/tools")).andExpect(status().isOk()).andExpect(content().contentTypeCompatibleWith("application/json"));
        var response=response();
        assertThat(response).containsOnlyKeys("initial_input_keys","tools");
        assertThat((List<String>)response.get("initial_input_keys"))
            .containsExactly("order.id","order.items","order.customer","order.payment_method","order.shipping");
    }

    /** 도구 항목마다 tool, skill, description, produces 네 키가 있고, 도구별 produces 는 OrderToolService 의 성공 경로 출력 키다. */
    @Test void everyToolDeclaresItsSkillDescriptionAndProduces() throws Exception {
        var tools=(List<Map<String,Object>>)response().get("tools");
        var produces=new LinkedHashMap<String,List<String>>();
        for(var tool:tools){
            assertThat(tool).containsOnlyKeys("tool","skill","description","produces");
            String name=(String)tool.get("tool");
            assertThat(ToolCatalog.toolFor((String)tool.get("skill"))).isEqualTo(name);
            assertThat(tool.get("description")).isEqualTo(ToolCatalog.definition(name).get("description"));
            produces.put(name,(List<String>)tool.get("produces"));
        }
        assertThat(produces).containsOnly(
            entry("order.inspect",List.of("order.id","order.customer","order.items","order.payment_method","order.shipping","order.total")),
            entry("inventory.reserve",List.of("inventory.available","inventory.reservable_quantity","inventory.reserved")),
            entry("payment.charge",List.of("payment.status","payment.id")),
            entry("shipping.reserve",List.of("shipping.id","shipping.eta")),
            entry("notify.send",List.of("notify.sent","notify.channel")));
        assertThat(tools).extracting(t->t.get("skill")).doesNotHaveDuplicates()
            .containsExactlyInAnyOrder("order-intake","inventory-check","payment","shipping-reserve","notify");
    }

    /** produces 는 비어 있지 않고 도구 안에서도 도구 사이에서도 겹치지 않으며, 서버 전용 inventory.release 는 목록에 없다. */
    @Test void producesAreNonEmptyAndUniqueAcrossTools() throws Exception {
        var tools=(List<Map<String,Object>>)response().get("tools");
        var all=new ArrayList<String>();
        for(var tool:tools){
            var keys=(List<String>)tool.get("produces");
            assertThat(keys).as((String)tool.get("tool")).isNotEmpty().doesNotHaveDuplicates().allSatisfy(k->assertThat(k).isNotBlank());
            all.addAll(keys);
        }
        assertThat(all).doesNotHaveDuplicates();
        assertThat(tools).extracting(t->t.get("tool")).doesNotHaveDuplicates().doesNotContain(ToolCatalog.RELEASE_TOOL);
    }
}
