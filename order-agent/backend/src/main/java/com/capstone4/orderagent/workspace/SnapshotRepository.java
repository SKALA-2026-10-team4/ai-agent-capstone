package com.capstone4.orderagent.workspace;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import com.capstone4.orderagent.harness.*;

@Repository
public class SnapshotRepository {
    private final JdbcTemplate jdbc;
    public SnapshotRepository(JdbcTemplate jdbc) { this.jdbc = jdbc; }
    public void insert(UUID id, String hash, Map<String, Object> metadata, SortedMap<String, byte[]> files, Instant now) {
        jdbc.update("insert into skill_snapshots(id,hash,metadata_json,created_at) values (?,?,?,?)",
            id, hash, Json.write(metadata), Timestamp.from(now));
        files.forEach((path, bytes) -> jdbc.update(
            "insert into snapshot_files(snapshot_id,path,hash,content) values (?,?,?,?)", id, path, Json.hash(bytes), bytes));
    }
    public record Stored(UUID id, String hash, Map<String, Object> metadata, Instant createdAt, SortedMap<String, byte[]> files) {}
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
