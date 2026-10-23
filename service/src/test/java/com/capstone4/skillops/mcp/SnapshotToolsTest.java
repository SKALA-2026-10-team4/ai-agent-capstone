package com.capstone4.skillops.mcp;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.ai.mcp.annotation.provider.tool.SyncMcpToolProvider;

import com.capstone4.skillops.snapshot.DependencyGraphService;
import com.capstone4.skillops.snapshot.OrderAgentClient;
import com.capstone4.skillops.snapshot.OrderAgentException;
import com.capstone4.skillops.snapshot.SkillDiffService;
import com.capstone4.skillops.snapshot.SnapshotManifest;

import io.modelcontextprotocol.server.McpServerFeatures.SyncToolSpecification;
import io.modelcontextprotocol.spec.McpSchema.CallToolRequest;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import io.modelcontextprotocol.spec.McpSchema.TextContent;
import tools.jackson.databind.json.JsonMapper;

/**
 * C-1 MCP 도구가 Spring AI MCP 로 노출되는 모양을 보호한다. 대상은 같은 패키지(service/src/main/java/com/capstone4/skillops/mcp/)의
 * SkillDiffTools(diff_skill), DependencyGraphTools(get_dependency_graph), PingTools(ping)와 결과 정책 ToolResults.run 이다.
 * 도구 이름, 입력 스키마의 snake_case 필수 키, 설명 글 가운데 Agent 가 기대는 결과 칸 목록과 오류 모양, 결과 JSON 키 순서
 * (null 칸 포함), 오류 JSON 모양, 그리고 OrderAgentException 만 구조화하는 좁은 예외 정책을 고정한다.
 * SyncMcpToolProvider 로 MCP 호출 경로를 거치게 하고, 주문 Agent(OrderAgentClient)는 Mockito 로 대신한다.
 * C-2 도구의 넓은 예외 정책은 service/src/test/java/com/capstone4/skillops/mcp/WorkspaceToolsTest.java 가 지킨다.
 */
class SnapshotToolsTest {

    static final JsonMapper JSON = JsonMapper.builder().build();
    static final String BEFORE = "11111111-1111-1111-1111-111111111111";
    static final String AFTER = "22222222-2222-2222-2222-222222222222";
    /** 오류 JSON 의 키와 순서다. ToolResults.error 가 이 순서로 쓴다. */
    static final List<String> ERROR_KEYS = List.of("code", "message", "retryable", "upstream_status", "snapshot_id");

    OrderAgentClient orderAgent = mock(OrderAgentClient.class);
    SkillDiffTools diffTools = new SkillDiffTools(new SkillDiffService(orderAgent));
    DependencyGraphTools graphTools = new DependencyGraphTools(new DependencyGraphService(orderAgent));
    PingTools pingTools = new PingTools();
    /** SyncMcpToolProvider 가 세 도구 객체의 McpTool 어노테이션에서 만든 도구 명세다. */
    List<SyncToolSpecification> specs = new SyncMcpToolProvider(List.of(diffTools, graphTools, pingTools))
            .getToolSpecifications();

    @Test
    void registersTheThreeToolNames() {
        assertThat(specs).extracting(s -> s.tool().name())
                .containsExactlyInAnyOrder("diff_skill", "get_dependency_graph", "ping");
    }

    @Test
    @SuppressWarnings("unchecked")
    void diffSkillTakesThreeRequiredSnakeCaseKeys() {
        Map<String, Object> schema = spec("diff_skill").tool().inputSchema();
        Map<String, Object> properties = (Map<String, Object>) schema.get("properties");

        assertThat(properties.keySet()).containsExactlyInAnyOrder("skill_id", "before_snapshot_id", "after_snapshot_id");
        assertThat((List<String>) schema.get("required"))
                .containsExactlyInAnyOrder("skill_id", "before_snapshot_id", "after_snapshot_id");
        properties.values().forEach(p -> assertThat((String) ((Map<String, Object>) p).get("description")).isNotBlank());
    }

    @Test
    @SuppressWarnings("unchecked")
    void dependencyGraphTakesTwoRequiredSnakeCaseKeys() {
        Map<String, Object> schema = spec("get_dependency_graph").tool().inputSchema();
        Map<String, Object> properties = (Map<String, Object>) schema.get("properties");

        assertThat(properties.keySet()).containsExactlyInAnyOrder("snapshot_id", "entry_skill_id");
        assertThat((List<String>) schema.get("required")).containsExactlyInAnyOrder("snapshot_id", "entry_skill_id");
        properties.values().forEach(p -> assertThat((String) ((Map<String, Object>) p).get("description")).isNotBlank());
    }

    /** 설명 글 가운데 Agent 가 기대는 결과 칸 목록과 오류 모양만 확인한다. 문장은 고정하지 않는다. */
    @Test
    void descriptionsListTheResultFieldsAndErrorShape() {
        assertThat(spec("diff_skill").tool().description())
                .contains("skill_id, before/after {snapshot_id, snapshot_hash, path, file_hash}")
                .contains("status(modified·added·removed·unchanged), frontmatter_changes[{field, before, after, added, removed}]")
                .contains("hunks[{index, hunk_id, part(frontmatter·body), header, before, after, diff}]")
                .contains("frontmatter_parse_error, related_files[{path, status, before_hash, after_hash}]")
                .contains("실패하면 isError=true 와 {code, message, retryable, upstream_status, snapshot_id} 를 돌려준다.");
        assertThat(spec("get_dependency_graph").tool().description())
                .contains("(order.id, order.items, order.customer, order.payment_method, order.shipping)")
                .contains("돌려주는 값: snapshot_id, snapshot_hash, entry_skill_id, steps,")
                .contains("nodes[{skill_id, path, file_hash, inputs, outputs, depends_on, parse_error}]")
                .contains("edges[{type(flow_order·depends_on·io), from, to, keys}]")
                .contains("missing[{skill_id, missing_inputs, missing_dependencies}]")
                .contains("ambiguous[{skill_id, key, providers}]")
                .contains("실패하면 isError=true 와 {code, message, retryable, upstream_status, snapshot_id} 를 돌려준다.");
        assertThat(spec("ping").tool().description()).contains("pong");
    }

    @Test
    @SuppressWarnings("unchecked")
    void diffSkillSuccessKeepsKeyOrderAndNullFields() {
        serveDiff();

        CallToolResult result = call("diff_skill", Map.of("skill_id", "x", "before_snapshot_id", BEFORE, "after_snapshot_id", AFTER));

        assertThat(result.isError()).isFalse();
        Map<String, Object> body = mapOf(textOf(result));
        assertThat(body.keySet()).containsExactly("skill_id", "before", "after", "status", "frontmatter_changes", "hunks",
                "frontmatter_parse_error", "related_files");
        assertThat(body).containsEntry("skill_id", "x").containsEntry("status", "modified")
                .containsEntry("frontmatter_parse_error", null);
        Map<String, Object> before = (Map<String, Object>) body.get("before");
        assertThat(before.keySet()).containsExactly("snapshot_id", "snapshot_hash", "path", "file_hash");
        assertThat(before).containsEntry("snapshot_id", BEFORE).containsEntry("snapshot_hash", "snap-b")
                .containsEntry("path", "skills/x/SKILL.md").containsEntry("file_hash", "h1");
        Map<String, Object> change = ((List<Map<String, Object>>) body.get("frontmatter_changes")).getFirst();
        assertThat(change.keySet()).containsExactly("field", "before", "after", "added", "removed");
        assertThat(change).containsEntry("field", "inputs").containsEntry("added", List.of("b"))
                .containsEntry("removed", List.of("a"));
        Map<String, Object> hunk = ((List<Map<String, Object>>) body.get("hunks")).getFirst();
        assertThat(hunk.keySet()).containsExactly("index", "hunk_id", "part", "header", "before", "after", "diff");
        assertThat(hunk).containsEntry("index", 0).containsEntry("part", "frontmatter");
        assertThat((String) hunk.get("hunk_id")).matches("h_[0-9a-f]{12}");
        Map<String, Object> related = ((List<Map<String, Object>>) body.get("related_files")).getFirst();
        assertThat(related.keySet()).containsExactly("path", "status", "before_hash", "after_hash");
        assertThat(related).containsEntry("path", "skills/x/resources/a.md").containsEntry("status", "added")
                .containsEntry("before_hash", null).containsEntry("after_hash", "a1");
    }

    /** 성공 글은 설정 없는 JsonMapper 로 결과 record 를 그대로 쓴 것이다. */
    @Test
    void successTextIsThePlainJsonOfTheServiceResult() {
        serveDiff();

        String text = textOf(diffTools.diffSkill("x", BEFORE, AFTER));

        assertThat(text).isEqualTo(JSON.writeValueAsString(new SkillDiffService(orderAgent).diffSkill("x", BEFORE, AFTER)));
    }

    @Test
    @SuppressWarnings("unchecked")
    void absentSideKeepsNullPathAndHash() {
        serve(BEFORE, "snap-b");
        serve(AFTER, "snap-a", file("skills/x/SKILL.md", "h2"));
        when(orderAgent.readText(AFTER, "skills/x/SKILL.md")).thenReturn("body\n");

        Map<String, Object> body = mapOf(textOf(diffTools.diffSkill("x", BEFORE, AFTER)));

        Map<String, Object> before = (Map<String, Object>) body.get("before");
        assertThat(before.keySet()).containsExactly("snapshot_id", "snapshot_hash", "path", "file_hash");
        assertThat(before).containsEntry("path", null).containsEntry("file_hash", null);
        assertThat(body).containsEntry("status", "added");
    }

    /** 파일이 없는 단계(ghost), 공급자가 둘인 값(k), 빠진 입력(z)이 한 번에 드러나는 flow 로 결과 키 순서와 칸 값을 확인한다. */
    @Test
    @SuppressWarnings("unchecked")
    void dependencyGraphSuccessKeepsKeyOrderAndNullFields() {
        serve(BEFORE, "snap-b",
                file("skills/f/SKILL.md", "m-f"), file("skills/a/SKILL.md", "m-a"),
                file("skills/b/SKILL.md", "m-b"), file("skills/c/SKILL.md", "m-c"));
        when(orderAgent.readText(BEFORE, "skills/f/SKILL.md")).thenReturn("---\nname: f\nsteps: [a, b, c, ghost]\n---\n");
        when(orderAgent.readText(BEFORE, "skills/a/SKILL.md")).thenReturn(step("a", "", "k", ""));
        when(orderAgent.readText(BEFORE, "skills/b/SKILL.md")).thenReturn(step("b", "", "k", ""));
        when(orderAgent.readText(BEFORE, "skills/c/SKILL.md")).thenReturn(step("c", "k, z", "", "a"));

        CallToolResult result = call("get_dependency_graph", Map.of("snapshot_id", BEFORE, "entry_skill_id", "f"));

        assertThat(result.isError()).isFalse();
        Map<String, Object> body = mapOf(textOf(result));
        assertThat(body.keySet()).containsExactly("snapshot_id", "snapshot_hash", "entry_skill_id", "steps", "nodes", "edges",
                "missing", "ambiguous", "complete");
        assertThat(body).containsEntry("snapshot_id", BEFORE).containsEntry("snapshot_hash", "snap-b")
                .containsEntry("entry_skill_id", "f").containsEntry("steps", List.of("a", "b", "c", "ghost"))
                .containsEntry("complete", false);
        List<Map<String, Object>> nodes = (List<Map<String, Object>>) body.get("nodes");
        assertThat(nodes.getFirst().keySet())
                .containsExactly("skill_id", "path", "file_hash", "inputs", "outputs", "depends_on", "parse_error");
        assertThat(nodes.getFirst()).containsEntry("file_hash", "m-a").containsEntry("parse_error", null);
        assertThat(nodes.getLast()).containsEntry("skill_id", "ghost").containsEntry("path", null)
                .containsEntry("file_hash", null).containsEntry("parse_error", "UNKNOWN_SKILL");
        List<Map<String, Object>> edges = (List<Map<String, Object>>) body.get("edges");
        assertThat(edges.getFirst().keySet()).containsExactly("type", "from", "to", "keys");
        assertThat(edges).extracting(e -> e.get("type") + ":" + e.get("from") + "->" + e.get("to") + e.get("keys"))
                .containsExactly("flow_order:a->b[]", "flow_order:b->c[]", "flow_order:c->ghost[]", "depends_on:a->c[]",
                        "io:b->c[k]");
        Map<String, Object> missing = ((List<Map<String, Object>>) body.get("missing")).getFirst();
        assertThat(missing.keySet()).containsExactly("skill_id", "missing_inputs", "missing_dependencies");
        assertThat(missing).isEqualTo(Map.of("skill_id", "c", "missing_inputs", List.of("z"), "missing_dependencies", List.of()));
        Map<String, Object> ambiguous = ((List<Map<String, Object>>) body.get("ambiguous")).getFirst();
        assertThat(ambiguous.keySet()).containsExactly("skill_id", "key", "providers");
        assertThat(ambiguous).isEqualTo(Map.of("skill_id", "c", "key", "k", "providers", List.of("a", "b")));
    }

    @Test
    void orderAgentFailureBecomesStructuredError() {
        when(orderAgent.getSnapshot(BEFORE)).thenThrow(
                new OrderAgentException("SNAPSHOT_NOT_FOUND", "snapshot 을 찾을 수 없습니다.", false, 404, BEFORE));

        CallToolResult result = call("diff_skill", Map.of("skill_id", "x", "before_snapshot_id", BEFORE, "after_snapshot_id", AFTER));

        assertThat(result.isError()).isTrue();
        Map<String, Object> error = mapOf(textOf(result));
        assertThat(List.copyOf(error.keySet())).isEqualTo(ERROR_KEYS);
        assertThat(error).containsEntry("code", "SNAPSHOT_NOT_FOUND").containsEntry("message", "snapshot 을 찾을 수 없습니다.")
                .containsEntry("retryable", false).containsEntry("upstream_status", 404).containsEntry("snapshot_id", BEFORE);
    }

    @Test
    void retryableUnavailableKeepsNullStatus() {
        when(orderAgent.getSnapshot(BEFORE)).thenThrow(new OrderAgentException("ORDER_AGENT_UNAVAILABLE",
                "주문 Agent 에 연결하지 못했습니다: refused", true, null, BEFORE));

        Map<String, Object> error = mapOf(textOf(graphTools.getDependencyGraph(BEFORE, "order-flow")));

        assertThat(List.copyOf(error.keySet())).isEqualTo(ERROR_KEYS);
        assertThat(error).containsEntry("code", "ORDER_AGENT_UNAVAILABLE").containsEntry("retryable", true)
                .containsEntry("upstream_status", null).containsEntry("snapshot_id", BEFORE);
    }

    /** diff_skill 의 skill_id 형식 오류는 snapshot_id 가 null 이고, get_dependency_graph 의 entry_skill_id 오류는 snapshot_id 를 싣는다. */
    @Test
    void invalidInputsCarryTheirSnapshotIds() {
        CallToolResult diff = diffTools.diffSkill("Bad_Id", BEFORE, AFTER);
        CallToolResult graph = graphTools.getDependencyGraph(BEFORE, "Bad_Id");

        assertThat(diff.isError()).isTrue();
        assertThat(mapOf(textOf(diff))).containsEntry("code", "INVALID_INPUT")
                .containsEntry("message", "skill_id 는 소문자·숫자·하이픈만 씁니다: Bad_Id")
                .containsEntry("retryable", false).containsEntry("upstream_status", null).containsEntry("snapshot_id", null);
        assertThat(graph.isError()).isTrue();
        assertThat(mapOf(textOf(graph))).containsEntry("code", "INVALID_INPUT")
                .containsEntry("message", "entry_skill_id 는 소문자·숫자·하이픈만 씁니다: Bad_Id")
                .containsEntry("snapshot_id", BEFORE);
    }

    /** C-1 도구는 OrderAgentException 만 구조화한다. 그 밖의 예외는 도구 메서드 밖으로 그대로 나간다(C-2 와 다르다). */
    @Test
    void otherExceptionsAreNotStructured() {
        when(orderAgent.getSnapshot(anyString())).thenThrow(new IllegalStateException("뜻밖의 실패"));

        assertThatThrownBy(() -> diffTools.diffSkill("x", BEFORE, AFTER))
                .isInstanceOf(IllegalStateException.class).hasMessage("뜻밖의 실패");
        assertThatThrownBy(() -> graphTools.getDependencyGraph(BEFORE, "order-flow"))
                .isInstanceOf(IllegalStateException.class).hasMessage("뜻밖의 실패");
    }

    /** C-2 정책과 달리 C-1 도구는 IllegalArgumentException 도 INVALID_INPUT 으로 바꾸지 않는다. */
    @Test
    void illegalArgumentIsNotTurnedIntoInvalidInput() {
        when(orderAgent.getSnapshot(anyString())).thenThrow(new IllegalArgumentException("잘못된 값"));

        assertThatThrownBy(() -> diffTools.diffSkill("x", BEFORE, AFTER))
                .isInstanceOf(IllegalArgumentException.class).hasMessage("잘못된 값");
    }

    /** MCP 를 거치면 SDK 가 글 오류로 감싼다. 구조화된 JSON 이 아니다. */
    @Test
    void otherExceptionsReachTheSdkAsPlainText() {
        when(orderAgent.getSnapshot(anyString())).thenThrow(new IllegalStateException("뜻밖의 실패"));

        CallToolResult result = call("diff_skill", Map.of("skill_id", "x", "before_snapshot_id", BEFORE, "after_snapshot_id", AFTER));

        assertThat(result.isError()).isTrue();
        assertThat(textOf(result)).doesNotStartWith("{").contains("뜻밖의 실패");
    }

    @Test
    void pingAnswersPongWithServerTime() {
        CallToolResult result = call("ping", Map.of());

        assertThat(result.isError()).isNotEqualTo(Boolean.TRUE);
        String text = textOf(result);
        assertThat(text).startsWith("pong ");
        assertThat(Instant.parse(text.substring("pong ".length()))).isNotNull();
        assertThat(pingTools.ping()).startsWith("pong ");
    }

    /** x skill 의 SKILL.md 가 inputs [a] 에서 [b] 로 바뀌고, after 에만 resources/a.md 가 있는 두 snapshot 을 꾸민다. */
    private void serveDiff() {
        serve(BEFORE, "snap-b", file("skills/x/SKILL.md", "h1"));
        serve(AFTER, "snap-a", file("skills/x/SKILL.md", "h2"), file("skills/x/resources/a.md", "a1"));
        when(orderAgent.readText(BEFORE, "skills/x/SKILL.md")).thenReturn("---\nname: x\ninputs: [a]\n---\nbody\n");
        when(orderAgent.readText(AFTER, "skills/x/SKILL.md")).thenReturn("---\nname: x\ninputs: [b]\n---\nbody\n");
    }

    /** snapshotId 의 manifest 를 hash 와 files 로 꾸며 getSnapshot 이 돌려주게 한다. */
    private void serve(String snapshotId, String hash, SnapshotManifest.FileEntry... files) {
        when(orderAgent.getSnapshot(snapshotId))
                .thenReturn(new SnapshotManifest(snapshotId, hash, "2026-10-20T00:00:00Z", Map.of(), List.of(files)));
    }

    /** 크기가 1 인 manifest 파일 항목을 만든다. */
    private static SnapshotManifest.FileEntry file(String path, String hash) {
        return new SnapshotManifest.FileEntry(path, hash, 1);
    }

    /** 단계 skill 의 SKILL.md 를 만든다. 목록 칸은 쉼표로 이은 글을 그대로 넣는다. */
    private static String step(String name, String inputs, String outputs, String dependsOn) {
        return "---\nname: " + name + "\ninputs: [" + inputs + "]\noutputs: [" + outputs + "]\ndepends_on: [" + dependsOn
                + "]\n---\n";
    }

    /** 이름으로 도구 명세를 찾는다. */
    private SyncToolSpecification spec(String name) {
        return specs.stream().filter(s -> s.tool().name().equals(name)).findFirst().orElseThrow();
    }

    /** MCP 클라이언트처럼 도구 명세의 callHandler 로 도구를 부른다. 세 도구는 교환 객체를 쓰지 않으므로 null 을 넣는다. */
    private CallToolResult call(String name, Map<String, Object> arguments) {
        return spec(name).callHandler().apply(null, CallToolRequest.builder().name(name).arguments(arguments).build());
    }

    /** JSON 글을 맵으로 읽는다. */
    @SuppressWarnings("unchecked")
    private static Map<String, Object> mapOf(String json) {
        return JSON.readValue(json, Map.class);
    }

    /** 결과의 첫 TextContent 글을 꺼낸다. */
    private static String textOf(CallToolResult result) {
        return ((TextContent) result.content().getFirst()).text();
    }
}
