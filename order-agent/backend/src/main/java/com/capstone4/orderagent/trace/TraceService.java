package com.capstone4.orderagent.trace;

import java.time.Clock;
import java.util.*;
import com.capstone4.orderagent.harness.Json;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class TraceService {
    private final ExecutionRepository store;
    private final Clock clock;
    public TraceService(ExecutionRepository store, Clock clock) { this.store = store; this.clock = clock; }
    @Transactional public UUID begin(UUID order, String mode, String model) {
        UUID id=UUID.randomUUID(); store.begin(id, order, mode, model, clock.instant());
        store.append(id, "EXECUTION_STARTED", null, Map.of("order_id",order,"mode",mode), clock.instant()); return id;
    }
    @Transactional public void fixSnapshot(UUID id,UUID snapshot,String hash) {
        store.attach(id,snapshot);store.append(id,"SNAPSHOT_FIXED",null,Map.of("snapshot_id",snapshot,"hash",hash),clock.instant());
    }
    @Transactional public void attach(UUID id, UUID snapshot, String hash, Object candidates) {
        store.attach(id,snapshot); store.append(id,"SNAPSHOT_FIXED",null,Map.of("snapshot_id",snapshot,"hash",hash),clock.instant());
        store.append(id,"CANDIDATES_OFFERED",null,Map.of("candidates",candidates),clock.instant());
    }
    @Transactional public void selection(UUID id, String raw, String resolved) {
        store.selection(id,raw,resolved);
        var data=new LinkedHashMap<String,Object>();data.put("raw_name",raw);data.put("resolved_skill_id",resolved);
        store.append(id,resolved==null?"SKILL_SELECTED":"SKILL_RESOLVED",null,data,clock.instant());
    }
    @Transactional public void event(UUID id, String kind, String skill, Map<String, ?> data) { store.append(id,kind,skill,data,clock.instant()); }
    @Transactional public void apiAttempt(UUID id, String skill, String purpose, int attempt) {
        store.apiAttempt(id);store.append(id,"MODEL_REQUEST",skill,Map.of("purpose",purpose,"attempt",attempt),clock.instant());
    }
    @Transactional public void modelResult(UUID id, String skill, String raw, Long input, Long output) {
        store.usage(id,input,output);
        var data=new LinkedHashMap<String,Object>();data.put("raw_response",raw);data.put("input_tokens",input);data.put("output_tokens",output);
        store.append(id,"MODEL_RESPONSE",skill,data,clock.instant());
    }
    @Transactional public void finish(UUID id, String completion, String status, String error, long elapsed) {
        store.finish(id,completion,status,error,elapsed,clock.instant());
        var data=new LinkedHashMap<String,Object>();data.put("completion",completion);data.put("final_status",status);data.put("error_category",error);
        store.append(id,"EXECUTION_FINISHED",null,data,clock.instant());
    }
    // TODO(C-1 연동): 공용 get_trace/run_id·snapshot 참조 계약으로 변환한다.
    // 모델의 "사용했다"는 문장 대신 현재 로더·도구 실행 지점의 관찰 이벤트를 그대로 전달한다.
    @Transactional(readOnly=true) public Map<String,Object> get(UUID id) { return store.get(id); }
    @Transactional(readOnly=true) public List<Map<String,Object>> recent() { return store.recent(); }
}
