package com.capstone4.skillops.snapshot;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeSet;

import org.springframework.stereotype.Service;

/**
 * diff_skill 을 조립하는 서비스다. 두 snapshot 의 manifest 에서 같은 skill 폴더의 파일 해시를 모으고, SKILL.md 해시가 다를
 * 때만 본문을 읽어 SkillDiffer 로 비교한다. 같은 폴더의 다른 파일은 해시로만 비교한다.
 * skill_id 로 만든 경로는 manifest 에 그 경로가 있을 때만 요청하고, 사용자 입력으로 만든 경로를 그대로 보내지 않는다.
 * service/src/main/java/com/capstone4/skillops/mcp/SkillDiffTools.java 의 diffSkill 이 부른다. 주문 Agent 조회는
 * service/src/main/java/com/capstone4/skillops/snapshot/OrderAgentClient.java 가, 글 비교는
 * service/src/main/java/com/capstone4/skillops/snapshot/SkillDiffer.java 가 맡는다.
 */
@Service
public class SkillDiffService {

    private final OrderAgentClient orderAgent;

    /** Spring 이 OrderAgentClient 빈을 넣어 만든다. 테스트는 mock 을 넣는다. */
    public SkillDiffService(OrderAgentClient orderAgent) {
        this.orderAgent = orderAgent;
    }

    /**
     * skill_id 형식을 검사한 뒤 before 와 after manifest 를 이 순서로 받고, 두 쪽의 SKILL.md 해시로 비교 결과를 만든다.
     * 형식이 틀리면 주문 Agent 를 부르기 전에 snapshot_id 없이 INVALID_INPUT 을 던진다. 두 snapshot 모두에 SKILL.md 가 없으면
     * after snapshot_id 를 실은 SKILL_NOT_FOUND 를 던진다. 한쪽에만 있으면 added 나 removed 이고, 없는 쪽 Side 의 path 와
     * fileHash 는 null 이다. skill 폴더 안 경로가 겹치거나 해시가 null 이면 SnapshotManifest.hashByPathUnder 가 예외를 던진다.
     */
    public DiffSkillResult diffSkill(String skillId, String beforeSnapshotId, String afterSnapshotId) {
        SkillPaths.requireSkillId("skill_id", skillId, null);
        SnapshotManifest before = orderAgent.getSnapshot(beforeSnapshotId);
        SnapshotManifest after = orderAgent.getSnapshot(afterSnapshotId);
        String skillDir = SkillPaths.skillDir(skillId);
        String skillMd = SkillPaths.skillMd(skillId);

        Map<String, String> beforeFiles = before.hashByPathUnder(skillDir);
        Map<String, String> afterFiles = after.hashByPathUnder(skillDir);
        String beforeHash = beforeFiles.get(skillMd);
        String afterHash = afterFiles.get(skillMd);
        if (beforeHash == null && afterHash == null) {
            throw new OrderAgentException("SKILL_NOT_FOUND", "두 snapshot 모두에 " + skillMd + " 가 없습니다.",
                    false, null, afterSnapshotId);
        }

        SkillFileDiff diff = compareSkillMd(skillMd, beforeSnapshotId, beforeHash, afterSnapshotId, afterHash);

        return new DiffSkillResult(skillId,
                side(before, beforeHash == null ? null : skillMd, beforeHash),
                side(after, afterHash == null ? null : skillMd, afterHash),
                diff.status(), diff.frontmatterChanges(), diff.hunks(), diff.frontmatterParseError(),
                relatedFiles(beforeFiles, afterFiles, skillMd));
    }

    /**
     * 두 해시가 같으면 파일을 읽지도 해석하지도 않고 unchanged 를 돌려준다. 그래서 깨진 YAML 이어도 parse error 가 생기지
     * 않는다. 다르면 있는 쪽만 before, after 순서로 읽어 SkillDiffer.diff 로 비교하고, 두 해시를 hunk_id 재료로 함께 넘긴다.
     */
    private SkillFileDiff compareSkillMd(String skillMd, String beforeSnapshotId, String beforeHash,
            String afterSnapshotId, String afterHash) {
        if (Objects.equals(beforeHash, afterHash)) {
            return new SkillFileDiff(SkillFileDiff.UNCHANGED, List.of(), List.of(), null);
        }
        String beforeText = readIfPresent(beforeSnapshotId, skillMd, beforeHash);
        String afterText = readIfPresent(afterSnapshotId, skillMd, afterHash);
        return SkillDiffer.diff(skillMd, beforeText, afterText, beforeHash, afterHash);
    }

    /** manifest 에 그 파일이 있을 때(해시가 있을 때)만 글을 읽는다. 없으면 null 을 돌려준다. */
    private String readIfPresent(String snapshotId, String path, String hash) {
        return hash == null ? null : orderAgent.readText(snapshotId, path);
    }

    /** manifest 의 snapshot_id 와 hash 에 SKILL.md 의 path 와 fileHash 를 더해 결과의 한쪽(Side)을 만든다. */
    private static DiffSkillResult.Side side(SnapshotManifest manifest, String path, String fileHash) {
        return new DiffSkillResult.Side(manifest.snapshotId(), manifest.hash(), path, fileHash);
    }

    /**
     * 같은 skill 폴더에서 SKILL.md 를 뺀 파일들을 경로순으로 해시만 비교해 related_files 를 만든다. 내용은 읽지 않는다.
     * 상태는 SkillFileDiff.statusOf 로 정하므로 SKILL.md 와 같은 규칙(added, removed, unchanged, modified)을 따른다.
     */
    private static List<DiffSkillResult.RelatedFile> relatedFiles(Map<String, String> before, Map<String, String> after,
            String skillMd) {
        TreeSet<String> paths = new TreeSet<>(before.keySet());
        paths.addAll(after.keySet());
        paths.remove(skillMd);
        List<DiffSkillResult.RelatedFile> result = new ArrayList<>();
        for (String p : paths) {
            result.add(new DiffSkillResult.RelatedFile(p, SkillFileDiff.statusOf(before.get(p), after.get(p)),
                    before.get(p), after.get(p)));
        }
        return result;
    }
}
