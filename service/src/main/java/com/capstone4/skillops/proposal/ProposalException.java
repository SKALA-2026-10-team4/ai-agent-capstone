package com.capstone4.skillops.proposal;

/** 제안서 결정(승인·거절)을 받을 수 없을 때. code·message 는 화면에 그대로 보여 주고, httpStatus 로 응답한다. */
public class ProposalException extends RuntimeException {

    private final String code;
    private final int httpStatus;

    public ProposalException(String code, String message, int httpStatus) {
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
