package com.capstone4.skillops.mcp;

import java.util.Map;

import org.springframework.ai.mcp.annotation.McpTool;
import org.springframework.ai.mcp.annotation.McpToolParam;
import org.springframework.stereotype.Component;

import com.capstone4.skillops.proposal.ProposalService;

import io.modelcontextprotocol.spec.McpSchema.CallToolResult;

/**
 * MCP 도구 save_proposal 을 둔 클래스다. 입출력은 docs/contracts/mcp-tools-input-output.md 의 save_proposal 절에 있고 담당은 C-2 다.
 * Spring AI MCP Server 의 annotation scanner 가 이 빈의 @McpTool 메서드를 save_proposal 도구로 등록한다.
 * SkillOps Agent 는 진단을 마친 뒤 skillops-agent/skillops_agent/nodes.py 의 save_proposal 노드에서
 * skillops-agent/skillops_agent/tools.py 의 MCPTools.save_proposal 을 거쳐 이 도구를 부른다.
 * 검사와 저장은 service/src/main/java/com/capstone4/skillops/proposal/ProposalService.java 의 save 가 하고, 이 클래스는 결과와
 * 오류를 service/src/main/java/com/capstone4/skillops/mcp/WorkspaceToolResults.java 의 run 으로 감싸기만 한다.
 * 그래서 결과와 오류 모양은 C-2 의 다른 도구와 같다.
 * 잘못된 제안서(IllegalArgumentException)는 INVALID_INPUT, 재판정이 주문 Agent 에서 snapshot metadata 나 기준본을 읽지 못한 것
 * (OrderAgentException)은 주문 Agent 의 code 와 retryable 그대로, 그 밖의 서버 오류는 INTERNAL_ERROR 오류 묶음이 된다.
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
            no_broken_links, metadata_valid, flow_valid 가 참. 최신 Agent는 results의 두 새 결과와 후보 metadata_errors/flow_errors를 보낸다.
            서비스는 Agent 의 참·거짓을 믿지 않고 실행 장부와 연습 공간 기록, 주문 Agent 의 snapshot metadata 로 다시 계산한다.
            의도: snapshot_id 의 snapshot metadata 에 intent 가 있으면 의도 요청이다. trigger.intent 와 baseline_snapshot_id 칸은 보지 않는다.
            expectations 는 metadata 의 intent.expectations, 없으면 제안서의 intent.expectations 이고, 기준본은 metadata 의 baseline_snapshot_id 다.
            fixes_failure: root_cause.repro_scenario_ids(없으면 장부의 f- 시나리오)가 그 후보 연습 공간에서 min-repeat(기본 2)번 이상 돌고 모두 통과.
            repro_scenario_ids 를 적으면 id 마다 root_cause.proof_run_ids 가운데 그 시나리오로 돌아 떨어진 실행이 있어야 하고, 없으면 모든 후보가 거짓.
            keeps_old_orders: 기준 주문 세트(skillops.golden-ids, 기본 기준 주문 폴더 전부)를 같은 기준으로 센 수, total 은 세트 크기.
            판정에 쓰는 실행: 의도 요청이면 그 expectations 를 intent_expectations 로 넣어 채점한 실행, 아니면 덧붙인 조건 없이 돌린 실행.
            no_broken_links, metadata_valid, flow_valid: 그 연습 공간의 마지막 apply_patch 결과.
            results.evidence_matches(서비스만 씀): 후보 patches 가 1개 이상이고 그 연습 공간에 넣은 수정과 같고, 연습 공간이 snapshot_id 에서
            시작해 run_scenarios 로 고정됐을 때 참. 거짓이면 불합격.
            의도 요청일 때만: keeps_intent 는 expectations 1개 이상, 그 의도로 채점한 실행이 모두 통과, scope 가 있는 expectation 마다 그
            scope 에 든 실행이 1개 이상, is_revert(담당자가 바꾼 SKILL.md 를 기준본과 바이트까지 같게 되돌림)와 weakens_contract(rules 항목
            삭제, 기존 rules 의 when 변경, rules 의 when 이나 scope 가 쓰는 키를 덮던 input 삭제. 다른 input 이 덮어도 해당)가 거짓.
            Agent 값과 다르면 거부하지 않고 서비스 값으로 덮어 저장하며, 바꾼 칸은 payload 의 server_overrides
            [{candidate_no, field, agent_value, server_value}] 에 남는다. 합격 후보가 바뀌면 status 와 recommended_candidate_no 도 서비스가 정한다.
            거부하는 경우: status 가 proposed·no_valid_fix 가 아닐 때, snapshot_id 가 없을 때, candidate_no 가 1부터 빠짐없지 않을 때,
            적용에 실패하지 않은 후보의 workspace_id·results 가 없거나 evidence_run_ids 가 비었거나 그 후보의 연습 공간에서 run_scenarios 로
            돌린 실행이 아닐 때, 시험한 후보가 있는데 snapshot metadata 나 기준본을 주문 Agent 에서 읽지 못할 때(주문 Agent 오류 code),
            서비스 판정이 proposed 인데 root_cause.proof_run_ids 가 비었거나 돌린 적 없는 실행일 때.
            같은 thread_id 로 다시 부르면 처음 저장한 제안서를 그대로 돌려준다.
            돌려주는 값: {proposal_id, status, valid_candidate_nos}. status 와 valid_candidate_nos 는 서비스 판정이다.
            proposal_id 는 운영자 화면과 승인 API 가 쓴다.
            실패하면 isError=true 와 {code, message, retryable, upstream_status, snapshot_id} 를 돌려준다.""")
    public CallToolResult saveProposal(
            @McpToolParam(description = "저장할 제안서 전체(Agent 의 proposal 묶음)") Map<String, Object> proposal) {
        return WorkspaceToolResults.run(() -> proposals.save(proposal));
    }
}
