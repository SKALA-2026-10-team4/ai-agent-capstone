package com.capstone4.skillops.snapshot;

import java.util.List;

import tools.jackson.databind.PropertyNamingStrategies;
import tools.jackson.databind.annotation.JsonNaming;

/**
 * MCP 도구 diff_skill 의 결과 record 다. 한 skill 의 SKILL.md 를 두 snapshot 사이에서 비교한 결과(SkillFileDiff)에 양쪽
 * snapshot 정보와 같은 폴더 다른 파일의 상태를 붙인다. 칸 순서가 곧 JSON 키 순서이고 값이 null 인 칸도 그대로 나간다.
 * service/src/main/java/com/capstone4/skillops/snapshot/SkillDiffService.java 의 diffSkill 이 만들고
 * service/src/main/java/com/capstone4/skillops/mcp/ToolResults.java 의 run 이 JSON 으로 쓴다.
 * 진단 Agent 는 skillops-agent/skillops_agent/contracts.py 의 check_diff_evidence 로 before 와 after 의 snapshot_id,
 * file_hash, snapshot_hash 가 고정한 snapshot 과 맞는지, hunks 마다 hunk_id 와 diff 가 있는지 검사한다.
 * skillops-agent/skillops_agent/stub_tools.py 의 StubMCPTools.diff_skill 은 이 모양의 일부를 흉내 낸 고정 결과를 돌려준다.
 *
 * @param status       modified, added, removed, unchanged 가운데 하나다.
 * @param relatedFiles 같은 skill 폴더의 다른 파일(resources 등)이다. 해시로만 비교하고 내용은 읽지 않는다.
 */
// TODO(C-1/C-2/B, 계약 확정 후): 지금 결과의 skill_id, before, after, hunks 는 v1 계약 문서
// (docs/contracts/mcp-tools-input-output.md 4장)의 skill, from_version, to_version, changes 와 다르다.
// Hunk 의 index(0부터)는 change_no(1부터)에, header 는 location 에, diff 는 patch 에, part 의 frontmatter 값은 header 에
// 매핑한다. 지금은 진단 Agent(skillops-agent/skillops_agent/nodes.py 의 _collect_changes)가 hunk 마다 change_no 를 직접 붙인다.
// apply_patch 도 같은 바뀌기 전 파일 기준 patch 를 받게 맞추고, 고정 hunk_id 와 양쪽 해시를 잃지 않는다.
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public record DiffSkillResult(
        String skillId,
        Side before,
        Side after,
        String status,
        List<SkillFileDiff.FrontmatterChange> frontmatterChanges,
        List<SkillFileDiff.Hunk> hunks,
        String frontmatterParseError,
        List<RelatedFile> relatedFiles) {

    /**
     * 비교한 한쪽 snapshot 과 그 안의 SKILL.md 다.
     *
     * @param snapshotHash snapshot 전체 해시이며 주문 Agent 값 그대로다.
     * @param path         그 snapshot 에 SKILL.md 가 없으면 null 이다.
     * @param fileHash     그 snapshot 에 SKILL.md 가 없으면 null 이다.
     */
    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record Side(String snapshotId, String snapshotHash, String path, String fileHash) {
    }

    /**
     * 같은 skill 폴더의 다른 파일 하나를 해시로 비교한 결과다. 한쪽에 파일이 없으면 그쪽 해시는 null 이다.
     *
     * @param status modified, added, removed, unchanged 가운데 하나다.
     */
    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record RelatedFile(String path, String status, String beforeHash, String afterHash) {
    }
}
