package com.capstone4.orderagent.harness;

import java.util.concurrent.TimeUnit;

/**
 * 실행 하나의 한도(마감 시각, API 호출 예산, 관측 토큰 상한)를 들고 검사한다. 일시 오류 재시도를 포함한 모든 API 시도가 같은 예산을
 * 나눠 쓰므로 재시도로 한도를 넘을 수 없다.
 * OrderHarness.execute 가 실행마다 order-agent/backend/src/main/resources/application.yml 의 order-agent.execution-timeout-ms,
 * order-agent.max-api-calls, order-agent.max-observed-tokens 로 하나를 만들고, OrderHarness 의 실행 루프와 SkillExecutor,
 * ToolDispatcher 가 단계마다 검사한다.
 * 필드에 동기화가 없으므로 실행 스레드 하나에서만 쓴다.
 * order-agent/backend/src/test/java/com/capstone4/orderagent/harness 의 SkillBoundaryTest 와 DecisionLimitTest 가 예산 규칙을 고정한다.
 */
public class ExecutionControl {
    // 실행을 시작한 System.nanoTime 값이다. 마감과 경과 시간의 기준이다.
    private final long start=System.nanoTime();
    private final long deadline;
    private final int maxCalls;
    private final long maxTokens;
    private int calls;
    private long observedTokens;
    /**
     * 한도로 실행 제어를 만든다. timeoutMs 와 maxTokens 는 1 이상, maxCalls 는 0 이상이어야 하며 아니면 IllegalArgumentException 이다.
     * maxCalls 가 0 이면 원격 API 호출을 하나도 허용하지 않고, fixed 모드는 예산을 쓰지 않으므로 그대로 돈다.
     */
    public ExecutionControl(long timeoutMs,int maxCalls,long maxTokens){
        if(timeoutMs<1||maxCalls<0||maxTokens<1)throw new IllegalArgumentException("Invalid limits");
        this.deadline=start+TimeUnit.MILLISECONDS.toNanos(timeoutMs);this.maxCalls=maxCalls;this.maxTokens=maxTokens;
    }
    /** 마감 시각이 지났으면 DEADLINE_EXCEEDED 를 던진다. 실행 루프가 단계 사이와 결정, 도구 실행 앞뒤에서 부른다. */
    public void check(){if(System.nanoTime()>=deadline)throw new AgentException("DEADLINE_EXCEEDED");}
    /**
     * 남은 시간을 밀리초로 돌려준다. 마감이 지났으면 DEADLINE_EXCEEDED 이고, 1ms 보다 적게 남아도 1 을 돌려준다.
     * SkillExecutor 가 원격 응답을 기다릴 시간을 정할 때 쓴다.
     */
    public long remainingMs(){check();return Math.max(1,TimeUnit.NANOSECONDS.toMillis(deadline-System.nanoTime()));}
    /** API 호출 하나를 예산에서 뺀다. 마감이 지났으면 DEADLINE_EXCEEDED, 예산을 다 썼으면 BUDGET_EXHAUSTED 를 던지고 호출 수를 늘리지 않는다. */
    public void attempt(){check();if(calls>=maxCalls)throw new AgentException("BUDGET_EXHAUSTED");calls++;}
    /**
     * 모델이 보고한 입력 토큰과 출력 토큰을 누적한다. null 은 0 으로 센다. 누적이 상한을 넘으면 TOKEN_LIMIT_EXCEEDED,
     * 넘지 않았는데 마감이 지났으면 DEADLINE_EXCEEDED 다. 토큰 수는 응답을 받은 뒤에야 알 수 있으므로 이미 쓴 호출을 막지는 못하고
     * 다음 호출을 멈추는 상한으로 동작한다.
     */
    public void observe(Long input,Long output){
        observedTokens+=(input==null?0:input)+(output==null?0:output);
        if(observedTokens>maxTokens)throw new AgentException("TOKEN_LIMIT_EXCEEDED");check();
    }
    /** 실행을 시작한 뒤 지난 시간을 밀리초로 돌려준다. 실행 종료 기록의 elapsed_ms 로 쓴다. */
    public long elapsedMs(){return TimeUnit.NANOSECONDS.toMillis(System.nanoTime()-start);}
}
