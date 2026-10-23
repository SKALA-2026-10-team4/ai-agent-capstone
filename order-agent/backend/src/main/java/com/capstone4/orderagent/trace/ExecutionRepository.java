package com.capstone4.orderagent.trace;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.*;
import com.capstone4.orderagent.harness.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * 실행 기록 세 테이블을 JDBC 로 읽고 쓰는 저장소다. agent_executions 는 실행 한 번의 요약(모드, 선택, 결과, 사용량, 시각),
 * execution_events 는 sequence 순 관찰 이벤트, agent_tool_operations 는 도구 하나의 멱등 결과를 담는다.
 * 트랜잭션을 직접 열지 않고 호출자의 트랜잭션에 참여한다. 관찰 기록은 order-agent/backend/src/main/java/com/capstone4/orderagent/trace/TraceService.java 가
 * 이벤트 단위 트랜잭션으로 감싸고, 도구 결과와 보상 기록은 order-agent/backend/src/main/java/com/capstone4/orderagent/service/ 의
 * OrderToolService, InventoryRecovery, OrderLifecycleService 가 업무 효과와 같은 트랜잭션에서 직접 남긴다.
 * get, recent, failed 가 만드는 Map 의 키 이름과 순서는 trace 응답 계약이다.
 * service/src/main/java/com/capstone4/skillops/scenario/OrderAgentTraceConverter.java, frontend/src/utils/eventView.js,
 * skillops-agent/skillops_agent/contracts.py 가 이 응답을 읽고
 * order-agent/backend/src/test/java/com/capstone4/orderagent/integration/TraceContractIntegrationTest.java 가 키 순서와 값 타입을 고정한다.
 */
@Repository
public class ExecutionRepository {
    /**
     * trace 요약에 execution_id, run_id 다음으로 이 순서대로 싣는 칼럼이다. snapshot_hash 만 skill_snapshots 와 조인해 얻는다.
     * 응답 키 순서가 계약이라 순서를 바꾸지 않는다. 칼럼을 더하면 recent 의 항목과 trace 응답에 함께 나타난다.
     */
    private static final List<String> SUMMARY_COLUMNS = List.of("order_id","snapshot_id","snapshot_hash","agent_id","mode","model_mode","model","completion",
        "raw_selection","resolved_skill_id","error_category","final_status","api_calls","input_tokens","output_tokens","elapsed_ms");
    private final JdbcTemplate jdbc;
    /** Spring 이 JdbcTemplate 을 주입해 빈으로 만든다. */
    public ExecutionRepository(JdbcTemplate jdbc) { this.jdbc = jdbc; }
    /**
     * 실행 행을 RUNNING 으로 만들고 주문 행에 실행 id 를 연결한다. runMode 는 live 나 dry_run(agent_executions.mode),
     * mode 는 모델 모드 fixed 나 openai(model_mode), model 은 기록할 모델 이름이다. agent_id 는 camping-order-agent 로 고정한다.
     * 주문 행의 execution_id 를 여기서 바로 바꾸므로 주문 응답의 trace_url 이 실행 시작부터 그 실행을 가리킨다. TraceService.begin 이 부른다.
     */
    public void begin(UUID id, UUID orderId, String runMode, String mode, String model, Instant now) {
        jdbc.update("insert into agent_executions(id,order_id,agent_id,mode,model_mode,model,completion,started_at) values (?,?,?,?,?,?,'RUNNING',?)",
            id, orderId, "camping-order-agent", runMode, mode, model, Timestamp.from(now));
        jdbc.update("update orders set execution_id=? where id=?", id, orderId);
    }
    /** 실행에 고정한 snapshot id 를 남긴다. TraceService.fixSnapshot 이 SNAPSHOT_FIXED 이벤트와 함께 부른다. */
    public void attach(UUID id, UUID snapshotId) { jdbc.update("update agent_executions set snapshot_id=? where id=?", snapshotId, id); }
    /** 엔트리 선택 원문(raw_selection)과 해석한 Skill id(resolved_skill_id)를 남긴다. 해석 전에는 resolved 가 null 이다. TraceService.selection 이 부른다. */
    public void selection(UUID id, String raw, String resolved) {
        jdbc.update("update agent_executions set raw_selection=?, resolved_skill_id=? where id=?", raw, resolved, id);
    }
    /**
     * 실행 이벤트 한 줄을 다음 sequence 로 더한다. agent_executions.event_sequence 를 UPDATE ... RETURNING 으로 올려 번호를 얻으므로,
     * 그 행 잠금이 커밋까지 유지돼 같은 실행의 이벤트 번호가 겹치거나 비지 않고 1 부터 이어진다. 실행 행이 없으면 EXECUTION_NOT_FOUND 다.
     * data 는 Json.write 로 snake_case JSON 이 된다. TraceService 의 기록 메서드와 OrderToolService, OrderLifecycleService, InventoryRecovery 가 부른다.
     */
    public void append(UUID id, String kind, String skill, Object data, Instant now) {
        Integer sequence = jdbc.queryForObject("update agent_executions set event_sequence=event_sequence+1 where id=? returning event_sequence", Integer.class, id);
        if (sequence == null) throw new AgentException("EXECUTION_NOT_FOUND");
        jdbc.update("insert into execution_events(execution_id,sequence,kind,skill_id,data_json,at) values (?,?,?,?,?,?)",
            id, sequence, kind, skill, Json.write(data), Timestamp.from(now));
    }
    /** 모델 API 시도 횟수(api_calls)를 하나 올린다. 실패한 시도와 재시도도 센다. TraceService.apiAttempt 가 MODEL_REQUEST 와 함께 부른다. */
    public void apiAttempt(UUID id) { jdbc.update("update agent_executions set api_calls=api_calls+1 where id=?", id); }
    /**
     * 관측한 입력과 출력 토큰 수를 누적한다. 값이 null(관측하지 못함)이면 그 칼럼은 건드리지 않으므로 fixed 모드 실행의 토큰은 null 로 남는다.
     * TraceService.modelResult 가 부른다.
     */
    public void usage(UUID id, Long input, Long output) {
        if (input != null) jdbc.update("update agent_executions set input_tokens=coalesce(input_tokens,0)+? where id=?", input, id);
        if (output != null) jdbc.update("update agent_executions set output_tokens=coalesce(output_tokens,0)+? where id=?", output, id);
    }
    /**
     * 실행을 끝난 상태로 닫는다. completion 은 COMPLETED, FAILED, INTERRUPTED 가운데 하나이고 finalStatus 는 그때의 주문 상태,
     * error 는 실패 category, elapsed 는 실행 경과 밀리초다. completion 이 아직 RUNNING 인 행만 바꾸므로 이미 닫힌 실행을 다시 닫아도 처음 결과가 남는다.
     * TraceService.finish, OrderToolService.complete, OrderLifecycleService 의 재시작 복구가 부른다.
     */
    public void finish(UUID id, String completion, String finalStatus, String error, long elapsed, Instant now) {
        jdbc.update("update agent_executions set completion=?,final_status=?,error_category=?,elapsed_ms=?,finished_at=? where id=? and completion='RUNNING'",
            completion, finalStatus, error, elapsed, Timestamp.from(now), id);
    }
    /**
     * 실행 하나를 trace 응답 모양으로 읽는다. 키 순서는 execution_id, run_id, SUMMARY_COLUMNS, started_at, finished_at, events 이고,
     * 없으면 EXECUTION_NOT_FOUND 다. TraceService.get 이 부르며 GET /api/executions/{id}/trace 와 GET /api/traces/{runId} 의 응답이 된다.
     */
    public Map<String, Object> get(UUID id) {
        var row = summary(id);
        row.put("events", events(id));
        return row;
    }
    /**
     * 실행 요약 칼럼을 응답 키 순서대로 읽는다. run_id 는 저장된 칼럼이 아니라 RunId.of 로 만든다. 시각은 ISO-8601 문자열로 바꾼다.
     * 없으면 EXECUTION_NOT_FOUND 다. get 과 recent 가 쓴다.
     */
    private Map<String,Object> summary(UUID id) {
        var rows = jdbc.query("select e.*,s.hash snapshot_hash from agent_executions e left join skill_snapshots s on s.id=e.snapshot_id where e.id=?", (r,n) -> {
            var row = new LinkedHashMap<String, Object>();
            row.put("execution_id", r.getObject("id")); row.put("run_id", RunId.of(id));
            for (var name : SUMMARY_COLUMNS) row.put(name, r.getObject(name));
            row.put("started_at", iso(r.getTimestamp("started_at")));
            row.put("finished_at", iso(r.getTimestamp("finished_at")));
            return row;
        }, id);
        if (rows.isEmpty()) throw new AgentException("EXECUTION_NOT_FOUND");
        return rows.getFirst();
    }
    /** 실행 이벤트를 sequence 순서로 읽는다. 항목 키는 sequence, kind, skill_id, data, at 이고 data 는 저장한 JSON 을 Map 으로 되읽은 값이다. */
    private List<Map<String,Object>> events(UUID id) {
        return jdbc.query("select * from execution_events where execution_id=? order by sequence", (r,n) -> {
            var event = new LinkedHashMap<String, Object>();
            event.put("sequence",r.getInt("sequence")); event.put("kind",r.getString("kind"));
            event.put("skill_id",r.getString("skill_id")); event.put("data",Json.object(r.getString("data_json")));
            event.put("at",iso(r.getTimestamp("at"))); return event;
        }, id);
    }
    /**
     * 진단 대상인 실행 실패를 모두 읽는다. mode 가 live 이고 completion 이 FAILED 나 INTERRUPTED 인 실행만 고르며, 최근 실행 목록과 달리
     * 개수 제한이 없고 실행마다 trace 를 따로 읽지 않는다. 실행의 마지막 EXECUTION_ERROR 이벤트 data 를 lateral 조인으로 한 번에 붙여 error 로 담고,
     * error 에 category 가 없으면(EXECUTION_ERROR 이벤트가 없는 중단이나 과거 실행) agent_executions.error_category 로 채운다.
     * 끝난 시각 내림차순이라 최신 실패가 앞에 온다. 업무 거절(REJECTED, HOLD)은 completion 이 COMPLETED 라 들어가지 않는다.
     * 항목 키는 execution_id, order_id, snapshot_id, snapshot_hash, completion, final_status, started_at, finished_at, error 다.
     * TraceService.failed 가 부르고 GET /api/executions/failed 의 items 가 된다.
     */
    public List<Map<String,Object>> failed() {
        return jdbc.query("""
            select e.id, e.order_id, e.snapshot_id, s.hash snapshot_hash,
                   e.completion, e.final_status, e.started_at, e.finished_at, e.error_category,
                   failure.data_json
              from agent_executions e
              left join skill_snapshots s on s.id=e.snapshot_id
              left join lateral (
                  select data_json from execution_events
                   where execution_id=e.id and kind='EXECUTION_ERROR'
                   order by sequence desc limit 1
              ) failure on true
             where e.mode='live' and e.completion in ('FAILED','INTERRUPTED')
             order by e.finished_at desc nulls last, e.id desc
            """, (r,n) -> {
                var row = new LinkedHashMap<String,Object>();
                row.put("execution_id",r.getObject("id"));
                row.put("order_id",r.getObject("order_id"));
                row.put("snapshot_id",r.getObject("snapshot_id"));
                row.put("snapshot_hash",r.getString("snapshot_hash"));
                row.put("completion",r.getString("completion"));
                row.put("final_status",r.getString("final_status"));
                row.put("started_at",iso(r.getTimestamp("started_at")));
                row.put("finished_at",iso(r.getTimestamp("finished_at")));
                String details = r.getString("data_json");
                var error = details==null?new LinkedHashMap<String,Object>():new LinkedHashMap<>(Json.object(details));
                if (!error.containsKey("category")) error.put("category",r.getString("error_category"));
                row.put("error",error);
                return row;
            });
    }
    /**
     * 최근 실행 20개의 요약을 시작 시각 내림차순으로 읽는다. live 와 dry_run 을 모두 담고 이벤트는 읽지 않는다.
     * id 를 먼저 고른 뒤 실행마다 summary 를 한 번씩 읽는다. 개수는 SQL 의 limit 20 이며 order-agent/docs/agent-evidence.md 와
     * TraceContractIntegrationTest 도 20 을 전제로 한다. TraceService.recent 가 부르고 GET /api/executions 의 items 가 된다.
     */
    public List<Map<String,Object>> recent() {
        return jdbc.query("select id from agent_executions order by started_at desc limit 20", (r,n) -> r.getObject("id", UUID.class))
            .stream().map(this::summary).toList();
    }
    /** 주문의 아직 끝나지 않은(RUNNING) 실행 id 를 모두 읽는다. 재시작 복구(OrderLifecycleService)가 중단된 실행을 닫을 때 쓴다. */
    public List<UUID> runningForOrder(UUID orderId) {
        return jdbc.query("select id from agent_executions where order_id=? and completion='RUNNING'", (r,n) -> r.getObject(1, UUID.class), orderId);
    }
    /**
     * 실행이 이 주문의 아직 끝나지 않은(RUNNING) 실행인지 확인한다. OrderToolService.execute 가 업무 효과 전에 requireRunning 으로 불러,
     * 이미 끝난 실행이나 다른 주문의 실행으로는 도구를 부를 수 없게 한다.
     */
    public boolean isRunningFor(UUID executionId, UUID orderId) {
        Integer running=jdbc.queryForObject("select count(*) from agent_executions where id=? and order_id=? and completion='RUNNING'",Integer.class,executionId,orderId);
        return running!=null&&running==1;
    }
    /** 실행 시작 시각을 읽는다. 없으면 EXECUTION_NOT_FOUND 다. 재시작 복구가 중단된 실행의 경과 시간을 계산할 때 쓴다. */
    public Instant startedAt(UUID id) {
        var rows = jdbc.query("select started_at from agent_executions where id=?", (r,n) -> r.getTimestamp("started_at").toInstant(), id);
        if (rows.isEmpty()) throw new AgentException("EXECUTION_NOT_FOUND");
        return rows.getFirst();
    }
    /**
     * 같은 실행에서 같은 도구를 이미 끝냈으면 저장한 결과 JSON 을 돌려준다. 멱등 키는 (execution_id, tool) 이다.
     * 저장된 요청 해시가 지금 해시와 다르면 같은 도구를 다른 인자로 다시 부른 것이라 IDEMPOTENCY_CONFLICT 다. OrderToolService 의 replay 가 부른다.
     */
    public Optional<String> cached(UUID executionId, String tool, String hash) {
        return jdbc.query("select request_hash,result_json from agent_tool_operations where execution_id=? and tool=?", (r,n) -> {
            if (!r.getString(1).equals(hash)) throw new AgentException("IDEMPOTENCY_CONFLICT");
            return r.getString(2);
        }, executionId, tool).stream().findFirst();
    }
    /**
     * 도구 하나의 멱등 결과를 저장한다. 업무 효과, TOOL_RESULT 와 같은 트랜잭션에서 커밋되므로 이 행이 있으면 업무 효과도 커밋된 것이다.
     * (execution_id, tool) 이 기본 키라 같은 도구 결과를 두 번 저장하면 제약 위반이다. OrderToolService.execute 가 부른다.
     */
    public void operation(UUID executionId, String tool, String hash, Object result, Instant now) {
        jdbc.update("insert into agent_tool_operations(execution_id,tool,request_hash,result_json,committed_at) values (?,?,?,?,?)",
            executionId, tool, hash, Json.write(result), Timestamp.from(now));
    }
    /** DB 시각을 ISO-8601 문자열로 바꾼다. null 이면 null 이다. */
    private static String iso(Timestamp time) { return time == null ? null : time.toInstant().toString(); }
}
