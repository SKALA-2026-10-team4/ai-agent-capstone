package com.capstone4.orderagent.workspace;

import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.Clock;
import java.util.*;
import com.capstone4.orderagent.harness.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class SnapshotService {
    private final SnapshotRepository store;
    private final Path root;
    private final Clock clock;
    private final org.springframework.jdbc.core.JdbcTemplate jdbc;
    public SnapshotService(SnapshotRepository store, @Value("${order-agent.skills-root}") String root, Clock clock, org.springframework.jdbc.core.JdbcTemplate jdbc) {
        this.store = store; this.root = Path.of(root).toAbsolutePath().normalize(); this.clock = clock; this.jdbc = jdbc;
    }
    /** The release manifest pins every byte; an actively edited/incomplete bundle is rejected. */
    public SortedMap<String, byte[]> readPublishedBundle() {
        try {
            if (Files.isSymbolicLink(root)) throw new AgentException("INVALID_BUNDLE_PATH");
            byte[] manifestBytes = Files.readAllBytes(root.resolve("bundle.json"));
            var manifest = Json.object(new String(manifestBytes, StandardCharsets.UTF_8));
            var declared = (Map<?, ?>) manifest.get("files");
            if (declared == null || declared.isEmpty()) throw new AgentException("INCOMPLETE_BUNDLE");
            var actual = new TreeSet<String>();
            try (var paths = Files.walk(root)) {
                for (var p : paths.toList()) {
                    if (Files.isSymbolicLink(p)) throw new AgentException("INVALID_BUNDLE_PATH");
                    if (Files.isRegularFile(p) && !p.equals(root.resolve("bundle.json")))
                        actual.add(root.relativize(p).toString().replace('\\', '/'));
                }
            }
            if (!actual.equals(declared.keySet())) throw new AgentException("INCOMPLETE_BUNDLE");
            var files = new TreeMap<String, byte[]>();
            for (var path : actual) {
                validatePath("skills/" + path);
                byte[] bytes = Files.readAllBytes(root.resolve(path));
                if (!Json.hash(bytes).equals(declared.get(path))) throw new AgentException("INCOMPLETE_BUNDLE");
                files.put("skills/" + path, bytes);
            }
            if (!Arrays.equals(manifestBytes, Files.readAllBytes(root.resolve("bundle.json"))))
                throw new AgentException("INCOMPLETE_BUNDLE");
            return files;
        } catch (AgentException e) { throw e; }
        catch (Exception e) { throw new AgentException("BUNDLE_READ_ERROR"); }
    }
    @Transactional
    public SnapshotRepository.Stored capture() {
        return publish(readPublishedBundle(), Map.of("source", "published-bundle", "release", "camping-v1"));
    }
    /** Save editor overrides without publishing to the live bundle or validating the candidate flow. */
    @Transactional
    public SnapshotRepository.Stored saveEdits(UUID baselineId, String baselineHash, Map<String, String> changes) {
        if(baselineId==null||baselineHash==null||changes==null||changes.isEmpty()||changes.size()>64)
            throw new AgentException("INVALID_SKILL_EDITS");
        var baseline=get(baselineId);
        if(!baseline.hash().equals(baselineHash))throw new AgentException("BASELINE_HASH_MISMATCH");
        var files=new TreeMap<>(baseline.files());
        var changedPaths=new TreeSet<String>();
        for(var entry:changes.entrySet()){
            String path=entry.getKey(),content=entry.getValue();
            if(path==null||!path.matches("skills/[a-z0-9][a-z0-9-]*/SKILL\\.md")||content==null)
                throw new AgentException("INVALID_SKILL_EDITS");
            byte[] bytes=content.getBytes(StandardCharsets.UTF_8);
            if(bytes.length>1_000_000)throw new AgentException("INVALID_BUNDLE_SIZE");
            if(!Arrays.equals(files.get(path),bytes)){
                files.put(path,bytes);changedPaths.add(path);
            }
        }
        if(changedPaths.isEmpty())throw new AgentException("NO_SKILL_CHANGES");
        // Invalid contracts/format may intentionally be saved for diagnosis. Execution owns validation.
        String changeId=UUID.randomUUID().toString();
        var saved=publish(files,Map.of("source","user-edit","change_id",changeId,
            "baseline_snapshot_id",baselineId.toString(),"baseline_hash",baselineHash,
            "changed_paths",List.copyOf(changedPaths),"validation_status","QUEUED"));
        jdbc.update("insert into skill_change_events(change_id,snapshot_id,payload) values (?,?,?)",
            UUID.fromString(changeId),saved.id(),Json.write(Map.of("type","skill_saved","change_id",changeId,
                "baseline_snapshot_id",baselineId.toString(),"candidate_snapshot_id",saved.id().toString(),
                "changed_paths",List.copyOf(changedPaths))));
        return saved;
    }
    // TODO(C-1/C-2 연동): 확정된 버전 집합 또는 SEALED workspace revision을 받아 발행한다.
    // 편집 중인 경로를 직접 읽지 않고 후보 메타데이터와 관련 파일 전체를 함께 넘겨야 한다.
    // 현재 저장소는 독립 데모용이다. 공용 저장소가 생기면 이 서비스 경계에서 연결하고 이중 원장을 만들지 않는다.
    /** Completed candidate maps only. Persist metadata and every related file in one transaction. */
    @Transactional
    public SnapshotRepository.Stored publish(SortedMap<String, byte[]> files, Map<String, Object> metadata) {
        var frozen = new TreeMap<String, byte[]>();
        long total = 0;
        if (files.isEmpty() || files.size() > 256) throw new AgentException("INVALID_BUNDLE_SIZE");
        for (var entry : files.entrySet()) {
            validatePath(entry.getKey());
            if (entry.getValue().length > 1_000_000) throw new AgentException("INVALID_BUNDLE_SIZE");
            total += entry.getValue().length; frozen.put(entry.getKey(), entry.getValue().clone());
        }
        if (total > 5_000_000) throw new AgentException("INVALID_BUNDLE_SIZE");
        UUID id = UUID.randomUUID();
        store.insert(id, bundleHash(frozen, metadata), metadata, frozen, clock.instant());
        return get(id);
    }
    @Transactional(readOnly = true)
    public SnapshotRepository.Stored get(UUID id) {
        var stored = store.get(id);
        if (!bundleHash(stored.files(), stored.metadata()).equals(stored.hash())) throw new AgentException("SNAPSHOT_TAMPERED");
        return stored;
    }
    @Transactional
    public SnapshotRepository.Stored demo(String fixture) {
        var files = readPublishedBundle();
        if (!Set.of("baseline", "broken-contract", "repaired").contains(fixture)) throw new AgentException("UNKNOWN_FIXTURE");
        if (!fixture.equals("baseline")) {
            String path = "skills/inventory-check/SKILL.md";
            String text = new String(files.get(path), StandardCharsets.UTF_8);
            text = text.replace("inventory.available", "inventory.reservable_quantity");
            if (fixture.equals("broken-contract")) text = text.replace(", inventory.reserved", "");
            else text += "\n예약 가능 수량이 주문 수량 이상이면 inventory.reserved를 true로 기록한다.\n";
            files.put(path, text.getBytes(StandardCharsets.UTF_8));
        }
        return publish(files, Map.of("source", "demo-fixture", "fixture", fixture));
    }
    public static String bundleHash(SortedMap<String, byte[]> files, Map<String, Object> metadata) {
        var hashes = new TreeMap<String, Object>(); files.forEach((p,b) -> hashes.put(p, Json.hash(b)));
        return Json.hash(Map.of("files", hashes, "metadata", metadata));
    }
    private void validatePath(String path) {
        if (!path.startsWith("skills/") || path.length() > 500 || path.contains("\\") || path.contains("\u0000"))
            throw new AgentException("INVALID_BUNDLE_PATH");
        for (var segment : path.split("/", -1)) if (segment.isEmpty() || segment.equals(".") || segment.equals(".."))
            throw new AgentException("INVALID_BUNDLE_PATH");
    }
}
