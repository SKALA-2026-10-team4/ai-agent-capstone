package com.capstone4.orderagent.harness;

import java.util.*;
import java.util.concurrent.*;
import jakarta.annotation.PreDestroy;
import com.capstone4.orderagent.trace.TraceService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * 결정 제공자에게 질문 하나를 보내고 응답 JSON 을 Map 으로 돌려준다. 엔트리 선택과 단계별 도구 결정이 모두 이 클래스를 거친다.
 * 결정마다 DECISION_INPUT 을 남긴다. fixed 모드가 아니면 시도마다 API 예산을 쓰고 MODEL_REQUEST 를 남긴 뒤, 가상 스레드에서
 * 호출 타임아웃(order-agent/backend/src/main/resources/application.yml 의 order-agent.call-timeout-ms)을 걸고 기다린다.
 * 응답은 MODEL_RESPONSE 로 원문과 토큰 수를 남긴 뒤 관측 토큰 한도를 확인하고 JSON 으로 해석한다.
 * 일시 오류(OrderFlowPolicy.RETRYABLE_DECISION_ERRORS)는 OrderFlowPolicy.DECISION_MAX_ATTEMPTS 까지 다시 시도하며,
 * 실패한 시도마다 DECISION_ERROR 를 남긴다. 이 이벤트들은 OrderHarness 클래스 주석의 trace 계약에 속하고
 * order-agent/backend/src/test/java/com/capstone4/orderagent/harness/SkillExecutorTraceOrderTest.java 가 순서를 고정한다.
 * OrderHarness 가 selectEntry 와 runStep 에서 decide 를 부르고, Spring 이 order-agent.model-mode 에 맞는 DecisionProvider 빈
 * 하나(FixedDecisionProvider 또는 OpenAiDecisionProvider)를 주입한다.
 */
@Component
public class SkillExecutor {
    private final DecisionProvider provider;
    private final TraceService trace;
    private final long callTimeout;
    // 원격 결정 호출을 돌리는 가상 스레드 실행기다. 호출마다 새 가상 스레드를 쓴다.
    private final ExecutorService workers=Executors.newVirtualThreadPerTaskExecutor();
    /** 결정 제공자, trace 기록기, 원격 호출 하나의 타임아웃을 주입받는다. */
    public SkillExecutor(DecisionProvider provider,TraceService trace,@Value("${order-agent.call-timeout-ms}") long callTimeout){this.provider=provider;this.trace=trace;this.callTimeout=callTimeout;}
    /** Spring 이 빈을 내릴 때 남은 원격 호출 스레드를 중단한다. 테스트는 실행기를 다 쓴 뒤 직접 부른다. */
    @PreDestroy void close(){workers.shutdownNow();}
    /**
     * 결정 질문 하나를 보내고 응답 JSON 을 돌려준다. 단계 결정이면 Skill id 를 이벤트의 skill_id 로, Skill 해시를 DECISION_INPUT 에 남긴다.
     * 각 시도 직전에 마감을 확인하며, 여기서 마감이 지났으면 DECISION_ERROR 없이 DEADLINE_EXCEEDED 가 나간다.
     * 다시 시도할 수 없는 오류나 마지막 시도의 오류는 DECISION_ERROR 를 남긴 뒤 그대로 던진다.
     * fixed 모드에서 제공자가 AgentException 이 아닌 예외를 던지면 DECISION_ERROR 없이 그대로 나간다.
     */
    public Map<String,Object> decide(UUID executionId,DecisionProvider.Question question,ExecutionControl control){
        String skill=question.skill()==null?null:question.skill().id();
        trace.event(executionId,"DECISION_INPUT",skill,Map.of("purpose",question.purpose(),"inputs",question.inputs(),
            "tool_definition",question.toolDefinition(),"skill_hash",question.skill()==null?"":question.skill().hash()));
        for(int attempt=1;attempt<=OrderFlowPolicy.DECISION_MAX_ATTEMPTS;attempt++){
            control.check();
            try {
                DecisionProvider.Reply reply=requestReply(executionId,skill,question,control,attempt);
                trace.modelResult(executionId,skill,reply.raw(),reply.inputTokens(),reply.outputTokens());
                control.observe(reply.inputTokens(),reply.outputTokens());
                return parse(reply);
            }catch(AgentException e){
                trace.event(executionId,"DECISION_ERROR",skill,Map.of("category",e.category(),"attempt",attempt));
                if(attempt==OrderFlowPolicy.DECISION_MAX_ATTEMPTS||!OrderFlowPolicy.RETRYABLE_DECISION_ERRORS.contains(e.category()))throw e;
                control.check();
            }
        }
        // DECISION_MAX_ATTEMPTS 가 1 이상이면 반복문 안에서 반환하거나 던지므로 이 줄에는 닿지 않는다.
        throw new AgentException("API_ERROR");
    }
    /** 결정 제공자의 모드(fixed 또는 openai)다. OrderHarness 가 실행 기록의 model_mode 와 모델 이름을 정할 때 읽는다. */
    public String mode(){return provider.mode();}
    /**
     * fixed 모드는 제공자를 바로 부르고, 그 밖의 모드는 API 예산을 쓰고 시도를 기록한 뒤 원격 응답을 기다린다.
     * 예산이 바닥났으면 ExecutionControl.attempt 가 BUDGET_EXHAUSTED 를 던지므로 제공자를 부르지 않는다.
     */
    private DecisionProvider.Reply requestReply(UUID executionId,String skill,DecisionProvider.Question question,ExecutionControl control,int attempt){
        if(provider.mode().equals(FixedDecisionProvider.MODE))return provider.decide(question);
        control.attempt();trace.apiAttempt(executionId,skill,question.purpose(),attempt);
        return awaitRemote(question,control);
    }
    /**
     * 원격 결정을 먼저 제출한 뒤 호출 타임아웃과 남은 실행 시간 가운데 짧은 쪽만큼 기다린다. 제출한 뒤 남은 시간을 볼 때 마감이
     * 이미 지났으면 ExecutionControl.remainingMs 가 DEADLINE_EXCEEDED 를 던지고, 이때 제출한 호출은 취소하지 않는다.
     * 기다리는 시간을 넘기면 호출을 취소하고 API_TIMEOUT 을, 기다리던 스레드가 끊기면 호출을 취소하고 EXECUTION_INTERRUPTED 를 던진다.
     * 제공자가 던진 AgentException 은 그대로 다시 던지고 그 밖의 예외는 API_ERROR 로 바꾼다.
     */
    private DecisionProvider.Reply awaitRemote(DecisionProvider.Question question,ExecutionControl control){
        Future<DecisionProvider.Reply> future=workers.submit(()->provider.decide(question));
        try{return future.get(Math.min(callTimeout,control.remainingMs()),TimeUnit.MILLISECONDS);}
        catch(TimeoutException e){future.cancel(true);throw new AgentException("API_TIMEOUT");}
        catch(InterruptedException e){future.cancel(true);Thread.currentThread().interrupt();throw new AgentException("EXECUTION_INTERRUPTED");}
        catch(ExecutionException e){if(e.getCause() instanceof AgentException a)throw a;throw new AgentException("API_ERROR");}
    }
    /** 모델 응답 원문을 JSON 객체로 읽는다. 읽을 수 없으면 MODEL_FORMAT_ERROR 다. */
    private static Map<String,Object> parse(DecisionProvider.Reply reply){
        try{return Json.object(reply.raw());}catch(Exception e){throw new AgentException("MODEL_FORMAT_ERROR");}
    }
}
