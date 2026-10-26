package com.capstone4.skillops.mcp;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import com.capstone4.skillops.evaluation.EvaluationService;
import com.capstone4.skillops.evaluation.EvaluationTypes;
import com.capstone4.skillops.proposal.VerifiedProposalService;
import io.modelcontextprotocol.spec.McpSchema.TextContent;

class EvaluationToolsTest {
    @Configuration
    @EnableMethodSecurity
    static class MethodSecurity { }

    /** 테스트 간 인증 정보가 전파되지 않게 정리한다. */
    @AfterEach
    void clearAuthentication() {
        SecurityContextHolder.clearContext();
    }

    /** 메서드 보안을 켠 실제 Spring 빈에서도 JWT 없이 네 도구를 사용할 수 있다. */
    @Test
    void allToolsAllowCallsWithoutAuthentication() {
        clearAuthentication();
        EvaluationService evaluations = mock(EvaluationService.class);
        VerifiedProposalService proposals = mock(VerifiedProposalService.class);
        try (var context = new AnnotationConfigApplicationContext()) {
            context.register(MethodSecurity.class);
            context.registerBean(EvaluationService.class, () -> evaluations);
            context.registerBean(VerifiedProposalService.class, () -> proposals);
            context.registerBean(EvaluationTools.class);
            context.refresh();
            var tools = context.getBean(EvaluationTools.class);
            var input = new VerifiedProposalService.Input("job", "attempt", "summary", "intent");

            assertThat(tools.submit(null, "key").isError()).isFalse();
            assertThat(tools.get("job").isError()).isFalse();
            assertThat(tools.report("job", "attempt").isError()).isFalse();
            assertThat(tools.save(input).isError()).isFalse();

            verify(evaluations).submit(null, "key", "mcp:anonymous");
            verify(evaluations).get("job");
            verify(evaluations).report("job", "attempt");
            verify(proposals).save(input);
        }
    }

    /** 토큰 유무·사용자 이름이 바뀌어도 MCP 접수 키의 범위는 고정한다. */
    @Test
    void submitScopeDoesNotDependOnAuthentication() {
        var evaluations = mock(EvaluationService.class);
        var tools = new EvaluationTools(evaluations, mock(VerifiedProposalService.class));
        var accepted = new EvaluationTypes.Accepted("job", "attempt", EvaluationTypes.Status.QUEUED, "/api/evaluations/job");
        when(evaluations.submit(null, "key", "mcp:anonymous")).thenReturn(accepted);
        clearAuthentication();
        var anonymous = tools.submit(null, "key");
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken("operator", "unused", List.of()));
        var authenticated = tools.submit(null, "key");

        assertThat(authenticated).isEqualTo(anonymous);
        verify(evaluations, times(2)).submit(null, "key", "mcp:anonymous");
    }

    /** 인증을 제거해도 평가 입력·증거 검증 오류는 기존 형식으로 반환한다. */
    @Test
    void domainValidationStillRejectsInvalidProposal() {
        var proposals = mock(VerifiedProposalService.class);
        var input = new VerifiedProposalService.Input("job", "attempt", "summary", "intent");
        when(proposals.save(input)).thenThrow(new com.capstone4.skillops.evaluation.EvaluationException(
                409, "EVIDENCE_MISMATCH", "서버 검증 실패"));

        var result = new EvaluationTools(mock(EvaluationService.class), proposals).save(input);

        assertThat(result.isError()).isTrue();
        assertThat(((TextContent) result.content().getFirst()).text()).contains("EVIDENCE_MISMATCH");
    }
}
