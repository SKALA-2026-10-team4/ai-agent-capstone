package com.capstone4.orderagent.harness;

import java.util.*;
import com.capstone4.orderagent.service.OrderToolService;
import com.capstone4.orderagent.trace.TraceService;
import org.springframework.stereotype.Component;

/**
 * 단계 Skill 이 요청한 도구를 업무 서비스에 넘기기 전의 마지막 관문이다. TOOL_REQUEST 를 먼저 남기고, 요청 도구가 ToolCatalog 에서
 * 그 단계에 묶인 도구인지 확인한 뒤(TOOL_NOT_ALLOWED) 업무 서비스의 execute 에 넘긴다. 실패하면 TOOL_ERROR 를 남기고
 * AgentException 으로 다시 던진다. 업무 서비스는 order-agent/backend/src/main/java/com/capstone4/orderagent/service/OrderToolService.java 다.
 * 거절된 요청도 TOOL_REQUEST 로 남기므로 모델이 무엇을 부르려 했는지 진단할 수 있다.
 * skillops-agent/skillops_agent/contracts.py 의 tools_called 가 TOOL_REQUEST 의 tool 값을 모아 실제로 요청한 도구 목록으로 쓴다.
 * OrderHarness.runStep 이 부른다.
 */
@Component
public class ToolDispatcher {
    private final OrderToolService business;
    private final TraceService trace;
    /** 업무 도구 서비스와 trace 기록기를 주입받는다. */
    public ToolDispatcher(OrderToolService business,TraceService trace){this.business=business;this.trace=trace;}
    /**
     * 도구 요청 하나를 기록하고 검사한 뒤 실행한다. 마감 시각은 기록 전과 실행 직전에 두 번 확인한다.
     * AgentException 이 아닌 예외는 TOOL_ERROR category 로 바꾼다. 도구 트랜잭션은 OrderToolService.execute 가 따로 열므로
     * TOOL_REQUEST 와 TOOL_ERROR 는 도구 트랜잭션이 되돌아가도 남는다.
     */
    public Map<String,Object> execute(UUID execution,UUID order,SkillLoader.Skill skill,String tool,Map<String,Object> args,ExecutionControl control){
        control.check();
        trace.event(execution,"TOOL_REQUEST",skill.id(),Map.of("tool",tool,"arguments",args));
        try{
            if(!ToolCatalog.toolFor(skill.id()).equals(tool))throw new AgentException("TOOL_NOT_ALLOWED");
            control.check();return business.execute(execution,order,skill.id(),tool,args);
        }catch(RuntimeException e){
            String category=e instanceof AgentException a?a.category():"TOOL_ERROR";
            trace.event(execution,"TOOL_ERROR",skill.id(),Map.of("tool",tool,"arguments",args,"category",category));
            if(e instanceof AgentException a)throw a;
            throw new AgentException(category);
        }
    }
}
