package com.capstone4.skillops.snapshot;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import org.springframework.stereotype.Service;

/**
 * snapshot 에서 entry flow 와 그 steps 의 SKILL.md 를 받아 연결 관계를 계산한다.
 * manifest 에 있는 경로만 요청하고, manifest 에 없는 단계는 받지 않고 UNKNOWN_SKILL 로 남긴다.
 */
@Service
public class DependencyGraphService {

    private static final Pattern SKILL_ID = Pattern.compile("^[a-z0-9][a-z0-9-]*$");

    private final OrderAgentClient orderAgent;

    public DependencyGraphService(OrderAgentClient orderAgent) {
        this.orderAgent = orderAgent;
    }

    public DependencyGraphResult dependencyGraph(String snapshotId, String entrySkillId) {
        if (entrySkillId == null || !SKILL_ID.matcher(entrySkillId).matches()) {
            throw new OrderAgentException("INVALID_INPUT", "entry_skill_id 는 소문자·숫자·하이픈만 씁니다: " + entrySkillId,
                    false, null, snapshotId);
        }
        SnapshotManifest manifest = orderAgent.getSnapshot(snapshotId);
        Map<String, String> hashByPath = manifest.files().stream()
                .collect(Collectors.toMap(SnapshotManifest.FileEntry::path, SnapshotManifest.FileEntry::hash));

        Map<String, SkillSource> sources = new LinkedHashMap<>();
        SkillSource entry = fetch(snapshotId, entrySkillId, hashByPath);
        if (entry == null) {
            throw new OrderAgentException("SKILL_NOT_FOUND", "snapshot 에 skills/" + entrySkillId + "/SKILL.md 가 없습니다.",
                    false, null, snapshotId);
        }
        sources.put(entrySkillId, entry);
        // steps 순서대로, 같은 skill 은 한 번만 받는다.
        for (String step : new LinkedHashSet<>(DependencyAnalyzer.flowSteps(entrySkillId, entry))) {
            if (SKILL_ID.matcher(step).matches()) {
                SkillSource source = fetch(snapshotId, step, hashByPath);
                if (source != null) {
                    sources.put(step, source);
                }
            }
        }
        return DependencyGraphResult.of(manifest, DependencyAnalyzer.analyze(entrySkillId, sources));
    }

    private SkillSource fetch(String snapshotId, String skillId, Map<String, String> hashByPath) {
        String path = "skills/" + skillId + "/SKILL.md";
        String hash = hashByPath.get(path);
        return hash == null ? null : new SkillSource(path, hash, orderAgent.readText(snapshotId, path));
    }
}
