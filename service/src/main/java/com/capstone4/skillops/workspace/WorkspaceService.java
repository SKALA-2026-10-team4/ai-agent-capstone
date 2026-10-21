package com.capstone4.skillops.workspace;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Collections;
import java.util.HexFormat;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import com.capstone4.skillops.snapshot.DependencyAnalyzer;
import com.capstone4.skillops.snapshot.DependencyGraph;
import com.capstone4.skillops.snapshot.OrderAgentClient;
import com.capstone4.skillops.snapshot.OrderAgentException;
import com.capstone4.skillops.snapshot.SkillSource;
import com.capstone4.skillops.snapshot.SnapshotManifest;

import tools.jackson.databind.PropertyNamingStrategies;
import tools.jackson.databind.annotation.JsonNaming;

/**
 * ⑦ 연습 공간 만들기, ⑧ 수정 적용하기. 운영 중인 skill 은 그대로 두고 원문을 복사해 workspaces 에 저장한다.
 * 이후의 수정(apply_patch)과 시험(run_scenarios)은 모두 이 복사본에서만 일어난다.
 * 시작점은 운영 폴더(start_versions) 또는 주문 Agent 의 snapshot(snapshot_id) 중 하나다.
 */
@Service
public class WorkspaceService {

    private static final String ID_CHARS = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789";
    private static final String SKILLS_PREFIX = "skills/";
    /** manifest 경로 skills/&lt;이름&gt;/SKILL.md. 더 깊은 폴더의 SKILL.md 는 skill 이 아니라 자원이다. */
    private static final Pattern SKILL_MD_PATH = Pattern.compile("skills/([^/]+)/SKILL\\.md");
    /** 끊긴 연결을 검사할 때 steps 를 읽는 flow skill */
    private static final String ENTRY_SKILL = "order-flow";

    /** create_workspace 도구의 결과. 계약 5 v1 의 {workspace_id, skill_versions, created_at}. */
    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record CreatedWorkspace(String workspaceId, Map<String, Integer> skillVersions, Instant createdAt) {
    }

    /**
     * apply_patch 도구 입력 patches 의 한 칸.
     *
     * @param skill       고칠 skill 이름
     * @param baseVersion 수정 내용을 만들 때 기준으로 삼은 버전. 연습 공간 안 그 skill 의 시작 버전과 같아야 한다
     * @param patch       unified diff
     */
    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record PatchInput(String skill, int baseVersion, String patch) {
    }

    /** @param version 수정이 들어간 뒤의 버전. 시작 버전 + 이 연습 공간에서 그 skill 에 수정이 들어간 횟수 */
    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record ChangedSkill(String skill, int version) {
    }

    /** @param missingInput skill 이 받는다고 적은 값인데 주문 입력에도, 앞선 skill 의 outputs 에도 없는 키 */
    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record BrokenLink(String skill, String missingInput) {
    }

    /** apply_patch 도구의 결과. 계약 5 v1 의 {workspace_id, changed_skills, broken_links}. */
    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record PatchResult(String workspaceId, List<ChangedSkill> changedSkills, List<BrokenLink> brokenLinks) {
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

    /**
     * 연습 공간의 skill 에 수정(unified diff)을 차례로 적용하고, 고친 뒤에 끊긴 연결이 남았는지 검사한다.
     * 전부 적용하거나 하나도 적용하지 않는다. 하나라도 실패하면 IllegalArgumentException 이고 아무것도 저장하지 않는다.
     * 끊긴 연결이 있어도 수정은 들어간다(목록으로 알려 줄 뿐이다). 같은 skill 을 여러 번 고치면 앞의 수정이 들어간 글 위에 적용한다.
     *
     * @param patches 적힌 순서대로 적용한다. 비어 있으면 안 된다
     * @throws IllegalArgumentException 없는 연습 공간·skill, 사용 중이 아닌 연습 공간, 기준 버전 불일치, 원문과 맞지 않는 수정,
     *                                  고치면 맨 위 칸을 읽을 수 없게 되는 수정
     */
    public PatchResult apply(String workspaceId, List<PatchInput> patches) {
        if (patches == null || patches.isEmpty()) {
            throw new IllegalArgumentException("patches 에 수정 내용을 하나 이상 넣으세요");
        }
        WorkspaceRecord record = find(workspaceId);
        if (record.getStatus() != WorkspaceRecord.Status.OPEN) {
            throw new IllegalArgumentException("workspace_id " + workspaceId + " 은 사용 중(OPEN)이 아니라 "
                    + record.getStatus() + " 상태라서 수정할 수 없습니다");
        }

        // 복사본에 적용한다. record 는 모두 성공한 뒤에만 바꾼다.
        Map<String, WorkspaceSkill> skills = new TreeMap<>(record.getSkills());
        Set<String> changed = new LinkedHashSet<>();
        for (PatchInput patch : patches) {
            if (patch == null || patch.skill() == null || patch.skill().isBlank()) {
                throw new IllegalArgumentException("patches 의 각 항목에 고칠 skill 이름을 넣으세요");
            }
            String skill = patch.skill();
            WorkspaceSkill current = skills.get(skill);
            if (current == null) {
                throw new IllegalArgumentException(skill + " 이라는 skill 이 연습 공간에 없습니다. 있는 skill: " + skills.keySet());
            }
            if (patch.baseVersion() != current.baseVersion()) {
                throw new IllegalArgumentException(skill + " 의 기준 버전이 맞지 않습니다 (연습 공간 v" + current.baseVersion()
                        + ", 수정 내용 v" + patch.baseVersion() + ")");
            }
            String text;
            try {
                text = PatchApplier.apply(current.fullText(), patch.patch());
            } catch (IllegalArgumentException e) {
                throw new IllegalArgumentException(skill + ": " + e.getMessage(), e);
            }
            skills.put(skill, new WorkspaceSkill(current.baseVersion(), text, current.revision() + 1));
            changed.add(skill);
        }

        DependencyGraph graph = analyze(skills);
        for (DependencyGraph.Node node : graph.nodes()) {
            if (node.parseError() != null && changed.contains(node.skillId())) {
                throw new IllegalArgumentException(
                        node.skillId() + ": 수정하면 SKILL.md 맨 위 칸을 읽을 수 없게 됩니다. " + node.parseError());
            }
        }

        record.replaceSkills(skills);
        repository.save(record);
        List<ChangedSkill> changedSkills = changed.stream().map(skill -> {
            WorkspaceSkill now = skills.get(skill);
            return new ChangedSkill(skill, now.baseVersion() + now.revision());
        }).toList();
        List<BrokenLink> brokenLinks = graph.missing().stream()
                .flatMap(m -> m.missingInputs().stream().map(input -> new BrokenLink(m.skillId(), input)))
                .toList();
        return new PatchResult(workspaceId, changedSkills, brokenLinks);
    }

    /** C-1 분석기로 연습 공간의 연결 관계를 계산한다. entry flow(order-flow)를 읽지 못하면 IllegalArgumentException. */
    private static DependencyGraph analyze(Map<String, WorkspaceSkill> skills) {
        Map<String, SkillSource> sources = new TreeMap<>();
        skills.forEach((id, skill) -> {
            String path = SKILLS_PREFIX + id + "/SKILL.md";
            sources.put(id, new SkillSource(path, sha256(skill.fullText()), skill.fullText()));
        });
        try {
            return DependencyAnalyzer.analyze(ENTRY_SKILL, sources);
        } catch (OrderAgentException e) {
            throw new IllegalArgumentException(ENTRY_SKILL + ": " + e.getMessage(), e);
        }
    }

    private static String sha256(String text) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
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
