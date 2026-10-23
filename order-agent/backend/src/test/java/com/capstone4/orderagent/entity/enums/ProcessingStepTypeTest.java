package com.capstone4.orderagent.entity.enums;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;
import static com.capstone4.orderagent.entity.enums.ProcessingStepType.*;

/**
 * order-agent/backend/src/main/java/com/capstone4/orderagent/entity/enums/ProcessingStepType.java 의 BUSINESS_FLOW 와 after 를 보호한다.
 * 업무 단계 순서와 '이후 단계' 계산이 거절 뒤(OrderToolService)와 실패 뒤(OrderLifecycleService.fail)의 SKIPPED 기록 순서를 한곳에서 정하는지 확인한다.
 * 선언 순서도 함께 고정해, 누군가 ordinal 을 업무 순서로 착각해 값 자리를 옮기면 바로 드러나게 한다.
 */
class ProcessingStepTypeTest {
    /** 업무 순서는 선언 순서와 달리 RELEASE 를 건너뛴 재고, 결제, 배송, 알림 순서다. */
    @Test void businessFlowOrder(){
        assertThat(BUSINESS_FLOW).containsExactly(INVENTORY,PAYMENT,SHIPPING,NOTIFICATION);
        assertThat(ProcessingStepType.values()).containsExactly(INTAKE,INVENTORY,PAYMENT,RELEASE,SHIPPING,NOTIFICATION,RECOVERY);
    }
    /** 업무 단계 뒤의 단계만 남기고, 업무 단계가 아니면 전체 업무 순서를 돌려준다. */
    @Test void afterReturnsTheRemainingBusinessSteps(){
        assertThat(after(INVENTORY)).containsExactly(PAYMENT,SHIPPING,NOTIFICATION);
        assertThat(after(PAYMENT)).containsExactly(SHIPPING,NOTIFICATION);
        assertThat(after(SHIPPING)).containsExactly(NOTIFICATION);
        assertThat(after(NOTIFICATION)).isEmpty();
        for(var other:new ProcessingStepType[]{INTAKE,RELEASE,RECOVERY})assertThat(after(other)).as(other.name()).isEqualTo(BUSINESS_FLOW);
        assertThatThrownBy(()->after(null)).isInstanceOf(NullPointerException.class);
    }
}
