package com.capstone4.orderagent.workspace;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import com.capstone4.orderagent.harness.*;

/**
 * snapshot 을 저장하고 읽는 JDBC 저장소다. skill_snapshots 에 snapshot 한 행(id, 전체 해시, metadata JSON, 만든 시각)을,
 * snapshot_files 에 파일마다 한 행(경로, SHA-256, 원본 바이트)을 쓴다. 고치거나 지우는 메서드가 없어 저장한 snapshot 은 바뀌지 않는다.
 * Skill 수정 저장 이벤트(skill_change_events) 한 행도 여기서 넣는다. 트랜잭션은 호출자인
 * order-agent/backend/src/main/java/com/capstone4/orderagent/workspace/SnapshotService.java 가 연다.
 */
@Repository
public class SnapshotRepository {
    private final JdbcTemplate jdbc;
    /** Spring 이 JdbcTemplate 을 주입해 빈으로 만든다. */
    public SnapshotRepository(JdbcTemplate jdbc) { this.jdbc = jdbc; }
    /**
     * snapshot 한 행과 파일 행들을 넣는다. 파일 해시는 여기서 다시 계산해 함께 저장하고, 읽을 때 get 이 이 값과 내용을 비교한다.
     * SnapshotService.publish 가 같은 트랜잭션에서 부른다.
     */
    public void insert(UUID id, String hash, Map<String, Object> metadata, SortedMap<String, byte[]> files, Instant now) {
        jdbc.update("insert into skill_snapshots(id,hash,metadata_json,created_at) values (?,?,?,?)",
            id, hash, Json.write(metadata), Timestamp.from(now));
        files.forEach((path, bytes) -> jdbc.update(
            "insert into snapshot_files(snapshot_id,path,hash,content) values (?,?,?,?)", id, path, Json.hash(bytes), bytes));
    }
    /**
     * 수정 저장과 같은 트랜잭션에 진단 요청 전달 대기 행(outbox)을 남긴다. thread_id 와 last_error 는 비어 있고 attempts 는 0 으로 시작한다.
     * SnapshotService.saveEdits 가 부르고 SkillChangeDispatcher.dispatch 가 꺼내 service 로 보낸다.
     */
    public void insertChangeEvent(UUID changeId, UUID snapshotId, String payload) {
        jdbc.update("insert into skill_change_events(change_id,snapshot_id,payload) values (?,?,?)", changeId, snapshotId, payload);
    }
    /**
     * 저장된 snapshot 하나다. files 는 경로 순으로 정렬한 경로별 원본 바이트다. metadata 의 source 는 snapshot 을 만든 경로
     * (published-bundle, user-edit, candidate, demo-fixture 등)를 알리고, 나머지 키는 그 경로가 덧붙인 값이다.
     * order-agent/backend/src/main/java/com/capstone4/orderagent/controller/EvidenceController.java 가 manifest 응답으로,
     * order-agent/backend/src/main/java/com/capstone4/orderagent/harness/OrderHarness.java 와 SkillQueryService 가 실행과 조회의 입력으로 쓴다.
     */
    public record Stored(UUID id, String hash, Map<String, Object> metadata, Instant createdAt, SortedMap<String, byte[]> files) {}
    /**
     * snapshot 과 파일 전체를 읽는다. 없으면 SNAPSHOT_NOT_FOUND 다. 파일마다 저장된 해시와 내용의 SHA-256 을 비교해 다르면 SNAPSHOT_TAMPERED 다.
     * 전체 해시 검증은 SnapshotService.get 이 이어서 한다.
     */
    public Stored get(UUID id) {
        var rows = jdbc.query("select * from skill_snapshots where id=?", (r, n) ->
            new Stored(id, r.getString("hash"), Json.object(r.getString("metadata_json")),
                r.getTimestamp("created_at").toInstant(), new TreeMap<>()), id);
        if (rows.isEmpty()) throw new AgentException("SNAPSHOT_NOT_FOUND");
        var stored = rows.getFirst();
        jdbc.query("select path,hash,content from snapshot_files where snapshot_id=? order by path", r -> {
            byte[] bytes = r.getBytes("content");
            if (!Json.hash(bytes).equals(r.getString("hash"))) throw new AgentException("SNAPSHOT_TAMPERED");
            stored.files().put(r.getString("path"), bytes);
        }, id);
        return stored;
    }
}
