package com.capstone4.orderagent.entity;

import java.time.Instant;
import com.capstone4.orderagent.entity.enums.*;
import jakarta.persistence.*;

@Entity
@Table(name = "order_processing_steps")
public class OrderProcessingStep {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) private Long id;
    @ManyToOne(fetch = FetchType.LAZY, optional = false) @JoinColumn(name = "order_id") private Order order;
    @Column(nullable = false) private int sequence;
    @Enumerated(EnumType.STRING) @Column(nullable = false, length = 32) private ProcessingStepType step;
    @Enumerated(EnumType.STRING) @Column(nullable = false, length = 16) private ProcessingStepStatus status;
    @Column(length = 64) private String resultCode;
    @Column(nullable = false, length = 500) private String message;
    @Column(nullable = false) private Instant startedAt;
    @Column(nullable = false) private Instant completedAt;

    protected OrderProcessingStep() {}
    public OrderProcessingStep(Order order, int sequence, ProcessingStepType step,
            ProcessingStepStatus status, String resultCode, String message, Instant started, Instant ended) {
        this.order = order; this.sequence = sequence; this.step = step; this.status = status;
        this.resultCode = resultCode; this.message = message; this.startedAt = started; this.completedAt = ended;
    }
    public int getSequence() { return sequence; }
    public ProcessingStepType getStep() { return step; }
    public ProcessingStepStatus getStatus() { return status; }
    public String getResultCode() { return resultCode; }
    public String getMessage() { return message; }
    public Instant getStartedAt() { return startedAt; }
    public Instant getCompletedAt() { return completedAt; }
}
