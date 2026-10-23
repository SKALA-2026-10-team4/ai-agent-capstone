package com.capstone4.orderagent.harness;

import java.util.*;
import com.capstone4.orderagent.service.*;
import com.capstone4.orderagent.entity.enums.ProcessingStepType;
import com.capstone4.orderagent.trace.TraceService;
import com.capstone4.orderagent.workspace.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * 주문 하나를 Skill 로 처리하는 주문 Agent 의 실행 루프다. SkillOps 가 진단하는 대상 Agent 이므로,
 * 무엇을 고르고 어떤 파일을 읽고 어떤 도구를 불렀는지를 모델의 말이 아니라 실제 실행 지점의 관찰로 trace 에 남긴다.
 * 흐름 설계는 order-agent/docs/skill-flow.md 에 있고, 그 문서보다 최신인 실행 경로와 트랜잭션 설명은
 * order-agent/docs/agent-evidence.md 에 있다.
 *
 * 한 번의 실행은 다음 순서로 진행한다.
 * 1. 실행 기록을 시작하고(EXECUTION_STARTED) snapshot 을 고정한다(SNAPSHOT_FIXED).
 * 2. snapshot 의 모든 Skill 로 후보를 만들고(CANDIDATES_OFFERED) 엔트리를 고르게 한다
 *    (DECISION_INPUT, MODEL_REQUEST, MODEL_RESPONSE, SKILL_SELECTED). MODEL_REQUEST 는 fixed 가 아닌 모드에서만 남는다.
 * 3. 고른 루트 flow 를 로드해 검증한다(SKILL_RESOLVED, SKILL_LOADED, FLOW_VALIDATED).
 * 4. steps 순서대로 단계마다 SKILL_LOADED, SKILL_STARTED, 결정 이벤트, TOOL_REQUEST, TOOL_RESULT, SKILL_COMPLETED,
 *    SKILL_FINISHED 를 남긴다.
 * 5. 모든 단계가 끝나면 주문을 SHIPPED 로 확정하고 EXECUTION_FINISHED 를 남긴다. 도구가 주문을 끝내면 BUSINESS_STOP 을,
 *    오류가 나면 EXECUTION_ERROR 를 남긴 뒤 EXECUTION_FINISHED 로 닫는다.
 *
 * 이 클래스에는 트랜잭션이 없다. 모델 응답을 기다리는 동안 DB 잠금을 잡지 않도록 trace 기록은
 * order-agent/backend/src/main/java/com/capstone4/orderagent/trace/TraceService.java 가, 업무 효과는
 * order-agent/backend/src/main/java/com/capstone4/orderagent/service/OrderToolService.java 가 각자 짧은 트랜잭션으로 커밋한다.
 *
 * trace 이벤트 이름, 순서, data 키는 모듈 밖에서 읽는 계약이다.
 * service/src/main/java/com/capstone4/skillops/scenario/OrderAgentTraceConverter.java 는 SKILL_STARTED 와 SKILL_FINISHED 의 step_no,
 * SKILL_STARTED 의 input, SKILL_FINISHED 의 status 와 output, EXECUTION_ERROR 의 data 를 채점에 쓴다.
 * frontend/src/utils/eventView.js 는 이벤트 종류마다 화면 문장을 만들고 frontend/src/utils/traceRun.js 는 단계 상태를 계산한다.
 * skillops-agent/skillops_agent/contracts.py 는 SKILL_STARTED 의 input 키, SKILL_FINISHED 의 output 키, 첫 EXECUTION_ERROR,
 * TOOL_REQUEST 의 tool 을 읽는다.
 * order-agent/frontend/src/utils/evidenceFormat.js 도 이벤트 종류 이름을 라벨로 바꾼다.
 * 이름이나 순서를 바꾸면 이 소비자들과 경로별 기대 줄을 함께 고친다. 기대 줄은
 * order-agent/backend/src/test/java/com/capstone4/orderagent/integration/TraceContractGolden.java 에 있다.
 *
 * 흐름을 튜닝하는 지점은 OrderFlowPolicy 의 상수, ToolCatalog 의 BINDINGS 표,
 * order-agent/backend/src/main/java/com/capstone4/orderagent/entity/enums/ProcessingStepType.java 의 BUSINESS_FLOW,
 * order-agent/backend/src/main/resources/application.yml 의 order-agent 실행 한도다. 단계 하나를 추가할 때 고칠 곳은
 * ToolCatalog 클래스 주석에 있다.
 *
 * order-agent/backend/src/main/java/com/capstone4/orderagent/service/OrderProcessingService.java 의 process 가 execute 를 부른다.
 * 주문 생성(order-agent/backend/src/main/java/com/capstone4/orderagent/service/OrderService.java 의 createOrder)과
 * 모의 실행(order-agent/backend/src/main/java/com/capstone4/orderagent/service/DryRunService.java 의 run)이 모두 그 경로로 들어오며,
 * service/src/main/java/com/capstone4/skillops/scenario/HarnessFlowRunner.java 는
 * service/src/main/java/com/capstone4/skillops/scenario/OrderAgentRunClient.java 의 dryRun 으로 POST /api/dry-runs 를 보내 이 루프에 닿는다.
 * snapshot 고정은 order-agent/backend/src/main/java/com/capstone4/orderagent/workspace/SnapshotService.java 가, 주문 실패 기록은
 * order-agent/backend/src/main/java/com/capstone4/orderagent/service/OrderLifecycleService.java 가 맡는다.
 */
@Component
public class OrderHarness {
    private final SnapshotService snapshots;
    private final SkillLoader loader;
    private final SkillExecutor decisions;
    private final ToolDispatcher tools;
    private final OrderToolService business;
    private final OrderLifecycleService lifecycle;
    private final TraceService trace;
    // 실행 한도는 order-agent/backend/src/main/resources/application.yml 의 order-agent.execution-timeout-ms, max-observed-tokens,
    // max-api-calls 에서 오고 실행마다 ExecutionControl 하나로 묶인다.
    private final long timeout,maxTokens;
    private final int maxCalls;
    // fixed 가 아닌 모드에서 실행 기록에 남길 모델 이름이다. 같은 설정 파일의 spring.ai.openai.chat.model(환경 변수 SKILLOPS_MODEL)에서 온다.
    private final String model;
    /** Spring 이 협력 객체와 실행 한도 설정을 주입해 빈으로 만든다. */
    public OrderHarness(SnapshotService snapshots,SkillLoader loader,SkillExecutor decisions,ToolDispatcher tools,
        OrderToolService business,OrderLifecycleService lifecycle,TraceService trace,
        @Value("${order-agent.execution-timeout-ms}") long timeout,@Value("${order-agent.max-api-calls}") int maxCalls,
        @Value("${order-agent.max-observed-tokens}") long maxTokens,@Value("${spring.ai.openai.chat.model}") String model){
        this.snapshots=snapshots;this.loader=loader;this.decisions=decisions;this.tools=tools;this.business=business;
        this.lifecycle=lifecycle;this.trace=trace;this.timeout=timeout;this.maxCalls=maxCalls;this.maxTokens=maxTokens;this.model=model;
    }
    // TODO(A 연동): 공용 Harness 가 제공되면 이 실행 경계를 그 구현에 연결한다.
    // 연결하더라도 같은 snapshot 에서 후보를 고르고 단계를 로드하는 것과, 실제 tool dispatch 지점에서 trace 를 남기는 계약은 지킨다.
    // TODO(C-2 연동): SingleAgentAdapter 와 Runner 도 이 진입점을 부르도록 연결한다.
    // Runner 의 기대값이나 정답 도구 목록은 DecisionProvider.Question 에 넣지 않는다.
    /**
     * 주문 하나를 Skill flow 로 처리한다. 주문 컨텍스트를 읽고 실행 기록을 시작한 뒤 snapshot 고정, 후보 제시, 엔트리 선택,
     * flow 검증, 단계 실행, 완료 순서로 진행한다. 엔트리가 none 이면 미선택으로, 도구가 주문을 끝내면 업무 중단으로 일찍 끝난다.
     * 실패하면 recordFailure 가 보상과 기록을 마친 뒤 AgentException 을 다시 던지고,
     * OrderProcessingService.processClaimed 가 그 예외를 오류 코드(INTERNAL_ERROR 나 PROCESSING_UNAVAILABLE)로 분류한다.
     * initial 과 begin 은 try 밖에 있으므로 여기서 실패하면 실행 기록 없이 예외가 그대로 나간다.
     */
    public void execute(UUID orderId){
        var control=new ExecutionControl(timeout,maxCalls,maxTokens);
        // run mode 는 begin 에서 정해지고 바뀌지 않는다. 모의 실행은 dry_run 으로 남아 live 실패 목록에 나오지 않는다.
        var initial=business.initial(orderId);
        UUID execution=trace.begin(orderId,runMode(initial),decisions.mode(),modelLabel());
        var progress=new ExecutionProgress(orderId,execution,control);
        try{
            var files=fixSnapshot(execution,initial).files();
            var candidates=offerCandidates(execution,files);
            progress.context=visibleContext(initial);
            String name=selectEntry(progress,candidates);
            if(name.equals(OrderFlowPolicy.NO_SELECTION)){finishWithoutSelection(progress);return;}
            var steps=loadValidatedFlow(execution,files,name);
            for(var definition:steps)if(runStep(progress,files,definition)==StepOutcome.BUSINESS_STOP)return;
            completeRun(progress);
        }catch(RuntimeException error){
            throw recordFailure(progress,error);
        }
    }
    /** 주문의 dry_run 표시로 실행 기록(agent_executions.mode)에 남길 run mode 를 정한다. */
    private static String runMode(OrderToolService.Initial initial){
        return initial.dryRun()?OrderFlowPolicy.RUN_MODE_DRY_RUN:OrderFlowPolicy.RUN_MODE_LIVE;
    }
    /** 실행 기록(agent_executions.model)에 남길 모델 이름을 정한다. fixed 모드는 FixedDecisionProvider.MODEL_LABEL 을, 그 밖의 모드는 설정한 모델 이름을 쓴다. */
    private String modelLabel(){
        return decisions.mode().equals(FixedDecisionProvider.MODE)?FixedDecisionProvider.MODEL_LABEL:model;
    }
    /**
     * 실행이 읽을 snapshot 을 고정하고 SNAPSHOT_FIXED 를 남긴다. 주문에 snapshot_id 가 있으면 SnapshotService.get 이 해시를 다시 검증하고,
     * 없으면 SnapshotService.capture 가 게시 번들(order-agent/skills)을 새 snapshot 으로 발행한다.
     * 후보 목록과 모든 단계 로드가 이 한 묶음의 바이트만 읽으므로, 실행 도중 파일이 바뀌어도 한 실행 안에서 섞이지 않는다.
     */
    private SnapshotRepository.Stored fixSnapshot(UUID execution,OrderToolService.Initial initial){
        var snapshot=initial.requestedSnapshotId()==null?snapshots.capture():snapshots.get(initial.requestedSnapshotId());
        trace.fixSnapshot(execution,snapshot.id(),snapshot.hash());
        return snapshot;
    }
    /**
     * snapshot 의 모든 Skill 로 후보 목록을 만들고 CANDIDATES_OFFERED 를 남긴다. SkillLoader.candidates 는 파일 하나라도 깨져 있으면
     * 실패하므로, 깨진 Skill 이 섞인 snapshot 은 엔트리 선택 전에 멈춘다.
     */
    private List<Map<String,Object>> offerCandidates(UUID execution,SortedMap<String,byte[]> files){
        var candidates=loader.candidates(files);
        trace.event(execution,"CANDIDATES_OFFERED",null,Map.of("candidates",candidates));
        return candidates;
    }
    /**
     * 초기 컨텍스트에서 OrderFlowPolicy.HIDDEN_INITIAL_KEYS 를 뺀다. 엔트리 선택 질문과 첫 단계가 이 컨텍스트에서 시작하고,
     * 빠진 값은 그 값을 선언 출력으로 내는 단계가 도구 결과로 채운다.
     */
    private static LinkedHashMap<String,Object> visibleContext(OrderToolService.Initial initial){
        var context=new LinkedHashMap<>(initial.context());
        OrderFlowPolicy.HIDDEN_INITIAL_KEYS.forEach(context::remove);
        return context;
    }
    /**
     * 엔트리 Skill 을 고르게 하고 해석하기 전의 선택 원문을 SKILL_SELECTED 로 남긴다. 알 수 없는 이름도 원문 그대로 남겨,
     * 미선택과 존재하지 않는 Skill 요청을 구분할 수 있게 한다. 이름이 문자열이 아니면 JSON 으로 적어 남긴 뒤 MODEL_FORMAT_ERROR 로 멈춘다.
     */
    private String selectEntry(ExecutionProgress progress,List<Map<String,Object>> candidates){
        var selection=decisions.decide(progress.executionId,DecisionProvider.Question.select(candidates,Map.copyOf(progress.context)),progress.control);
        Object raw=selection.get("skill");
        if(!(raw instanceof String name)){trace.selection(progress.executionId,Json.write(raw),null);throw new AgentException("MODEL_FORMAT_ERROR");}
        trace.selection(progress.executionId,name,null);
        return name;
    }
    /**
     * 명시적 미선택을 업무 거절로 기록하고 실행을 COMPLETED 로 닫는다. OrderToolService.noSelection 이 주문을
     * REJECTED(NO_SELECTION)로 바꾸고 업무 단계를 모두 SKIPPED 로 남긴다.
     */
    private void finishWithoutSelection(ExecutionProgress progress){
        business.noSelection(progress.orderId);
        trace.finish(progress.executionId,"COMPLETED","REJECTED","NO_SELECTION",progress.control.elapsedMs());
    }
    /**
     * 선택한 루트 flow 를 로드하고 검증한 뒤 FLOW_VALIDATED 를 남긴다. 로드에 실패하면 요청 이름과 category 를 SKILL_LOAD_REJECTED 로 남기고,
     * 성공하면 SKILL_RESOLVED 와 SKILL_LOADED 를 남긴다. 그다음 루트가 OrderFlowPolicy.ROOT_FLOW_ID 가 아니면 ROOT_SKILL_MISMATCH 다.
     * SkillLoader.validateFlow 가 단계 수, 중복, 중첩 flow, 정적 입력 공급을 검사하고, 이어서 모든 단계에 ToolCatalog 바인딩이 있는지 본다.
     * 이 검사는 업무 도구가 하나도 실행되기 전에 끝나므로, 계약 오류는 재고나 결제에 손대지 않고 멈춘다.
     */
    private List<SkillLoader.Skill> loadValidatedFlow(UUID execution,SortedMap<String,byte[]> files,String name){
        SkillLoader.Skill flow;
        try{flow=loader.load(files,name);}catch(AgentException e){
            trace.event(execution,"SKILL_LOAD_REJECTED",null,Map.of("requested_name",name,"category",e.category()));throw e;
        }
        trace.selection(execution,name,flow.id());loaded(execution,flow);
        if(!flow.id().equals(OrderFlowPolicy.ROOT_FLOW_ID))throw new AgentException("ROOT_SKILL_MISMATCH");
        var steps=loader.validateFlow(files,flow);
        for(var step:steps)ToolCatalog.toolFor(step.id());
        trace.event(execution,"FLOW_VALIDATED",flow.id(),Map.of("steps",flow.steps()));
        return steps;
    }
    /**
     * 단계 Skill 하나를 실행한다. snapshot 에서 단계 파일을 다시 로드해 SKILL_LOADED 를, 선언 입력만 골라 SKILL_STARTED 를 남긴다.
     * 실행 중 입력 검증(runtime_validation)을 통과하면 묶인 도구 정의와 함께 결정을 요청하고 ToolDispatcher 로 도구를 실행한다.
     * 도구가 돌려준 주문 상태가 OrderFlowPolicy.CONTINUE_STATUSES 밖이면 실행을 업무 중단으로 닫고 BUSINESS_STOP 을 돌려준다.
     * 계속할 수 있으면 선언 출력이 모두 있는지 보고(MISSING_SKILL_OUTPUT) 그 값만 컨텍스트에 넣은 뒤 SKILL_COMPLETED 와
     * SKILL_FINISHED 를 남긴다. 모델은 선언 입력만 보고 다음 단계도 선언 출력만 받으므로, SKILL.md 의 계약이 실제 데이터 흐름을 정한다.
     */
    @SuppressWarnings("unchecked")
    private StepOutcome runStep(ExecutionProgress progress,SortedMap<String,byte[]> files,SkillLoader.Skill definition){
        UUID execution=progress.executionId;
        progress.control.check();var skill=loader.load(files,definition.id());loaded(execution,skill);
        progress.orderStep=ToolCatalog.stepTypeFor(skill.id());
        progress.activeSkill=skill;progress.stepStartedNanos=System.nanoTime();progress.stepNo++;
        // 모델과 trace 에는 Skill 이 선언한 입력만 보인다.
        var inputs=pick(progress.context,skill.inputs());
        trace.event(execution,"SKILL_STARTED",skill.id(),Map.of("step_no",progress.stepNo,"version",skill.version(),
            "hash",skill.hash(),"input",inputs));
        loader.validateInputs(skill,progress.context.keySet(),progress.completed,"runtime_validation");
        // 모델에는 이 단계에 묶인 도구 하나의 정의만 준다.
        var decision=decisions.decide(execution,DecisionProvider.Question.tool(skill,inputs,
            ToolCatalog.definition(ToolCatalog.toolFor(skill.id()))),progress.control);
        // 응답 모양이 틀리면 도구를 부르기 전에 멈춘다.
        if(!(decision.get("tool") instanceof String tool)||!(decision.get("arguments") instanceof Map<?,?>))throw new AgentException("MODEL_FORMAT_ERROR");
        Map<String,Object> args=(Map<String,Object>)decision.get("arguments");
        var actual=tools.execute(execution,progress.orderId,skill,tool,args,progress.control);
        progress.control.check();
        var outputs=(Map<String,Object>)actual.get("context");
        // 재고 부족이나 결제 거절처럼 도구가 주문을 끝낸 상태면 오류가 아니라 정상 업무 종료다.
        if(!OrderFlowPolicy.CONTINUE_STATUSES.contains(actual.get("final_status"))){
            stopForBusinessResult(progress,skill,outputs,actual.get("final_status"));
            return StepOutcome.BUSINESS_STOP;
        }
        // 도구가 더 많은 값을 돌려줘도 다음 단계에는 선언 출력만 넘긴다.
        if(!outputs.keySet().containsAll(skill.outputs()))throw new AgentException("MISSING_SKILL_OUTPUT");
        for(var key:skill.outputs())progress.context.put(key,outputs.get(key));
        progress.completed.add(skill.id());
        var declared=pick(outputs,skill.outputs());
        trace.event(execution,"SKILL_COMPLETED",skill.id(),Map.of("declared_output",declared));
        finishedStep(execution,skill,progress.stepNo,progress.stepStartedNanos,"ok",declared,Map.of());
        progress.activeSkill=null;
        return StepOutcome.CONTINUE;
    }
    /**
     * 도구가 업무 중단 상태를 돌려준 단계를 정상 종료로 기록하고 실행을 COMPLETED 로 닫는다.
     * SKILL_FINISHED(status ok, business_stop true), BUSINESS_STOP, EXECUTION_FINISHED 순서로 남기고, 최종 상태는
     * OrderToolService.status 로 다시 읽는다. 거절 분기에서는 승인 분기의 선언 출력이 없어도 오류로 바꾸지 않는다.
     */
    private void stopForBusinessResult(ExecutionProgress progress,SkillLoader.Skill skill,Map<String,Object> outputs,Object finalStatus){
        finishedStep(progress.executionId,skill,progress.stepNo,progress.stepStartedNanos,"ok",pick(outputs,skill.outputs()),
            Map.of("final_status",finalStatus,"business_stop",true));
        progress.activeSkill=null;
        trace.event(progress.executionId,"BUSINESS_STOP",skill.id(),Map.of("final_status",finalStatus));
        trace.finish(progress.executionId,"COMPLETED",business.status(progress.orderId),null,progress.control.elapsedMs());
    }
    /**
     * 모든 단계를 마친 실행을 완료로 닫는다. OrderFlowPolicy.REQUIRED_FINAL_STEP 이 완료되지 않았으면 INCOMPLETE_FLOW 다.
     * OrderToolService.complete 가 주문 SHIPPED 확정, 실행 종료, EXECUTION_FINISHED 를 한 트랜잭션으로 커밋한다.
     */
    private void completeRun(ExecutionProgress progress){
        if(!progress.completed.contains(OrderFlowPolicy.REQUIRED_FINAL_STEP))throw new AgentException("INCOMPLETE_FLOW");
        progress.control.check();business.complete(progress.orderId,progress.executionId,progress.control.elapsedMs());
    }
    /**
     * 실패를 정해진 순서로 기록하고 다시 던질 예외를 돌려준다. 먼저 진행 중이던 단계에 SKILL_FINISHED(status error)를 남긴다.
     * 이어서 OrderToolService.compensateUnpaid 가 결제 전 예약 재고를 되돌리고, OrderLifecycleService.fail 이 실패 단계를 FAILED 로,
     * 업무 순서에서 그 뒤에 오는 단계를 SKIPPED 로 남긴다. 마지막으로 EXECUTION_ERROR 와 실행 종료(FAILED)를 기록한다.
     * EXECUTION_ERROR 의 data 는 category 와 AgentException 의 details 이며 skill_id 가 없으면 진행 중이던 단계 이름으로 채운다.
     * 주문 실패 단계는 details 의 skill_id 로 정하므로 정적 검증 실패처럼 단계를 시작하기 전에 난 오류도 그 단계로 남는다.
     * skill_id 가 없으면 마지막으로 시작한 단계의 종류를 쓰고(INCOMPLETE_FLOW 가 그렇다), 아직 단계를 시작하지 않았으면 RECOVERY 다.
     * AgentException 이 아닌 예외는 EXECUTION_ERROR category 의 AgentException 으로 바꿔 돌려준다.
     */
    private AgentException recordFailure(ExecutionProgress progress,RuntimeException error){
        UUID execution=progress.executionId;
        String category=error instanceof AgentException a?a.category():"EXECUTION_ERROR";
        var details=new LinkedHashMap<String,Object>();details.put("category",category);
        if(error instanceof AgentException agent)details.putAll(agent.details());
        if(progress.activeSkill!=null){
            details.putIfAbsent("skill_id",progress.activeSkill.id());
            finishedStep(execution,progress.activeSkill,progress.stepNo,progress.stepStartedNanos,"error",Map.of(),Map.of("category",category));
        }
        String failedSkill=details.get("skill_id") instanceof String id?id:null;
        if(failedSkill!=null)progress.orderStep=ToolCatalog.stepTypeFor(failedSkill);
        business.compensateUnpaid(progress.orderId,execution,category);
        lifecycle.fail(progress.orderId,progress.orderStep,category);
        trace.event(execution,"EXECUTION_ERROR",failedSkill,details);
        trace.finish(execution,"FAILED",business.status(progress.orderId),category,progress.control.elapsedMs());
        return error instanceof AgentException agent?agent:new AgentException(category);
    }
    /**
     * 단계 끝 기록 SKILL_FINISHED 를 남긴다. data 는 extra 의 키 뒤에 step_no, version, status(ok 또는 error), output, duration_ms 를 둔다.
     * service 의 OrderAgentTraceConverter 가 step_no 로 단계를 묶고 status 와 output 을 채점에 쓰므로 키 이름을 바꾸지 않는다.
     */
    private void finishedStep(UUID execution,SkillLoader.Skill skill,int stepNo,long started,String status,
            Map<String,Object> output,Map<String,Object> extra){
        var data=new LinkedHashMap<String,Object>(extra);
        data.put("step_no",stepNo);data.put("version",skill.version());data.put("status",status);data.put("output",output);
        data.put("duration_ms",java.util.concurrent.TimeUnit.NANOSECONDS.toMillis(System.nanoTime()-started));
        trace.event(execution,"SKILL_FINISHED",skill.id(),data);
    }
    /** source 에 있는 키만 keys 순서대로 골라 담는다. Skill 의 선언 입력과 선언 출력만 보이게 할 때 쓴다. */
    private static Map<String,Object> pick(Map<String,Object> source,List<String> keys){
        var result=new LinkedHashMap<String,Object>();
        for(var key:keys)if(source.containsKey(key))result.put(key,source.get(key));
        return result;
    }
    /** 로더가 실제로 읽은 Skill 파일의 경로, version, 해시를 SKILL_LOADED 로 남긴다. 모델의 주장 대신 실제 로드를 증거로 남기는 이벤트다. */
    private void loaded(UUID execution,SkillLoader.Skill skill){trace.event(execution,"SKILL_LOADED",skill.id(),
        Map.of("path",skill.path(),"version",skill.version(),"hash",skill.hash()));}

    /** 실행 하나가 진행되며 바뀌는 상태다. 실패 기록은 활성 단계와 주문 처리 단계 종류를 여기서 읽는다. */
    private static final class ExecutionProgress {
        final UUID orderId;
        final UUID executionId;
        final ExecutionControl control;
        // 단계 사이에 넘기는 확인된 업무 값이다. 처음에는 감춘 키를 뺀 주문 컨텍스트이고 단계마다 선언 출력이 더해진다.
        LinkedHashMap<String,Object> context;
        // 완료한 단계 Skill id 다. 실행 중 depends_on 검사와 필수 마지막 단계 확인에 쓴다.
        final Set<String> completed=new HashSet<>();
        // 실패하면 FAILED 로 남길 주문 처리 단계 종류다. 단계를 시작할 때마다 그 단계의 종류로 바뀌고, 단계를 시작하기 전에는 RECOVERY 다.
        ProcessingStepType orderStep=ProcessingStepType.RECOVERY;
        // 로드한 뒤 아직 SKILL_FINISHED 를 남기지 않은 단계다. 실패 기록이 이 단계를 error 로 닫는다.
        SkillLoader.Skill activeSkill;
        long stepStartedNanos;
        // trace 의 step_no 이며 1 부터 센다.
        int stepNo;
        /** 시작한 실행의 식별자와 실행 한도로 진행 상태를 만든다. */
        ExecutionProgress(UUID orderId,UUID executionId,ExecutionControl control){this.orderId=orderId;this.executionId=executionId;this.control=control;}
    }
    /** 단계 하나를 마친 뒤 실행 루프가 다음 단계로 갈지, 업무 중단으로 끝낼지를 나타낸다. */
    private enum StepOutcome { CONTINUE, BUSINESS_STOP }
}
