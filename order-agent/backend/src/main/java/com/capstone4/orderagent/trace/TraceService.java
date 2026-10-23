package com.capstone4.orderagent.trace;

import java.time.Clock;
import java.util.*;
import com.capstone4.orderagent.harness.Json;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 실행 기록(trace)을 남기고 읽는 서비스다. 기록 메서드 하나가 짧은 트랜잭션 하나라서, 트랜잭션 없이 도는
 * order-agent/backend/src/main/java/com/capstone4/orderagent/harness/OrderHarness.java 가 모델 응답을 기다리는 동안에도 이미 남긴 관찰은 커밋돼 있다.
 * 실행이 도중에 멈춰도 그 지점까지의 이벤트가 남아 진단 근거가 된다. 각 메서드는 요약 칼럼 갱신과 이벤트 한 줄을 같은 트랜잭션에 묶는다.
 * 이벤트 이름과 data 키, 키 순서는 trace 계약이다. 바꾸면 service/src/main/java/com/capstone4/skillops/scenario/OrderAgentTraceConverter.java,
 * frontend/src/utils/eventView.js, skillops-agent/skillops_agent/contracts.py 와
 * order-agent/backend/src/test/java/com/capstone4/orderagent/integration/TraceContractGolden.java 를 함께 고친다.
 * 기록 메서드는 order-agent/backend/src/main/java/com/capstone4/orderagent/harness/ 의 OrderHarness(begin, fixSnapshot, selection, event, finish),
 * SkillExecutor(event, apiAttempt, modelResult), ToolDispatcher(event)가 부른다. 조회 메서드는
 * order-agent/backend/src/main/java/com/capstone4/orderagent/controller/EvidenceController.java(get, failed, recent)와
 * order-agent/backend/src/main/java/com/capstone4/orderagent/service/DryRunService.java(get)가 부른다.
 * 업무 효과와 함께 커밋해야 하는 도구 결과와 보상 기록, 정상 완료의 EXECUTION_FINISHED 는 이 서비스가 아니라
 * order-agent/backend/src/main/java/com/capstone4/orderagent/service/OrderToolService.java 가 ExecutionRepository 를 직접 불러 남긴다.
 */
@Service
public class TraceService {
    private final ExecutionRepository store;
    private final Clock clock;
    /** Spring 이 실행 기록 저장소와 UTC Clock 을 주입해 빈으로 만든다. */
    public TraceService(ExecutionRepository store, Clock clock) { this.store = store; this.clock = clock; }
    /**
     * 실행을 시작한다. 새 실행 id 를 만들어 RUNNING 행과 주문 연결을 남기고 EXECUTION_STARTED(order_id, mode)를 기록한 뒤 id 를 돌려준다.
     * 이벤트 data 의 mode 는 run mode 가 아니라 모델 모드(fixed, openai)다. run mode 는 실행 요약의 mode 칼럼에 남는다. OrderHarness.execute 가 부른다.
     */
    @Transactional public UUID begin(UUID order, String runMode, String mode, String model) {
        UUID id=UUID.randomUUID(); store.begin(id, order, runMode, mode, model, clock.instant());
        store.append(id, "EXECUTION_STARTED", null, Map.of("order_id",order,"mode",mode), clock.instant()); return id;
    }
    /** 실행이 읽을 snapshot 을 실행 행에 연결하고 SNAPSHOT_FIXED(snapshot_id, hash)를 남긴다. OrderHarness.fixSnapshot 이 부른다. */
    @Transactional public void fixSnapshot(UUID id,UUID snapshot,String hash) {
        store.attach(id,snapshot);store.append(id,"SNAPSHOT_FIXED",null,Map.of("snapshot_id",snapshot,"hash",hash),clock.instant());
    }
    /**
     * 엔트리 선택을 기록한다. resolved 가 null 이면 해석 전 원문만 SKILL_SELECTED 로, 값이 있으면 해석 결과를 SKILL_RESOLVED 로 남긴다.
     * data 키 순서는 raw_name, resolved_skill_id 다. OrderHarness 의 selectEntry 가 원문을, loadValidatedFlow 가 해석 결과를 차례로 남긴다.
     */
    @Transactional public void selection(UUID id, String raw, String resolved) {
        store.selection(id,raw,resolved);
        var data=new LinkedHashMap<String,Object>();data.put("raw_name",raw);data.put("resolved_skill_id",resolved);
        store.append(id,resolved==null?"SKILL_SELECTED":"SKILL_RESOLVED",null,data,clock.instant());
    }
    /** 요약 칼럼을 바꾸지 않는 이벤트 한 줄을 남긴다. 이벤트 이름, skill_id, data 는 호출자가 정한다. */
    @Transactional public void event(UUID id, String kind, String skill, Map<String, ?> data) { store.append(id,kind,skill,data,clock.instant()); }
    /**
     * 모델 API 시도 하나를 센 뒤 MODEL_REQUEST(purpose, attempt)를 남긴다. SkillExecutor 가 원격 결정을 부르기 직전에 부르며,
     * fixed 모드에서는 부르지 않으므로 api_calls 가 0 으로 남는다.
     */
    @Transactional public void apiAttempt(UUID id, String skill, String purpose, int attempt) {
        store.apiAttempt(id);store.append(id,"MODEL_REQUEST",skill,Map.of("purpose",purpose,"attempt",attempt),clock.instant());
    }
    /**
     * 결정 응답의 토큰 수를 누적하고 MODEL_RESPONSE 를 남긴다. data 키 순서는 raw_response, input_tokens, output_tokens 이고 원문을 그대로 담는다.
     * SkillExecutor.decide 가 fixed 모드를 포함한 모든 응답마다 부른다.
     */
    @Transactional public void modelResult(UUID id, String skill, String raw, Long input, Long output) {
        store.usage(id,input,output);
        var data=new LinkedHashMap<String,Object>();data.put("raw_response",raw);data.put("input_tokens",input);data.put("output_tokens",output);
        store.append(id,"MODEL_RESPONSE",skill,data,clock.instant());
    }
    /**
     * 실행을 닫고 EXECUTION_FINISHED(completion, final_status, error_category)를 남긴다. 이미 닫힌 실행이면 요약은 바뀌지 않지만 이벤트는 한 줄 더 남는다.
     * OrderHarness 가 미선택, 업무 중단, 실패로 끝낼 때 부른다. 정상 완료는 OrderToolService.complete 가 error_category 없는 EXECUTION_FINISHED 를 남긴다.
     */
    @Transactional public void finish(UUID id, String completion, String status, String error, long elapsed) {
        store.finish(id,completion,status,error,elapsed,clock.instant());
        var data=new LinkedHashMap<String,Object>();data.put("completion",completion);data.put("final_status",status);data.put("error_category",error);
        store.append(id,"EXECUTION_FINISHED",null,data,clock.instant());
    }
    // TODO(C-1 연동): 공용 get_trace, run_id, snapshot 참조 계약으로 바꾼다.
    // 바꾸더라도 모델이 "사용했다"고 말한 문장 대신 지금 로더와 도구 실행 지점에서 관찰한 이벤트를 그대로 넘긴다.
    /** 실행 하나의 요약과 이벤트 전체를 읽기 전용 트랜잭션으로 읽는다. EvidenceController 의 trace, legacyTrace 와 DryRunService.run 이 부른다. */
    @Transactional(readOnly=true) public Map<String,Object> get(UUID id) { return store.get(id); }
    /** live 실행 실패 전체를 읽는다. EvidenceController.failed(GET /api/executions/failed)가 부른다. */
    @Transactional(readOnly=true) public List<Map<String,Object>> failed() { return store.failed(); }
    /** 최근 실행 20개의 요약을 읽는다. EvidenceController.recent(GET /api/executions)가 부른다. */
    @Transactional(readOnly=true) public List<Map<String,Object>> recent() { return store.recent(); }
}
