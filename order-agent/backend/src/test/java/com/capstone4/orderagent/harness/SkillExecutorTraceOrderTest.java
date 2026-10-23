package com.capstone4.orderagent.harness;

import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;
import com.capstone4.orderagent.trace.TraceService;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * order-agent/backend/src/main/java/com/capstone4/orderagent/harness/SkillExecutor.java 의 결정 요청 trace 기록 순서, 재시도 판정,
 * 호출 타임아웃과 마감 처리를 고정하는 특성 테스트다. DECISION_INPUT, MODEL_REQUEST, MODEL_RESPONSE, DECISION_ERROR 의 순서와 data 는
 * 모듈 밖에서 읽는 trace 계약이므로, 이 테스트가 깨지면
 * order-agent/backend/src/main/java/com/capstone4/orderagent/harness/OrderHarness.java 클래스 주석에 적힌 소비자도 함께 확인한다.
 * order-agent/backend/src/main/java/com/capstone4/orderagent/trace/TraceService.java 는 mock 이라 DB 없이 돈다.
 */
class SkillExecutorTraceOrderTest {
    final UUID id=UUID.randomUUID();
    final TraceService trace=mock(TraceService.class);
    final AtomicInteger calls=new AtomicInteger();
    final SkillLoader.Skill payment=new SkillLoader.Skill("payment","결제",1,List.of("order.id"),List.of("payment.id"),
        List.of(),List.of(),"본문","skills/payment/SKILL.md","payment-hash","재무팀");

    /** 엔트리 선택 질문 하나를 만든다. */
    DecisionProvider.Question select(){return new DecisionProvider.Question("select",List.of(),null,Map.of("order.id","o-1"),Map.of());}
    /** 지정한 모드로 동작하고 호출 횟수를 세는 결정 제공자를 만든다. */
    DecisionProvider provider(String mode,Function<DecisionProvider.Question,DecisionProvider.Reply> answer){
        return new DecisionProvider(){
            public String mode(){return mode;}
            public Reply decide(Question q){calls.incrementAndGet();return answer.apply(q);}
        };
    }
    /** 결정 입력 이벤트 data 를 만든다. */
    Map<String,Object> input(String purpose,Map<String,Object> inputs,Map<String,Object> definition,String hash){
        return Map.of("purpose",purpose,"inputs",inputs,"tool_definition",definition,"skill_hash",hash);
    }

    /** fixed 모드는 API 예산과 호출 타임아웃을 거치지 않고 응답을 기록한다. */
    @Test void fixedModeBypassesBudgetAndCallTimeout(){
        var executor=new SkillExecutor(provider("fixed",q->new DecisionProvider.Reply("{\"skill\":\"order-flow\"}",null,null)),trace,1);
        try{
            assertThat(executor.decide(id,select(),new ExecutionControl(5000,0,100))).isEqualTo(Map.of("skill","order-flow"));
            var order=inOrder(trace);
            order.verify(trace).event(id,"DECISION_INPUT",null,input("select",Map.of("order.id","o-1"),Map.of(),""));
            order.verify(trace).modelResult(id,null,"{\"skill\":\"order-flow\"}",null,null);
            verifyNoMoreInteractions(trace);
            assertThat(calls).hasValue(1);
            assertThat(executor.mode()).isEqualTo("fixed");
        }finally{executor.close();}
    }
    /** 단계 결정은 Skill id 로 기록하고 Skill 해시와 도구 정의를 결정 입력에 남긴다. */
    @Test void stepDecisionRecordsSkillIdHashAndToolDefinition(){
        var executor=new SkillExecutor(provider("fixed",q->new DecisionProvider.Reply("{\"tool\":\"payment.charge\",\"arguments\":{}}",2L,null)),trace,1000);
        try{
            var definition=Map.<String,Object>of("name","payment.charge","description","d");
            var question=new DecisionProvider.Question("tool",List.of(),payment,Map.of("order.id","o-1"),definition);
            assertThat(executor.decide(id,question,new ExecutionControl(5000,0,100))).containsEntry("tool","payment.charge");
            var order=inOrder(trace);
            order.verify(trace).event(id,"DECISION_INPUT","payment",input("tool",Map.of("order.id","o-1"),definition,"payment-hash"));
            order.verify(trace).modelResult(id,"payment","{\"tool\":\"payment.charge\",\"arguments\":{}}",2L,null);
            verifyNoMoreInteractions(trace);
        }finally{executor.close();}
    }
    /** JSON 이 아닌 응답은 결정 오류를 남기고 재시도하지 않는다. */
    @Test void malformedReplyIsRecordedAndNeverRetried(){
        var executor=new SkillExecutor(provider("openai",q->new DecisionProvider.Reply("not json",1L,1L)),trace,1000);
        try{
            assertThatThrownBy(()->executor.decide(id,select(),new ExecutionControl(5000,5,100)))
                .isInstanceOf(AgentException.class).hasMessage("MODEL_FORMAT_ERROR");
            var order=inOrder(trace);
            order.verify(trace).event(eq(id),eq("DECISION_INPUT"),isNull(),anyMap());
            order.verify(trace).apiAttempt(id,null,"select",1);
            order.verify(trace).modelResult(id,null,"not json",1L,1L);
            order.verify(trace).event(id,"DECISION_ERROR",null,Map.of("category","MODEL_FORMAT_ERROR","attempt",1));
            verifyNoMoreInteractions(trace);
            assertThat(calls).hasValue(1);
        }finally{executor.close();}
    }
    /** 관측 토큰 한도를 넘기면 응답을 먼저 기록한 뒤 재시도 없이 TOKEN_LIMIT_EXCEEDED 다. */
    @Test void tokenLimitIsRecordedAfterTheReplyAndNeverRetried(){
        var executor=new SkillExecutor(provider("openai",q->new DecisionProvider.Reply("{\"skill\":\"order-flow\"}",60L,50L)),trace,1000);
        try{
            assertThatThrownBy(()->executor.decide(id,select(),new ExecutionControl(5000,5,100)))
                .isInstanceOf(AgentException.class).hasMessage("TOKEN_LIMIT_EXCEEDED");
            var order=inOrder(trace);
            order.verify(trace).event(eq(id),eq("DECISION_INPUT"),isNull(),anyMap());
            order.verify(trace).apiAttempt(id,null,"select",1);
            order.verify(trace).modelResult(id,null,"{\"skill\":\"order-flow\"}",60L,50L);
            order.verify(trace).event(id,"DECISION_ERROR",null,Map.of("category","TOKEN_LIMIT_EXCEEDED","attempt",1));
            verifyNoMoreInteractions(trace);
            assertThat(calls).hasValue(1);
        }finally{executor.close();}
    }
    /** 호출 타임아웃은 일시 오류로 한 번 재시도하고 두 번째에도 넘으면 API_TIMEOUT 이다. */
    @Test void callTimeoutIsRetriedOnceThenRaised(){
        var executor=new SkillExecutor(provider("openai",q->{
            try{Thread.sleep(2000);}catch(InterruptedException e){Thread.currentThread().interrupt();}
            return new DecisionProvider.Reply("{}",null,null);
        }),trace,50);
        try{
            assertThatThrownBy(()->executor.decide(id,select(),new ExecutionControl(10000,5,100)))
                .isInstanceOf(AgentException.class).hasMessage("API_TIMEOUT");
            var order=inOrder(trace);
            order.verify(trace).event(eq(id),eq("DECISION_INPUT"),isNull(),anyMap());
            order.verify(trace).apiAttempt(id,null,"select",1);
            order.verify(trace).event(id,"DECISION_ERROR",null,Map.of("category","API_TIMEOUT","attempt",1));
            order.verify(trace).apiAttempt(id,null,"select",2);
            order.verify(trace).event(id,"DECISION_ERROR",null,Map.of("category","API_TIMEOUT","attempt",2));
            verifyNoMoreInteractions(trace);
        }finally{executor.close();}
    }
    /** 일시 오류 재시도는 결정 입력, 시도 1, 결정 오류, 시도 2, 응답 순서로 기록한다. */
    @Test void transientRetryRecordsAttemptsInOrder(){
        var executor=new SkillExecutor(provider("openai",q->{
            if(calls.get()==1)throw new AgentException("API_TRANSIENT");
            return new DecisionProvider.Reply("{\"skill\":\"order-flow\"}",3L,2L);
        }),trace,1000);
        try{
            assertThat(executor.decide(id,select(),new ExecutionControl(5000,5,100))).isEqualTo(Map.of("skill","order-flow"));
            var order=inOrder(trace);
            order.verify(trace).event(eq(id),eq("DECISION_INPUT"),isNull(),anyMap());
            order.verify(trace).apiAttempt(id,null,"select",1);
            order.verify(trace).event(id,"DECISION_ERROR",null,Map.of("category","API_TRANSIENT","attempt",1));
            order.verify(trace).apiAttempt(id,null,"select",2);
            order.verify(trace).modelResult(id,null,"{\"skill\":\"order-flow\"}",3L,2L);
            verifyNoMoreInteractions(trace);
            assertThat(calls).hasValue(2);
        }finally{executor.close();}
    }
    /** 원격 모드의 일반 예외는 API_ERROR 이고 재시도하지 않는다. fixed 모드에서는 결정 오류 없이 그대로 나간다. */
    @Test void genericProviderFailureDependsOnMode(){
        var remote=new SkillExecutor(provider("openai",q->{throw new IllegalStateException("boom");}),trace,1000);
        try{
            assertThatThrownBy(()->remote.decide(id,select(),new ExecutionControl(5000,5,100)))
                .isInstanceOf(AgentException.class).hasMessage("API_ERROR");
            verify(trace).event(id,"DECISION_ERROR",null,Map.of("category","API_ERROR","attempt",1));
            assertThat(calls).hasValue(1);
        }finally{remote.close();}
        var fixedTrace=mock(TraceService.class);
        var fixed=new SkillExecutor(provider("fixed",q->{throw new IllegalStateException("boom");}),fixedTrace,1000);
        try{
            assertThatThrownBy(()->fixed.decide(id,select(),new ExecutionControl(5000,5,100)))
                .isInstanceOf(IllegalStateException.class).hasMessage("boom");
            verify(fixedTrace).event(eq(id),eq("DECISION_INPUT"),isNull(),anyMap());
            verifyNoMoreInteractions(fixedTrace);
        }finally{fixed.close();}
    }
    /** 시도 전에 마감이 지났으면 결정 오류 없이 DEADLINE_EXCEEDED 이고 제공자를 부르지 않는다. */
    @Test void deadlineBeforeTheFirstAttemptSkipsDecisionError() throws Exception {
        var executor=new SkillExecutor(provider("openai",q->new DecisionProvider.Reply("{}",null,null)),trace,1000);
        try{
            var control=new ExecutionControl(1,5,100);
            Thread.sleep(20);
            assertThatThrownBy(()->executor.decide(id,select(),control))
                .isInstanceOf(AgentException.class).hasMessage("DEADLINE_EXCEEDED");
            verify(trace).event(eq(id),eq("DECISION_INPUT"),isNull(),anyMap());
            verifyNoMoreInteractions(trace);
            assertThat(calls).hasValue(0);
        }finally{executor.close();}
    }
    /** 원격 호출은 남은 시간을 확인하기 전에 제출되므로, 그 사이 마감이 지나도 호출은 나가고 DEADLINE_EXCEEDED 다. */
    @Test void remoteCallIsSubmittedBeforeTheRemainingTimeIsChecked() throws Exception {
        var started=new CountDownLatch(1);
        var executor=new SkillExecutor(provider("openai",q->{started.countDown();return new DecisionProvider.Reply("{}",null,null);}),trace,1000);
        doAnswer(invocation->{Thread.sleep(800);return null;}).when(trace).apiAttempt(any(),any(),any(),anyInt());
        try{
            assertThatThrownBy(()->executor.decide(id,select(),new ExecutionControl(500,5,100)))
                .isInstanceOf(AgentException.class).hasMessage("DEADLINE_EXCEEDED");
            assertThat(started.await(5,TimeUnit.SECONDS)).isTrue();
            var order=inOrder(trace);
            order.verify(trace).event(eq(id),eq("DECISION_INPUT"),isNull(),anyMap());
            order.verify(trace).apiAttempt(id,null,"select",1);
            order.verify(trace).event(id,"DECISION_ERROR",null,Map.of("category","DEADLINE_EXCEEDED","attempt",1));
            verifyNoMoreInteractions(trace);
        }finally{executor.close();}
    }
}
