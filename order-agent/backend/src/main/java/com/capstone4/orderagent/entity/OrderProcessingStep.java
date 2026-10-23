package com.capstone4.orderagent.entity;

import java.time.Instant;
import com.capstone4.orderagent.entity.enums.*;
import jakarta.persistence.*;

/**
 * 주문 처리 단계 기록 한 줄(order_processing_steps)이다. 순번, 단계 종류, 상태, 결과 코드, 사람이 읽는 메시지, 시작과 끝 시각을 담는다.
 * 실행 기록(trace)이 Agent 의 관찰을 모두 남기는 것과 달리, 이 기록은 사용자에게 보여 줄 업무 결과 요약이며 주문 응답의 steps 가 된다.
 * 바꾸는 메서드가 없어 한 번 남긴 기록은 고치지 않는다. 순번은 order-agent/backend/src/main/resources/schema.sql 의
 * (order_id, sequence) UNIQUE 로 겹치지 않는다.
 * order-agent/backend/src/main/java/com/capstone4/orderagent/entity/Order.java 의 addStep 만 만들고,
 * order-agent/backend/src/main/java/com/capstone4/orderagent/dto/order/OrderResponse.java 의 from 이 읽는다. 접근자는 칼럼 값을 그대로 돌려준다.
 */
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

    /** JPA 가 행을 읽을 때 쓰는 기본 생성자다. */
    protected OrderProcessingStep() {}
    /** 단계 기록 한 줄을 만든다. sequence 는 Order.addStep 이 지금 단계 수에 1 을 더해 정한다. */
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
