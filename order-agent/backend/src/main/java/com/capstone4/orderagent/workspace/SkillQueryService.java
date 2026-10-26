package com.capstone4.orderagent.workspace;

import java.nio.charset.StandardCharsets;
import java.util.*;
import com.capstone4.orderagent.harness.*;
import org.springframework.stereotype.Service;

/**
 * 고정된 snapshot 의 Skill 목록과 본문을 진단용으로 조회한다. snapshot 바이트만 읽고 파일 시스템은 보지 않으며,
 * 실행 로더(order-agent/backend/src/main/java/com/capstone4/orderagent/harness/SkillLoader.java)와 같은 해석과 검증을 써서
 * 진단 Agent 가 보는 메타데이터가 실행 때 Harness 가 본 것과 어긋나지 않게 한다. 읽기 전에 SnapshotService.get 이 해시를 다시 검증한다.
 * order-agent/backend/src/main/java/com/capstone4/orderagent/controller/EvidenceController.java 의 skills(GET /api/snapshots/{id}/skills)와
 * skill(GET /api/snapshots/{id}/skills/{skillId})이 부른다. 응답은 skillops-agent/skillops_agent/order_agent.py 의 list_skills, get_skill 과
 * frontend/src/api/skillSets.js 의 loadSkillSet 이 읽는다.
 */
@Service
public class SkillQueryService {
    private final SnapshotService snapshots;
    private final SkillLoader loader;

    /** Spring 이 snapshot 서비스와 Skill 로더를 주입해 빈으로 만든다. */
    public SkillQueryService(SnapshotService snapshots, SkillLoader loader) {
        this.snapshots = snapshots;
        this.loader = loader;
    }

    // TODO(C-1/B, 공용 계약 확정 후): HTTP 응답의 items, skill_id, name 과 v1 계약의 skills, skill 응답을 어떻게 바꿀지 합의한다.
    // v1 의 운영본이나 버전 조회와 달리 여기서는 snapshot_id 가 필수다. 진단하는 동안 조회 대상을 고정해야 하기 때문이다.
    // saved_at, saved_by, last_working_version 을 채우려면 Registry 연결이 필요하다. snapshot 생성 시각이나 파일의 version 선언만으로
    // 저장 이력이나 정상 버전을 추측하지 말고, 정상 실행의 기준도 함께 합의한다.
    /**
     * snapshot 의 Skill 목록을 메타데이터와 함께 돌려준다. skills 폴더 바로 아래 폴더의 SKILL.md 마다 SkillLoader.load 로 해석하고,
     * 실패한 파일은 목록에서 빼 parse_errors 에 경로, 오류 코드, 문구를 남긴다. 하나라도 실패하면 partial 이 true 다.
     * 실행 경로의 후보 목록(SkillLoader.candidates)은 깨진 파일이 하나라도 있으면 전체가 실패하지만, 진단 조회는 나머지 Skill 을 보여 주고
     * 깨진 파일을 parse_errors 로 알린다(order-agent/docs/diagnosis-read-api.md). partial 이 true 인 목록은 완전한 그래프의 근거로 쓰지 않으며,
     * 해석에 성공했다고 Skill 사이 계약 검증을 통과했다는 뜻은 아니다.
     * 후보 목록과 달리 이름 규칙에 맞지 않는 폴더도 고르며, 그런 파일은 parse_errors 에 INVALID_SKILL_NAME 으로 남는다.
     * items 는 skill_id 순이다. 응답 키는 snapshot_id, snapshot_hash, items, partial, parse_errors 이고 Map.of 라 키 순서는 보장하지 않는다.
     */
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

    // TODO(C-1/B, 공용 계약 확정 후): HTTP 응답의 content 와 평면 메타데이터 필드는 v1 계약의 full_text, header 와 다르다.
    // 호출하는 쪽에서 바꿀 때 원문 바이트, 줄 위치, 해시를 유지하고, version 에서 snapshot 이나 파일 참조로 가는 대응은
    // Registry 의 불변 매핑을 쓴다. 같은 version 을 선언한 다른 후보를 같은 파일로 취급하지 않는다.
    /**
     * Skill 하나의 메타데이터와 SKILL.md 원문을 돌려준다. 이름 규칙 위반은 INVALID_SKILL_NAME, snapshot 이 없거나 변조됐으면
     * SNAPSHOT_NOT_FOUND 나 SNAPSHOT_TAMPERED, 파일이 없으면 SKILL_NOT_FOUND, 해석에 실패하면 원래 오류 대신 SKILL_METADATA_INVALID 다.
     * 응답은 metadata 의 키 뒤에 snapshot_id, snapshot_hash, size, encoding(늘 utf-8), content 를 이 순서로 붙인다.
     * content 는 원본 바이트를 UTF-8 로 읽은 글이며, 해석할 때와 달리 줄바꿈을 바꾸지 않는다. 해석에 성공한 파일만 돌려주므로 늘 UTF-8 로 읽힌다.
     * 해석에 실패한 파일의 원문은 GET /api/snapshots/{id}/files 로 읽는다.
     */
    public Map<String, Object> get(UUID id, String skillId) {
        // 경로를 만들기 전에 이름을 먼저 검사한다. 운영체제 경로로는 절대 해석하지 않고 snapshot 파일 맵의 키로만 쓴다.
        SkillLoader.validateName(skillId);
        var snapshot = snapshots.get(id);
        String path = SkillLoader.skillPath(skillId);
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

    /**
     * 해석한 Skill 을 응답 메타데이터로 바꾼다. 키 순서는 skill_id, name, version, description, owner, path, hash, inputs, outputs, depends_on, steps, rules 다.
     * front matter 의 name 은 폴더 이름과 같아야 하므로 name 은 늘 skill_id 와 같다. hash 는 원본 바이트의 SHA-256 이라
     * GET /api/snapshots/{id}/files 의 hash 와 같다. flow Skill 은 steps 가 있고 단계 Skill 은 steps 가 빈 목록이다.
     * rules 는 실행 로더가 읽고 검증한 실행 규칙을 front matter 순서대로 {when, then, reason} 목록으로 담은 추가 칸이며, 규칙이 없거나 flow Skill 이면 빈 목록이다.
     * 기존 소비자(skillops-agent/skillops_agent/contracts.py 의 check_skill_listing, partial_evidence.py, frontend/src/api/skillSets.js)는
     * 모르는 칸을 무시하고, skillSets.js 는 이 칸이 있으면 원문 front matter 대신 이 값을 쓴다.
     */
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
        result.put("rules", skill.rules().stream().map(SkillQueryService::rule).toList());
        return result;
    }

    /** 실행 규칙 한 줄을 when, then, reason 순서의 Map 으로 바꾼다. 해석한 조건(condition)은 응답에 넣지 않는다. */
    private static Map<String, Object> rule(SkillLoader.Rule rule) {
        var result = new LinkedHashMap<String, Object>();
        result.put("when", rule.when());
        result.put("then", rule.then());
        result.put("reason", rule.reason());
        return result;
    }
}
