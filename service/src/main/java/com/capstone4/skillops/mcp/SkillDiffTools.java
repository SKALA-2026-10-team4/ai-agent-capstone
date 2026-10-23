package com.capstone4.skillops.mcp;

import org.springframework.ai.mcp.annotation.McpTool;
import org.springframework.ai.mcp.annotation.McpToolParam;
import org.springframework.stereotype.Component;

import com.capstone4.skillops.snapshot.SkillDiffService;

import io.modelcontextprotocol.spec.McpSchema.CallToolResult;

/**
 * C-1 MCP 도구 diff_skill 을 등록한다. 주문 Agent snapshot 두 개(기준과 실패)에서 같은 skill 의 SKILL.md 를 비교해
 * 바뀐 덩어리(hunk)와 frontmatter 키 변화를 돌려준다.
 * Spring 이 빈으로 만들고, Spring AI MCP Server 의 annotation scanner(service/src/main/resources/application.yml 의
 * spring.ai.mcp.server.annotation-scanner)가 @McpTool 메서드를 찾아 도구로 노출한다.
 * 계산은 service/src/main/java/com/capstone4/skillops/snapshot/SkillDiffService.java 가 하고, 결과와 오류 모양은
 * service/src/main/java/com/capstone4/skillops/mcp/ToolResults.java 의 run 이 정한다.
 * 진단 Agent 에서는 skillops-agent/skillops_agent/nodes.py 의 fetch_evidence(증거 수집 단계)가 _collect_changes 를 거쳐
 * skillops-agent/skillops_agent/tools.py 의 MCPTools.diff_skill 로 이 도구를 부른다. 돌려받은 hunk 의 diff 는 원인 확인
 * (ablation)에서 변경을 하나씩 되돌리는 patch 재료가 된다. skillops-agent/scripts/b_c_contract_smoke.py 와
 * skillops-agent/scripts/c2_real_judge.py 도 이 도구를 부른다.
 */
@Component
public class SkillDiffTools {

    private final SkillDiffService diffService;

    /** Spring 이 SkillDiffService 빈을 넣어 만든다. */
    public SkillDiffTools(SkillDiffService diffService) {
        this.diffService = diffService;
    }

    // 입력은 skill_id, before_snapshot_id, after_snapshot_id 이고, 진단 Agent(skillops-agent/skillops_agent/tools.py 의
    // MCPTools.diff_skill)도 이 이름으로 보낸다. 입출력 표는 docs/contracts/mcp-tools-input-output.md 의 diff_skill 절에 있다.
    // 버전 번호로 비교하는 입력을 더하려면 Registry 버전을 불변 snapshot 으로 바꾸는 변환 계층을 따로 두고,
    // 버전 정수를 snapshot UUID 자리에 넘기지 않는다.
    /**
     * before_snapshot_id 와 after_snapshot_id 의 snapshot 에서 skill_id 의 SKILL.md 를 비교한 결과를 JSON 으로 돌려준다.
     * 계산은 SkillDiffService.diffSkill 에 맡긴다. OrderAgentException 으로 올라온 실패(주문 Agent 조회 실패, 입력 형식
     * 오류, SKILL_NOT_FOUND, SKILL_FILE_NOT_TEXT)는 ToolResults.run 이 isError=true 구조화 오류로 바꾸고, 그 밖의 예외는
     * SDK 의 글 오류가 된다. 입력 키 이름과 설명 글의 결과 칸 목록, 오류 모양은
     * service/src/test/java/com/capstone4/skillops/mcp/SnapshotToolsTest.java 가 확인한다.
     */
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
    // 매개변수 이름이 곧 MCP 입력 스키마의 키라서 주문 Agent 명세와 결과 칸에 맞춰 snake_case 로 둔다.
    public CallToolResult diffSkill(
            @McpToolParam(description = "skill ID. snapshot 의 skills/<skill_id>/SKILL.md 폴더 이름 (예: inventory-check)")
            String skill_id,
            @McpToolParam(description = "기준(정상) snapshot UUID") String before_snapshot_id,
            @McpToolParam(description = "비교 대상(실패) snapshot UUID") String after_snapshot_id) {
        return ToolResults.run(() -> diffService.diffSkill(skill_id, before_snapshot_id, after_snapshot_id));
    }
}
