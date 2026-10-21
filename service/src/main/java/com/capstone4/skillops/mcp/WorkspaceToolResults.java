package com.capstone4.skillops.mcp;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Supplier;

import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import tools.jackson.databind.json.JsonMapper;

/**
 * C-2 MCP 도구의 결과·오류를 C-1 의 {@link ToolResults} 와 같은 모양으로 만든다.
 * 예외를 그대로 던지면 SDK 가 "Error invoking method" 뒤에 메시지를 이어 붙여 Agent 가 code·retryable 을 읽지 못한다.
 * C-1 파일을 건드리지 않으려고 따로 두었다. 오류 칸 이름은 C-1 과 같다.
 */
final class WorkspaceToolResults {

    /** 입력이 잘못됨. 고쳐서 다시 부르면 되므로 같은 입력으로 재시도하지 않는다. */
    static final String INVALID_INPUT = "INVALID_INPUT";
    /** 서버 쪽 문제(설정, 깨진 파일 등). 같은 입력으로 다시 불러도 결과가 같다. */
    static final String INTERNAL_ERROR = "INTERNAL_ERROR";

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private WorkspaceToolResults() {
    }

    /** 성공이면 결과 JSON 한 덩어리, 실패면 isError=true 와 {code, message, retryable, upstream_status, snapshot_id}. */
    static CallToolResult run(Supplier<Object> body) {
        try {
            return CallToolResult.builder().isError(false).addTextContent(JSON.writeValueAsString(body.get())).build();
        } catch (IllegalArgumentException e) {
            return error(INVALID_INPUT, e.getMessage());
        } catch (RuntimeException e) {
            return error(INTERNAL_ERROR, e.getMessage());
        }
    }

    private static CallToolResult error(String code, String message) {
        Map<String, Object> error = new LinkedHashMap<>();
        error.put("code", code);
        error.put("message", message);
        error.put("retryable", false);
        error.put("upstream_status", null);
        error.put("snapshot_id", null);
        return CallToolResult.builder().isError(true).addTextContent(JSON.writeValueAsString(error)).build();
    }
}
