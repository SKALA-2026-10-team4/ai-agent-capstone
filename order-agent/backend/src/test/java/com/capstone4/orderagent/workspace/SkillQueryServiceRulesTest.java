package com.capstone4.orderagent.workspace;

import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;
import com.capstone4.orderagent.harness.SkillLoader;
import org.junit.jupiter.api.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * order-agent/backend/src/main/java/com/capstone4/orderagent/workspace/SkillQueryService.java 의 메타데이터 rules 칸을 DB 없이 고정한다.
 * 게시 번들(order-agent/skills)의 Skill 여섯 개 가운데 payment 에만 규칙 두 줄을 끼운 snapshot 을 SnapshotService mock 으로 준다.
 * rules 는 기존 칸(steps) 뒤에 붙는 추가 칸이라 기존 키 순서는 그대로여야 하고, 규칙은 front matter 순서대로 {when, then, reason} 이며,
 * 규칙이 없는 단계 Skill 과 flow Skill 은 빈 목록이어야 한다. 목록(list)과 한 건 조회(get)가 같은 값을 내는지도 본다.
 */
@SuppressWarnings("unchecked")
class SkillQueryServiceRulesTest {
    static final List<String> SKILLS=List.of("order-flow","order-intake","inventory-check","payment","shipping-reserve","notify");
    static final String RULES="""
        rules:
          - when: "order.payment_method != 'prepaid'"
            then: hold
            reason: PREPAID_ONLY
          - when: "order.total >= 1000000"
            then: reject
            reason: TOO_EXPENSIVE
        """;
    final UUID id=UUID.randomUUID();
    final SnapshotService snapshots=mock(SnapshotService.class);
    final SkillQueryService service=new SkillQueryService(snapshots,new SkillLoader());

    /** order-agent/skills 의 SKILL.md 여섯 개를 읽고 payment 의 front matter 끝(닫는 --- 앞)에 RULES 를 넣은 snapshot 을 get 이 돌려주게 한다. */
    @BeforeEach void snapshot() throws Exception {
        var files=new TreeMap<String,byte[]>();
        for(String skill:SKILLS){
            String text=Files.readString(Path.of("../skills",skill,"SKILL.md"),StandardCharsets.UTF_8).replace("\r\n","\n");
            if(skill.equals("payment")){int close=text.indexOf("\n---\n",4);text=text.substring(0,close+1)+RULES+text.substring(close+1);}
            files.put(SkillLoader.skillPath(skill),text.getBytes(StandardCharsets.UTF_8));
        }
        when(snapshots.get(id)).thenReturn(new SnapshotRepository.Stored(id,"snapshot-hash",Map.of(),Instant.EPOCH,files));
    }

    /** 목록의 각 항목은 기존 키 순서 뒤에 rules 를 붙이고, payment 만 규칙 두 줄을 front matter 순서대로 담는다. */
    @Test void listAddsRulesAfterTheExistingKeys(){
        var list=service.list(id);
        assertThat(list).containsEntry("partial",false).containsEntry("parse_errors",List.of());
        var items=(List<Map<String,Object>>)list.get("items");
        assertThat(items).extracting(item->item.get("skill_id")).containsExactly(
            "inventory-check","notify","order-flow","order-intake","payment","shipping-reserve");
        for(var item:items)assertThat(new ArrayList<>(item.keySet())).containsExactly(
            "skill_id","name","version","description","owner","path","hash","inputs","outputs","depends_on","steps","rules");
        var payment=items.stream().filter(item->item.get("skill_id").equals("payment")).findFirst().orElseThrow();
        var rules=(List<Map<String,Object>>)payment.get("rules");
        assertThat(rules).containsExactly(
            Map.of("when","order.payment_method != 'prepaid'","then","hold","reason","PREPAID_ONLY"),
            Map.of("when","order.total >= 1000000","then","reject","reason","TOO_EXPENSIVE"));
        assertThat(new ArrayList<>(rules.get(0).keySet())).containsExactly("when","then","reason");
        assertThat(items).filteredOn(item->!item.get("skill_id").equals("payment")).allSatisfy(item->assertThat(item.get("rules")).isEqualTo(List.of()));
    }
    /** 한 건 조회도 같은 rules 를 메타데이터 칸 끝에 두고, 그 뒤에 snapshot_id 부터 content 까지 기존 칸이 이어진다. */
    @Test void getReturnsTheSameRulesBeforeTheSnapshotFields(){
        var skill=service.get(id,"payment");
        assertThat(new ArrayList<>(skill.keySet()).subList(10,17)).containsExactly(
            "steps","rules","snapshot_id","snapshot_hash","size","encoding","content");
        assertThat((List<Map<String,Object>>)skill.get("rules")).extracting(rule->rule.get("reason")).containsExactly("PREPAID_ONLY","TOO_EXPENSIVE");
        assertThat(service.get(id,"order-flow").get("rules")).isEqualTo(List.of());
    }
}
