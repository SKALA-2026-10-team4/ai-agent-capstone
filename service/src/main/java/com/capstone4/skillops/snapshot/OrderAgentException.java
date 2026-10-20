package com.capstone4.skillops.snapshot;

/**
 * 주문 Agent 조회 실패. 빈 결과로 바꾸지 않고 도구 오류로 그대로 올린다.
 * code 는 주문 Agent 가 준 값(SNAPSHOT_NOT_FOUND, SNAPSHOT_TAMPERED …)을 보존하고,
 * 주문 Agent 에 닿지 못했으면 ORDER_AGENT_UNAVAILABLE, 입력이 잘못됐으면 INVALID_INPUT 이다.
 */
public class OrderAgentException extends RuntimeException {

    private final String code;
    private final boolean retryable;
    private final Integer upstreamStatus;
    private final String snapshotId;

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

    /** 주문 Agent 의 HTTP 상태. 닿지 못했거나 보내기 전에 거절했으면 null. */
    public Integer upstreamStatus() {
        return upstreamStatus;
    }

    public String snapshotId() {
        return snapshotId;
    }
}
