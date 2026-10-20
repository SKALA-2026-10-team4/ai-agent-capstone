package com.capstone4.skillops.mcp;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Supplier;

import com.capstone4.skillops.snapshot.OrderAgentException;

import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import tools.jackson.databind.json.JsonMapper;

/**
 * C-1 MCP 도구의 결과·오류를 같은 모양으로 만든다. 예외를 그대로 던지면 SDK 가 메시지를 글로 이어 붙여
 * 진단 Agent 가 code·retryable 을 읽을 수 없기 때문에 CallToolResult 를 직접 만든다.
 * 오류 모양은 주문 Agent 명세(order-agent/docs/mcp-order-api-draft.md 5장)의 오류 래퍼를 따른다.
 */
final class ToolResults {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private ToolResults() {
    }

    /** 성공이면 결과 JSON 한 덩어리, 주문 Agent 조회 실패면 isError=true 와 {code, message, retryable, upstream_status, snapshot_id}. */
    static CallToolResult run(Supplier<Object> body) {
        try {
            return CallToolResult.builder().isError(false).addTextContent(JSON.writeValueAsString(body.get())).build();
        } catch (OrderAgentException e) {
            Map<String, Object> error = new LinkedHashMap<>();
            error.put("code", e.code());
            error.put("message", e.getMessage());
            error.put("retryable", e.retryable());
            error.put("upstream_status", e.upstreamStatus());
            error.put("snapshot_id", e.snapshotId());
            return CallToolResult.builder().isError(true).addTextContent(JSON.writeValueAsString(error)).build();
        }
    }
}
