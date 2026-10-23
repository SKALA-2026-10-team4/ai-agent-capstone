package com.capstone4.orderagent.harness;

import java.util.*;
import java.util.stream.Collectors;
import com.capstone4.orderagent.entity.enums.ProcessingStepType;

/**
 * 단계 Skill 과 업무 도구를 1:1 로 묶는 바인딩 표다. 단계 Skill 마다 허용 도구 하나, 주문 처리 단계 종류,
 * 모델에 보여 줄 도구 설명, 서버 컨텍스트로 도구 인자를 채우는 규칙을 BINDINGS 한 줄에 둔다.
 * 이 표 하나가 네 가지를 함께 정한다. 첫째, 그 단계에서 부를 수 있는 도구다(ToolDispatcher 의 TOOL_NOT_ALLOWED 검사).
 * 둘째, fixed 모드 응답과 서버 대조에 쓰는 도구 인자다(FixedDecisionProvider, OrderToolService 의 INVALID_TOOL_ARGUMENTS 검사).
 * 셋째, 그 단계가 실패했을 때 주문에 FAILED 로 남길 처리 단계 종류다(OrderHarness).
 * 넷째, 결정 질문과 DECISION_INPUT 에 실리는 도구 정의다(definition).
 *
 * 주문 흐름에 단계 하나를 추가할 때 고칠 곳은 다음과 같다.
 * 1. order-agent/skills 에 새 Skill 폴더와 SKILL.md 를 만들고 order-agent/skills/order-flow/SKILL.md 의 steps 에 넣은 뒤
 *    order-agent/scripts/publish-skills.py 로 번들을 다시 봉인한다. steps 는 OrderFlowPolicy.MAX_FLOW_STEPS 를 넘을 수 없다.
 * 2. 이 클래스의 BINDINGS 에 Binding 한 줄을 더한다.
 * 3. order-agent/backend/src/main/java/com/capstone4/orderagent/service/OrderToolService.java 의 applyTool 에 case 와 처리 메서드를 더한다.
 * 4. 새 주문 처리 단계 종류가 필요하면 enum 값을 더하고 BUSINESS_FLOW 의 알맞은 자리에 넣는다. 거절이나 실패 뒤 SKIPPED 로 남길
 *    단계가 이 순서로 정해진다. 파일은 order-agent/backend/src/main/java/com/capstone4/orderagent/entity/enums/ProcessingStepType.java 다.
 * 5. 새 단계가 반드시 끝나야 하는 마지막 단계라면 OrderFlowPolicy.REQUIRED_FINAL_STEP 을 바꾸고, 새 초기 입력 키가 생기면
 *    OrderFlowPolicy.INITIAL_INPUT_KEYS 주석이 가리키는 복제본까지 맞춘다.
 * 6. 화면에 한글 이름을 보이려면 frontend/src/utils/eventView.js 의 TOOL_LABELS 에 도구를, 새 컨텍스트 키가 생기면 같은 파일의
 *    KEY_LABELS 에 키를 더한다. 새 처리 단계 종류를 더했다면 order-agent/frontend/src/utils/format.js 의 stepText 에도 이름을 더한다.
 * order-agent/backend/src/test/java/com/capstone4/orderagent/harness 의 ToolCatalogTest 와 ToolCatalogStepTypeTest 가 현재 표를 고정하므로
 * 두 테스트의 기대값도 함께 고친다. 게시 번들로 실행 경로를 비교하는
 * order-agent/backend/src/test/java/com/capstone4/orderagent/integration/TraceContractGolden.java 의 기대 줄도 다시 맞춘다.
 */
public final class ToolCatalog {
    /** 정적 표와 정적 메서드만 있는 클래스라 객체를 만들지 않는다. */
    private ToolCatalog() {}
    /**
     * 재고 예약을 되돌리는 서버 전용 도구 이름이다. 모델에 내놓지 않으므로 BINDINGS 에 없고 definition 은 UNKNOWN_TOOL 을 던진다.
     * OrderToolService 의 release 와 order-agent/backend/src/main/java/com/capstone4/orderagent/service/InventoryRecovery.java 가
     * 서버가 실행한 해제를 trace 의 TOOL_REQUEST, TOOL_RESULT 에 이 이름으로 남긴다.
     */
    public static final String RELEASE_TOOL = "inventory.release";

    /**
     * 단계 Skill 하나의 허용 도구, 주문 처리 단계 종류, 모델용 도구 설명, 서버 인자 채우기 규칙이다.
     * description 은 모델에게 그대로 보내는 문장이라 프롬프트처럼 다룬다.
     */
    private record Binding(String skill, String tool, ProcessingStepType orderStep, String description, ArgumentFiller fill) {}
    /**
     * 서버 컨텍스트에서 order_id 뒤의 도구 인자를 정해진 순서로 채운다. fixed 모드에서는 넣는 순서가 TOOL_REQUEST 에 보이는
     * 인자 키 순서가 되며 ToolCatalogTest 가 이 순서를 고정한다. 서버 대조는 정규화 해시로 하므로 키 순서와 무관하다.
     */
    @FunctionalInterface
    private interface ArgumentFiller { void fill(Map<String,Object> context, Map<String,Object> args); }

    /**
     * 단계 하나를 추가하거나 도구를 바꿀 때 고치는 표다. 줄 순서에는 의미가 없다. skill 이름과 tool 이름은 각각 겹치면 안 되며,
     * 겹치면 BY_SKILL 과 BY_TOOL 을 만드는 toUnmodifiableMap 이 클래스 초기화 중에 실패한다.
     */
    private static final List<Binding> BINDINGS = List.of(
        new Binding("order-intake", "order.inspect", ProcessingStepType.INTAKE,
            "Read the accepted order and server-calculated total. Does not create another order.",
            (context, args) -> {}),
        new Binding("inventory-check", "inventory.reserve", ProcessingStepType.INVENTORY,
            "Atomically reserve all order items or return OUT_OF_STOCK. Arguments: order_id, items[{sku,qty}].",
            (context, args) -> args.put("items", context.get("order.items"))),
        // 결제 금액은 모델이 아니라 서버 컨텍스트의 order.total 로 채운다. 모델이 다른 금액을 보내면 서버 대조에서 막힌다.
        new Binding("payment", "payment.charge", ProcessingStepType.PAYMENT,
            "Mock charge a reserved order. Arguments: order_id, amount (server total), method (card/prepaid).",
            (context, args) -> {args.put("amount", context.get("order.total")); args.put("method", context.get("order.payment_method"));}),
        new Binding("shipping-reserve", "shipping.reserve", ProcessingStepType.SHIPPING,
            "Reserve shipment only for a paid and reserved order. Arguments: order_id, country, express.",
            ToolCatalog::shippingArguments),
        new Binding("notify", "notify.send", ProcessingStepType.NOTIFICATION,
            "Record success notification only after shipment. Arguments: order_id, customer.",
            (context, args) -> args.put("customer", context.get("order.customer"))));
    /** skill 이름과 tool 이름으로 BINDINGS 를 찾는 색인이다. 바꿀 수 없는 Map 이라 null 키로 찾으면 NullPointerException 이 난다. */
    private static final Map<String,Binding> BY_SKILL = BINDINGS.stream().collect(Collectors.toUnmodifiableMap(Binding::skill, b -> b));
    private static final Map<String,Binding> BY_TOOL = BINDINGS.stream().collect(Collectors.toUnmodifiableMap(Binding::tool, b -> b));

    /**
     * 단계 Skill 에 묶인 허용 도구 이름을 돌려준다. 바인딩이 없으면 UNSUPPORTED_SKILL 이다.
     * OrderHarness 가 flow 검증 때 모든 단계에 미리 부르고 단계마다 도구 정의를 찾을 때 다시 부른다.
     * ToolDispatcher 는 요청 도구가 허용 도구인지 볼 때, FixedDecisionProvider 는 고정 응답을 만들 때 부른다.
     */
    public static String toolFor(String skill){
        Binding binding=BY_SKILL.get(skill);if(binding==null)throw new AgentException("UNSUPPORTED_SKILL");return binding.tool();
    }
    /**
     * 컨텍스트에서 도구 인자를 만든다. order_id 를 먼저 넣고 Binding 의 채우기 규칙으로 나머지를 넣으며, RELEASE_TOOL 은 order_id 만 담는다.
     * 모르는 도구는 UNKNOWN_TOOL 이고, 컨텍스트에 없는 값은 null 로 남는다. 다만 order.shipping 이 없거나 Map 이 아니면
     * shippingArguments 가 CONTRACT_VIOLATION 을 던진다.
     * FixedDecisionProvider 는 단계의 선언 입력으로 고정 응답 인자를 만들고, OrderToolService 의 requireServerArguments 는
     * 주문 전체 컨텍스트로 같은 인자를 다시 만들어 모델이 보낸 인자와 정규화 해시를 비교한다.
     * 그래서 단계 Skill 이 인자에 필요한 값을 inputs 에 선언하지 않으면 fixed 모드에서도 INVALID_TOOL_ARGUMENTS 로 멈추고,
     * order.shipping 을 선언하지 않은 배송 단계는 도구를 요청하기 전에 CONTRACT_VIOLATION 으로 멈춘다.
     */
    public static Map<String,Object> arguments(String tool,Map<String,Object> context){
        var args=new LinkedHashMap<String,Object>();args.put("order_id",context.get("order.id"));
        if(RELEASE_TOOL.equals(tool))return args;
        Binding binding=BY_TOOL.get(tool);
        if(binding==null)throw new AgentException("UNKNOWN_TOOL");
        binding.fill().fill(context,args);
        return args;
    }
    /**
     * 모델에 보여 줄 도구 정의(name, description)를 만든다. RELEASE_TOOL 과 모르는 도구는 UNKNOWN_TOOL 이다.
     * OrderHarness 가 단계마다 결정 질문에 넣고, SkillExecutor 가 DECISION_INPUT 의 tool_definition 으로 남긴다.
     */
    public static Map<String,Object> definition(String tool){
        Binding binding=BY_TOOL.get(tool);
        if(binding==null)throw new AgentException("UNKNOWN_TOOL");
        return Map.of("name",tool,"description",binding.description());
    }
    /**
     * 단계 Skill 이 기록할 주문 처리 단계 종류다. 바인딩이 없는 이름은 RECOVERY 다.
     * OrderHarness 가 단계를 시작할 때와 실패를 기록할 때 불러,
     * order-agent/backend/src/main/java/com/capstone4/orderagent/service/OrderLifecycleService.java 의 fail 이 어느 단계를 FAILED 로 두고
     * 그 뒤 어느 단계를 SKIPPED 로 남길지 정하게 한다.
     */
    public static ProcessingStepType stepTypeFor(String skill){
        Binding binding=BY_SKILL.get(skill);
        return binding==null?ProcessingStepType.RECOVERY:binding.orderStep();
    }
    /** 배송 정보 Map 에서 국가와 빠른 배송 여부를 꺼낸다. Map 이 아니면 CONTRACT_VIOLATION 이다. */
    private static void shippingArguments(Map<String,Object> context,Map<String,Object> args){
        Object shipping=context.get("order.shipping");
        if(!(shipping instanceof Map<?,?> s))throw new AgentException("CONTRACT_VIOLATION");
        args.put("country",s.get("country"));args.put("express",s.get("express"));
    }
}
