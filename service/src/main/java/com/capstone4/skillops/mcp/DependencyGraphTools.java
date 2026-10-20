package com.capstone4.skillops.mcp;

import org.springframework.ai.mcp.annotation.McpTool;
import org.springframework.ai.mcp.annotation.McpToolParam;
import org.springframework.stereotype.Component;

import com.capstone4.skillops.snapshot.DependencyGraphService;

import io.modelcontextprotocol.spec.McpSchema.CallToolResult;

/** C-1 MCP 도구 get_dependency_graph. 주문 Agent snapshot 안의 skill 연결 관계를 계산한다. */
@Component
public class DependencyGraphTools {

    private final DependencyGraphService graphService;

    public DependencyGraphTools(DependencyGraphService graphService) {
        this.graphService = graphService;
    }

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
    // 매개변수 이름이 곧 MCP 입력 스키마의 키라서, 주문 Agent 명세·결과 칸과 같은 snake_case 로 둔다.
    public CallToolResult getDependencyGraph(
            @McpToolParam(description = "조사할 snapshot UUID (실패 이벤트의 snapshot.snapshot_id)") String snapshot_id,
            @McpToolParam(description = "시작 flow skill ID (예: order-flow)") String entry_skill_id) {
        return ToolResults.run(() -> graphService.dependencyGraph(snapshot_id, entry_skill_id));
    }
}
