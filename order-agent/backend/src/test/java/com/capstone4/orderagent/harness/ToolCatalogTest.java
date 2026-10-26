package com.capstone4.orderagent.harness;

import java.util.*;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

/**
 * order-agent/backend/src/main/java/com/capstone4/orderagent/harness/ToolCatalog.java 의 단계 Skill 과 도구 바인딩, 서버 대조용 도구 인자,
 * 도구 설명 모양과 order-agent/backend/src/main/java/com/capstone4/orderagent/harness/FixedDecisionProvider.java 의 응답을 고정하는 특성 테스트다.
 * 주문 흐름에 단계를 추가하거나 BINDINGS 를 바꾸면 이 테스트의 기대값도 함께 고친다.
 */
@SuppressWarnings("unchecked")
class ToolCatalogTest {
    // 모델에 내놓는 다섯 도구 이름이다. ToolCatalog 의 BINDINGS 에 있는 도구와 같은 집합이다.
    static final List<String> TOOLS=List.of("order.inspect","inventory.reserve","payment.charge","shipping.reserve","notify.send");

    /**
     * order-agent/backend/src/main/java/com/capstone4/orderagent/service/OrderToolService.java 의 context 가 주문마다 만드는 것과
     * 같은 모양의 서버 컨텍스트다.
     */
    static Map<String,Object> context(){
        var context=new LinkedHashMap<String,Object>();
        context.put("order.id","o-1");context.put("order.customer","c_1001");
        context.put("order.items",List.of(Map.of("sku","CAMP-LANTERN-LED","qty",2)));
        context.put("order.payment_method","card");context.put("order.shipping",Map.of("country","KR","express",false));
        context.put("order.total",60000L);
        return context;
    }

    /** 다섯 단계 Skill 은 도구 하나씩에 묶이고, 그 밖의 이름은 UNSUPPORTED_SKILL 이다. */
    @Test void eachStepSkillIsBoundToExactlyOneTool(){
        assertThat(ToolCatalog.toolFor("order-intake")).isEqualTo("order.inspect");
        assertThat(ToolCatalog.toolFor("inventory-check")).isEqualTo("inventory.reserve");
        assertThat(ToolCatalog.toolFor("payment")).isEqualTo("payment.charge");
        assertThat(ToolCatalog.toolFor("shipping-reserve")).isEqualTo("shipping.reserve");
        assertThat(ToolCatalog.toolFor("notify")).isEqualTo("notify.send");
        for(String unknown:List.of("order-flow","gift-wrap","","Payment","inventory.reserve"))
            assertThatThrownBy(()->ToolCatalog.toolFor(unknown)).isInstanceOf(AgentException.class).hasMessage("UNSUPPORTED_SKILL");
        assertThatThrownBy(()->ToolCatalog.toolFor(null)).isInstanceOf(NullPointerException.class);
    }

    /** 도구 인자는 order_id 를 먼저 두고 도구별 키를 정해진 순서로 서버 컨텍스트에서 채운다. */
    @Test void argumentsFollowServerContextInFixedKeyOrder(){
        var context=context();
        assertThat(ToolCatalog.arguments("order.inspect",context)).containsExactly(entry("order_id","o-1"));
        assertThat(ToolCatalog.arguments("inventory.reserve",context))
            .containsExactly(entry("order_id","o-1"),entry("items",context.get("order.items")));
        assertThat(ToolCatalog.arguments("inventory.reserve",context).get("items")).isSameAs(context.get("order.items"));
        assertThat(ToolCatalog.arguments("payment.charge",context))
            .containsExactly(entry("order_id","o-1"),entry("amount",60000L),entry("method","card"));
        assertThat(ToolCatalog.arguments("shipping.reserve",context))
            .containsExactly(entry("order_id","o-1"),entry("country","KR"),entry("express",false));
        assertThat(ToolCatalog.arguments("notify.send",context))
            .containsExactly(entry("order_id","o-1"),entry("customer","c_1001"));
        assertThat(ToolCatalog.arguments("inventory.release",context)).containsExactly(entry("order_id","o-1"));
        assertThat(ToolCatalog.arguments("payment.charge",context)).isInstanceOf(LinkedHashMap.class);
    }

    /** 컨텍스트에 값이 없으면 인자 값은 null 로 남고, 배송 정보가 Map 이 아니면 CONTRACT_VIOLATION 이다. */
    @Test void missingContextValuesStayNullAndShippingMustBeAMap(){
        var payment=ToolCatalog.arguments("payment.charge",Map.of());
        assertThat(payment.keySet()).containsExactly("order_id","amount","method");
        assertThat(payment.values()).containsOnlyNulls();
        assertThat(ToolCatalog.arguments("shipping.reserve",Map.of("order.id","o-1","order.shipping",Map.of("country","US"))))
            .containsExactly(entry("order_id","o-1"),entry("country","US"),entry("express",null));
        assertThatThrownBy(()->ToolCatalog.arguments("shipping.reserve",Map.of("order.id","o-1")))
            .isInstanceOf(AgentException.class).hasMessage("CONTRACT_VIOLATION");
        assertThatThrownBy(()->ToolCatalog.arguments("shipping.reserve",Map.of("order.id","o-1","order.shipping","KR")))
            .isInstanceOf(AgentException.class).hasMessage("CONTRACT_VIOLATION");
        assertThat(((AgentException)catchThrowable(()->ToolCatalog.arguments("shipping.reserve",Map.of()))).details()).isEmpty();
    }

    /** 모르는 도구는 UNKNOWN_TOOL 이고 null 이름이나 null 컨텍스트는 NullPointerException 이다. */
    @Test void unknownToolsAreRejected(){
        for(String unknown:List.of("gift.wrap","","order-intake","ORDER.INSPECT"))
            assertThatThrownBy(()->ToolCatalog.arguments(unknown,context())).isInstanceOf(AgentException.class).hasMessage("UNKNOWN_TOOL");
        assertThatThrownBy(()->ToolCatalog.arguments(null,context())).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(()->ToolCatalog.arguments("order.inspect",null)).isInstanceOf(NullPointerException.class);
    }

    /** 묶인 도구마다 이름과 서로 다른 설명을 가진 정의가 있고, inventory.release 는 모델에 내놓지 않는다. */
    @Test void definitionsNameEveryBoundToolWithADistinctDescription(){
        var descriptions=new HashSet<Object>();
        for(String tool:TOOLS){
            var definition=ToolCatalog.definition(tool);
            assertThat(definition.keySet()).containsExactlyInAnyOrder("name","description");
            assertThat(definition.get("name")).isEqualTo(tool);
            assertThat(definition.get("description")).isInstanceOf(String.class);
            assertThat((String)definition.get("description")).isNotBlank();
            descriptions.add(definition.get("description"));
        }
        assertThat(descriptions).hasSize(TOOLS.size());
        for(String unknown:List.of("inventory.release","gift.wrap",""))
            assertThatThrownBy(()->ToolCatalog.definition(unknown)).isInstanceOf(AgentException.class).hasMessage("UNKNOWN_TOOL");
        assertThatThrownBy(()->ToolCatalog.definition(null)).isInstanceOf(NullPointerException.class);
    }

    /**
     * 묶인 도구마다 produces 가 있고 바꿀 수 없으며, tools 목록이 같은 값을 담는다. inventory.release 와 모르는 도구는 UNKNOWN_TOOL 이다.
     * 처음 입력 키는 OrderFlowPolicy.INITIAL_INPUT_KEYS 그대로다.
     */
    @Test void producesAreDeclaredForEveryBoundToolAndListedByTools(){
        assertThat(ToolCatalog.tools()).extracting(t->t.get("tool")).containsExactlyElementsOf(TOOLS);
        for(var item:ToolCatalog.tools()){
            String tool=(String)item.get("tool");
            assertThat(item.keySet()).containsExactly("tool","skill","description","produces");
            assertThat(item.get("produces")).isEqualTo(ToolCatalog.produces(tool));
            assertThat(ToolCatalog.produces(tool)).isNotEmpty();
            assertThatThrownBy(()->ToolCatalog.produces(tool).add("x")).isInstanceOf(UnsupportedOperationException.class);
        }
        for(String unknown:List.of("inventory.release","gift.wrap",""))
            assertThatThrownBy(()->ToolCatalog.produces(unknown)).isInstanceOf(AgentException.class).hasMessage("UNKNOWN_TOOL");
        assertThat(ToolCatalog.initialInputKeys()).isSameAs(OrderFlowPolicy.INITIAL_INPUT_KEYS);
    }

    /** 고정 제공자는 select 에 order-flow 를, 그 밖의 목적에는 묶인 도구와 서버 인자를 돌려준다. */
    @Test void fixedProviderSelectsOrderFlowAndRequestsTheBoundToolWithServerArguments(){
        var provider=new FixedDecisionProvider();
        assertThat(provider.mode()).isEqualTo("fixed");
        var select=provider.decide(new DecisionProvider.Question("select",List.of(),null,Map.of(),Map.of()));
        assertThat(Json.object(select.raw())).isEqualTo(Map.of("skill","order-flow"));
        assertThat(select.inputTokens()).isNull();assertThat(select.outputTokens()).isNull();
        var skill=new SkillLoader.Skill("payment","결제",1,List.of("order.id","order.total","order.payment_method"),
            List.of("payment.id"),List.of(),List.of(),"본문","skills/payment/SKILL.md","hash","재무팀");
        var inputs=new LinkedHashMap<String,Object>();inputs.put("order.id","o-1");inputs.put("order.total",30000);inputs.put("order.payment_method","prepaid");
        for(String purpose:List.of("tool","other")){
            var reply=provider.decide(new DecisionProvider.Question(purpose,List.of(),skill,inputs,Map.of()));
            var parsed=Json.object(reply.raw());
            assertThat(parsed.keySet()).containsExactlyInAnyOrder("tool","arguments");
            assertThat(parsed.get("tool")).isEqualTo("payment.charge");
            assertThat((Map<String,Object>)parsed.get("arguments"))
                .containsExactly(entry("order_id","o-1"),entry("amount",30000),entry("method","prepaid"));
            assertThat(reply.inputTokens()).isNull();assertThat(reply.outputTokens()).isNull();
        }
        var flow=new SkillLoader.Skill("order-flow","흐름",1,List.of(),List.of(),List.of(),List.of("payment"),"본문","skills/order-flow/SKILL.md","hash","팀");
        assertThatThrownBy(()->provider.decide(new DecisionProvider.Question("tool",List.of(),flow,Map.of(),Map.of())))
            .isInstanceOf(AgentException.class).hasMessage("UNSUPPORTED_SKILL");
        assertThatThrownBy(()->provider.decide(new DecisionProvider.Question(null,List.of(),skill,inputs,Map.of())))
            .isInstanceOf(NullPointerException.class);
    }
}
