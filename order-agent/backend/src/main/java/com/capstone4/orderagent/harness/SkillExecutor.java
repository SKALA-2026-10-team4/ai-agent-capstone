package com.capstone4.orderagent.harness;

import java.util.*;
import java.util.concurrent.*;
import jakarta.annotation.PreDestroy;
import com.capstone4.orderagent.trace.TraceService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public class SkillExecutor {
    private final DecisionProvider provider;
    private final TraceService trace;
    private final long callTimeout;
    private final ExecutorService workers=Executors.newVirtualThreadPerTaskExecutor();
    public SkillExecutor(DecisionProvider provider,TraceService trace,@Value("${order-agent.call-timeout-ms}") long callTimeout){this.provider=provider;this.trace=trace;this.callTimeout=callTimeout;}
    @PreDestroy void close(){workers.shutdownNow();}
    public Map<String,Object> decide(UUID executionId,DecisionProvider.Question question,ExecutionControl control){
        String skill=question.skill()==null?null:question.skill().id();
        trace.event(executionId,"DECISION_INPUT",skill,Map.of("purpose",question.purpose(),"inputs",question.inputs(),
            "tool_definition",question.toolDefinition(),"skill_hash",question.skill()==null?"":question.skill().hash()));
        for(int attempt=1;attempt<=2;attempt++){
            control.check();
            try {
                DecisionProvider.Reply reply;
                if(provider.mode().equals("fixed"))reply=provider.decide(question);
                else {
                    control.attempt();trace.apiAttempt(executionId,skill,question.purpose(),attempt);
                    Future<DecisionProvider.Reply> future=workers.submit(()->provider.decide(question));
                    try{reply=future.get(Math.min(callTimeout,control.remainingMs()),TimeUnit.MILLISECONDS);}
                    catch(TimeoutException e){future.cancel(true);throw new AgentException("API_TIMEOUT");}
                    catch(InterruptedException e){future.cancel(true);Thread.currentThread().interrupt();throw new AgentException("EXECUTION_INTERRUPTED");}
                    catch(ExecutionException e){if(e.getCause() instanceof AgentException a)throw a;throw new AgentException("API_ERROR");}
                }
                trace.modelResult(executionId,skill,reply.raw(),reply.inputTokens(),reply.outputTokens());
                control.observe(reply.inputTokens(),reply.outputTokens());
                try{return Json.object(reply.raw());}catch(Exception e){throw new AgentException("MODEL_FORMAT_ERROR");}
            }catch(AgentException e){
                trace.event(executionId,"DECISION_ERROR",skill,Map.of("category",e.category(),"attempt",attempt));
                if(attempt==2||!Set.of("API_TIMEOUT","API_RATE_LIMIT","API_TRANSIENT").contains(e.category()))throw e;
                control.check();
            }
        }
        throw new AgentException("API_ERROR");
    }
    public String mode(){return provider.mode();}
}
