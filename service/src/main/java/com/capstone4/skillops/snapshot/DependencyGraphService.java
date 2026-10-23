package com.capstone4.skillops.snapshot;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;

import org.springframework.stereotype.Service;

/**
 * get_dependency_graph 를 조립하는 서비스다. 주문 Agent snapshot 에서 entry flow 와 그 steps 의 SKILL.md 를 받아
 * service/src/main/java/com/capstone4/skillops/snapshot/DependencyAnalyzer.java 로 연결 관계를 계산하고, snapshot_id 와
 * snapshot_hash 를 붙여 돌려준다. 파일 해시는 주문 Agent manifest 값을 그대로 쓴다.
 * manifest 에 있는 경로만 요청한다. manifest 에 없거나 skill ID 형식이 틀린 단계는 받지 않으며, 분석기에서 UNKNOWN_SKILL
 * 노드가 된다. 어떤 단계를 받아 올지 바꾸려면 fetchFlowSources 를 고친다.
 * service/src/main/java/com/capstone4/skillops/mcp/DependencyGraphTools.java 의 getDependencyGraph 가 부른다. 주문 Agent
 * 조회는 service/src/main/java/com/capstone4/skillops/snapshot/OrderAgentClient.java 가 맡는다.
 */
@Service
public class DependencyGraphService {

    private final OrderAgentClient orderAgent;

    /** Spring 이 OrderAgentClient 빈을 넣어 만든다. 테스트는 mock 을 넣는다. */
    public DependencyGraphService(OrderAgentClient orderAgent) {
        this.orderAgent = orderAgent;
    }

    /**
     * entry_skill_id 형식을 검사하고 manifest 를 받은 뒤, entry 와 단계 SKILL.md 를 읽어 연결 관계를 계산한다.
     * 형식이 틀리면 주문 Agent 를 부르기 전에 snapshot_id 를 실은 INVALID_INPUT 을 던진다. 주문 Agent 조회 실패는
     * OrderAgentException 으로 그대로 올라간다. manifest 에 같은 경로가 두 번 있거나 해시가 null 이면
     * SnapshotManifest.hashByPath 가 IllegalStateException 이나 NullPointerException 을 던지는데, 이 예외는 MCP 결과에서
     * 구조화 오류가 되지 않는다.
     */
    public DependencyGraphResult dependencyGraph(String snapshotId, String entrySkillId) {
        SkillPaths.requireSkillId("entry_skill_id", entrySkillId, snapshotId);
        SnapshotManifest manifest = orderAgent.getSnapshot(snapshotId);
        Map<String, SkillSource> sources = fetchFlowSources(snapshotId, entrySkillId, manifest.hashByPath());
        return DependencyGraphResult.of(manifest, DependencyAnalyzer.analyze(entrySkillId, sources));
    }

    /**
     * entry 와 그 steps 의 SKILL.md 를 받아 skill ID 를 키로 담는다. entry 를 먼저 읽고, steps 는 적힌 순서대로 읽는다.
     * manifest 에 있고 skill ID 형식이 맞는 단계만 받는다. entry 가 manifest 에 없으면 snapshot_id 를 실은 SKILL_NOT_FOUND 를
     * 던진다. 받지 않은 단계는 결과 맵에 없으므로 DependencyAnalyzer 가 UNKNOWN_SKILL 노드로 남긴다.
     * entry 를 steps 에 다시 적으면 단계로서 한 번 더 읽는다.
     */
    private Map<String, SkillSource> fetchFlowSources(String snapshotId, String entrySkillId,
            Map<String, String> hashByPath) {
        Map<String, SkillSource> sources = new LinkedHashMap<>();
        SkillSource entry = fetch(snapshotId, entrySkillId, hashByPath);
        if (entry == null) {
            throw SkillPaths.notInSnapshot(entrySkillId, snapshotId);
        }
        sources.put(entrySkillId, entry);
        // steps 순서대로 읽되 같은 skill 은 한 번만 받는다. 분석기는 중복 단계를 두 번 계산하지만 조회는 한 번이면 된다.
        for (String step : new LinkedHashSet<>(DependencyAnalyzer.flowSteps(entrySkillId, entry))) {
            if (SkillPaths.isSkillId(step)) {
                SkillSource source = fetch(snapshotId, step, hashByPath);
                if (source != null) {
                    sources.put(step, source);
                }
            }
        }
        return sources;
    }

    /**
     * skillId 의 SKILL.md 경로가 manifest 에 있으면 글을 읽어 manifest 해시와 함께 SkillSource 로 돌려주고, 없으면 null 을
     * 돌려준다. 경로는 SkillPaths.skillMd 로 만들지만 manifest 에 있는 경로만 요청한다.
     */
    private SkillSource fetch(String snapshotId, String skillId, Map<String, String> hashByPath) {
        String path = SkillPaths.skillMd(skillId);
        String hash = hashByPath.get(path);
        return hash == null ? null : new SkillSource(path, hash, orderAgent.readText(snapshotId, path));
    }
}
