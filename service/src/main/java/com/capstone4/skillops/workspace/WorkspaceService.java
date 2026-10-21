package com.capstone4.skillops.workspace;

import java.security.SecureRandom;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Collections;
import java.util.Map;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import com.capstone4.skillops.snapshot.OrderAgentClient;
import com.capstone4.skillops.snapshot.SnapshotManifest;

import tools.jackson.databind.PropertyNamingStrategies;
import tools.jackson.databind.annotation.JsonNaming;

/**
 * ⑦ 연습 공간 만들기. 운영 중인 skill 은 그대로 두고 원문을 복사해 workspaces 에 저장한다.
 * 이후의 수정(apply_patch)과 시험(run_scenarios)은 모두 이 복사본에서만 일어난다.
 * 시작점은 운영 폴더(start_versions) 또는 주문 Agent 의 snapshot(snapshot_id) 중 하나다.
 */
@Service
public class WorkspaceService {

    private static final String ID_CHARS = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789";
    private static final String SKILLS_PREFIX = "skills/";
    /** manifest 경로 skills/&lt;이름&gt;/SKILL.md. 더 깊은 폴더의 SKILL.md 는 skill 이 아니라 자원이다. */
    private static final Pattern SKILL_MD_PATH = Pattern.compile("skills/([^/]+)/SKILL\\.md");

    /** create_workspace 도구의 결과. 계약 5 v1 의 {workspace_id, skill_versions, created_at}. */
    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record CreatedWorkspace(String workspaceId, Map<String, Integer> skillVersions, Instant createdAt) {
    }

    private final WorkspaceRepository repository;
    private final SkillVersionStore store;
    /** snapshot 에서 시작할 때만 쓴다. 주문 Agent 연결 없이 만든 서비스(단위 시험)에서는 null */
    private final OrderAgentClient orderAgent;
    private final SecureRandom random = new SecureRandom();

    @Autowired
    public WorkspaceService(WorkspaceRepository repository, SkillVersionStore store, OrderAgentClient orderAgent) {
        this.repository = repository;
        this.store = store;
        this.orderAgent = orderAgent;
    }

    /** 운영 폴더에서만 시작하는 서비스. snapshot_id 로 시작하려 하면 IllegalStateException. */
    public WorkspaceService(WorkspaceRepository repository, SkillVersionStore store) {
        this(repository, store, null);
    }

    /** 운영 폴더에서 시작. {@code create(startVersions, null)} 과 같다. */
    public CreatedWorkspace create(Map<String, Integer> startVersions) {
        return create(startVersions, null);
    }

    /**
     * @param startVersions 도구 입력 start_versions. skill 이름 → 시작 버전. 적지 않은 skill 은 운영 버전. null 이면 전부 운영 버전
     * @param snapshotId    도구 입력 snapshot_id. 주면 그 주문 Agent snapshot 의 skills/ 파일을 복사해 시작한다.
     *                      start_versions 와 함께 쓸 수 없다. null 이면 운영 폴더에서 시작
     */
    public CreatedWorkspace create(Map<String, Integer> startVersions, String snapshotId) {
        if (snapshotId != null) {
            if (startVersions != null && !startVersions.isEmpty()) {
                throw new IllegalArgumentException("snapshot_id 와 start_versions 는 함께 쓸 수 없습니다");
            }
            return createFromSnapshot(snapshotId);
        }
        Map<String, Integer> live = store.liveVersions();
        Map<String, Integer> versions = new TreeMap<>(live);
        if (startVersions != null) {
            startVersions.forEach((skill, version) -> {
                if (!live.containsKey(skill)) {
                    throw new IllegalArgumentException(skill + " 이라는 skill 이 없습니다. 있는 skill: " + live.keySet());
                }
                if (version == null) {
                    throw new IllegalArgumentException("start_versions 의 " + skill + " 에 버전 번호를 넣으세요");
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

    /**
     * 주문 Agent snapshot 의 skills/ 파일을 모두 읽어 복사한다. 파일 내용은 고치지 않는다(끝 줄바꿈 포함).
     * 하나라도 읽지 못하면 오류를 그대로 올리고 아무것도 저장하지 않는다.
     */
    private CreatedWorkspace createFromSnapshot(String snapshotId) {
        if (orderAgent == null) {
            throw new IllegalStateException("주문 Agent 연결이 설정되지 않아 snapshot_id 로 시작할 수 없습니다");
        }
        SnapshotManifest manifest = orderAgent.getSnapshot(snapshotId);
        Map<String, Integer> versions = new TreeMap<>();
        Map<String, WorkspaceSkill> skills = new TreeMap<>();
        Map<String, String> resources = new TreeMap<>();
        for (SnapshotManifest.FileEntry file : manifest.files()) {
            String path = file.path();
            if (!path.startsWith(SKILLS_PREFIX)) {
                continue;
            }
            String text = orderAgent.readText(snapshotId, path);
            Matcher skillMd = SKILL_MD_PATH.matcher(path);
            if (skillMd.matches()) {
                int version = versionOf(path, text);
                skills.put(skillMd.group(1), new WorkspaceSkill(version, text));
                versions.put(skillMd.group(1), version);
            } else {
                resources.put(path, text);
            }
        }
        if (skills.isEmpty()) {
            throw new IllegalArgumentException("snapshot " + snapshotId + " 에 skills/<이름>/SKILL.md 가 없습니다");
        }

        WorkspaceRecord record = WorkspaceRecord.open(newId(), Instant.now().truncatedTo(ChronoUnit.MICROS), skills,
                resources, snapshotId);
        repository.save(record);
        return new CreatedWorkspace(record.getWorkspaceId(), Collections.unmodifiableMap(versions), record.getCreatedAt());
    }

    private static int versionOf(String path, String text) {
        Integer version;
        try {
            version = SkillHeader.parse(text).version();
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException(path + ": " + e.getMessage(), e);
        }
        if (version == null) {
            throw new IllegalArgumentException(path + " 맨 위 칸에 version 이 없습니다");
        }
        return version;
    }

    /** 연습 공간 안의 skill 원문 전부. 없는 번호면 IllegalArgumentException. */
    public Map<String, WorkspaceSkill> skillsOf(String workspaceId) {
        return find(workspaceId).getSkills();
    }

    /**
     * 연습 공간의 모든 파일. skills/&lt;이름&gt;/SKILL.md 는 skill 원문(수정 반영), 그 밖의 파일은 시작할 때 복사한 글 그대로.
     * 주문 Agent 후보 snapshot 을 만들 때 그대로 보낸다. 없는 번호면 IllegalArgumentException.
     */
    public Map<String, String> filesOf(String workspaceId) {
        WorkspaceRecord record = find(workspaceId);
        Map<String, String> files = new TreeMap<>(record.getResources());
        record.getSkills().forEach((skill, text) -> files.put(SKILLS_PREFIX + skill + "/SKILL.md", text.fullText()));
        return files;
    }

    private WorkspaceRecord find(String workspaceId) {
        return repository.findById(workspaceId)
                .orElseThrow(() -> new IllegalArgumentException(
                        "workspace_id " + workspaceId + " 이 없습니다. create_workspace 로 먼저 만드세요"));
    }

    private String newId() {
        StringBuilder id = new StringBuilder("w_");
        for (int i = 0; i < 8; i++) {
            id.append(ID_CHARS.charAt(random.nextInt(ID_CHARS.length())));
        }
        return id.toString();
    }
}
