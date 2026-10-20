package com.capstone4.orderagent.trace;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.*;
import com.capstone4.orderagent.harness.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class ExecutionRepository {
    private final JdbcTemplate jdbc;
    public ExecutionRepository(JdbcTemplate jdbc) { this.jdbc = jdbc; }
    public void begin(UUID id, UUID orderId, String mode, String model, Instant now) {
        jdbc.update("insert into agent_executions(id,order_id,agent_id,mode,model_mode,model,completion,started_at) values (?,?,?,'live',?,?,'RUNNING',?)",
            id, orderId, "camping-order-agent", mode, model, Timestamp.from(now));
        jdbc.update("update orders set execution_id=? where id=?", id, orderId);
    }
    public void attach(UUID id, UUID snapshotId) { jdbc.update("update agent_executions set snapshot_id=? where id=?", snapshotId, id); }
    public void selection(UUID id, String raw, String resolved) {
        jdbc.update("update agent_executions set raw_selection=?, resolved_skill_id=? where id=?", raw, resolved, id);
    }
    public void append(UUID id, String kind, String skill, Object data, Instant now) {
        Integer sequence = jdbc.queryForObject("update agent_executions set event_sequence=event_sequence+1 where id=? returning event_sequence", Integer.class, id);
        if (sequence == null) throw new AgentException("EXECUTION_NOT_FOUND");
        jdbc.update("insert into execution_events(execution_id,sequence,kind,skill_id,data_json,at) values (?,?,?,?,?,?)",
            id, sequence, kind, skill, Json.write(data), Timestamp.from(now));
    }
    public void apiAttempt(UUID id) { jdbc.update("update agent_executions set api_calls=api_calls+1 where id=?", id); }
    public void usage(UUID id, Long input, Long output) {
        if (input != null) jdbc.update("update agent_executions set input_tokens=coalesce(input_tokens,0)+? where id=?", input, id);
        if (output != null) jdbc.update("update agent_executions set output_tokens=coalesce(output_tokens,0)+? where id=?", output, id);
    }
    public void finish(UUID id, String completion, String finalStatus, String error, long elapsed, Instant now) {
        jdbc.update("update agent_executions set completion=?,final_status=?,error_category=?,elapsed_ms=?,finished_at=? where id=? and completion='RUNNING'",
            completion, finalStatus, error, elapsed, Timestamp.from(now), id);
    }
    public Map<String, Object> get(UUID id) {
        var rows = jdbc.query("select e.*,s.hash snapshot_hash from agent_executions e left join skill_snapshots s on s.id=e.snapshot_id where e.id=?", (r,n) -> {
            var row = new LinkedHashMap<String, Object>();
            row.put("execution_id", r.getObject("id")); row.put("run_id", "r_" + id.toString().replace("-", ""));
            for (var name : List.of("order_id","snapshot_id","snapshot_hash","agent_id","mode","model_mode","model","completion",
                    "raw_selection","resolved_skill_id","error_category","final_status","api_calls","input_tokens","output_tokens","elapsed_ms")) row.put(name, r.getObject(name));
            row.put("started_at", r.getTimestamp("started_at").toInstant().toString());
            var finished = r.getTimestamp("finished_at"); row.put("finished_at", finished == null ? null : finished.toInstant().toString());
            return row;
        }, id);
        if (rows.isEmpty()) throw new AgentException("EXECUTION_NOT_FOUND");
        var row = rows.getFirst();
        row.put("events", jdbc.query("select * from execution_events where execution_id=? order by sequence", (r,n) -> {
            var event = new LinkedHashMap<String, Object>();
            event.put("sequence",r.getInt("sequence")); event.put("kind",r.getString("kind"));
            event.put("skill_id",r.getString("skill_id")); event.put("data",Json.object(r.getString("data_json")));
            event.put("at",r.getTimestamp("at").toInstant().toString()); return event;
        }, id));
        return row;
    }
    public List<Map<String,Object>> recent() {
        return jdbc.query("select id from agent_executions order by started_at desc limit 20", (r,n) -> r.getObject("id", UUID.class))
            .stream().map(this::summary).toList();
    }
    private Map<String,Object> summary(UUID id) {
        var result = get(id); result.remove("events"); return result;
    }
    public List<UUID> runningForOrder(UUID orderId) {
        return jdbc.query("select id from agent_executions where order_id=? and completion='RUNNING'", (r,n) -> r.getObject(1, UUID.class), orderId);
    }
    public Optional<String> cached(UUID executionId, String tool, String hash) {
        return jdbc.query("select request_hash,result_json from agent_tool_operations where execution_id=? and tool=?", (r,n) -> {
            if (!r.getString(1).equals(hash)) throw new AgentException("IDEMPOTENCY_CONFLICT");
            return r.getString(2);
        }, executionId, tool).stream().findFirst();
    }
    public void operation(UUID executionId, String tool, String hash, Object result, Instant now) {
        jdbc.update("insert into agent_tool_operations(execution_id,tool,request_hash,result_json,committed_at) values (?,?,?,?,?)",
            executionId, tool, hash, Json.write(result), Timestamp.from(now));
    }
}
