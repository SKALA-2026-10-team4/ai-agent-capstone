package com.capstone4.orderagent.workspace;

import java.nio.charset.StandardCharsets;
import java.time.*;
import java.util.*;
import com.capstone4.orderagent.controller.EvidenceController;
import com.capstone4.orderagent.harness.*;
import org.junit.jupiter.api.*;
import org.mockito.ArgumentCaptor;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.PropertyNamingStrategies;
import tools.jackson.databind.json.JsonMapper;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * POST /api/snapshots/edits 의 선택 칸 intent(담당자 의도)를 DB 없이 고정한다. 보호 대상은
 * order-agent/backend/src/main/java/com/capstone4/orderagent/workspace/SnapshotService.java 의 saveEdits 와
 * order-agent/backend/src/main/java/com/capstone4/orderagent/controller/EvidenceController.java 의 EditRequest 다.
 * SnapshotRepository 는 넣은 snapshot 을 기억했다가 돌려주는 mock 이다. intent 가 있으면 snapshot metadata 와 outbox 본문에 같은 모양으로 담기고,
 * 없으면 두 곳 모두 intent 칸이 없어 기존 본문과 같으며, 모양이 틀린 intent 는 아무것도 저장하지 않고 INVALID_SKILL_EDITS 인지 본다.
 * 모양 계약은 docs/plans/2026-10-26-behavior-edit-root-fix.md 2.3 절이다.
 */
@SuppressWarnings("unchecked")
class SnapshotEditIntentTest {
    static final String PATH="skills/payment/SKILL.md";
    final SnapshotRepository store=mock(SnapshotRepository.class);
    final Map<UUID,SnapshotRepository.Stored> saved=new HashMap<>();
    final SnapshotService service=new SnapshotService(store,"../skills",Clock.fixed(Instant.parse("2026-10-26T00:00:00Z"),ZoneOffset.UTC));
    SnapshotRepository.Stored baseline;

    /** 기준 snapshot 하나를 저장소에 두고, insert 한 snapshot 을 get 이 그대로 돌려주게 한다. */
    @BeforeEach void store(){
        var files=new TreeMap<String,byte[]>();files.put(PATH,"원래 본문".getBytes(StandardCharsets.UTF_8));
        Map<String,Object> metadata=Map.of("source","published-bundle");
        baseline=new SnapshotRepository.Stored(UUID.randomUUID(),SnapshotService.bundleHash(files,metadata),metadata,Instant.EPOCH,files);
        saved.put(baseline.id(),baseline);
        doAnswer(inv->{
            UUID id=inv.getArgument(0);
            // 실제 저장소처럼 metadata 를 JSON 으로 썼다가 다시 읽어 돌려준다.
            saved.put(id,new SnapshotRepository.Stored(id,inv.getArgument(1),Json.object(Json.write(inv.getArgument(2))),inv.getArgument(4),
                new TreeMap<>((SortedMap<String,byte[]>)inv.getArgument(3))));
            return null;
        }).when(store).insert(any(),anyString(),anyMap(),any(),any());
        when(store.get(any())).thenAnswer(inv->{
            var stored=saved.get((UUID)inv.getArgument(0));
            if(stored==null)throw new AgentException("SNAPSHOT_NOT_FOUND");
            return stored;
        });
    }

    /** intent 가 있으면 생략한 scope 는 null 로 채운 같은 모양이 metadata 와 outbox 본문의 intent 에 담긴다. */
    @Test void intentIsStoredInMetadataAndPayload(){
        var intent=new SnapshotService.EditIntent("선입금 주문만 결제한다",List.of(
            new SnapshotService.EditIntent.Expectation("order.payment_method == 'card'",List.of("order.final_status in ['HOLD', 'REJECTED']","payment.status == null")),
            new SnapshotService.EditIntent.Expectation(null,List.of("order.final_status != null"))));
        var result=service.saveEdits(baseline.id(),baseline.hash(),Map.of(PATH,"새 본문"),intent);
        var expected=Map.of("text","선입금 주문만 결제한다","expectations",List.of(
            mapOf("scope","order.payment_method == 'card'","checks",List.of("order.final_status in ['HOLD', 'REJECTED']","payment.status == null")),
            mapOf("scope",null,"checks",List.of("order.final_status != null"))));
        assertThat(result.metadata().get("intent")).isEqualTo(expected);
        assertThat(result.hash()).isEqualTo(SnapshotService.bundleHash(result.files(),result.metadata()));
        var payload=payload();
        assertThat(payload.keySet()).containsExactly("type","change_id","baseline_snapshot_id","candidate_snapshot_id","changed_paths","intent");
        assertThat(payload.get("intent")).isEqualTo(expected);
        assertThat(payload.get("candidate_snapshot_id")).isEqualTo(result.id().toString());
    }
    /** expectations 를 생략한 intent 는 빈 expectations 로 담긴다. */
    @Test void omittedExpectationsBecomeAnEmptyList(){
        var result=service.saveEdits(baseline.id(),baseline.hash(),Map.of(PATH,"새 본문"),new SnapshotService.EditIntent("해외 배송은 받지 않는다",null));
        assertThat(result.metadata().get("intent")).isEqualTo(Map.of("text","해외 배송은 받지 않는다","expectations",List.of()));
        assertThat(payload().get("intent")).isEqualTo(Map.of("text","해외 배송은 받지 않는다","expectations",List.of()));
    }
    /** intent 가 없으면 metadata 와 outbox 본문 어디에도 intent 칸이 없고 기존 키만 남는다. */
    @Test void absentIntentLeavesTheExistingShape(){
        var result=service.saveEdits(baseline.id(),baseline.hash(),Map.of(PATH,"새 본문"),null);
        assertThat(result.metadata().keySet()).containsExactlyInAnyOrder("source","change_id","baseline_snapshot_id","baseline_hash","changed_paths","validation_status");
        assertThat(payload().keySet()).containsExactly("type","change_id","baseline_snapshot_id","candidate_snapshot_id","changed_paths");
    }
    /** 모양이 틀린 intent 는 기준 snapshot 을 읽기 전에 INVALID_SKILL_EDITS 로 거절하고 아무것도 저장하지 않는다. */
    @Test void malformedIntentIsRejectedBeforeAnythingIsSaved(){
        var ok=List.of("payment.status == null");
        var bad=new LinkedHashMap<String,SnapshotService.EditIntent>();
        bad.put("text 없음",new SnapshotService.EditIntent(null,List.of()));
        bad.put("text 공백",new SnapshotService.EditIntent("  ",List.of()));
        bad.put("text 501자",new SnapshotService.EditIntent("가".repeat(501),List.of()));
        bad.put("expectation 11개",new SnapshotService.EditIntent("의도",Collections.nCopies(11,new SnapshotService.EditIntent.Expectation(null,ok))));
        bad.put("expectation null",new SnapshotService.EditIntent("의도",Arrays.asList((SnapshotService.EditIntent.Expectation)null)));
        bad.put("scope 공백",new SnapshotService.EditIntent("의도",List.of(new SnapshotService.EditIntent.Expectation(" ",ok))));
        bad.put("checks 없음",new SnapshotService.EditIntent("의도",List.of(new SnapshotService.EditIntent.Expectation(null,null))));
        bad.put("checks 빈 목록",new SnapshotService.EditIntent("의도",List.of(new SnapshotService.EditIntent.Expectation(null,List.of()))));
        bad.put("checks 11개",new SnapshotService.EditIntent("의도",List.of(new SnapshotService.EditIntent.Expectation(null,Collections.nCopies(11,"a == 1")))));
        bad.put("check 공백",new SnapshotService.EditIntent("의도",List.of(new SnapshotService.EditIntent.Expectation(null,List.of("a == 1"," ")))));
        bad.put("check null",new SnapshotService.EditIntent("의도",List.of(new SnapshotService.EditIntent.Expectation(null,Arrays.asList("a == 1",null)))));
        bad.forEach((reason,intent)->assertThatThrownBy(()->service.saveEdits(baseline.id(),baseline.hash(),Map.of(PATH,"새 본문"),intent))
            .as(reason).isInstanceOf(AgentException.class).hasMessage("INVALID_SKILL_EDITS"));
        verify(store,never()).get(any());
        verify(store,never()).insert(any(),anyString(),anyMap(),any(),any());
        verify(store,never()).insertChangeEvent(any(),any(),anyString());
        var limits=new SnapshotService.EditIntent("가".repeat(500),Collections.nCopies(10,new SnapshotService.EditIntent.Expectation(null,Collections.nCopies(10,"a == 1"))));
        assertThat(service.saveEdits(baseline.id(),baseline.hash(),Map.of(PATH,"새 본문"),limits).metadata()).containsKey("intent");
    }
    /**
     * scope 와 checks 는 조건 문법 v2 로 읽혀야 하고 scope 의 값 이름은 시험 주문의 다섯 처음 값 가운데 하나와 정확히 같아야 한다(2.10 절 V3-2).
     * 어기면 기준 snapshot 을 읽기 전에 INVALID_SKILL_EDITS 이고, details 가 그 칸의 위치와 원문을 밝히며 아무것도 저장하지 않는다.
     */
    @Test void unreadableConditionsAndForeignScopeKeysAreRejectedWithTheirLine(){
        var ok=List.of("order.final_status == 'HOLD'");
        record Bad(SnapshotService.EditIntent intent, String field, String reason, String line) {}
        var bad=List.of(
            new Bad(intent(new SnapshotService.EditIntent.Expectation("order.payment_method == 'card'",List.of("order.final_status == 'HOLD'","payment.status = null"))),
                "intent.expectations[0].checks[1]","CONDITION_UNREADABLE","payment.status = null"),
            new Bad(intent(new SnapshotService.EditIntent.Expectation(null,ok),new SnapshotService.EditIntent.Expectation("order.payment_method = 'card'",ok)),
                "intent.expectations[1].scope","CONDITION_UNREADABLE","order.payment_method = 'card'"),
            new Bad(intent(new SnapshotService.EditIntent.Expectation("order.total >= '100000'",ok)),"intent.expectations[0].scope","CONDITION_UNREADABLE","order.total >= '100000'"),
            new Bad(intent(new SnapshotService.EditIntent.Expectation(null,List.of("order.final_status in 'HOLD'"))),
                "intent.expectations[0].checks[0]","CONDITION_UNREADABLE","order.final_status in 'HOLD'"),
            new Bad(intent(new SnapshotService.EditIntent.Expectation("payment.status == null",ok)),"intent.expectations[0].scope","SCOPE_KEY_NOT_ALLOWED","payment.status == null"),
            new Bad(intent(new SnapshotService.EditIntent.Expectation("order.shipping == 'KR'",ok)),"intent.expectations[0].scope","SCOPE_KEY_NOT_ALLOWED","order.shipping == 'KR'"),
            new Bad(intent(new SnapshotService.EditIntent.Expectation("order.shipping.country.code == 'KR'",ok)),
                "intent.expectations[0].scope","SCOPE_KEY_NOT_ALLOWED","order.shipping.country.code == 'KR'"),
            new Bad(intent(new SnapshotService.EditIntent.Expectation("order.final_status == 'HOLD'",ok)),"intent.expectations[0].scope","SCOPE_KEY_NOT_ALLOWED","order.final_status == 'HOLD'"));
        for(var b:bad)assertThatThrownBy(()->service.saveEdits(baseline.id(),baseline.hash(),Map.of(PATH,"새 본문"),b.intent())).as(b.line())
            .isInstanceOfSatisfying(AgentException.class,e->{
                assertThat(e.category()).isEqualTo("INVALID_SKILL_EDITS");
                assertThat(e.details()).containsEntry("field",b.field()).containsEntry("reason",b.reason());
                assertThat((String)e.details().get("message")).contains(b.line());
            });
        verify(store,never()).get(any());
        verify(store,never()).insert(any(),anyString(),anyMap(),any(),any());
        verify(store,never()).insertChangeEvent(any(),any(),anyString());
    }
    /** 다섯 scope 키(점이 두 번 든 order.shipping.country, order.shipping.express 포함)와 숫자·불리언·목록 값은 모두 저장된다. checks 의 값 이름은 묻지 않는다. */
    @Test void everyOrderStartValueIsAnAllowedScopeKey(){
        var checks=List.of("order.final_status in ['HOLD', 'REJECTED']","payment.status == null","order.shipments == 0","inventory.reserved != true");
        var intent=intent(
            new SnapshotService.EditIntent.Expectation("order.payment_method != 'prepaid'",checks),
            new SnapshotService.EditIntent.Expectation("order.customer in ['c_declined']",checks),
            new SnapshotService.EditIntent.Expectation("order.shipping.country != 'KR'",checks),
            new SnapshotService.EditIntent.Expectation("order.shipping.express == true",checks),
            new SnapshotService.EditIntent.Expectation("order.total >= 100000",checks),
            new SnapshotService.EditIntent.Expectation("  order.total < 99.5  ",checks),
            new SnapshotService.EditIntent.Expectation(null,List.of("order.final_status != null")));
        var result=service.saveEdits(baseline.id(),baseline.hash(),Map.of(PATH,"새 본문"),intent);
        assertThat((List<?>)((Map<?,?>)result.metadata().get("intent")).get("expectations")).hasSize(7);
        assertThat(payload()).containsKey("intent");
    }
    /** 요청 본문의 intent 는 snake_case 이름 그대로 읽히고, 모르는 키는 fail-on-unknown-properties 설정에서 읽기 오류다. */
    @Test void editRequestBindsTheIntentField(){
        var mapper=JsonMapper.builder().propertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE)
            .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES).build();
        String body="""
            {"baseline_snapshot_id":"%s","baseline_hash":"h","changes":{"%s":"x"},
             "intent":{"text":"카드 주문은 보류","expectations":[{"scope":"order.payment_method == 'card'","checks":["order.final_status == 'HOLD'"]}]}}
            """.formatted(baseline.id(),PATH);
        var request=mapper.readValue(body,EvidenceController.EditRequest.class);
        assertThat(request.intent()).isEqualTo(new SnapshotService.EditIntent("카드 주문은 보류",List.of(
            new SnapshotService.EditIntent.Expectation("order.payment_method == 'card'",List.of("order.final_status == 'HOLD'")))));
        String legacy="{\"baseline_snapshot_id\":\"%s\",\"baseline_hash\":\"h\",\"changes\":{}}".formatted(baseline.id());
        assertThat(mapper.readValue(legacy,EvidenceController.EditRequest.class).intent()).isNull();
        String unknown=body.replace("\"text\":","\"source\":\"owner\",\"text\":");
        assertThatThrownBy(()->mapper.readValue(unknown,EvidenceController.EditRequest.class)).isInstanceOf(RuntimeException.class);
    }

    /** 가장 최근 outbox 본문 JSON 을 키 순서대로 읽는다. */
    Map<String,Object> payload(){
        var captor=ArgumentCaptor.forClass(String.class);
        verify(store,atLeastOnce()).insertChangeEvent(any(),any(),captor.capture());
        return Json.object(captor.getValue());
    }
    /** 같은 의도 글에 expectations 를 붙인 intent 를 만든다. */
    static SnapshotService.EditIntent intent(SnapshotService.EditIntent.Expectation... expectations){
        return new SnapshotService.EditIntent("카드 주문은 결제하지 않는다",List.of(expectations));
    }
    /** null 값을 허용하는 두 칸짜리 순서 있는 Map 을 만든다. */
    static Map<String,Object> mapOf(String k1,Object v1,String k2,Object v2){
        var map=new LinkedHashMap<String,Object>();map.put(k1,v1);map.put(k2,v2);return map;
    }
}
