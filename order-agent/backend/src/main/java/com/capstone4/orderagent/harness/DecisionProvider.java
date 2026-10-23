package com.capstone4.orderagent.harness;

import java.util.*;

/**
 * 엔트리 선택과 단계 도구 결정을 내리는 쪽의 교체 경계다. FixedDecisionProvider(fixed 모드, 기본값)와
 * OpenAiDecisionProvider(openai 모드) 가운데 order-agent.model-mode 설정에 맞는 빈 하나만 만들어지고,
 * SkillExecutor 가 그 빈을 주입받아 부른다. 실행 루프와 trace 이벤트 순서는 모드와 무관하게 같고,
 * fixed 가 아닌 모드에서만 시도마다 MODEL_REQUEST 가 더 남는다.
 */
public interface DecisionProvider {
    /** Question.purpose 값이다. OpenAiDecisionProvider 의 시스템 프롬프트가 같은 이름으로 목적별 응답 모양을 알려 준다. */
    String PURPOSE_SELECT = "select";
    String PURPOSE_TOOL = "tool";
    /**
     * 제공자에게 보내는 결정 질문이다. openai 모드에서는 이 레코드를 Json.write 로 바꾼 JSON 이 그대로 사용자 메시지가 되므로,
     * 모델이 보아도 되는 값만 담는다. 채점 기대값이나 정답 도구 목록 같은 평가 정보는 넣지 않는다.
     * select 질문은 후보 목록과 감춘 키를 뺀 초기 컨텍스트를, tool 질문은 본문까지 담은 단계 Skill 레코드, 그 Skill 의 선언 입력,
     * 묶인 도구 정의를 담는다. SkillExecutor 가 purpose, inputs, toolDefinition 과 Skill 해시를 DECISION_INPUT 으로 남긴다.
     */
    record Question(String purpose, List<Map<String,Object>> candidates, SkillLoader.Skill skill,
                    Map<String,Object> inputs, Map<String,Object> toolDefinition) {
        /** 후보 가운데 엔트리 Skill 하나를 고르게 하는 질문을 만든다. OrderHarness.selectEntry 가 부른다. */
        public static Question select(List<Map<String,Object>> candidates, Map<String,Object> inputs) {
            return new Question(PURPOSE_SELECT, candidates, null, inputs, Map.of());
        }
        /** 단계 Skill 에 묶인 도구와 인자를 정하게 하는 질문을 만든다. OrderHarness.runStep 이 부른다. */
        public static Question tool(SkillLoader.Skill skill, Map<String,Object> inputs, Map<String,Object> toolDefinition) {
            return new Question(PURPOSE_TOOL, List.of(), skill, inputs, toolDefinition);
        }
    }
    /** 제공자 응답 원문과 보고된 토큰 수다. 토큰 수를 모르면 null 이다. SkillExecutor 가 원문을 MODEL_RESPONSE 로 남기고 JSON 으로 해석한다. */
    record Reply(String raw, Long inputTokens, Long outputTokens) {}
    /**
     * 질문 하나에 답한다. 응답 원문은 select 면 skill 키 하나, tool 이면 tool 과 arguments 키를 가진 JSON 객체여야 한다.
     * 실패를 알릴 때는 category 를 담은 AgentException 을 던진다.
     */
    Reply decide(Question question);
    /** 실행 기록의 model_mode 에 남길 모드 이름이다. SkillExecutor 는 이 값이 fixed 인지 보고 API 예산과 호출 타임아웃을 건너뛸지 정한다. */
    String mode();
}
