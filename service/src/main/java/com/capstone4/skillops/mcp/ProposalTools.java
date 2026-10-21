package com.capstone4.skillops.mcp;

import java.util.Map;

import org.springframework.ai.mcp.annotation.McpTool;
import org.springframework.ai.mcp.annotation.McpToolParam;
import org.springframework.stereotype.Component;

import com.capstone4.skillops.proposal.ProposalService;

import io.modelcontextprotocol.spec.McpSchema.CallToolResult;

/**
 * 계약 5 §10 save_proposal (담당 C-2). Agent 가 진단을 마친 뒤 부른다.
 * 결과·오류 모양은 C-2 의 다른 도구와 같다({@link WorkspaceToolResults}).
 */
@Component
public class ProposalTools {

    private final ProposalService proposals;

    public ProposalTools(ProposalService proposals) {
        this.proposals = proposals;
    }

    @McpTool(name = "save_proposal", description = """
            진단을 마친 수정 제안서를 저장하고 운영자의 검토를 기다리게 한다.
            proposal 에는 thread_id, trigger, baseline_snapshot_id, snapshot_id, failure, root_cause, intent, candidates,
            recommended_candidate_no, status 가 들어간다. status 는 proposed(합격 후보 있음) 또는 no_valid_fix(합격 후보 없음)다.
            후보마다 candidate_no(1부터 빠짐없이), kind, workspace_id, patches, results, valid, validation_error, evidence_run_ids,
            rank, explanation 을 넣는다.
            합격 조건: fixes_failure 가 참, keeps_old_orders 의 passed 와 total 이 같고 total 이 1 이상, keeps_intent 가 참,
            no_broken_links 가 참. 서비스가 다시 계산하며 valid 와 다르면 저장하지 않는다.
            거부하는 경우: 적용에 실패하지 않은 후보의 evidence_run_ids 가 비었거나 그 후보의 연습 공간에서 run_scenarios 로 돌린 실행이
            아닐 때, proposed 인데 root_cause.proof_run_ids 가 비었거나 돌린 적 없는 실행일 때, recommended_candidate_no 가
            합격 후보가 아닐 때, status 가 합격 후보와 맞지 않을 때.
            같은 thread_id 로 다시 부르면 처음 저장한 제안서를 그대로 돌려준다.
            돌려주는 값: {proposal_id, status, valid_candidate_nos}. proposal_id 는 운영자 화면과 승인 API 가 쓴다.
            실패하면 isError=true 와 {code, message, retryable, upstream_status, snapshot_id} 를 돌려준다.""")
    public CallToolResult saveProposal(
            @McpToolParam(description = "저장할 제안서 전체(Agent 의 proposal 묶음)") Map<String, Object> proposal) {
        return WorkspaceToolResults.run(() -> proposals.save(proposal));
    }
}
