package com.capstone4.skillops.snapshot;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeSet;
import java.util.function.Function;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import org.springframework.stereotype.Service;

/**
 * 두 snapshot 에서 같은 skill 의 SKILL.md 를 찾아 비교한다.
 * 경로는 manifest 에 있는 값만 쓰고, skill_id 로 경로를 직접 만들어 요청하지 않는다.
 */
@Service
public class SkillDiffService {

    private static final Pattern SKILL_ID = Pattern.compile("^[a-z0-9][a-z0-9-]*$");

    private final OrderAgentClient orderAgent;

    public SkillDiffService(OrderAgentClient orderAgent) {
        this.orderAgent = orderAgent;
    }

    public DiffSkillResult diffSkill(String skillId, String beforeSnapshotId, String afterSnapshotId) {
        if (skillId == null || !SKILL_ID.matcher(skillId).matches()) {
            throw new OrderAgentException("INVALID_INPUT", "skill_id 는 소문자·숫자·하이픈만 씁니다: " + skillId,
                    false, null, null);
        }
        SnapshotManifest before = orderAgent.getSnapshot(beforeSnapshotId);
        SnapshotManifest after = orderAgent.getSnapshot(afterSnapshotId);
        String skillDir = "skills/" + skillId + "/";
        String skillMd = skillDir + "SKILL.md";

        Map<String, String> beforeFiles = filesUnder(before, skillDir);
        Map<String, String> afterFiles = filesUnder(after, skillDir);
        String beforeHash = beforeFiles.get(skillMd);
        String afterHash = afterFiles.get(skillMd);
        if (beforeHash == null && afterHash == null) {
            throw new OrderAgentException("SKILL_NOT_FOUND", "두 snapshot 모두에 " + skillMd + " 가 없습니다.",
                    false, null, afterSnapshotId);
        }

        SkillFileDiff diff = Objects.equals(beforeHash, afterHash)
                ? new SkillFileDiff("unchanged", List.of(), List.of(), null)
                : SkillDiffer.diff(skillMd,
                        beforeHash == null ? null : orderAgent.readText(beforeSnapshotId, skillMd),
                        afterHash == null ? null : orderAgent.readText(afterSnapshotId, skillMd),
                        beforeHash, afterHash);

        return new DiffSkillResult(skillId,
                side(before, beforeHash == null ? null : skillMd, beforeHash),
                side(after, afterHash == null ? null : skillMd, afterHash),
                diff.status(), diff.frontmatterChanges(), diff.hunks(), diff.frontmatterParseError(),
                relatedFiles(beforeFiles, afterFiles, skillMd));
    }

    private static Map<String, String> filesUnder(SnapshotManifest manifest, String dir) {
        return manifest.files().stream()
                .filter(f -> f.path().startsWith(dir))
                .collect(Collectors.toMap(SnapshotManifest.FileEntry::path, SnapshotManifest.FileEntry::hash));
    }

    private static DiffSkillResult.Side side(SnapshotManifest manifest, String path, String fileHash) {
        return new DiffSkillResult.Side(manifest.snapshotId(), manifest.hash(), path, fileHash);
    }

    private static List<DiffSkillResult.RelatedFile> relatedFiles(Map<String, String> before, Map<String, String> after,
            String skillMd) {
        TreeSet<String> paths = new TreeSet<>(before.keySet());
        paths.addAll(after.keySet());
        paths.remove(skillMd);
        Function<String, String> statusOf = p -> {
            String b = before.get(p);
            String a = after.get(p);
            return b == null ? "added" : a == null ? "removed" : b.equals(a) ? "unchanged" : "modified";
        };
        List<DiffSkillResult.RelatedFile> result = new ArrayList<>();
        for (String p : paths) {
            result.add(new DiffSkillResult.RelatedFile(p, statusOf.apply(p), before.get(p), after.get(p)));
        }
        return result;
    }
}
