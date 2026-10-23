package com.capstone4.skillops.mcp;

import org.springframework.ai.mcp.annotation.McpTool;
import org.springframework.ai.mcp.annotation.McpToolParam;
import org.springframework.stereotype.Component;

import com.capstone4.skillops.snapshot.DependencyGraphService;

import io.modelcontextprotocol.spec.McpSchema.CallToolResult;

/**
 * C-1 MCP 도구 get_dependency_graph 를 등록한다. 주문 Agent snapshot 안에서 entry flow 와 그 steps 의 SKILL.md 선언을 읽어
 * skill 사이의 연결 관계와 끊긴 곳을 계산한 결과를 돌려준다.
 * Spring 이 빈으로 만들고, Spring AI MCP Server 의 annotation scanner(service/src/main/resources/application.yml 의
 * spring.ai.mcp.server.annotation-scanner)가 @McpTool 메서드를 찾아 도구로 노출한다.
 * 계산은 service/src/main/java/com/capstone4/skillops/snapshot/DependencyGraphService.java 가 하고, 결과와 오류 모양은
 * service/src/main/java/com/capstone4/skillops/mcp/ToolResults.java 의 run 이 정한다.
 * 진단 Agent 에서는 skillops-agent/skillops_agent/nodes.py 의 generate_candidates(후보 생성 단계)가 _repair_inputs 를 거쳐
 * skillops-agent/skillops_agent/tools.py 의 MCPTools.get_dependency_graph 로 이 도구를 부른다. generate_candidates 는
 * complete 가 true 가 아니면 ContractError 를 올려 멈추고, true 이면 결과 JSON 을 그대로 후보 생성 프롬프트에 넣는다.
 */
@Component
public class DependencyGraphTools {

    private final DependencyGraphService graphService;

    /** Spring 이 DependencyGraphService 빈을 넣어 만든다. */
    public DependencyGraphTools(DependencyGraphService graphService) {
        this.graphService = graphService;
    }

    // TODO(C-1/B, 계약 확정 후): 이 도구는 snapshot_id 와 entry_skill_id 를 필수로 받는다. 진단 Agent 는 이미 고정한 snapshot 과
    // entry_skill_id(기본값 order-flow)를 넘긴다(skillops-agent/skillops_agent/nodes.py 의 _repair_inputs). 그러나 v1 계약 문서
    // (docs/contracts/mcp-tools-input-output.md 6장)는 빈 입력 {} 을 보내 운영본으로 계산한다고 적고 있다.
    // 문서를 이 서버 스키마에 맞출지, 운영본을 해석하는 계층을 둘지 합의해야 한다.
    // 무인자 형태로 바꾸더라도 진단 도중 최신 운영본이 섞이지 않게 대상 snapshot 을 한 번 고정한다.
    /**
     * snapshot_id 의 snapshot 에서 entry_skill_id flow 의 연결 관계를 계산해 JSON 결과로 돌려준다.
     * 계산은 DependencyGraphService.dependencyGraph 에 맡긴다. OrderAgentException 으로 올라온 실패(주문 Agent 조회 실패,
     * 입력 형식 오류, SKILL_NOT_FOUND, SKILL_FILE_NOT_TEXT, INVALID_FLOW)는 ToolResults.run 이 isError=true 구조화 오류로
     * 바꾸고, 그 밖의 예외는 SDK 의 글 오류가 된다. 입력 키 이름과 설명 글의 주문 입력 키, 결과 칸 목록, 오류 모양은
     * service/src/test/java/com/capstone4/skillops/mcp/SnapshotToolsTest.java 가 확인한다.
     * 설명 글에 적은 주문 입력 키 다섯 개는 service/src/main/java/com/capstone4/skillops/snapshot/DependencyAnalyzer.java 의
     * ORDER_INPUT_KEYS 와 같아야 하므로, 그 키를 바꾸면 이 설명 글도 함께 고친다.
     */
    @McpTool(name = "get_dependency_graph", description = """
            snapshot 안의 flow 와 단계 skill 들의 연결 관계와 끊긴 곳을 돌려준다.
            바뀐 부분이 다른 skill 에 어떤 영향을 주는지, 실패 이벤트의 누락 입력이 어디서 끊겼는지 볼 때 쓴다.
            SKILL.md 선언만 보고 계산하므로 실제 업무 도구가 실행됐는지는 trace 로 확인해야 한다.
            판단 규칙은 주문 Agent 정적 계약 검사와 같다: steps 순서대로 inputs 가 주문 입력 키
            (order.id, order.items, order.customer, order.payment_method, order.shipping)나 앞선 outputs 에 있는지,
            depends_on 이 이미 끝났는지 본다. 첫 위반에서 멈추지 않고 전부 계산한다.
            돌려주는 값: snapshot_id, snapshot_hash, entry_skill_id, steps,
            nodes[{skill_id, path, file_hash, inputs, outputs, depends_on, parse_error}],
            edges[{type(flow_order·depends_on·io), from, to, keys}],
            missing[{skill_id, missing_inputs, missing_dependencies}],
            ambiguous[{skill_id, key, providers}], complete(읽지 못한 skill 이 있으면 false).
            실패하면 isError=true 와 {code, message, retryable, upstream_status, snapshot_id} 를 돌려준다.""")
    // 매개변수 이름이 곧 MCP 입력 스키마의 키라서 주문 Agent 명세와 결과 칸에 맞춰 snake_case 로 둔다.
    public CallToolResult getDependencyGraph(
            @McpToolParam(description = "조사할 snapshot UUID (실패 이벤트의 snapshot.snapshot_id)") String snapshot_id,
            @McpToolParam(description = "시작 flow skill ID (예: order-flow)") String entry_skill_id) {
        return ToolResults.run(() -> graphService.dependencyGraph(snapshot_id, entry_skill_id));
    }
}
