package com.capstone4.orderagent.harness;

import java.util.concurrent.TimeUnit;

/** Execution-local limits, shared by all API attempts including transient retries. */
public class ExecutionControl {
    private final long start=System.nanoTime();
    private final long deadline;
    private final int maxCalls;
    private final long maxTokens;
    private int calls;
    private long observedTokens;
    public ExecutionControl(long timeoutMs,int maxCalls,long maxTokens){
        if(timeoutMs<1||maxCalls<0||maxTokens<1)throw new IllegalArgumentException("Invalid limits");
        this.deadline=start+TimeUnit.MILLISECONDS.toNanos(timeoutMs);this.maxCalls=maxCalls;this.maxTokens=maxTokens;
    }
    public void check(){if(System.nanoTime()>=deadline)throw new AgentException("DEADLINE_EXCEEDED");}
    public long remainingMs(){check();return Math.max(1,TimeUnit.NANOSECONDS.toMillis(deadline-System.nanoTime()));}
    public void attempt(){check();if(calls>=maxCalls)throw new AgentException("BUDGET_EXHAUSTED");calls++;}
    public void observe(Long input,Long output){
        observedTokens+=(input==null?0:input)+(output==null?0:output);
        if(observedTokens>maxTokens)throw new AgentException("TOKEN_LIMIT_EXCEEDED");check();
    }
    public long elapsedMs(){return TimeUnit.NANOSECONDS.toMillis(System.nanoTime()-start);}
}
