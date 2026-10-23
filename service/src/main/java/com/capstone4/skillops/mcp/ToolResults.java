package com.capstone4.skillops.mcp;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Supplier;

import com.capstone4.skillops.snapshot.OrderAgentException;

import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import tools.jackson.databind.json.JsonMapper;

/**
 * MCP 도구의 결과와 오류를 CallToolResult 로 만드는 공용 코드(success, orderAgentError, error)와 C-1 도구의 예외 정책(run)을
 * 둔다. 예외를 그대로 던지면 SDK 가 "Error invoking method" 로 시작하는 글 오류로 감싸서 진단 Agent 가 code 와 retryable 을
 * 읽을 수 없기 때문에 CallToolResult 를 직접 만든다. 오류 모양은 주문 Agent 명세(order-agent/docs/mcp-order-api-draft.md
 * 5장)의 오류 래퍼를 따른다.
 *
 * 예외 정책은 둘이고 JSON 을 만드는 코드는 하나다. 이 클래스의 run 은 C-1 정책으로 OrderAgentException 만 구조화하고,
 * service/src/main/java/com/capstone4/skillops/mcp/WorkspaceToolResults.java 의 run 은 C-2 정책으로
 * IllegalArgumentException 과 그 밖의 RuntimeException 까지 구조화한다. 두 run 모두 이 클래스의 success, orderAgentError,
 * error 로 결과를 만들므로, 이 서버의 도구 7개 가운데 ping 을 뺀 6개의 성공 JSON 과 구조화 오류 JSON 은 모두 여기서 나온다.
 * 오류 JSON 의 칸 이름이나 순서를 바꾸면 C-1 도구와 C-2 도구가 함께 바뀐다. 새 도구는 두 run 가운데 맞는 정책을 고른다.
 *
 * C-1 의 run 은 service/src/main/java/com/capstone4/skillops/mcp/SkillDiffTools.java(diff_skill)와
 * service/src/main/java/com/capstone4/skillops/mcp/DependencyGraphTools.java(get_dependency_graph)가 부른다.
 * 진단 Agent 쪽에서는 skillops-agent/skillops_agent/tools.py 의 MCPTools._call 이 isError 결과의 글을 JSON 객체로 읽어
 * MCPToolError 로 올리고, JSON 객체가 아니면 ContractError 로 올린다. 그래서 C-1 도구에서 잡지 않은 예외가 만든 SDK 글
 * 오류는 진단 Agent 에서 ContractError 가 된다.
 */
final class ToolResults {

    /** 결과와 오류를 JSON 으로 쓰는 매퍼다. 설정을 더하지 않으므로 칸 이름은 결과 record 의 JsonNaming 이 정한다. */
    private static final JsonMapper JSON = JsonMapper.builder().build();

    /** 정적 메서드만 있는 도구 클래스라서 객체를 만들지 못하게 막는다. */
    private ToolResults() {
    }

    /**
     * C-1 도구의 결과 정책이다. body 가 돌려준 값을 성공 결과로 감싸고, OrderAgentException 만 isError=true 와
     * {code, message, retryable, upstream_status, snapshot_id} 오류로 바꾼다. IllegalArgumentException 을 포함한 그 밖의
     * 예외는 잡지 않으므로 SDK 가 글 오류로 감싼다. C-2 정책과 다른 이 동작은
     * service/src/test/java/com/capstone4/skillops/mcp/SnapshotToolsTest.java 가 고정한다.
     */
    static CallToolResult run(Supplier<Object> body) {
        try {
            return success(body.get());
        } catch (OrderAgentException e) {
            return orderAgentError(e);
        }
    }

    /** 결과 값을 JSON 글 한 덩어리로 담고 isError=false 로 표시한 성공 결과를 만든다. run 과 WorkspaceToolResults.run 이 부른다. */
    static CallToolResult success(Object value) {
        return CallToolResult.builder().isError(false).addTextContent(JSON.writeValueAsString(value)).build();
    }

    /**
     * 주문 Agent 조회 실패의 code, message, retryable, upstream_status, snapshot_id 를 바꾸지 않고 담은 오류 결과를 만든다.
     * run 과 WorkspaceToolResults.run 이 OrderAgentException 을 잡았을 때 부른다.
     */
    static CallToolResult orderAgentError(OrderAgentException e) {
        return error(e.code(), e.getMessage(), e.retryable(), e.upstreamStatus(), e.snapshotId());
    }

    /**
     * isError=true 와 {code, message, retryable, upstream_status, snapshot_id} 를 이 순서로 담은 오류 결과를 만든다.
     * LinkedHashMap 으로 칸 순서를 고정하고 값이 null 인 칸도 빼지 않는다. SnapshotToolsTest 의 ERROR_KEYS 가 이 순서를 확인한다.
     * orderAgentError 가 부르고, WorkspaceToolResults.run 이 INVALID_INPUT 과 INTERNAL_ERROR 오류를 만들 때 부른다.
     */
    static CallToolResult error(String code, String message, boolean retryable, Integer upstreamStatus,
            String snapshotId) {
        Map<String, Object> error = new LinkedHashMap<>();
        error.put("code", code);
        error.put("message", message);
        error.put("retryable", retryable);
        error.put("upstream_status", upstreamStatus);
        error.put("snapshot_id", snapshotId);
        return CallToolResult.builder().isError(true).addTextContent(JSON.writeValueAsString(error)).build();
    }
}
