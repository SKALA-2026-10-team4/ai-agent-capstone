package com.capstone4.skillops.mcp;

import java.util.function.Supplier;
import org.springframework.ai.mcp.annotation.McpTool;
import org.springframework.ai.mcp.annotation.McpToolParam;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import com.capstone4.skillops.evaluation.*;
import com.capstone4.skillops.proposal.VerifiedProposalService;
import com.capstone4.skillops.snapshot.OrderAgentException;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;

/** 새 평가 도구는 REST와 같은 서비스·권한·입력 검사를 사용한다. */
@Component
public class EvaluationTools {
    private final EvaluationService evaluations;
    private final VerifiedProposalService proposals;
    /** 공통 서비스를 주입한다. */
    public EvaluationTools(EvaluationService evaluations,VerifiedProposalService proposals) { this.evaluations=evaluations; this.proposals=proposals; }
    /** 장시간 작업은 접수 결과만 반환한다. */
    @McpTool(name="submit_evaluation",description="불변 snapshot 두 개와 평가셋으로 비동기 평가를 접수한다. 실모델·자동 수정은 지원하지 않는다. OPERATOR JWT가 필요하다.")
    @PreAuthorize("hasRole('OPERATOR')")
    public CallToolResult submit(@McpToolParam(description="평가 접수 입력") EvaluationTypes.Submit request,
            @McpToolParam(description="권한 범위 안에서 유일한 재접수 키") String idempotency_key) {
        return result(() -> evaluations.submit(request,idempotency_key,SecurityContextHolder.getContext().getAuthentication().getName()));
    }
    /** 원문을 제외한 상태·진행을 조회한다. */
    @McpTool(name="get_evaluation",description="평가 작업의 최신 시도와 진행 상태를 조회한다. 로그인 JWT가 필요하다.")
    @PreAuthorize("isAuthenticated()")
    public CallToolResult get(@McpToolParam(description="평가 UUID") String evaluation_id) { return result(() -> evaluations.get(evaluation_id)); }
    /** 특정 시도의 저장된 결과만 비교한다. */
    @McpTool(name="get_evaluation_report",description="평가 시도의 비교 보고서를 조회한다. 미완료는 INCONCLUSIVE다. 로그인 JWT가 필요하다.")
    @PreAuthorize("isAuthenticated()")
    public CallToolResult report(@McpToolParam(description="평가 UUID") String evaluation_id,
            @McpToolParam(description="시도 UUID") String attempt_id) { return result(() -> evaluations.report(evaluation_id,attempt_id)); }
    /** 생산자가 제출한 valid 대신 서버 평가로 제안서를 만든다. */
    @McpTool(name="save_proposal_v2",description="SERVER_VERIFIED 후보 평가로 snapshot 제안서를 저장한다. 운영 반영하지 않는다. OPERATOR JWT가 필요하다.")
    @PreAuthorize("hasRole('OPERATOR')")
    public CallToolResult save(@McpToolParam(description="평가 ID·시도 ID와 설명") VerifiedProposalService.Input request) { return result(() -> proposals.save(request)); }
    /** SDK가 읽을 수 있는 JSON 결과와 오류를 만든다. */
    private static CallToolResult result(Supplier<Object> body) {
        try { return ToolResults.success(body.get()); }
        catch (EvaluationException error) { return ToolResults.error(error.code,error.getMessage(),error.status==429 || error.status==503,null,null); }
        catch (OrderAgentException error) { return ToolResults.orderAgentError(error); }
        catch (IllegalArgumentException error) { return ToolResults.error("INVALID_EVALUATION_INPUT",String.valueOf(error.getMessage()),false,null,null); }
        catch (RuntimeException error) { return ToolResults.error("EVALUATION_FAILED","평가 요청 처리에 실패했습니다",false,null,null); }
    }
}
