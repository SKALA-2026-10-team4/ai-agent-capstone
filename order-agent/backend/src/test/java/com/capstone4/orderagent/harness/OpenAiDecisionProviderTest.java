package com.capstone4.orderagent.harness;

import java.time.Duration;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.ai.chat.messages.*;
import org.springframework.ai.chat.metadata.*;
import org.springframework.ai.chat.model.*;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.openai.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * order-agent/backend/src/main/java/com/capstone4/orderagent/harness/OpenAiDecisionProvider.java 의 메시지 구성, 호출 옵션,
 * 응답 변환과 오류 분류를 고정한다. ChatModel 은 mock 이라 실제 API 를 부르지 않는다.
 * 시스템 프롬프트 문구는 튜닝 대상이라 고정하지 않는다.
 */
class OpenAiDecisionProviderTest {
    final ChatModel model=mock(ChatModel.class);
    final OpenAiDecisionProvider provider=new OpenAiDecisionProvider(model,10000);

    /** 후보 하나를 가진 엔트리 선택 질문을 만든다. */
    DecisionProvider.Question select(){
        return new DecisionProvider.Question("select",List.of(Map.of("skill_id","order-flow","name","order-flow","description","흐름","version",1)),
            null,Map.of("order.id","o-1"),Map.of());
    }
    /** payment 단계의 도구 결정 질문을 만든다. */
    DecisionProvider.Question tool(){
        var skill=new SkillLoader.Skill("payment","결제",1,List.of("order.id"),List.of("payment.id"),List.of("inventory-check"),
            List.of(),"본문","skills/payment/SKILL.md","hash","재무팀");
        return new DecisionProvider.Question("tool",List.of(),skill,Map.of("order.id","o-1"),Map.of("name","payment.charge","description","d"));
    }
    /** 지정한 본문과 사용량을 가진 모델 응답을 만든다. */
    static ChatResponse response(String text,Usage usage){
        return new ChatResponse(List.of(new Generation(new AssistantMessage(text))),ChatResponseMetadata.builder().usage(usage).build());
    }
    /** 지정한 토큰 값을 null 까지 그대로 돌려주는 사용량이다. */
    static Usage usage(Integer prompt,Integer completion){
        return new Usage(){
            public Integer getPromptTokens(){return prompt;}
            public Integer getCompletionTokens(){return completion;}
            public Object getNativeUsage(){return null;}
        };
    }

    /** 시스템 지시 하나와 질문 JSON 하나를 보내고, 호출마다 재시도 0, 타임아웃, 출력 토큰 상한, JSON 응답 형식 옵션을 새로 붙인다. */
    @Test void sendsOneSystemInstructionAndTheQuestionJsonWithPerCallOptions(){
        var captor=ArgumentCaptor.forClass(Prompt.class);
        when(model.call(captor.capture())).thenReturn(response("{\"skill\":\"order-flow\"}",new DefaultUsage(10,4)));
        var select=select();var tool=tool();
        assertThat(provider.decide(select)).isEqualTo(new DecisionProvider.Reply("{\"skill\":\"order-flow\"}",10L,4L));
        provider.decide(tool);
        var prompts=captor.getAllValues();
        assertThat(prompts).hasSize(2);
        for(int i=0;i<2;i++){
            var messages=prompts.get(i).getInstructions();
            assertThat(messages).hasSize(2);
            assertThat(messages.get(0)).isInstanceOf(SystemMessage.class);
            assertThat(messages.get(0).getText()).isNotBlank();
            assertThat(messages.get(1)).isInstanceOf(UserMessage.class);
            assertThat(messages.get(1).getText()).isEqualTo(Json.write(i==0?select:tool));
            assertThat(prompts.get(i).getOptions()).isInstanceOf(OpenAiChatOptions.class);
            var options=(OpenAiChatOptions)prompts.get(i).getOptions();
            assertThat(options.getMaxRetries()).isZero();
            assertThat(options.getTimeout()).isEqualTo(Duration.ofMillis(10000));
            assertThat(options.getMaxCompletionTokens()).isEqualTo(512);
            assertThat(options.getResponseFormat().getType()).isEqualTo(OpenAiChatModel.ResponseFormat.Type.JSON_OBJECT);
        }
        assertThat(prompts.get(1).getInstructions().get(0).getText()).isEqualTo(prompts.get(0).getInstructions().get(0).getText());
        assertThat(prompts.get(1).getOptions()).isNotSameAs(prompts.get(0).getOptions());
        assertThat(provider.mode()).isEqualTo("openai");
    }
    /** 사용량이 없거나 토큰 값이 비어 있으면 null 로, 기본 메타데이터의 사용량은 0 으로 옮긴다. */
    @Test void tokenUsageIsCopiedAsReported(){
        doReturn(response("a",null)).when(model).call(any(Prompt.class));
        assertThat(provider.decide(select())).isEqualTo(new DecisionProvider.Reply("a",null,null));
        doReturn(response("b",usage(null,4))).when(model).call(any(Prompt.class));
        assertThat(provider.decide(select())).isEqualTo(new DecisionProvider.Reply("b",null,4L));
        doReturn(response("c",usage(7,null))).when(model).call(any(Prompt.class));
        assertThat(provider.decide(select())).isEqualTo(new DecisionProvider.Reply("c",7L,null));
        doReturn(new ChatResponse(List.of(new Generation(new AssistantMessage("d"))))).when(model).call(any(Prompt.class));
        assertThat(provider.decide(select())).isEqualTo(new DecisionProvider.Reply("d",0L,0L));
    }
    /** 응답이 없거나 생성 결과가 없으면 MODEL_FORMAT_ERROR 다. */
    @Test void missingResponseIsAFormatError(){
        doReturn(null).when(model).call(any(Prompt.class));
        assertThatThrownBy(()->provider.decide(select())).isInstanceOf(AgentException.class).hasMessage("MODEL_FORMAT_ERROR");
        doReturn(new ChatResponse(List.of())).when(model).call(any(Prompt.class));
        assertThatThrownBy(()->provider.decide(select())).isInstanceOf(AgentException.class).hasMessage("MODEL_FORMAT_ERROR");
    }
    /** 예외 클래스 이름을 인증, 속도 제한, 타임아웃, 일시 오류 순서로 보고 분류하며, cause 사슬은 가장 바깥 예외부터 본다. */
    @Test void failuresAreClassifiedBySimpleClassNameAlongTheCauseChain(){
        var cases=new LinkedHashMap<RuntimeException,String>();
        cases.put(new AuthenticationFailure(),"API_AUTH");
        cases.put(new UnauthorizedFailure(),"API_AUTH");
        cases.put(new PermissionDeniedFailure(),"API_AUTH");
        cases.put(new RateLimitFailure(),"API_RATE_LIMIT");
        cases.put(new TimeoutFailure(),"API_TIMEOUT");
        cases.put(new InternalServerFailure(),"API_TRANSIENT");
        cases.put(new ConnectionFailure(),"API_TRANSIENT");
        cases.put(new OpenAIIoFailure(),"API_TRANSIENT");
        cases.put(new IllegalStateException("other"),"API_ERROR");
        cases.put(new ConnectionTimeoutFailure(),"API_TIMEOUT");
        cases.put(new RateLimitTimeoutFailure(),"API_RATE_LIMIT");
        cases.put(new AuthenticationRateLimitFailure(),"API_AUTH");
        cases.put(new IllegalStateException("wrapped",new RateLimitFailure()),"API_RATE_LIMIT");
        cases.put(new ConnectionFailure(new AuthenticationFailure()),"API_TRANSIENT");
        cases.put(new IllegalStateException("deep",new IllegalArgumentException("mid",new TimeoutFailure())),"API_TIMEOUT");
        cases.forEach((failure,category)->{
            doThrow(failure).when(model).call(any(Prompt.class));
            assertThatThrownBy(()->provider.decide(select())).as(failure.getClass().getSimpleName())
                .isInstanceOf(AgentException.class).hasMessage(category);
        });
        var agent=new AgentException("BUDGET_EXHAUSTED");
        doThrow(agent).when(model).call(any(Prompt.class));
        assertThat(catchThrowable(()->provider.decide(select()))).isSameAs(agent);
    }

    // 아래 예외들은 클래스 단순 이름만 분류 규칙에 걸리도록 만든 가짜 API 예외다. 이름에 여러 단서를 섞어 검사 순서를 확인한다.
    static class AuthenticationFailure extends RuntimeException {}
    static class UnauthorizedFailure extends RuntimeException {}
    static class PermissionDeniedFailure extends RuntimeException {}
    static class RateLimitFailure extends RuntimeException {}
    static class TimeoutFailure extends RuntimeException {}
    static class InternalServerFailure extends RuntimeException {}
    static class OpenAIIoFailure extends RuntimeException {}
    static class ConnectionTimeoutFailure extends RuntimeException {}
    static class RateLimitTimeoutFailure extends RuntimeException {}
    static class AuthenticationRateLimitFailure extends RuntimeException {}
    /** 원인을 감쌀 수 있는 연결 실패 예외다. */
    static class ConnectionFailure extends RuntimeException {
        ConnectionFailure(){}
        ConnectionFailure(Throwable cause){super(cause);}
    }
}
