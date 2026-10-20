package com.capstone4.orderagent.harness;

import java.util.*;
import com.capstone4.orderagent.service.OrderToolService;
import com.capstone4.orderagent.trace.TraceService;
import org.springframework.stereotype.Component;

@Component
public class ToolDispatcher {
    private final OrderToolService business;
    private final TraceService trace;
    public ToolDispatcher(OrderToolService business,TraceService trace){this.business=business;this.trace=trace;}
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
