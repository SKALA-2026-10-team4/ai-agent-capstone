package com.capstone4.skillops.mcp;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.capstone4.skillops.proposal.ProposalService;
import com.capstone4.skillops.proposal.ProposalService.SavedProposal;

import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import io.modelcontextprotocol.spec.McpSchema.TextContent;
import tools.jackson.databind.json.JsonMapper;

/**
 * service/src/main/java/com/capstone4/skillops/mcp/ProposalTools.java 의 saveProposal 이 MCP 결과로 내보내는 모양을 고정한다.
 * 성공하면 {proposal_id, status, valid_candidate_nos} 세 키만 담은 JSON 을 돌려주는지(docs/contracts/mcp-tools-input-output.md 의 save_proposal 절),
 * service/src/main/java/com/capstone4/skillops/proposal/ProposalService.java 의 save 가 IllegalArgumentException 을 던지면
 * C-2 오류 모양의 INVALID_INPUT 오류(retryable=false)를 돌려주는지 본다.
 * SkillOps Agent 의 skillops-agent/skillops_agent/tools.py 와 skillops-agent/skillops_agent/nodes.py 의 save_proposal 이 이 모양을 읽는다.
 */
class ProposalToolsTest {

    static final JsonMapper JSON = JsonMapper.builder().build();

    ProposalService service = mock(ProposalService.class);
    ProposalTools tools = new ProposalTools(service);

    @Test
    void returnsProposalIdStatusAndValidNumbers() {
        when(service.save(any())).thenReturn(new SavedProposal("p_abc", "proposed", List.of(1)));

        CallToolResult result = tools.saveProposal(Map.of("status", "proposed"));

        assertThat(result.isError()).isFalse();
        Map<String, Object> body = mapOf(textOf(result));
        assertThat(body.keySet()).containsExactly("proposal_id", "status", "valid_candidate_nos");
        assertThat(body).containsEntry("proposal_id", "p_abc").containsEntry("valid_candidate_nos", List.of(1));
    }

    @Test
    void invalidProposalBecomesInvalidInputError() {
        when(service.save(any())).thenThrow(new IllegalArgumentException("후보 1 의 근거 실행 r_x 을 찾을 수 없습니다"));

        CallToolResult result = tools.saveProposal(Map.of());

        assertThat(result.isError()).isTrue();
        assertThat(mapOf(textOf(result))).containsEntry("code", "INVALID_INPUT").containsEntry("retryable", false)
                .containsKey("upstream_status").containsKey("snapshot_id");
    }

    /** 실제 저장 서비스의 실패 증거 거부가 기존 MCP 오류 계약으로 전달된다. */
    @Test
    @SuppressWarnings("unchecked")
    void storedFailureCannotBeSavedThroughLegacyTool() {
        var repository = mock(com.capstone4.skillops.proposal.ProposalRepository.class);
        var ledger = new com.capstone4.skillops.scenario.TestFixtures.InMemoryRunLedger();
        ledger.record("w_c1", "g-01", "r_c1000000000000000000000000000001", true);
        ledger.record("w_c1", "g-02", "r_c1000000000000000000000000000002", false);
        Map<String, Object> proposal = JSON.readValue(
                java.nio.file.Path.of("src/test/resources/proposal/f1-proposed.json").toFile(), Map.class);

        CallToolResult result = new ProposalTools(new ProposalService(repository, ledger)).saveProposal(proposal);

        assertThat(result.isError()).isTrue();
        assertThat(mapOf(textOf(result))).containsEntry("code", "INVALID_INPUT")
                .containsEntry("retryable", false);
        assertThat(mapOf(textOf(result)).get("message").toString()).contains("실제 채점에서 실패했습니다");
        org.mockito.Mockito.verify(repository, org.mockito.Mockito.never()).save(any());
    }

    /** MCP 결과의 JSON 글을 Map 으로 읽는다. */
    @SuppressWarnings("unchecked")
    private static Map<String, Object> mapOf(String json) {
        return JSON.readValue(json, Map.class);
    }

    /** MCP 결과의 첫 내용(TextContent)에서 글을 꺼낸다. */
    private static String textOf(CallToolResult result) {
        return ((TextContent) result.content().getFirst()).text();
    }
}
