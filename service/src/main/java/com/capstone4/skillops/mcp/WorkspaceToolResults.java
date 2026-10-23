package com.capstone4.skillops.mcp;

import java.util.function.Supplier;

import com.capstone4.skillops.snapshot.OrderAgentException;

import io.modelcontextprotocol.spec.McpSchema.CallToolResult;

/**
 * C-2 MCP 도구(create_workspace, apply_patch, run_scenarios, save_proposal)의 예외 정책을 둔다. 이 클래스는 어떤 예외를
 * 어떤 code 로 바꿀지만 정하고, 결과와 오류 JSON 은 service/src/main/java/com/capstone4/skillops/mcp/ToolResults.java 의
 * success, orderAgentError, error 로 만든다. 그래서 C-1 도구와 오류 JSON 의 칸 이름도 순서도 같다.
 *
 * C-1 의 ToolResults.run 은 OrderAgentException 만 구조화하지만 이 정책은 더 넓다. 주문 Agent 조회가 실패하면(연습 공간을
 * snapshot_id 로 시작할 때) OrderAgentException 의 code 와 retryable 등을 그대로 돌려주고, IllegalArgumentException 은
 * INVALID_INPUT, 그 밖의 RuntimeException 은 INTERNAL_ERROR 로 바꾼다. 예외를 그대로 던지면 SDK 가 "Error invoking method"
 * 로 시작하는 글 오류로 감싸서 Agent 가 code 와 retryable 을 읽지 못하기 때문이다.
 *
 * service/src/main/java/com/capstone4/skillops/mcp/WorkspaceTools.java 의 createWorkspace, applyPatch, runScenarios 와
 * service/src/main/java/com/capstone4/skillops/mcp/ProposalTools.java 의 saveProposal 이 run 을 부른다.
 * 이 정책은 service/src/test/java/com/capstone4/skillops/mcp/WorkspaceToolsTest.java 가 확인하고, save_proposal 의
 * INVALID_INPUT 은 service/src/test/java/com/capstone4/skillops/mcp/ProposalToolsTest.java 도 확인한다.
 */
final class WorkspaceToolResults {

    /** 입력이 잘못됐다는 code 다. 같은 입력으로 다시 부르지 말고 입력을 고쳐야 하므로 retryable=false 다. */
    static final String INVALID_INPUT = "INVALID_INPUT";
    /** 서버 쪽 문제(설정, 깨진 파일 등)라는 code 다. 같은 입력으로 다시 불러도 결과가 같으므로 retryable=false 다. */
    static final String INTERNAL_ERROR = "INTERNAL_ERROR";

    /** 정적 메서드만 있는 도구 클래스라서 객체를 만들지 못하게 막는다. */
    private WorkspaceToolResults() {
    }

    /**
     * body 를 실행해 성공하면 결과 JSON 한 덩어리를, 실패하면 isError=true 와
     * {code, message, retryable, upstream_status, snapshot_id} 를 돌려준다. catch 순서가 곧 정책이다.
     * OrderAgentException 과 IllegalArgumentException 을 RuntimeException 보다 먼저 잡아야 주문 Agent 의 code 와 입력 오류가
     * INTERNAL_ERROR 로 묻히지 않는다.
     */
    static CallToolResult run(Supplier<Object> body) {
        try {
            return ToolResults.success(body.get());
        } catch (OrderAgentException e) {
            return ToolResults.orderAgentError(e);
        } catch (IllegalArgumentException e) {
            return ToolResults.error(INVALID_INPUT, e.getMessage(), false, null, null);
        } catch (RuntimeException e) {
            return ToolResults.error(INTERNAL_ERROR, e.getMessage(), false, null, null);
        }
    }
}
