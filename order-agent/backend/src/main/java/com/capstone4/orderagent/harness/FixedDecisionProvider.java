package com.capstone4.orderagent.harness;

import java.util.Map;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * 기본 fixed 모드의 결정 제공자다. 모델 없이 규칙으로 답한다. select 질문에는 늘 OrderFlowPolicy.ROOT_FLOW_ID 를, 그 밖의 질문에는
 * ToolCatalog 에서 그 단계에 묶인 도구와 단계 입력으로 채운 인자를 돌려준다.
 * Skill 본문은 읽지 않으므로 본문에만 적은 정책은 지키지 않고, front matter 계약(steps, inputs, outputs, depends_on)만 실행에 영향을 준다.
 * front matter 의 실행 규칙(rules)은 이 제공자가 아니라 OrderHarness 가 결정 요청 전에 평가하므로 fixed 모드에서도 지켜진다.
 * 비용 없이 Skill 선택, 로드, 입출력, 오류 분기를 재현하려고 둔다.
 * order-agent/backend/src/test/java/com/capstone4/orderagent/integration/AgentEvidenceIntegrationTest.java 의
 * bodyOnlyPrepaidPolicyIsDeliveredButNotEnforcedByFixedMode 가 본문 정책을 지키지 않는 동작을 보여 준다.
 * order-agent.model-mode 가 fixed 이거나 설정이 없으면 Spring 이 빈으로 만들어 SkillExecutor 에 주입한다.
 */
@Component
@ConditionalOnProperty(name="order-agent.model-mode", havingValue=FixedDecisionProvider.MODE, matchIfMissing=true)
public class FixedDecisionProvider implements DecisionProvider {
    /**
     * order-agent.model-mode 값이자 실행 기록 model_mode 에 쓰는 이름이며 mode 가 이 값을 돌려준다.
     * SkillExecutor 와 OrderHarness 가 이 값으로 fixed 모드를 알아본다.
     */
    public static final String MODE="fixed";
    /**
     * fixed 모드 실행 기록의 model 칸에 남기는 라벨이다.
     * order-agent/backend/src/test/java/com/capstone4/orderagent/integration/TraceContractGolden.java 의 실행 요약 줄이 이 값을 고정한다.
     */
    public static final String MODEL_LABEL="fixed-policy-v1";
    /**
     * select 질문이면 루트 flow 를 고르고, 그 밖의 질문이면 단계 Skill 에 묶인 도구와 ToolCatalog.arguments 로 채운 인자를 JSON 으로 돌려준다.
     * 토큰 수는 없으므로 null 이다. 단계 Skill 이 인자에 필요한 값을 inputs 에 선언하지 않으면 그 인자가 null 로 채워져
     * order-agent/backend/src/main/java/com/capstone4/orderagent/service/OrderToolService.java 의 서버 대조에서
     * INVALID_TOOL_ARGUMENTS 로 멈춘다. 배송 단계가 order.shipping 을 선언하지 않았으면 ToolCatalog.arguments 가
     * 응답을 만들기 전에 CONTRACT_VIOLATION 을 던진다.
     */
    public Reply decide(Question q){
        if(q.purpose().equals(PURPOSE_SELECT))return new Reply(Json.write(Map.of("skill",OrderFlowPolicy.ROOT_FLOW_ID)),null,null);
        String tool=ToolCatalog.toolFor(q.skill().id());
        return new Reply(Json.write(Map.of("tool",tool,"arguments",ToolCatalog.arguments(tool,q.inputs()))),null,null);
    }
    public String mode(){return MODE;}
}
