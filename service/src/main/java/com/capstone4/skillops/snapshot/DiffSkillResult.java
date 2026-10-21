package com.capstone4.skillops.snapshot;

import java.util.List;

import tools.jackson.databind.PropertyNamingStrategies;
import tools.jackson.databind.annotation.JsonNaming;

/**
 * MCP 도구 diff_skill 의 결과. 한 skill 의 SKILL.md 를 두 snapshot 사이에서 비교한다.
 *
 * @param status       modified · added · removed · unchanged
 * @param relatedFiles 같은 skill 폴더의 다른 파일(resources 등). 해시로만 비교하고 내용은 읽지 않는다
 */
// TODO(C-1/C-2/B, 계약 확정 후): 현재 skill_id/before/after/hunks와 v1 skill/from_version/to_version/changes가 다르다.
// Hunk의 index(0부터)->change_no(1부터), header->location, diff->patch, part frontmatter->header를 매핑한다.
// apply_patch도 같은 바뀌기 전 파일 기준 patch를 받게 맞추고, stable hunk_id와 양쪽 해시를 잃지 않는다.
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
     * @param snapshotHash snapshot 전체 해시 (주문 Agent 값 그대로)
     * @param path         그 snapshot 에 SKILL.md 가 없으면 null
     * @param fileHash     그 snapshot 에 SKILL.md 가 없으면 null
     */
    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record Side(String snapshotId, String snapshotHash, String path, String fileHash) {
    }

    /** @param status modified · added · removed · unchanged */
    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record RelatedFile(String path, String status, String beforeHash, String afterHash) {
    }
}
