package com.capstone4.orderagent.workspace;

import java.nio.charset.StandardCharsets;
import java.util.*;
import com.capstone4.orderagent.harness.*;
import org.springframework.stereotype.Service;

/** Reads immutable snapshot bytes; shares execution-time metadata validation with the loader. */
@Service
public class SkillQueryService {
    private final SnapshotService snapshots;
    private final SkillLoader loader;

    public SkillQueryService(SnapshotService snapshots, SkillLoader loader) {
        this.snapshots = snapshots;
        this.loader = loader;
    }

    public Map<String, Object> list(UUID id) {
        var snapshot = snapshots.get(id);
        var items = new ArrayList<Map<String, Object>>();
        var errors = new ArrayList<Map<String, Object>>();
        for (String path : snapshot.files().keySet()) {
            if (!path.matches("skills/[^/]+/SKILL\\.md")) continue;
            String skillId = path.split("/")[1];
            try {
                items.add(metadata(loader.load(snapshot.files(), skillId)));
            } catch (AgentException error) {
                errors.add(Map.of("path", path, "code", error.category(),
                    "message", "Skill 메타데이터를 해석할 수 없습니다: " + error.category()));
            }
        }
        items.sort(Comparator.comparing(item -> (String) item.get("skill_id")));
        return Map.of("snapshot_id", id, "snapshot_hash", snapshot.hash(), "items", items,
            "partial", !errors.isEmpty(), "parse_errors", errors);
    }

    public Map<String, Object> get(UUID id, String skillId) {
        // Validate names before resolving any path. Never resolve an operating-system path here.
        SkillLoader.validateName(skillId);
        var snapshot = snapshots.get(id);
        String path = "skills/" + skillId + "/SKILL.md";
        byte[] bytes = snapshot.files().get(path);
        if (bytes == null) throw new AgentException("SKILL_NOT_FOUND");
        SkillLoader.Skill skill;
        try {
            skill = loader.load(snapshot.files(), skillId);
        } catch (AgentException error) {
            throw new AgentException("SKILL_METADATA_INVALID");
        }
        var result = new LinkedHashMap<>(metadata(skill));
        result.put("snapshot_id", id);
        result.put("snapshot_hash", snapshot.hash());
        result.put("size", bytes.length);
        result.put("encoding", "utf-8");
        result.put("content", new String(bytes, StandardCharsets.UTF_8));
        return result;
    }

    private Map<String, Object> metadata(SkillLoader.Skill skill) {
        var result = new LinkedHashMap<String, Object>();
        result.put("skill_id", skill.id());
        result.put("name", skill.id());
        result.put("version", skill.version());
        result.put("description", skill.description());
        result.put("owner", skill.owner());
        result.put("path", skill.path());
        result.put("hash", skill.hash());
        result.put("inputs", skill.inputs());
        result.put("outputs", skill.outputs());
        result.put("depends_on", skill.dependsOn());
        result.put("steps", skill.steps());
        return result;
    }
}
