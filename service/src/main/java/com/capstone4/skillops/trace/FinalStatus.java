package com.capstone4.skillops.trace;

/** 주문 최종 상태. 계약 1·2 공용 값 그대로(대문자). */
public enum FinalStatus {
    SHIPPED,
    REJECTED,
    HOLD,
    FAILED
}
