package com.capstone4.skillops.mcp;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.capstone4.skillops.scenario.ScenarioRunReport;
import com.capstone4.skillops.scenario.ScenarioRunner;
import com.capstone4.skillops.workspace.WorkspaceService;
import com.capstone4.skillops.workspace.WorkspaceService.CreatedWorkspace;

import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import io.modelcontextprotocol.spec.McpSchema.TextContent;
import tools.jackson.databind.json.JsonMapper;

/** B 의 Agent(skillops-agent/skillops_agent/tools.py)가 읽는 계약 5 v0 이름과 C-1 과 같은 오류 모양을 고정한다. */
class WorkspaceToolsTest {

    static final JsonMapper JSON = JsonMapper.builder().build();

    WorkspaceService workspaces = mock(WorkspaceService.class);
    ScenarioRunner runner = mock(ScenarioRunner.class);
    WorkspaceTools tools = new WorkspaceTools(workspaces, runner);

    @Test
    void createWorkspaceReturnsWsId() {
        when(workspaces.create(Map.of("inventory-check", 1))).thenReturn(new CreatedWorkspace(
                "w_abc", Map.of("inventory-check", 1), Instant.parse("2026-10-21T00:00:00Z")));

        CallToolResult result = tools.createWorkspace(Map.of("inventory-check", 1));

        assertThat(result.isError()).isFalse();
        Map<String, Object> body = mapOf(textOf(result));
        assertThat(body.get("ws_id")).isEqualTo("w_abc");
        assertThat(body.get("skill_versions")).isEqualTo(Map.of("inventory-check", 1));
        assertThat(body.get("created_at")).isEqualTo("2026-10-21T00:00:00Z");
    }

    @Test
    void runScenariosReturnsV0List() {
        when(runner.run("w_abc", List.of("g-01"), null, 2)).thenReturn(new ScenarioRunReport("w_abc", false, List.of(
                new ScenarioRunReport.ScenarioResult("g-01", false, List.of("r_1", "r_2"), List.of(
                        new ScenarioRunReport.Failure("r_1", "payment", "최종 상태 기대 SHIPPED, 실제 FAILED"))))));

        CallToolResult result = tools.runScenarios("w_abc", List.of("g-01"), null, 2);

        assertThat(result.isError()).isFalse();
        List<Map<String, Object>> body = listOf(textOf(result));
        Map<String, Object> first = body.getFirst();
        assertThat(first.keySet()).containsExactly("scenario_id", "passed", "runs", "failures");
        assertThat(first.get("runs")).isEqualTo(List.of("r_1", "r_2"));
        assertThat(((List<?>) first.get("failures")).getFirst()).isEqualTo(Map.of(
                "run_id", "r_1", "step", "payment", "error", "최종 상태 기대 SHIPPED, 실제 FAILED"));
    }

    @Test
    void invalidInputBecomesStructuredError() {
        when(runner.run(any(), any(), any(), any())).thenThrow(
                new IllegalArgumentException("확인 조건을 읽을 수 없습니다: payment.status = PAID (예: payment.status == 'PAID')"));

        CallToolResult result = tools.runScenarios("w_abc", List.of("g-01"), List.of("payment.status = PAID"), null);

        assertThat(result.isError()).isTrue();
        Map<String, Object> error = mapOf(textOf(result));
        assertThat(error.keySet()).containsExactly("code", "message", "retryable", "upstream_status", "snapshot_id");
        assertThat(error.get("code")).isEqualTo("INVALID_INPUT");
        assertThat(error.get("message"))
                .isEqualTo("확인 조건을 읽을 수 없습니다: payment.status = PAID (예: payment.status == 'PAID')");
        assertThat(error.get("retryable")).isEqualTo(false);
        assertThat(error.get("upstream_status")).isNull();
        assertThat(error.get("snapshot_id")).isNull();
    }

    @Test
    void unexpectedFailureBecomesInternalError() {
        when(workspaces.create(any())).thenThrow(new IllegalStateException("payment 의 SKILL.md 맨 위 칸에 version 이 없습니다"));

        CallToolResult result = tools.createWorkspace(null);

        assertThat(result.isError()).isTrue();
        Map<String, Object> error = mapOf(textOf(result));
        assertThat(error.get("code")).isEqualTo("INTERNAL_ERROR");
        assertThat(error.get("message")).isEqualTo("payment 의 SKILL.md 맨 위 칸에 version 이 없습니다");
        assertThat(error.get("retryable")).isEqualTo(false);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> mapOf(String json) {
        return JSON.readValue(json, Map.class);
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> listOf(String json) {
        return JSON.readValue(json, List.class);
    }

    private static String textOf(CallToolResult result) {
        return ((TextContent) result.content().getFirst()).text();
    }
}
