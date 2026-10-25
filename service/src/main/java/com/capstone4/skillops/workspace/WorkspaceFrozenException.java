package com.capstone4.skillops.workspace;

/**
 * run_scenarios 를 한 번이라도 돌린 연습 공간에 apply_patch 를 넣으려 했다는 예외다. 시험한 skill 과 제안서의 수정 내용이
 * 어긋나지 않게 하려고, 시험을 시작한 연습 공간은 고정한다(docs/plans/2026-10-26-behavior-edit-root-fix.md 2.7 절).
 * service/src/main/java/com/capstone4/skillops/mcp/WorkspaceToolResults.java 가 code WORKSPACE_FROZEN(retryable=false) 오류로 바꾼다.
 * 입력 오류(INVALID_INPUT)와 구분하려고 IllegalArgumentException 이 아니라 IllegalStateException 을 잇는다.
 * 진단 Agent 는 후보마다 새 연습 공간을 쓰므로 정상 흐름에서는 나오지 않는다.
 */
public class WorkspaceFrozenException extends IllegalStateException {

    /** MCP 오류 code 다. */
    public static final String CODE = "WORKSPACE_FROZEN";

    /** 고정된 연습 공간 번호를 밝힌 문구로 만든다. */
    public WorkspaceFrozenException(String workspaceId) {
        super("workspace_id " + workspaceId + " 은 run_scenarios 로 시험을 시작해 고정됐습니다. "
                + "수정을 더 넣으려면 create_workspace 로 새 연습 공간을 만드세요");
    }
}
