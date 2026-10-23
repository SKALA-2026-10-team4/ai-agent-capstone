package com.capstone4.skillops.mcp;

import java.util.Map;

import org.springframework.ai.mcp.annotation.McpTool;
import org.springframework.ai.mcp.annotation.McpToolParam;
import org.springframework.stereotype.Component;

import com.capstone4.skillops.proposal.ProposalService;

import io.modelcontextprotocol.spec.McpSchema.CallToolResult;

/**
 * MCP 도구 save_proposal 을 둔 클래스다. 계약 5 §10(docs/contracts/mcp-tools-input-output.md)의 도구이고 담당은 C-2 다.
 * Spring AI MCP Server 의 annotation scanner 가 이 빈의 @McpTool 메서드를 save_proposal 도구로 등록한다.
 * SkillOps Agent 는 진단을 마친 뒤 skillops-agent/skillops_agent/nodes.py 의 save_proposal 노드에서
 * skillops-agent/skillops_agent/tools.py 의 MCPTools.save_proposal 을 거쳐 이 도구를 부른다.
 * 검사와 저장은 service/src/main/java/com/capstone4/skillops/proposal/ProposalService.java 의 save 가 하고, 이 클래스는 결과와
 * 오류를 service/src/main/java/com/capstone4/skillops/mcp/WorkspaceToolResults.java 의 run 으로 감싸기만 한다.
 * 그래서 결과와 오류 모양은 C-2 의 다른 도구와 같다.
 * 잘못된 제안서(IllegalArgumentException)는 INVALID_INPUT, 그 밖의 서버 오류는 INTERNAL_ERROR 오류 묶음이 된다.
 * 도구 설명은 @McpTool 과 @McpToolParam 의 문자열이다. 이 문자열은 MCP tools/list 응답에 그대로 실리는 도구 계약이므로,
 * 주석을 다듬을 때 함께 고치지 않는다.
 */
@Component
public class ProposalTools {

    private final ProposalService proposals;

    /** Spring 이 ProposalService 빈을 주입해 만든다. 테스트는 mock 을 넣어 직접 만든다. */
    public ProposalTools(ProposalService proposals) {
        this.proposals = proposals;
    }

    /**
     * save_proposal 도구 호출 하나를 처리한다. 받은 proposal 묶음을 ProposalService 의 save 에 넘기고,
     * 성공하면 {proposal_id, status, valid_candidate_nos} JSON 을, 실패하면 isError=true 인 오류 묶음을 돌려준다.
     * 같은 thread_id 로 다시 부르면 검사 없이 처음 저장한 제안서의 결과를 돌려준다.
     */
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
