package com.capstone4.skillops.workspace;

import java.security.SecureRandom;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Collections;
import java.util.Map;
import java.util.TreeMap;

import org.springframework.stereotype.Service;

import tools.jackson.databind.PropertyNamingStrategies;
import tools.jackson.databind.annotation.JsonNaming;

/**
 * ⑦ 연습 공간 만들기. 운영 중인 skill 은 그대로 두고 원문을 복사해 workspaces 에 저장한다.
 * 이후의 수정(apply_patch)과 시험(run_scenarios)은 모두 이 복사본에서만 일어난다.
 */
@Service
public class WorkspaceService {

    private static final String ID_CHARS = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789";

    /** create_workspace 도구의 결과. 계약 5 v0 의 {ws_id} 에 시작 버전과 만든 시각을 더했다. */
    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record CreatedWorkspace(String wsId, Map<String, Integer> skillVersions, Instant createdAt) {
    }

    private final WorkspaceRepository repository;
    private final SkillVersionStore store;
    private final SecureRandom random = new SecureRandom();

    public WorkspaceService(WorkspaceRepository repository, SkillVersionStore store) {
        this.repository = repository;
        this.store = store;
    }

    /**
     * @param startVersions 도구 입력 base. skill 이름 → 시작 버전. 적지 않은 skill 은 운영 버전. null 이면 전부 운영 버전
     */
    public CreatedWorkspace create(Map<String, Integer> startVersions) {
        Map<String, Integer> live = store.liveVersions();
        Map<String, Integer> versions = new TreeMap<>(live);
        if (startVersions != null) {
            startVersions.forEach((skill, version) -> {
                if (!live.containsKey(skill)) {
                    throw new IllegalArgumentException(skill + " 이라는 skill 이 없습니다. 있는 skill: " + live.keySet());
                }
                if (version == null) {
                    throw new IllegalArgumentException("base 의 " + skill + " 에 버전 번호를 넣으세요");
                }
                versions.put(skill, version);
            });
        }
        // 원문을 모두 읽은 뒤에 저장한다. 읽다가 실패하면 아무것도 남지 않는다.
        Map<String, WorkspaceSkill> skills = new TreeMap<>();
        versions.forEach((skill, version) -> skills.put(skill, new WorkspaceSkill(version, store.fullText(skill, version))));

        WorkspaceRecord record = WorkspaceRecord.open(newId(), Instant.now().truncatedTo(ChronoUnit.MICROS), skills);
        repository.save(record);
        return new CreatedWorkspace(record.getWorkspaceId(), Collections.unmodifiableMap(versions), record.getCreatedAt());
    }

    /** 연습 공간 안의 skill 원문 전부. 없는 번호면 IllegalArgumentException. */
    public Map<String, WorkspaceSkill> skillsOf(String workspaceId) {
        return repository.findById(workspaceId)
                .map(WorkspaceRecord::getSkills)
                .orElseThrow(() -> new IllegalArgumentException(
                        "ws_id " + workspaceId + " 이 없습니다. create_workspace 로 먼저 만드세요"));
    }

    private String newId() {
        StringBuilder id = new StringBuilder("w_");
        for (int i = 0; i < 8; i++) {
            id.append(ID_CHARS.charAt(random.nextInt(ID_CHARS.length())));
        }
        return id.toString();
    }
}
