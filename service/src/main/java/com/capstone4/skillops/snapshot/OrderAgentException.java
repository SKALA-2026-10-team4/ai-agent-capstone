package com.capstone4.skillops.snapshot;

/**
 * C-1 의 주문 Agent 조회 실패와 입력 오류를 담는 예외다. 빈 결과로 바꾸지 않고 MCP 도구 오류로 그대로 올린다.
 * code 는 주문 Agent 가 준 값(SNAPSHOT_NOT_FOUND, SNAPSHOT_TAMPERED 등)을 보존하고, 오류 본문에 code 가 없으면
 * ORDER_AGENT_HTTP_ 뒤에 상태 코드를 붙인다. 주문 Agent 에 닿지 못했으면 ORDER_AGENT_UNAVAILABLE, 입력이 잘못됐으면
 * INVALID_INPUT, snapshot 에 skill 이 없으면 SKILL_NOT_FOUND, entry flow 를 읽지 못하면 INVALID_FLOW, 글이 아닌 파일이면
 * SKILL_FILE_NOT_TEXT 다.
 *
 * 다섯 칸(code, message, retryable, upstream_status, snapshot_id)이 MCP 오류 JSON 의 원천이다.
 * service/src/main/java/com/capstone4/skillops/mcp/ToolResults.java 의 orderAgentError 가 이 칸을 그대로 옮긴다. C-1 도구는
 * ToolResults.run 이, C-2 도구는 service/src/main/java/com/capstone4/skillops/mcp/WorkspaceToolResults.java 의 run 이 이 예외를
 * 잡는다. service/src/main/java/com/capstone4/skillops/workspace/WorkspaceService.java 의 analyze 는 분석기가 던진 이 예외를
 * order-flow 접두어를 붙인 IllegalArgumentException 으로 바꾼다.
 * code() 와 retryable() 은 같은 이름의 칸을 그대로 돌려주는 접근자다.
 */
public class OrderAgentException extends RuntimeException {

    private final String code;
    private final boolean retryable;
    private final Integer upstreamStatus;
    private final String snapshotId;

    /** 다섯 칸을 모두 받는다. upstreamStatus 와 snapshotId 는 모르면 null 을 넣는다. message 는 예외 메시지가 된다. */
    public OrderAgentException(String code, String message, boolean retryable, Integer upstreamStatus, String snapshotId) {
        super(message);
        this.code = code;
        this.retryable = retryable;
        this.upstreamStatus = upstreamStatus;
        this.snapshotId = snapshotId;
    }

    public String code() {
        return code;
    }

    public boolean retryable() {
        return retryable;
    }

    /** 주문 Agent 가 돌려준 HTTP 상태다. 닿지 못했거나 보내기 전에 거절했으면 null 이다. */
    public Integer upstreamStatus() {
        return upstreamStatus;
    }

    /**
     * 오류와 관련된 snapshot UUID 다. 한 snapshot 으로 정할 수 없거나(diff_skill 의 skill_id 형식 오류) 호출처가 snapshot 을
     * 모르면(분석기가 던진 SKILL_NOT_FOUND 와 INVALID_FLOW, 재시도 대기 중 조회 중단) null 이다.
     */
    public String snapshotId() {
        return snapshotId;
    }
}
