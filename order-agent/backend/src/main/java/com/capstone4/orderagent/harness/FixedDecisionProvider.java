package com.capstone4.orderagent.harness;

import java.util.Map;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name="order-agent.model-mode", havingValue="fixed", matchIfMissing=true)
public class FixedDecisionProvider implements DecisionProvider {
    public Reply decide(Question q){
        if(q.purpose().equals("select"))return new Reply(Json.write(Map.of("skill","order-flow")),null,null);
        String tool=ToolCatalog.toolFor(q.skill().id());
        return new Reply(Json.write(Map.of("tool",tool,"arguments",ToolCatalog.arguments(tool,q.inputs()))),null,null);
    }
    public String mode(){return "fixed";}
}
