package com.capstone4.skillops.evaluation;

/** REST와 MCP에서 동일하게 전달하는 평가 오류다. */
public class EvaluationException extends RuntimeException {
    public final int status;
    public final String code;
    /** HTTP 상태와 안정적인 오류 코드를 보관한다. */
    public EvaluationException(int status, String code, String message) { super(message); this.status=status; this.code=code; }
    /** 입력 조건을 위반하면 접수를 거부한다. */
    public static void require(boolean condition, String message) {
        if (!condition) { throw new EvaluationException(400, "INVALID_EVALUATION_INPUT", message); }
    }
}
