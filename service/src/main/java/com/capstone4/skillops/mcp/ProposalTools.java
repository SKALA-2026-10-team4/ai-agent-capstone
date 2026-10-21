package com.capstone4.skillops.mcp;

import java.util.Map;

import org.springframework.ai.mcp.annotation.McpTool;
import org.springframework.ai.mcp.annotation.McpToolParam;
import org.springframework.stereotype.Component;

import com.capstone4.skillops.proposal.ProposalService;

import io.modelcontextprotocol.spec.McpSchema.CallToolResult;

/**
 * Agent 수정안 저장 MCP 도구. 저장된 id 를 ApprovalController(POST /api/proposals/{id}/approve·reject) 에서 쓴다.
 */
@Component
public class ProposalTools {

    private final ProposalService proposals;

    public ProposalTools(ProposalService proposals) {
        this.proposals = proposals;
    }

    @McpTool(name = "save_proposal", description = """
            진단 Agent 가 생성한 수정안을 저장하고 운영자 승인 대기 상태로 만든다.
            proposal 에는 root_cause, intent, candidates, recommended_candidate_no, status 가 들어간다.
            status 는 proposed(유효 후보 있음) 또는 no_valid_fix(유효 후보 없음) 중 하나다.
            돌려주는 값: {id, status}. id(p_ + UUID)는 이후 /api/proposals/{id}/approve·reject 에 넣는다.
            실패하면 isError=true 와 {code, message, retryable, upstream_status, snapshot_id} 를 돌려준다.""")
    public CallToolResult saveProposal(
            @McpToolParam(description = "저장할 수정안 전체 객체(Agent 의 proposal 필드)")
            Map<String, Object> proposal) {
        return WorkspaceToolResults.run(() -> proposals.save(proposal));
    }
}
