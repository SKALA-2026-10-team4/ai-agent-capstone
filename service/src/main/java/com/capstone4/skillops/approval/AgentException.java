package com.capstone4.skillops.approval;

/** SkillOps Agent 전달 실패. httpStatus 는 화면에 돌려줄 상태 코드다. */
public class AgentException extends RuntimeException {

    private final String code;
    private final int httpStatus;

    public AgentException(String code, String message, int httpStatus) {
        super(message);
        this.code = code;
        this.httpStatus = httpStatus;
    }

    public String code() {
        return code;
    }

    public int httpStatus() {
        return httpStatus;
    }
}
