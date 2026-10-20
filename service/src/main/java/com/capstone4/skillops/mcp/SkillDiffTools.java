package com.capstone4.skillops.mcp;

import org.springframework.ai.mcp.annotation.McpTool;
import org.springframework.ai.mcp.annotation.McpToolParam;
import org.springframework.stereotype.Component;

import com.capstone4.skillops.snapshot.SkillDiffService;

import io.modelcontextprotocol.spec.McpSchema.CallToolResult;

/** C-1 MCP 도구 diff_skill. 주문 Agent snapshot 두 개에서 같은 skill 을 비교한다. */
@Component
public class SkillDiffTools {

    private final SkillDiffService diffService;

    public SkillDiffTools(SkillDiffService diffService) {
        this.diffService = diffService;
    }

    @McpTool(name = "diff_skill", description = """
            같은 skill 의 SKILL.md 를 두 snapshot(기준·실패) 사이에서 비교해 바뀐 곳을 돌려준다.
            담당자가 무엇을 바꿨는지 확인할 때 쓴다. 실패 이벤트의 baseline_snapshot.snapshot_id 를 before,
            snapshot.snapshot_id 를 after 로 넣는다. 기준 snapshot 이 없으면 부르지 않는다.
            돌려주는 값: skill_id, before/after {snapshot_id, snapshot_hash, path, file_hash},
            status(modified·added·removed·unchanged), frontmatter_changes[{field, before, after, added, removed}],
            hunks[{index, hunk_id, part(frontmatter·body), header, before, after, diff}],
            frontmatter_parse_error, related_files[{path, status, before_hash, after_hash}].
            hunk_id 는 다시 조회해도 같은 덩어리를 가리키는 고정 ID 이고, diff 는 그 덩어리만 담은 unified diff 다.
            실패하면 isError=true 와 {code, message, retryable, upstream_status, snapshot_id} 를 돌려준다.""")
    // 매개변수 이름이 곧 MCP 입력 스키마의 키라서, 주문 Agent 명세·결과 칸과 같은 snake_case 로 둔다.
    public CallToolResult diffSkill(
            @McpToolParam(description = "skill ID. snapshot 의 skills/<skill_id>/SKILL.md 폴더 이름 (예: inventory-check)")
            String skill_id,
            @McpToolParam(description = "기준(정상) snapshot UUID") String before_snapshot_id,
            @McpToolParam(description = "비교 대상(실패) snapshot UUID") String after_snapshot_id) {
        return ToolResults.run(() -> diffService.diffSkill(skill_id, before_snapshot_id, after_snapshot_id));
    }
}
