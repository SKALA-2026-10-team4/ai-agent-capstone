package com.capstone4.skillops.mcp;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.ai.mcp.annotation.provider.tool.SyncMcpToolProvider;

import com.capstone4.skillops.scenario.ScenarioRunReport;
import com.capstone4.skillops.scenario.ScenarioRunner;
import com.capstone4.skillops.snapshot.OrderAgentException;
import com.capstone4.skillops.workspace.WorkspaceService;
import com.capstone4.skillops.workspace.WorkspaceService.BrokenLink;
import com.capstone4.skillops.workspace.WorkspaceService.ChangedSkill;
import com.capstone4.skillops.workspace.WorkspaceService.CreatedWorkspace;
import com.capstone4.skillops.workspace.WorkspaceService.PatchInput;
import com.capstone4.skillops.workspace.WorkspaceService.PatchResult;

import io.modelcontextprotocol.server.McpServerFeatures.SyncToolSpecification;
import io.modelcontextprotocol.spec.McpSchema.CallToolRequest;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import io.modelcontextprotocol.spec.McpSchema.TextContent;
import tools.jackson.databind.json.JsonMapper;

/** B 의 Agent(skillops-agent/skillops_agent/tools.py)가 읽는 계약 5 v1 이름(B 의 #52 이후)과 C-1 과 같은 오류 모양을 고정한다. */
class WorkspaceToolsTest {

    static final JsonMapper JSON = JsonMapper.builder().build();

    WorkspaceService workspaces = mock(WorkspaceService.class);
    ScenarioRunner runner = mock(ScenarioRunner.class);
    WorkspaceTools tools = new WorkspaceTools(workspaces, runner);

    @Test
    void createWorkspaceReturnsWorkspaceId() {
        when(workspaces.create(Map.of("inventory-check", 1), null)).thenReturn(new CreatedWorkspace(
                "w_abc", Map.of("inventory-check", 1), Instant.parse("2026-10-21T00:00:00Z")));

        CallToolResult result = tools.createWorkspace(Map.of("inventory-check", 1), null);

        assertThat(result.isError()).isFalse();
        Map<String, Object> body = mapOf(textOf(result));
        assertThat(body.keySet()).containsExactly("workspace_id", "skill_versions", "created_at");
        assertThat(body.get("workspace_id")).isEqualTo("w_abc");
        assertThat(body.get("skill_versions")).isEqualTo(Map.of("inventory-check", 1));
        assertThat(body.get("created_at")).isEqualTo("2026-10-21T00:00:00Z");
    }

    @Test
    void createWorkspacePassesSnapshotId() {
        String snapshot = "11111111-2222-3333-4444-555555555555";
        when(workspaces.create(null, snapshot)).thenReturn(new CreatedWorkspace(
                "w_snap", Map.of("order-flow", 1, "payment", 1), Instant.parse("2026-10-21T00:00:00Z")));

        CallToolResult result = tools.createWorkspace(null, snapshot);

        assertThat(result.isError()).isFalse();
        Map<String, Object> body = mapOf(textOf(result));
        assertThat(body.keySet()).containsExactly("workspace_id", "skill_versions", "created_at");
        assertThat(body.get("workspace_id")).isEqualTo("w_snap");
        assertThat(body.get("skill_versions")).isEqualTo(Map.of("order-flow", 1, "payment", 1));
    }

    @Test
    void baseAndSnapshotTogetherIsInvalidInput() {
        when(workspaces.create(any(), any())).thenThrow(
                new IllegalArgumentException("snapshot_id 와 start_versions 는 함께 쓸 수 없습니다"));

        CallToolResult result = tools.createWorkspace(Map.of("payment", 1), "11111111-2222-3333-4444-555555555555");

        assertThat(result.isError()).isTrue();
        Map<String, Object> error = mapOf(textOf(result));
        assertThat(error.get("code")).isEqualTo("INVALID_INPUT");
        assertThat(error.get("message")).isEqualTo("snapshot_id 와 start_versions 는 함께 쓸 수 없습니다");
        assertThat(error.get("retryable")).isEqualTo(false);
    }

    @Test
    void orderAgentFailureKeepsItsCodeAndRetryable() {
        String snapshot = "11111111-2222-3333-4444-555555555555";
        when(workspaces.create(any(), any())).thenThrow(new OrderAgentException(
                "ORDER_AGENT_UNAVAILABLE", "주문 Agent 에 연결하지 못했습니다: refused", true, null, snapshot));

        CallToolResult result = tools.createWorkspace(null, snapshot);

        assertThat(result.isError()).isTrue();
        Map<String, Object> error = mapOf(textOf(result));
        assertThat(error.keySet()).containsExactly("code", "message", "retryable", "upstream_status", "snapshot_id");
        assertThat(error.get("code")).isEqualTo("ORDER_AGENT_UNAVAILABLE");
        assertThat(error.get("message")).isEqualTo("주문 Agent 에 연결하지 못했습니다: refused");
        assertThat(error.get("retryable")).isEqualTo(true);
        assertThat(error.get("upstream_status")).isNull();
        assertThat(error.get("snapshot_id")).isEqualTo(snapshot);
    }

    @Test
    void orderAgentNotFoundKeepsUpstreamStatus() {
        String snapshot = "11111111-2222-3333-4444-555555555555";
        when(workspaces.create(any(), any())).thenThrow(
                new OrderAgentException("SNAPSHOT_NOT_FOUND", "snapshot 이 없습니다", false, 404, snapshot));

        Map<String, Object> error = mapOf(textOf(tools.createWorkspace(null, snapshot)));

        assertThat(error.get("code")).isEqualTo("SNAPSHOT_NOT_FOUND");
        assertThat(error.get("retryable")).isEqualTo(false);
        assertThat(error.get("upstream_status")).isEqualTo(404);
        assertThat(error.get("snapshot_id")).isEqualTo(snapshot);
    }

    @Test
    @SuppressWarnings("unchecked")
    void runScenariosReturnsV1Report() {
        when(runner.run("w_abc", List.of("g-01"), null, 2)).thenReturn(new ScenarioRunReport("w_abc", false, List.of(
                new ScenarioRunReport.ScenarioResult("g-01", null, false, List.of("r_1", "r_2"), List.of(
                        new ScenarioRunReport.Failure("r_1", "payment", "최종 상태 기대 SHIPPED, 실제 FAILED"))))));

        CallToolResult result = tools.runScenarios("w_abc", List.of("g-01"), null, 2);

        assertThat(result.isError()).isFalse();
        Map<String, Object> body = mapOf(textOf(result));
        assertThat(body.keySet()).containsExactly("workspace_id", "all_passed", "results");
        assertThat(body.get("workspace_id")).isEqualTo("w_abc");
        assertThat(body.get("all_passed")).isEqualTo(false);
        Map<String, Object> first = ((List<Map<String, Object>>) body.get("results")).getFirst();
        assertThat(first.keySet()).containsExactly("scenario_id", "replay_of_run_id", "passed", "run_ids", "failures");
        assertThat(first.get("replay_of_run_id")).isNull();
        assertThat(first.get("run_ids")).isEqualTo(List.of("r_1", "r_2"));
        assertThat(((List<?>) first.get("failures")).getFirst()).isEqualTo(Map.of(
                "run_id", "r_1", "skill", "payment", "reason", "최종 상태 기대 SHIPPED, 실제 FAILED"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void applyPatchReturnsV1Shape() {
        when(workspaces.apply("w_abc", List.of(new PatchInput("inventory-check", 1, "@@ -5 +5 @@\n-a\n+b\n"))))
                .thenReturn(new PatchResult("w_abc", List.of(new ChangedSkill("inventory-check", 2)),
                        List.of(new BrokenLink("payment", "inventory.reserved"))));

        CallToolResult result = tools.applyPatch("w_abc",
                List.of(new PatchInput("inventory-check", 1, "@@ -5 +5 @@\n-a\n+b\n")));

        assertThat(result.isError()).isFalse();
        Map<String, Object> body = mapOf(textOf(result));
        assertThat(body.keySet()).containsExactly("workspace_id", "changed_skills", "broken_links");
        assertThat(body.get("workspace_id")).isEqualTo("w_abc");
        assertThat(((List<Map<String, Object>>) body.get("changed_skills")).getFirst())
                .isEqualTo(Map.of("skill", "inventory-check", "version", 2));
        assertThat(((List<Map<String, Object>>) body.get("broken_links")).getFirst())
                .isEqualTo(Map.of("skill", "payment", "missing_input", "inventory.reserved"));
    }

    @Test
    void applyPatchFailureBecomesInvalidInput() {
        when(workspaces.apply(any(), any())).thenThrow(new IllegalArgumentException(
                "inventory-check: 5번째 줄 근처가 수정 내용과 맞지 않아 적용하지 못했습니다"));

        CallToolResult result = tools.applyPatch("w_abc", List.of(new PatchInput("inventory-check", 1, "@@ -5 +5 @@\n-a\n+b\n")));

        assertThat(result.isError()).isTrue();
        Map<String, Object> error = mapOf(textOf(result));
        assertThat(error.get("code")).isEqualTo("INVALID_INPUT");
        assertThat(error.get("message")).isEqualTo("inventory-check: 5번째 줄 근처가 수정 내용과 맞지 않아 적용하지 못했습니다");
        assertThat(error.get("retryable")).isEqualTo(false);
    }

    /** B 의 tools.py 가 보내는 snake_case 입력(patches[].base_version)이 MCP 를 거쳐 PatchInput 으로 들어오는지 본다. */
    @Test
    @SuppressWarnings("unchecked")
    void applyPatchTakesSnakeCaseInputThroughMcp() {
        SyncToolSpecification spec = new SyncMcpToolProvider(List.of(tools)).getToolSpecifications().stream()
                .filter(s -> s.tool().name().equals("apply_patch")).findFirst().orElseThrow();
        Map<String, Object> schema = spec.tool().inputSchema();
        Map<String, Object> properties = (Map<String, Object>) schema.get("properties");
        Map<String, Object> patchesItems = (Map<String, Object>) ((Map<String, Object>) properties.get("patches")).get("items");
        assertThat((List<String>) schema.get("required")).containsExactlyInAnyOrder("workspace_id", "patches");
        assertThat(((Map<String, Object>) patchesItems.get("properties")).keySet())
                .containsExactlyInAnyOrder("skill", "base_version", "patch");
        when(workspaces.apply("w_abc", List.of(new PatchInput("inventory-check", 1, "diff"))))
                .thenReturn(new PatchResult("w_abc", List.of(new ChangedSkill("inventory-check", 2)), List.of()));

        CallToolResult result = spec.callHandler().apply(null, CallToolRequest.builder().name("apply_patch")
                .arguments(Map.of("workspace_id", "w_abc",
                        "patches", List.of(Map.of("skill", "inventory-check", "base_version", 1, "patch", "diff"))))
                .build());

        assertThat(result.isError()).isFalse();
        assertThat(mapOf(textOf(result)).get("changed_skills")).isEqualTo(List.of(Map.of("skill", "inventory-check", "version", 2)));
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
        when(workspaces.create(any(), any())).thenThrow(new IllegalStateException("payment 의 SKILL.md 맨 위 칸에 version 이 없습니다"));

        CallToolResult result = tools.createWorkspace(null, null);

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

    private static String textOf(CallToolResult result) {
        return ((TextContent) result.content().getFirst()).text();
    }
}
