package com.capstone4.skillops.trace;

import com.fasterxml.jackson.annotation.JsonValue;

/** step 결과. 계약 2 는 소문자 값을 쓴다. */
public enum StepStatus {
    OK("ok"),
    ERROR("error"),
    SKIPPED("skipped");

    private final String value;

    StepStatus(String value) {
        this.value = value;
    }

    @JsonValue
    public String value() {
        return value;
    }
}
