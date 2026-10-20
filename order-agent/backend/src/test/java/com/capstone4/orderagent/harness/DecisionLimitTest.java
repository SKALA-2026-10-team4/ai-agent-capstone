package com.capstone4.orderagent.harness;

import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import com.capstone4.orderagent.trace.TraceService;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class DecisionLimitTest {
    DecisionProvider.Question question(){return new DecisionProvider.Question("select",List.of(),null,Map.of(),Map.of());}
    @Test void transientApiRetryCountsEveryCallAndUsesSameQuestion(){
        var calls=new AtomicInteger();var trace=mock(TraceService.class);
        DecisionProvider provider=new DecisionProvider(){
            public String mode(){return "openai";}
            public Reply decide(Question q){if(calls.incrementAndGet()==1)throw new AgentException("API_RATE_LIMIT");return new Reply("{\"skill\":\"order-flow\"}",10L,4L);}
        };
        var executor=new SkillExecutor(provider,trace,1000);UUID id=UUID.randomUUID();
        try{
            assertThat(executor.decide(id,question(),new ExecutionControl(5000,2,100)).get("skill")).isEqualTo("order-flow");
            assertThat(calls).hasValue(2);verify(trace).apiAttempt(id,null,"select",1);verify(trace).apiAttempt(id,null,"select",2);
        }finally{executor.close();}
    }
    @Test void authenticationAndWrongChoicesAreNeverRetried(){
        var provider=mock(DecisionProvider.class);when(provider.mode()).thenReturn("openai");
        when(provider.decide(any())).thenThrow(new AgentException("API_AUTH"));
        var executor=new SkillExecutor(provider,mock(TraceService.class),1000);
        try{assertThatThrownBy(()->executor.decide(UUID.randomUUID(),question(),new ExecutionControl(5000,5,100))).hasMessage("API_AUTH");verify(provider,times(1)).decide(any());}
        finally{executor.close();}
        var wrong=mock(DecisionProvider.class);when(wrong.mode()).thenReturn("openai");when(wrong.decide(any())).thenReturn(new DecisionProvider.Reply("{\"skill\":\"wrong-choice\"}",1L,1L));
        var second=new SkillExecutor(wrong,mock(TraceService.class),1000);
        try{assertThat(second.decide(UUID.randomUUID(),question(),new ExecutionControl(5000,5,100)).get("skill")).isEqualTo("wrong-choice");verify(wrong,times(1)).decide(any());}
        finally{second.close();}
    }
    @Test void retryCannotBypassOverallApiBudget(){
        var provider=mock(DecisionProvider.class);when(provider.mode()).thenReturn("openai");when(provider.decide(any())).thenThrow(new AgentException("API_TRANSIENT"));
        var executor=new SkillExecutor(provider,mock(TraceService.class),1000);
        try{assertThatThrownBy(()->executor.decide(UUID.randomUUID(),question(),new ExecutionControl(5000,1,100))).hasMessage("BUDGET_EXHAUSTED");verify(provider,times(1)).decide(any());}
        finally{executor.close();}
    }
}
