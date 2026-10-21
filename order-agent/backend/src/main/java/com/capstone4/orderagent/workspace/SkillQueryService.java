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

    // TODO(C-1/B, 공용 계약 확정 후): HTTP items/skill_id/name과 v1 skills/skill의 변환을 합의한다.
    // v1 운영본/버전 조회와 달리 여기서는 snapshot_id가 필수다. 진단 중 조회 대상을 고정해야 한다.
    // saved_at/saved_by/last_working_version은 Registry 연결이 필요하다. snapshot 생성 시각이나
    // 파일 version 선언만으로 저장 이력·정상 버전을 추측하지 말고, 정상 실행 기준도 함께 합의한다.
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

    // TODO(C-1/B, 공용 계약 확정 후): HTTP content/메타데이터 평면 필드와 v1 full_text/header가 다르다.
    // 호출 측 변환 시 원문 바이트·줄 위치·해시를 유지하고, version -> snapshot/파일 참조는
    // Registry의 불변 매핑을 사용한다. 동일 version 선언의 다른 후보를 같은 파일로 취급하지 않는다.
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
