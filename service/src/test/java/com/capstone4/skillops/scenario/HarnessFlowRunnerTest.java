package com.capstone4.skillops.scenario;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.hamcrest.Matchers.nullValue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.client.ExpectedCount.once;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.jsonPath;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withException;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServiceUnavailable;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import java.net.ConnectException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.TreeMap;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.test.web.client.ResponseCreator;
import org.springframework.web.client.RestClient;

import com.capstone4.skillops.workspace.WorkspaceService;

/**
 * service/src/main/java/com/capstone4/skillops/scenario/HarnessFlowRunner.java 가 주문 Agent 를 부르는 순서와 모양을 보호한다.
 * 연습 공간 파일 전부를 후보 snapshot 으로 올리는지, 같은 묶음이면 snapshot 을 다시 쓰고 바뀌면 다시 올리는지,
 * 모의 실행에 주문 칸만 보내는지, 기억한 snapshot 이 사라졌을 때만 한 번 다시 올리는지 본다.
 * 주문 Agent 오류를 어떤 예외와 문구로 알리는지, 운영 폴더 연습 공간과 order 없음을 파일을 읽기 전에 거부하는지도 고정한다.
 * 주문 Agent 는 MockRestServiceServer 로 대신하고, trace 응답은 주문 Agent 에서 저장한 실측 파일
 * service/src/test/resources/order-agent/trace-shipped.json 이다.
 */
class HarnessFlowRunnerTest {

    static final String BASE = "http://order-agent";
    static final String SOURCE = "83be1b30-57f6-4885-8af8-859c630ecf13";
    static final String CANDIDATE = "aaaaaaaa-1111-2222-3333-444444444444";
    static final String CANDIDATE_2 = "bbbbbbbb-1111-2222-3333-444444444444";
    static final String EXECUTION = "374d6c7e-6c31-4f65-9ed1-51dda24608cd";
    static final String RUN = "r_374d6c7e6c314f659ed151dda24608cd";
    static final String POLICY = "skills/inventory-check/resources/policy.md";

    RestClient.Builder builder = RestClient.builder().baseUrl(BASE);
    MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
    WorkspaceService workspaces = mock(WorkspaceService.class);
    HarnessFlowRunner runner = new HarnessFlowRunner(workspaces, new OrderAgentRunClient(builder, BASE));
    Map<String, String> files = new TreeMap<>();

    /** 연습 공간 w_snap 은 snapshot SOURCE 에서 시작했고, skill 두 개와 resources 파일 하나를 갖는다. */
    @BeforeEach
    void setUp() {
        files.put("skills/order-flow/SKILL.md", "---\nname: order-flow\nversion: 1\n---\n");
        files.put("skills/inventory-check/SKILL.md", "---\nname: inventory-check\nversion: 1\n---\n");
        files.put(POLICY, "재고 정책\n");
        when(workspaces.filesOf("w_snap")).thenReturn(files);
        when(workspaces.sourceSnapshotIdOf("w_snap")).thenReturn(SOURCE);
    }

    @Test
    void publishesAllWorkspaceFiles() {
        server.expect(requestTo(BASE + "/api/snapshots/candidates"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(jsonPath("$.files['" + POLICY + "']").value("재고 정책\n"))
                .andExpect(jsonPath("$.files['skills/order-flow/SKILL.md']").exists())
                .andExpect(jsonPath("$.files['skills/inventory-check/SKILL.md']").exists())
                .andExpect(jsonPath("$.metadata.workspace_id").value("w_snap"))
                .andExpect(jsonPath("$.metadata.source_snapshot_id").value(SOURCE))
                .andRespond(manifest(CANDIDATE));
        expectDryRun(CANDIDATE);
        expectTrace();

        runner.runDryRun(request(g01Order()));

        server.verify();
    }

    @Test
    void reusesSnapshotForSameFiles() {
        server.expect(once(), requestTo(BASE + "/api/snapshots/candidates")).andRespond(manifest(CANDIDATE));
        expectDryRun(CANDIDATE);
        expectTrace();
        expectDryRun(CANDIDATE);
        expectTrace();

        runner.runDryRun(request(g01Order()));
        // 같은 글이면 순서가 다른 Map 이어도 같은 묶음이다.
        Map<String, String> reversed = new LinkedHashMap<>();
        new TreeMap<>(files).descendingMap().forEach(reversed::put);
        when(workspaces.filesOf("w_snap")).thenReturn(reversed);
        runner.runDryRun(request(g01Order()));

        server.verify();
    }

    @Test
    void changedFilesArePublishedAgain() {
        server.expect(requestTo(BASE + "/api/snapshots/candidates")).andRespond(manifest(CANDIDATE));
        expectDryRun(CANDIDATE);
        expectTrace();
        server.expect(requestTo(BASE + "/api/snapshots/candidates"))
                .andExpect(jsonPath("$.files['skills/inventory-check/SKILL.md']").value("고친 글\n"))
                .andRespond(manifest(CANDIDATE_2));
        expectDryRun(CANDIDATE_2);
        expectTrace();

        runner.runDryRun(request(g01Order()));
        Map<String, String> patched = new TreeMap<>(files);
        patched.put("skills/inventory-check/SKILL.md", "고친 글\n");
        when(workspaces.filesOf("w_snap")).thenReturn(patched);
        runner.runDryRun(request(g01Order()));

        server.verify();
    }

    @Test
    void sendsOnlyOrderFields() {
        Map<String, Object> order = new LinkedHashMap<>(g01Order());
        order.put("note", "주문 Agent 에 보내지 않는 칸");
        server.expect(requestTo(BASE + "/api/snapshots/candidates")).andRespond(manifest(CANDIDATE));
        server.expect(requestTo(BASE + "/api/dry-runs"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(jsonPath("$.snapshot_id").value(CANDIDATE))
                .andExpect(jsonPath("$.order.customer").value("c_1001"))
                .andExpect(jsonPath("$.order.items[0].sku").value("SKU-APPLE-1KG"))
                .andExpect(jsonPath("$.order.items[0].qty").value(2))
                .andExpect(jsonPath("$.order.payment_method").value("card"))
                .andExpect(jsonPath("$.order.shipping.country").value("KR"))
                .andExpect(jsonPath("$.order.note").doesNotExist())
                .andRespond(dryRunStarted());
        expectTrace();

        runner.runDryRun(request(order));

        server.verify();
    }

    @Test
    void runsAndConverts() {
        server.expect(requestTo(BASE + "/api/snapshots/candidates")).andRespond(manifest(CANDIDATE));
        expectDryRun(CANDIDATE);
        expectTrace();

        RunOutcome outcome = runner.runDryRun(request(g01Order()));

        assertThat(outcome.runId()).startsWith("r_").isEqualTo(RUN);
        assertThat(outcome.finalStatus()).isEqualTo("SHIPPED");
        assertThat(outcome.steps()).hasSize(5);
        server.verify();
    }

    @Test
    void orderAgentDownIsReported() {
        server.expect(requestTo(BASE + "/api/snapshots/candidates")).andRespond(withServiceUnavailable());

        assertThatThrownBy(() -> runner.runDryRun(request(g01Order())))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("주문 Agent")
                .hasMessageStartingWith("주문 Agent 에 연결할 수 없습니다: " + BASE + "/api/snapshots/candidates (")
                .hasMessageContaining("503");
    }

    @Test
    void connectionFailureIsReported() {
        server.expect(requestTo(BASE + "/api/snapshots/candidates"))
                .andRespond(withException(new ConnectException("Connection refused")));

        assertThatThrownBy(() -> runner.runDryRun(request(g01Order())))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageStartingWith("주문 Agent 에 연결할 수 없습니다: " + BASE + "/api/snapshots/candidates (")
                .hasMessageContaining("Connection refused");
    }

    @Test
    void refusesWorkspaceNotStartedFromSnapshot() {
        when(workspaces.sourceSnapshotIdOf("w_live")).thenReturn(null);

        assertThatThrownBy(() -> runner.runDryRun(new FlowRunner.DryRunRequest("g-01", g01Order(), "w_live", Map.of())))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("w_live")
                .hasMessageContaining("create_workspace")
                .hasMessageContaining("snapshot_id");
        // 주문 Agent 는 부르지 않는다.
        server.verify();
    }

    /** 주문 Agent 는 docker 를 다시 켜면 DB 가 비어 캐시한 snapshot 이 사라진다. 한 번만 다시 올리고 다시 돌린다. */
    @Test
    void republishesWhenCachedSnapshotIsGone() {
        server.expect(requestTo(BASE + "/api/snapshots/candidates")).andRespond(manifest(CANDIDATE));
        expectDryRun(CANDIDATE);
        expectTrace();
        server.expect(requestTo(BASE + "/api/dry-runs"))
                .andExpect(jsonPath("$.snapshot_id").value(CANDIDATE))
                .andRespond(snapshotNotFound());
        server.expect(requestTo(BASE + "/api/snapshots/candidates")).andRespond(manifest(CANDIDATE_2));
        expectDryRun(CANDIDATE_2);
        expectTrace();
        // 다시 올린 snapshot 을 캐시에 넣으므로 세 번째 실행은 다시 올리지 않는다.
        expectDryRun(CANDIDATE_2);
        expectTrace();

        runner.runDryRun(request(g01Order()));
        RunOutcome outcome = runner.runDryRun(request(g01Order()));
        runner.runDryRun(request(g01Order()));

        assertThat(outcome.finalStatus()).isEqualTo("SHIPPED");
        server.verify();
    }

    /** 방금 올린 snapshot 이 없다고 하면 다시 올려도 같으므로 그대로 알린다. */
    @Test
    void freshSnapshotNotFoundIsNotRetried() {
        server.expect(once(), requestTo(BASE + "/api/snapshots/candidates")).andRespond(manifest(CANDIDATE));
        server.expect(once(), requestTo(BASE + "/api/dry-runs")).andRespond(snapshotNotFound());

        assertThatThrownBy(() -> runner.runDryRun(request(g01Order())))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("SNAPSHOT_NOT_FOUND");
        server.verify();
    }

    /** 404 라도 snapshot 이 아닌 것(없는 상품)은 다시 올리지 않고 입력 오류로 알린다. */
    @Test
    void orderAgentRejectionIsInvalidInputWithItsCode() {
        server.expect(requestTo(BASE + "/api/snapshots/candidates")).andRespond(manifest(CANDIDATE));
        expectDryRun(CANDIDATE);
        expectTrace();
        server.expect(requestTo(BASE + "/api/dry-runs")).andRespond(withStatus(HttpStatus.NOT_FOUND)
                .contentType(MediaType.APPLICATION_JSON)
                .body("{\"code\":\"PRODUCT_NOT_FOUND\",\"message\":\"선택한 상품을 찾을 수 없습니다.\",\"order_id\":null,\"field_errors\":[]}"));

        runner.runDryRun(request(g01Order()));
        assertThatThrownBy(() -> runner.runDryRun(request(g01Order())))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("PRODUCT_NOT_FOUND")
                .hasMessageContaining("선택한 상품을 찾을 수 없습니다.");
        server.verify();
    }

    @Test
    void validationErrorCarriesFieldErrors() {
        server.expect(requestTo(BASE + "/api/snapshots/candidates")).andRespond(manifest(CANDIDATE));
        server.expect(requestTo(BASE + "/api/dry-runs")).andRespond(withStatus(HttpStatus.BAD_REQUEST)
                .contentType(MediaType.APPLICATION_JSON)
                .body("{\"code\":\"VALIDATION_ERROR\",\"message\":\"주문 입력을 확인해주세요.\",\"order_id\":null,"
                        + "\"field_errors\":[{\"field\":\"order.customer\",\"message\":\"must match\"}]}"));

        assertThatThrownBy(() -> runner.runDryRun(request(g01Order())))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("VALIDATION_ERROR")
                .hasMessageContaining("order.customer");
    }

    /** order 가 없는 시험 주문은 주문 Agent 를 부르기 전에 거부한다. */
    @Test
    void scenarioWithoutOrderIsRejectedBeforeCallingOrderAgent() {
        assertThatThrownBy(() -> runner.runDryRun(request(null)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("시험 주문 g-01 에 order 가 없습니다");
        server.verify();
    }

    /** 시험 주문 order 에 없는 칸은 보내지 않는다. */
    @Test
    void absentOrderFieldIsNotSent() {
        Map<String, Object> order = new LinkedHashMap<>(g01Order());
        order.remove("shipping");
        server.expect(requestTo(BASE + "/api/snapshots/candidates")).andRespond(manifest(CANDIDATE));
        server.expect(requestTo(BASE + "/api/dry-runs"))
                .andExpect(jsonPath("$.order.customer").value("c_1001"))
                .andExpect(jsonPath("$.order.shipping").doesNotExist())
                .andRespond(dryRunStarted());
        expectTrace();

        runner.runDryRun(request(order));

        server.verify();
    }

    /** 칸이 있으면 값이 null 이어도 그대로 보낸다. */
    @Test
    void nullOrderFieldIsSentAsNull() {
        Map<String, Object> order = new LinkedHashMap<>(g01Order());
        order.put("shipping", null);
        server.expect(requestTo(BASE + "/api/snapshots/candidates")).andRespond(manifest(CANDIDATE));
        server.expect(requestTo(BASE + "/api/dry-runs"))
                .andExpect(jsonPath("$.order.shipping").value(nullValue()))
                .andRespond(dryRunStarted());
        expectTrace();

        runner.runDryRun(request(order));

        server.verify();
    }

    /** 운영 폴더 연습 공간과 order 없음은 파일 목록을 읽기 전에 거부한다. */
    @Test
    void checksComeBeforeReadingWorkspaceFiles() {
        when(workspaces.sourceSnapshotIdOf("w_live")).thenReturn(null);

        assertThatThrownBy(() -> runner.runDryRun(new FlowRunner.DryRunRequest("g-01", null, "w_live", Map.of())))
                .hasMessageContaining("w_live");
        assertThatThrownBy(() -> runner.runDryRun(request(null)))
                .hasMessage("시험 주문 g-01 에 order 가 없습니다");
        verify(workspaces, never()).filesOf(anyString());
        server.verify();
    }

    // ---- 도우미 ----

    /** snapshot 에서 시작한 연습 공간 w_snap 으로 g-01 을 돌리는 요청을 만든다. skills 는 이 실행기가 쓰지 않아 비워 둔다. */
    private static FlowRunner.DryRunRequest request(Map<String, Object> order) {
        return new FlowRunner.DryRunRequest("g-01", order, "w_snap", Map.of());
    }

    /** 저장소 g-01 의 order 칸이다. */
    private static Map<String, Object> g01Order() {
        return TestFixtures.g01Order();
    }

    /** 그 snapshot 으로 모의 실행 요청이 한 번 오고 시작 응답을 돌려줄 것을 기대한다. */
    private void expectDryRun(String snapshotId) {
        server.expect(requestTo(BASE + "/api/dry-runs"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(jsonPath("$.snapshot_id").value(snapshotId))
                .andRespond(dryRunStarted());
    }

    /** 실행 기록 읽기 요청이 한 번 오고 실측 SHIPPED trace 를 돌려줄 것을 기대한다. */
    private void expectTrace() {
        server.expect(requestTo(BASE + "/api/executions/" + EXECUTION + "/trace"))
                .andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess(TestFixtures.JSON.writeValueAsString(
                        OrderAgentTraceConverterTest.trace("trace-shipped.json")), MediaType.APPLICATION_JSON));
    }

    /** 후보 snapshot 올리기의 성공 응답(snapshot manifest)이다. */
    private static ResponseCreator manifest(String snapshotId) {
        return withSuccess("{\"snapshot_id\":\"" + snapshotId + "\",\"hash\":\"h\",\"created_at\":\"2026-10-21T00:00:00Z\","
                + "\"metadata\":{\"source\":\"candidate\"},\"files\":[]}", MediaType.APPLICATION_JSON);
    }

    /** SHIPPED 로 끝난 모의 실행 응답이다. execution_id 와 run_id 는 EXECUTION 과 RUN 이다. */
    private static ResponseCreator dryRunStarted() {
        return withSuccess("{\"execution_id\":\"" + EXECUTION + "\",\"run_id\":\"" + RUN
                + "\",\"order_id\":\"4f966e14-04ac-4ff3-9ccf-df454aca378f\",\"final_status\":\"SHIPPED\",\"completion\":\"COMPLETED\"}",
                MediaType.APPLICATION_JSON);
    }

    /** 주문 Agent 에 snapshot 이 없다는 404 SNAPSHOT_NOT_FOUND 응답이다. */
    private static ResponseCreator snapshotNotFound() {
        return withStatus(HttpStatus.NOT_FOUND).contentType(MediaType.APPLICATION_JSON)
                .body("{\"code\":\"SNAPSHOT_NOT_FOUND\",\"message\":\"실행 자료를 확인할 수 없습니다: SNAPSHOT_NOT_FOUND\","
                        + "\"order_id\":null,\"field_errors\":[]}");
    }
}
