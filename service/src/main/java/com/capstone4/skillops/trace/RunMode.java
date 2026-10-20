package com.capstone4.skillops.trace;

import com.fasterxml.jackson.annotation.JsonValue;

/** 실제 실행(live)인지, C-2 러너의 모의 실행(dry-run)인지. 계약 2 의 mode. */
public enum RunMode {
    LIVE("live"),
    DRY_RUN("dry-run");

    private final String value;

    RunMode(String value) {
        this.value = value;
    }

    @JsonValue
    public String value() {
        return value;
    }
}
