package com.capstone4.orderagent.exception;

import com.capstone4.orderagent.entity.enums.ProcessingStepType;

/** Only the failed stage crosses the rollback boundary; managed entities do not. */
public class ProcessingFailure extends RuntimeException {
    private final ProcessingStepType step;
    public ProcessingFailure(ProcessingStepType step, RuntimeException cause) { super(cause); this.step = step; }
    public ProcessingStepType getStep() { return step; }
}
