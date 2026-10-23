package com.capstone4.skillops.workspace;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import com.capstone4.skillops.common.RandomIds;
import com.capstone4.skillops.skill.FlowStructureValidator;
import com.capstone4.skillops.skill.SkillMetadataValidator;
import com.capstone4.skillops.skill.SkillMetadataValidator.Violation;
import com.capstone4.skillops.snapshot.DependencyAnalyzer;
import com.capstone4.skillops.snapshot.DependencyGraph;
import com.capstone4.skillops.snapshot.OrderAgentClient;
import com.capstone4.skillops.snapshot.OrderAgentException;
import com.capstone4.skillops.snapshot.SkillPaths;
import com.capstone4.skillops.snapshot.SkillSource;
import com.capstone4.skillops.snapshot.SnapshotManifest;

import tools.jackson.databind.PropertyNamingStrategies;
import tools.jackson.databind.annotation.JsonNaming;

/**
 * 연습 공간을 만들고(⑦ create_workspace) 연습 공간의 skill 에 수정을 넣는(⑧ apply_patch) 서비스다.
 * 운영 중인 skill 은 그대로 두고 원문을 복사해 workspaces 표에 저장한다. 이후의 수정(apply_patch)과 시험(run_scenarios)은
 * 모두 이 복사본에서만 일어나므로, 진단 Agent 가 가설 실험과 후보 검증을 여러 번 해도 운영 skill 은 바뀌지 않는다.
 *
 * 시작점은 둘 가운데 하나다. snapshot_id 가 없으면 운영 버전에 start_versions 를 덮어쓴 버전의 원문을
 * SkillVersionStore(지금은 운영 폴더를 읽는 LiveFolderSkillVersionStore)에서 읽는다. snapshot_id 로 시작하면 주문 Agent 가
 * 보관한 snapshot 의 skills/ 아래 파일 전부를 OrderAgentClient 로 받아 복사한다. 주문 Agent 에서 실제로 돌리는
 * 실행기(HarnessFlowRunner)는 snapshot 에서 시작한 연습 공간만 받으며, Agent 의 노드는 지금 언제나 snapshot_id 로 연습
 * 공간을 만든다.
 *
 * service/src/main/java/com/capstone4/skillops/mcp/WorkspaceTools.java 가 create 와 apply 를 MCP 도구로 내놓는다.
 * service/src/main/java/com/capstone4/skillops/scenario/ScenarioRunner.java 는 skillsOf 를,
 * service/src/main/java/com/capstone4/skillops/scenario/HarnessFlowRunner.java 는 sourceSnapshotIdOf 와 filesOf 를 읽는다.
 *
 * 흐름을 바꿀 때 고칠 곳은 다음과 같다. 연습 공간 번호 모양은 newId, created_at 정밀도는 saveNew, snapshot 안에서
 * skill 로 볼 파일 규칙은 SKILL_MD_PATH, 끊긴 연결을 셀 flow 는 ENTRY_SKILL, 수정 거부 정책은 rejectUnreadableHeaders,
 * 버전 번호는 changedSkillsOf, 입력 누락은 brokenLinksOf, 선행 단계 누락은 dependencyViolationsOf가 정한다.
 * diff 를 읽는 규칙은 PatchApplier 에 있다.
 */
@Service
public class WorkspaceService {

    /**
     * snapshot manifest 에서 skill 원문으로 볼 경로 skills/&lt;이름&gt;/SKILL.md 의 정규식이다. 그룹 1 이 skill 이름이다.
     * 더 깊은 폴더의 SKILL.md 는 skill 이 아니라 자원으로 보고 resources 에 복사한다.
     */
    private static final Pattern SKILL_MD_PATH = Pattern.compile("skills/([^/]+)/SKILL\\.md");
    /**
     * 끊긴 연결을 검사할 때 steps 를 읽는 flow skill 의 이름이다. analyze 가 C-1 분석기에 넘기고,
     * service/src/main/java/com/capstone4/skillops/scenario/ContractOnlyFlowRunner.java 도 이 상수로 실행 순서를 읽는다.
     */
    public static final String ENTRY_SKILL = "order-flow";

    /**
     * create_workspace 도구의 결과다. 계약 5 v1 의 {workspace_id, skill_versions, created_at} 모양이고, 이름은
     * {@code @JsonNaming} 이 snake_case 로 바꾼다. 키 이름과 순서는
     * service/src/test/java/com/capstone4/skillops/mcp/WorkspaceToolsTest.java 가 고정한다.
     */
    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record CreatedWorkspace(String workspaceId, Map<String, Integer> skillVersions, Instant createdAt) {
    }

    /**
     * apply_patch 도구 입력 patches 의 항목 하나다. MCP 가 넘긴 {skill, base_version, patch} 를 snake_case 이름으로 읽는다.
     *
     * @param skill       고칠 skill 이름이다
     * @param baseVersion 수정 내용을 만들 때 기준으로 삼은 버전이다. 연습 공간 안 그 skill 의 시작 버전과 같아야 한다
     * @param patch       원문에 적용할 unified diff 다
     */
    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record PatchInput(String skill, int baseVersion, String patch) {
    }

    /**
     * apply_patch 결과 changed_skills 의 항목 하나다.
     *
     * @param version 수정이 들어간 뒤의 버전이다. 시작 버전에 이 연습 공간에서 그 skill 에 수정이 들어간 횟수를 더한 값이다
     */
    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record ChangedSkill(String skill, int version) {
    }

    /**
     * apply_patch 결과 broken_links 의 항목 하나다.
     *
     * @param missingInput skill 이 받는다고 적었지만 주문 입력에도, 앞선 skill 의 outputs 에도 없는 키다
     */
    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record BrokenLink(String skill, String missingInput) {
    }

    /** apply_patch 결과에서 앞서 완료되지 않은 depends_on 단계를 나타낸다. */
    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record DependencyViolation(String skill, String missingDependency) {
    }

    /** patch 적용 결과에 연결·필수 정보·실행 구조 오류를 구분해 담는다. */
    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record PatchResult(String workspaceId, List<ChangedSkill> changedSkills, List<BrokenLink> brokenLinks,
            List<DependencyViolation> dependencyViolations, List<Violation> metadataErrors,
            List<FlowStructureValidator.Violation> flowErrors) {
    }

    /** workspaces 표의 JPA 저장소다. 이 서비스는 findById 와 save 만 쓴다. */
    private final WorkspaceRepository repository;
    /** snapshot_id 없이 시작할 때 skill 원문을 읽는 버전 보관함이다. */
    private final SkillVersionStore store;
    /** snapshot_id 로 시작할 때만 쓰는 주문 Agent 클라이언트다. 두 인자 생성자로 만든 서비스(단위 시험)에서는 null 이다. */
    private final OrderAgentClient orderAgent;

    /**
     * Spring 이 쓰는 생성자다. 생성자가 둘이라서 @Autowired 로 Spring 이 고를 생성자를 정한다.
     * WorkspaceRepository, SkillVersionStore 구현(지금은 LiveFolderSkillVersionStore), C-1 의 OrderAgentClient 빈을 받는다.
     */
    @Autowired
    public WorkspaceService(WorkspaceRepository repository, SkillVersionStore store, OrderAgentClient orderAgent) {
        this.repository = repository;
        this.store = store;
        this.orderAgent = orderAgent;
    }

    /**
     * 운영 폴더에서만 시작하는 서비스를 만든다. 테스트가 주문 Agent 없이 쓴다.
     * 이 서비스로 snapshot_id 시작을 부르면 IllegalStateException 이 난다.
     */
    public WorkspaceService(WorkspaceRepository repository, SkillVersionStore store) {
        this(repository, store, null);
    }

    /**
     * 운영 폴더에서 시작한다. {@code create(startVersions, null)} 과 같고,
     * service/src/test/java/com/capstone4/skillops/workspace/WorkspaceServiceTest.java 에서만 부른다.
     */
    public CreatedWorkspace create(Map<String, Integer> startVersions) {
        return create(startVersions, null);
    }

    /**
     * create_workspace 의 본체다. snapshot_id 가 없으면 운영 폴더에서, 있으면 그 snapshot 에서 연습 공간을 만든다.
     * 빈 start_versions 는 넣지 않은 것으로 보므로 snapshot_id 와 함께 와도 된다.
     * service/src/main/java/com/capstone4/skillops/mcp/WorkspaceTools.java 의 createWorkspace 가 부른다.
     *
     * @param startVersions 도구 입력 start_versions 다. skill 이름별 시작 버전이고, 적지 않은 skill 은 운영 버전으로
     *                      시작한다. null 이면 모두 운영 버전이다
     * @param snapshotId    도구 입력 snapshot_id 다. 주면 그 주문 Agent snapshot 의 skills/ 아래 파일을 복사해 시작한다.
     *                      start_versions 와 함께 쓸 수 없다. null 이면 운영 폴더에서 시작한다
     * @throws IllegalArgumentException snapshot_id 와 비어 있지 않은 start_versions 를 함께 넣으면 던진다
     */
    public CreatedWorkspace create(Map<String, Integer> startVersions, String snapshotId) {
        if (snapshotId == null) {
            return createFromLive(startVersions);
        }
        if (startVersions != null && !startVersions.isEmpty()) {
            throw new IllegalArgumentException("snapshot_id 와 start_versions 는 함께 쓸 수 없습니다");
        }
        return createFromSnapshot(snapshotId);
    }

    /**
     * 운영 폴더(버전 보관함)의 skill 원문을 복사해 연습 공간을 만든다. 시작 버전은 startVersionsOver 가 정하고,
     * 원문은 SkillVersionStore.fullText 로 읽는다. 없는 버전을 요청하면 fullText 의 IllegalArgumentException 이
     * 그대로 올라간다.
     */
    private CreatedWorkspace createFromLive(Map<String, Integer> startVersions) {
        Map<String, Integer> versions = startVersionsOver(store.liveVersions(), startVersions);
        // 원문을 모두 읽은 뒤에 저장한다. 읽다가 실패하면 아무것도 남지 않는다.
        Map<String, WorkspaceSkill> skills = new TreeMap<>();
        versions.forEach((skill, version) -> skills.put(skill, new WorkspaceSkill(version, store.fullText(skill, version))));
        return saveNew(skills, Map.of(), null);
    }

    /**
     * 운영 버전 위에 start_versions 를 덮어쓴 시작 버전을 skill 이름 순으로 돌려준다.
     * 운영에 없는 skill 이나 버전 번호가 빠진 항목이 있으면 IllegalArgumentException 을 던진다.
     * 그 버전의 원문이 실제로 있는지는 여기서 보지 않고 createFromLive 가 원문을 읽을 때 드러난다.
     */
    private static Map<String, Integer> startVersionsOver(Map<String, Integer> live, Map<String, Integer> startVersions) {
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
        return versions;
    }

    /**
     * 주문 Agent snapshot 의 skills/ 아래 파일을 모두 읽어 복사한다. 파일 내용은 끝 줄바꿈까지 고치지 않는다.
     * C-1 의 service/src/main/java/com/capstone4/skillops/snapshot/OrderAgentClient.java 에서 getSnapshot
     * (GET /api/snapshots/{id})으로 manifest 를 받고, 파일마다 readText(GET /api/snapshots/{id}/files)로 글을 받는다.
     * 주문 Agent 쪽에서는
     * order-agent/backend/src/main/java/com/capstone4/orderagent/controller/EvidenceController.java 가 두 요청을 받는다.
     * skills/&lt;이름&gt;/SKILL.md 는 맨 위 칸의 version 을 시작 버전으로 삼아 skill 로 두고, 나머지 skills/ 아래 파일은
     * resources 에 둔다. skills/ 밖의 파일은 읽지 않는다.
     * 하나라도 읽지 못하면 오류를 그대로 올리고 아무것도 저장하지 않는다. OrderAgentException 을 바꾸지 않고 올리므로
     * 그 code 와 retryable 이 도구 오류로 Agent 까지 간다. skill 이 하나도 없으면 IllegalArgumentException 을,
     * 주문 Agent 연결 없이 만든 서비스면 IllegalStateException 을 던진다.
     */
    private CreatedWorkspace createFromSnapshot(String snapshotId) {
        if (orderAgent == null) {
            throw new IllegalStateException("주문 Agent 연결이 설정되지 않아 snapshot_id 로 시작할 수 없습니다");
        }
        SnapshotManifest manifest = orderAgent.getSnapshot(snapshotId);
        Map<String, WorkspaceSkill> skills = new TreeMap<>();
        Map<String, String> resources = new TreeMap<>();
        for (SnapshotManifest.FileEntry file : manifest.files()) {
            String path = file.path();
            if (!path.startsWith(SkillPaths.SKILLS_DIR)) {
                continue;
            }
            String text = orderAgent.readText(snapshotId, path);
            Matcher skillMd = SKILL_MD_PATH.matcher(path);
            if (skillMd.matches()) {
                skills.put(skillMd.group(1), new WorkspaceSkill(versionOf(path, text), text));
            } else {
                resources.put(path, text);
            }
        }
        if (skills.isEmpty()) {
            throw new IllegalArgumentException("snapshot " + snapshotId + " 에 skills/<이름>/SKILL.md 가 없습니다");
        }
        return saveNew(skills, resources, snapshotId);
    }

    /**
     * 새 연습 공간을 OPEN 상태로 저장하고 create_workspace 결과를 만든다. createFromLive 와 createFromSnapshot 이 함께 쓴다.
     * created_at 은 DB 정밀도인 마이크로초로 잘라, 돌려준 값과 DB 에서 다시 읽은 값이 같게 한다.
     * skill_versions 는 이름 순으로 정렬한 읽기 전용 맵으로 돌려준다.
     */
    private CreatedWorkspace saveNew(Map<String, WorkspaceSkill> skills, Map<String, String> resources,
            String sourceSnapshotId) {
        WorkspaceRecord record = WorkspaceRecord.open(newId(), Instant.now().truncatedTo(ChronoUnit.MICROS), skills,
                resources, sourceSnapshotId);
        repository.save(record);
        return new CreatedWorkspace(record.getWorkspaceId(), Collections.unmodifiableMap(versionsOf(skills)),
                record.getCreatedAt());
    }

    /** skill 이름별 시작 버전(baseVersion)을 이름 순으로 정렬한 맵으로 돌려준다. */
    private static Map<String, Integer> versionsOf(Map<String, WorkspaceSkill> skills) {
        Map<String, Integer> versions = new TreeMap<>();
        skills.forEach((skill, workspaceSkill) -> versions.put(skill, workspaceSkill.baseVersion()));
        return versions;
    }

    /**
     * snapshot 에서 받은 SKILL.md 의 맨 위 칸 version 을 읽는다. createFromSnapshot 이 시작 버전을 정할 때 부른다.
     * 맨 위 칸을 찾지 못하거나 version 이 없으면 파일 경로를 앞에 붙인 IllegalArgumentException 을 던지고,
     * 도구 오류 code 는 INVALID_INPUT 이 된다.
     */
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

    /**
     * 연습 공간 안의 skill 원문 전부를 skill 이름별로 돌려준다. 없는 번호면 IllegalArgumentException 을 던진다.
     * service/src/main/java/com/capstone4/skillops/scenario/ScenarioRunner.java 의 run 이 시험을 돌리기 전에 연습 공간이
     * 있는지 확인하고 실행기에 넘길 원문을 얻으려고 부른다.
     */
    public Map<String, WorkspaceSkill> skillsOf(String workspaceId) {
        return find(workspaceId).getSkills();
    }

    /**
     * 연습 공간을 시작한 주문 Agent snapshot 번호를 돌려준다. 운영 폴더에서 시작했으면 null 이다.
     * 없는 번호면 IllegalArgumentException 을 던진다.
     * service/src/main/java/com/capstone4/skillops/scenario/HarnessFlowRunner.java 가 snapshot 에서 시작한 연습 공간인지
     * 볼 때(requireSnapshotStart) 부르고, 그 값을 후보 snapshot 의 metadata(source_snapshot_id)에도 싣는다.
     */
    public String sourceSnapshotIdOf(String workspaceId) {
        return find(workspaceId).getSourceSnapshotId();
    }

    /**
     * 연습 공간의 모든 파일을 경로별 글로 돌려준다. 없는 번호면 IllegalArgumentException 을 던진다.
     * skills/&lt;이름&gt;/SKILL.md 에는 수정이 반영된 skill 원문을, 그 밖의 경로에는 시작할 때 복사한 글을 그대로 넣는다.
     * service/src/main/java/com/capstone4/skillops/scenario/HarnessFlowRunner.java 의 runDryRun 이 이 묶음을 그대로 주문
     * Agent 후보 snapshot 으로 올린다. resources 칸이 null 인 옛 행이면 skill 파일만 돌려준다.
     */
    public Map<String, String> filesOf(String workspaceId) {
        WorkspaceRecord record = find(workspaceId);
        Map<String, String> files = new TreeMap<>(record.getResources());
        record.getSkills().forEach((skill, text) -> files.put(SkillPaths.skillMd(skill), text.fullText()));
        return files;
    }

    /**
     * apply_patch 의 본체다. 연습 공간의 skill 에 수정(unified diff)을 차례로 적용하고, 고친 뒤에 끊긴 연결이 남았는지
     * 검사한다. service/src/main/java/com/capstone4/skillops/mcp/WorkspaceTools.java 의 applyPatch 가 부른다.
     * 전부 적용하거나 하나도 적용하지 않는다. 하나라도 실패하면 IllegalArgumentException 을 던지고 아무것도 저장하지 않는다.
     * 연결·필수 정보·flow 구조 오류가 있어도 기존 거부 조건에 걸리지 않으면 저장하고 오류 목록을 돌려준다.
     * Agent의 _judge는 연결 검사와 별도로 metadata_valid와 flow_valid를 판정한다.
     * 같은 skill 을 여러 번 고치면 앞의 수정이 들어간 글 위에 다음 수정을 적용한다.
     *
     * 단계는 입력 검사, 연습 공간 조회와 OPEN 확인, 복사본에 수정 적용(applyOne), 연결 분석(analyze), 맨 위 칸을 깨는 수정
     * 거부(rejectUnreadableHeaders), 저장 순이다. 저장은 모든 검사를 통과한 뒤 맨 끝에 한 번만 한다.
     *
     * @param patches 적힌 순서대로 적용한다. 비어 있으면 안 된다
     * @throws IllegalArgumentException 없는 연습 공간이나 skill, 사용 중이 아닌 연습 공간, 기준 버전 불일치, 원문과 맞지 않는
     *                                  수정, 고치면 맨 위 칸을 읽을 수 없게 되는 수정, entry flow 를 읽을 수 없는 연습 공간이면
     *                                  던진다
     */
    public PatchResult apply(String workspaceId, List<PatchInput> patches) {
        if (patches == null || patches.isEmpty()) {
            throw new IllegalArgumentException("patches 에 수정 내용을 하나 이상 넣으세요");
        }
        WorkspaceRecord record = find(workspaceId);
        requireOpen(record, workspaceId);

        // 복사본에 적용한다. record 는 모두 성공한 뒤에만 바꾼다.
        Map<String, WorkspaceSkill> skills = new TreeMap<>(record.getSkills());
        Set<String> changed = new LinkedHashSet<>();
        for (PatchInput patch : patches) {
            changed.add(applyOne(skills, patch));
        }
        DependencyGraph graph = analyze(skills);
        rejectUnreadableHeaders(graph, changed);
        List<Violation> metadataErrors = metadataErrorsOf(skills);
        List<FlowStructureValidator.Violation> flowErrors = FlowStructureValidator.validate(graph);

        record.replaceSkills(skills);
        repository.save(record);
        return new PatchResult(workspaceId, changedSkillsOf(skills, changed), brokenLinksOf(graph),
                dependencyViolationsOf(graph), metadataErrors, flowErrors);
    }

    /** 실행기 후보 조회처럼 flow 밖의 Skill까지 필수 정보를 검사하고 이름순으로 오류를 모은다. */
    private static List<Violation> metadataErrorsOf(Map<String, WorkspaceSkill> skills) {
        return skills.entrySet().stream()
                .flatMap(entry -> SkillMetadataValidator.validate(entry.getKey(), entry.getValue().fullText()).stream())
                .toList();
    }

    /**
     * 사용 중(OPEN)이 아닌 연습 공간이면 IllegalArgumentException 을 던진다. 지금 코드에는 상태를 OPEN 밖으로 바꾸는 곳이
     * 없고, service/src/test/java/com/capstone4/skillops/workspace/ApplyPatchTest.java 가 PROMOTED 를 직접 넣어 이 검사를
     * 확인한다.
     */
    private static void requireOpen(WorkspaceRecord record, String workspaceId) {
        if (record.getStatus() != WorkspaceRecord.Status.OPEN) {
            throw new IllegalArgumentException("workspace_id " + workspaceId + " 은 사용 중(OPEN)이 아니라 "
                    + record.getStatus() + " 상태라서 수정할 수 없습니다");
        }
    }

    /**
     * 수정 하나를 skills 복사본에 적용하고 고친 skill 이름을 돌려준다. 성공하면 그 skill 의 revision 을 1 올린다.
     * base_version 은 앞서 같은 skill 을 고쳤더라도 revision 이 아니라 시작 버전(baseVersion)과 비교한다. 그래서 부르는 쪽은
     * create_workspace 가 준 skill_versions 값을 그대로 넣으면 된다. PatchApplier 의 오류 문구 앞에는 skill 이름과 ': ' 을
     * 붙여 어느 수정이 실패했는지 알린다.
     */
    private static String applyOne(Map<String, WorkspaceSkill> skills, PatchInput patch) {
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
        return skill;
    }

    /**
     * 고친 skill 가운데 C-1 분석기가 맨 위 칸을 읽지 못한 것(parseError)이 있으면 IllegalArgumentException 을 던진다.
     * 고치지 않은 skill 은 원래 깨져 있어도 넘어가므로, 이미 깨진 skill 이 있는 연습 공간에서도 다른 skill 은 고칠 수 있다.
     * 분석기는 entry flow 의 steps 에 있는 skill 만 노드로 만들므로, steps 밖의 skill 과 order-flow 자신의 맨 위 칸은 이
     * 검사가 보지 않는다. order-flow 의 steps 를 읽을 수 없게 되는 수정은 analyze 가 먼저 막는다.
     * 수정 거부 정책을 바꾸려면 이 메서드를 고친다.
     */
    private static void rejectUnreadableHeaders(DependencyGraph graph, Set<String> changed) {
        for (DependencyGraph.Node node : graph.nodes()) {
            if (node.parseError() != null && changed.contains(node.skillId())) {
                throw new IllegalArgumentException(
                        node.skillId() + ": 수정하면 SKILL.md 맨 위 칸을 읽을 수 없게 됩니다. " + node.parseError());
            }
        }
    }

    /**
     * 고친 skill 마다 {skill, version} 을 처음 고친 순서대로 만든다. 같은 skill 을 여러 번 고쳐도 한 번만 나온다.
     * version 은 시작 버전에 이 연습 공간에서의 수정 횟수(revision)를 더한 값이다.
     */
    private static List<ChangedSkill> changedSkillsOf(Map<String, WorkspaceSkill> skills, Set<String> changed) {
        return changed.stream().map(skill -> {
            WorkspaceSkill now = skills.get(skill);
            return new ChangedSkill(skill, now.baseVersion() + now.revision());
        }).toList();
    }

    /**
     * 분석 결과의 끊긴 연결을 broken_links 항목으로 펼친다. 고친 skill 만이 아니라 entry flow 의 steps 전체에서 찾은
     * 끊긴 연결을 담는다.
     * 빠진 입력만 기존 broken_links에 넣고 선행 단계 누락은 dependencyViolationsOf가 따로 보존한다.
     */
    private static List<BrokenLink> brokenLinksOf(DependencyGraph graph) {
        return graph.missing().stream()
                .flatMap(m -> m.missingInputs().stream().map(input -> new BrokenLink(m.skillId(), input)))
                .toList();
    }

    /** 전체 flow에서 발견한 선행 단계 누락을 선언 순서대로 별도 목록에 보존한다. */
    private static List<DependencyViolation> dependencyViolationsOf(DependencyGraph graph) {
        return graph.missing().stream()
                .flatMap(missing -> missing.missingDependencies().stream()
                        .map(dependency -> new DependencyViolation(missing.skillId(), dependency)))
                .toList();
    }

    /**
     * C-1 분석기(service/src/main/java/com/capstone4/skillops/snapshot/DependencyAnalyzer.java 의 analyze)로 연습 공간의
     * 연결 관계를 계산한다. 이 분석기의 연결 판단 규칙은 주문 Agent 의
     * order-agent/backend/src/main/java/com/capstone4/orderagent/harness/SkillLoader.java 에 있는 validateFlow 와 같다.
     * 다만 첫 위반에서 멈추지 않고 steps 끝까지 계산한다.
     * entry flow(ENTRY_SKILL)가 없거나 읽을 수 없으면 C-1 의 OrderAgentException 을 "order-flow: " 로 시작하는
     * IllegalArgumentException 으로 바꿔, 도구 오류 code 가 INVALID_INPUT 이 되게 한다.
     */
    private static DependencyGraph analyze(Map<String, WorkspaceSkill> skills) {
        Map<String, SkillSource> sources = new TreeMap<>();
        skills.forEach((id, skill) -> sources.put(id, SkillSource.fromText(SkillPaths.skillMd(id), skill.fullText())));
        try {
            return DependencyAnalyzer.analyze(ENTRY_SKILL, sources);
        } catch (OrderAgentException e) {
            throw new IllegalArgumentException(ENTRY_SKILL + ": " + e.getMessage(), e);
        }
    }

    /**
     * workspaces 표에서 연습 공간을 찾는다. 없으면 create_workspace 를 먼저 부르라는 IllegalArgumentException 을 던진다.
     * skillsOf, sourceSnapshotIdOf, filesOf, apply 가 모두 이 메서드를 거치므로 apply_patch 와 run_scenarios 의
     * '없는 연습 공간' 오류 문구가 같다.
     */
    private WorkspaceRecord find(String workspaceId) {
        return repository.findById(workspaceId)
                .orElseThrow(() -> new IllegalArgumentException(
                        "workspace_id " + workspaceId + " 이 없습니다. create_workspace 로 먼저 만드세요"));
    }

    /**
     * 연습 공간 번호를 w_ 와 영숫자 8자로 만든다. 이미 있는 번호와 겹치는지는 검사하지 않는다. 번호 모양을 바꾸면 모양을
     * 고정한 service/src/test/java/com/capstone4/skillops/workspace/WorkspaceServiceTest.java 와
     * service/src/test/java/com/capstone4/skillops/workspace/WorkspaceFromSnapshotTest.java, w_ 접두어를 확인하는
     * skillops-agent/scripts/c2_smoke.py 도 함께 고친다.
     */
    private static String newId() {
        return RandomIds.next("w_", 8, RandomIds.ALPHANUMERIC);
    }
}
