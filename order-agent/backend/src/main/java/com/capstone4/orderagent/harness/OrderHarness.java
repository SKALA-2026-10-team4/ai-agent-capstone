package com.capstone4.orderagent.harness;

import java.util.*;
import com.capstone4.orderagent.service.*;
import com.capstone4.orderagent.entity.enums.ProcessingStepType;
import com.capstone4.orderagent.trace.TraceService;
import com.capstone4.orderagent.workspace.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public class OrderHarness {
    private final SnapshotService snapshots;
    private final SkillLoader loader;
    private final SkillExecutor decisions;
    private final ToolDispatcher tools;
    private final OrderToolService business;
    private final OrderLifecycleService lifecycle;
    private final TraceService trace;
    private final long timeout,maxTokens;
    private final int maxCalls;
    private final String model;
    public OrderHarness(SnapshotService snapshots,SkillLoader loader,SkillExecutor decisions,ToolDispatcher tools,
        OrderToolService business,OrderLifecycleService lifecycle,TraceService trace,
        @Value("${order-agent.execution-timeout-ms}") long timeout,@Value("${order-agent.max-api-calls}") int maxCalls,
        @Value("${order-agent.max-observed-tokens}") long maxTokens,@Value("${spring.ai.openai.chat.model}") String model){
        this.snapshots=snapshots;this.loader=loader;this.decisions=decisions;this.tools=tools;this.business=business;
        this.lifecycle=lifecycle;this.trace=trace;this.timeout=timeout;this.maxCalls=maxCalls;this.maxTokens=maxTokens;this.model=model;
    }
    // TODO(A 연동): 공용 Harness가 제공되면 이 실행 경계를 해당 구현에 연결한다.
    // 유지할 계약: 동일한 snapshot 소스로 후보 선택·단계 로드, 실제 tool dispatch에서 trace 기록.
    // TODO(C-2 연동): SingleAgentAdapter/Runner도 이 진입점을 호출하도록 연결한다.
    // Runner의 기대값/정답 도구 목록은 DecisionProvider.Question에 추가하지 않는다.
    @SuppressWarnings("unchecked")
    public void execute(UUID orderId){
        var control=new ExecutionControl(timeout,maxCalls,maxTokens);
        UUID execution=trace.begin(orderId,decisions.mode(),decisions.mode().equals("fixed")?"fixed-policy-v1":model);
        ProcessingStepType current=ProcessingStepType.RECOVERY;
        try{
            var initial=business.initial(orderId);
            var snapshot=initial.requestedSnapshotId()==null?snapshots.capture():snapshots.get(initial.requestedSnapshotId());
            trace.fixSnapshot(execution,snapshot.id(),snapshot.hash());
            var files=snapshot.files();var candidates=loader.candidates(files);
            trace.event(execution,"CANDIDATES_OFFERED",null,Map.of("candidates",candidates));
            var context=new LinkedHashMap<>(initial.context());context.remove("order.total");
            var selection=decisions.decide(execution,new DecisionProvider.Question("select",candidates,null,Map.copyOf(context),Map.of()),control);
            Object raw=selection.get("skill");
            if(!(raw instanceof String name)){trace.selection(execution,Json.write(raw),null);throw new AgentException("MODEL_FORMAT_ERROR");}
            trace.selection(execution,name,null);
            if(name.equals("none")){
                business.noSelection(orderId);trace.finish(execution,"COMPLETED","REJECTED","NO_SELECTION",control.elapsedMs());return;
            }
            SkillLoader.Skill flow;
            try{flow=loader.load(files,name);}catch(AgentException e){
                trace.event(execution,"SKILL_LOAD_REJECTED",null,Map.of("requested_name",name,"category",e.category()));throw e;
            }
            trace.selection(execution,name,flow.id());loaded(execution,flow);
            if(!flow.id().equals("order-flow"))throw new AgentException("ROOT_SKILL_MISMATCH");
            var steps=loader.validateFlow(files,flow);
            for(var step:steps)ToolCatalog.toolFor(step.id());
            trace.event(execution,"FLOW_VALIDATED",flow.id(),Map.of("steps",flow.steps()));
            var completed=new HashSet<String>();
            for(var definition:steps){
                control.check();var skill=loader.load(files,definition.id());loaded(execution,skill);
                current=stepType(skill.id());
                loader.validateInputs(skill,context.keySet(),completed,"runtime_validation");
                var inputs=new LinkedHashMap<String,Object>();for(var key:skill.inputs())inputs.put(key,context.get(key));
                var decision=decisions.decide(execution,new DecisionProvider.Question("tool",List.of(),skill,inputs,
                    ToolCatalog.definition(ToolCatalog.toolFor(skill.id()))),control);
                if(!(decision.get("tool") instanceof String tool)||!(decision.get("arguments") instanceof Map<?,?>))throw new AgentException("MODEL_FORMAT_ERROR");
                Map<String,Object> args=(Map<String,Object>)decision.get("arguments");
                var actual=tools.execute(execution,orderId,skill,tool,args,control);
                control.check();
                var outputs=(Map<String,Object>)actual.get("context");
                if(!actual.get("final_status").equals("PROCESSING")&&!actual.get("final_status").equals("SHIPPED")){
                    trace.event(execution,"BUSINESS_STOP",skill.id(),Map.of("final_status",actual.get("final_status")));
                    trace.finish(execution,"COMPLETED",business.status(orderId),null,control.elapsedMs());return;
                }
                if(!outputs.keySet().containsAll(skill.outputs()))throw new AgentException("MISSING_SKILL_OUTPUT");
                for(var key:skill.outputs())context.put(key,outputs.get(key));
                completed.add(skill.id());
                trace.event(execution,"SKILL_COMPLETED",skill.id(),Map.of("declared_output",project(outputs,skill.outputs())));
            }
            if(!completed.contains("notify"))throw new AgentException("INCOMPLETE_FLOW");
            control.check();business.complete(orderId,execution,control.elapsedMs());
        }catch(RuntimeException error){
            String category=error instanceof AgentException a?a.category():"EXECUTION_ERROR";
            var details=new LinkedHashMap<String,Object>();details.put("category",category);
            if(error instanceof AgentException agent)details.putAll(agent.details());
            String failedSkill=details.get("skill_id") instanceof String id?id:null;
            if(failedSkill!=null)current=stepType(failedSkill);
            business.compensateUnpaid(orderId,execution,category);
            lifecycle.fail(orderId,current,category);
            trace.event(execution,"EXECUTION_ERROR",failedSkill,details);
            trace.finish(execution,"FAILED",business.status(orderId),category,control.elapsedMs());
            if(error instanceof AgentException agent)throw agent;
            throw new AgentException(category);
        }
    }
    private void loaded(UUID execution,SkillLoader.Skill skill){trace.event(execution,"SKILL_LOADED",skill.id(),
        Map.of("path",skill.path(),"version",skill.version(),"hash",skill.hash()));}
    private Map<String,Object> project(Map<String,Object> source,List<String> keys){var result=new LinkedHashMap<String,Object>();for(var k:keys)result.put(k,source.get(k));return result;}
    private ProcessingStepType stepType(String id){return switch(id){case "order-intake"->ProcessingStepType.INTAKE;case "inventory-check"->ProcessingStepType.INVENTORY;
        case "payment"->ProcessingStepType.PAYMENT;case "shipping-reserve"->ProcessingStepType.SHIPPING;case "notify"->ProcessingStepType.NOTIFICATION;default->ProcessingStepType.RECOVERY;};}
}
