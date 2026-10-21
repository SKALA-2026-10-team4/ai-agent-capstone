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

/** B 의 Agent 가 읽는 save_proposal 결과(계약 5 §10)와 C-2 오류 모양을 고정한다. */
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

    @SuppressWarnings("unchecked")
    private static Map<String, Object> mapOf(String json) {
        return JSON.readValue(json, Map.class);
    }

    private static String textOf(CallToolResult result) {
        return ((TextContent) result.content().getFirst()).text();
    }
}
