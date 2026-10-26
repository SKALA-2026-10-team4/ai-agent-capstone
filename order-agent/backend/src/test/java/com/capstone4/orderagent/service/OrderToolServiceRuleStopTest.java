package com.capstone4.orderagent.service;

import java.time.*;
import java.util.*;
import com.capstone4.orderagent.adapter.*;
import com.capstone4.orderagent.entity.Order;
import com.capstone4.orderagent.entity.enums.*;
import com.capstone4.orderagent.harness.AgentException;
import com.capstone4.orderagent.repository.OrderRepository;
import com.capstone4.orderagent.trace.ExecutionRepository;
import org.junit.jupiter.api.*;
import org.springframework.test.util.ReflectionTestUtils;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * order-agent/backend/src/main/java/com/capstone4/orderagent/service/OrderToolService.java 의 stopByRule 과
 * order-agent/backend/src/main/java/com/capstone4/orderagent/entity/Order.java 의 stopByRule 을 DB 없이 고정한다.
 * 저장소, 재고 예약, 실행 기록은 mock 이고 주문 엔티티는 실제 객체다. PROCESSING 전이는 원래 OrderRepository.claim 의 UPDATE 가 하므로
 * 여기서는 칼럼 값을 직접 넣는다. 결제 전 규칙은 예약을 compensateUnpaid 와 같은 정책으로 되돌리고, 결제 뒤 규칙은 결제와 예약을 그대로 두며,
 * 어느 쪽이든 결제 상태는 바꾸지 않는지 본다.
 */
class OrderToolServiceRuleStopTest {
    final Instant now=Instant.parse("2026-10-26T00:00:00Z");
    final UUID execution=UUID.randomUUID();
    final OrderRepository orders=mock(OrderRepository.class);
    final InventoryReservations reservations=mock(InventoryReservations.class);
    final ExecutionRepository evidence=mock(ExecutionRepository.class);
    final OrderToolService service=new OrderToolService(orders,reservations,mock(PaymentMockAdapter.class),mock(ShippingMockAdapter.class),
        mock(NotificationMockAdapter.class),evidence,Clock.fixed(now,ZoneOffset.UTC));
    Order order;

    /** 처리 중인 카드 주문 하나를 저장소와 RUNNING 실행에 연결하고, 재고 해제 mock 이 주문의 예약 표시를 지우게 한다. */
    @BeforeEach void processingOrder(){
        order=new Order(UUID.randomUUID(),"h".repeat(64),"c_1001","card","KR",false,now);
        ReflectionTestUtils.setField(order,"status",OrderStatus.PROCESSING);
        when(orders.lockById(order.getId())).thenReturn(Optional.of(order));
        when(evidence.isRunningFor(execution,order.getId())).thenReturn(true);
        doAnswer(inv->{order.released();return null;}).when(reservations).releaseAll(order);
    }

    /** 결제 전 hold 규칙은 예약을 되돌린 증거를 규칙 단계 이름과 reason 으로 남기고 HOLD 로 끝내며, 결제는 시도하지 않은 상태로 둔다. */
    @Test void holdBeforePaymentReleasesReservationAndKeepsPaymentUntouched(){
        order.reserved();
        assertThat(service.stopByRule(order.getId(),execution,"payment",ProcessingStepType.PAYMENT,"hold","PREPAID_ONLY")).isEqualTo("HOLD");
        assertThat(order.getStatus()).isEqualTo(OrderStatus.HOLD);
        assertThat(order.getResultCode()).isEqualTo("PREPAID_ONLY");
        assertThat(order.getPaymentStatus()).isEqualTo(PaymentStatus.NOT_ATTEMPTED);
        assertThat(order.isInventoryReserved()).isFalse();
        assertThat(steps()).containsExactly(
            "PAYMENT SKIPPED PREPAID_ONLY 업무 규칙에 따라 주문을 보류해 실행하지 않았습니다.",
            "RELEASE SUCCESS null 예약 재고를 모두 복원했습니다.",
            "SHIPPING SKIPPED UPSTREAM_REJECTED 앞 단계 거절로 실행하지 않았습니다.",
            "NOTIFICATION SKIPPED UPSTREAM_REJECTED 앞 단계 거절로 실행하지 않았습니다.");
        var args=Map.of("order_id",order.getId().toString());
        var sequence=inOrder(evidence,reservations,orders);
        sequence.verify(evidence).append(execution,"TOOL_REQUEST","payment",Map.of("tool","inventory.release","arguments",args,"reason","PREPAID_ONLY"),now);
        sequence.verify(reservations).releaseAll(order);
        sequence.verify(evidence).append(execution,"TOOL_RESULT","payment",Map.of("tool","inventory.release","arguments",args,
            "result",Map.of("inventory.reserved",false)),now);
        sequence.verify(orders).flush();
    }
    /** 결제 뒤 단계의 reject 규칙은 승인된 결제와 예약을 확정 결과로 남기고 그 단계부터 SKIPPED 로 둔 채 REJECTED 로 끝낸다. */
    @Test void rejectAfterPaymentKeepsPaidPaymentAndReservation(){
        order.reserved();order.paid("pay_1");
        assertThat(service.stopByRule(order.getId(),execution,"shipping-reserve",ProcessingStepType.SHIPPING,"reject","NO_OVERSEAS")).isEqualTo("REJECTED");
        assertThat(order.getStatus()).isEqualTo(OrderStatus.REJECTED);
        assertThat(order.getResultCode()).isEqualTo("NO_OVERSEAS");
        assertThat(order.getPaymentStatus()).isEqualTo(PaymentStatus.PAID);
        assertThat(order.getPaymentReference()).isEqualTo("pay_1");
        assertThat(order.isInventoryReserved()).isTrue();
        assertThat(steps()).containsExactly(
            "SHIPPING SKIPPED NO_OVERSEAS 업무 규칙에 따라 주문을 거절해 실행하지 않았습니다.",
            "NOTIFICATION SKIPPED UPSTREAM_REJECTED 앞 단계 거절로 실행하지 않았습니다.");
        verifyNoInteractions(reservations);
        verify(evidence,never()).append(any(),anyString(),any(),any(),any());
    }
    /** 접수 검수 단계의 규칙은 예약 전이라 해제가 없고, 그 단계와 업무 단계 전체를 SKIPPED 로 남긴다. */
    @Test void ruleAtIntakeSkipsEveryBusinessStep(){
        assertThat(service.stopByRule(order.getId(),execution,"order-intake",ProcessingStepType.INTAKE,"reject","DOMESTIC_ONLY")).isEqualTo("REJECTED");
        assertThat(steps()).containsExactly(
            "INTAKE SKIPPED DOMESTIC_ONLY 업무 규칙에 따라 주문을 거절해 실행하지 않았습니다.",
            "INVENTORY SKIPPED UPSTREAM_REJECTED 앞 단계 거절로 실행하지 않았습니다.",
            "PAYMENT SKIPPED UPSTREAM_REJECTED 앞 단계 거절로 실행하지 않았습니다.",
            "SHIPPING SKIPPED UPSTREAM_REJECTED 앞 단계 거절로 실행하지 않았습니다.",
            "NOTIFICATION SKIPPED UPSTREAM_REJECTED 앞 단계 거절로 실행하지 않았습니다.");
        verifyNoInteractions(reservations);
    }
    /** 끝난 실행이나 처리 중이 아닌 주문에는 업무 효과 없이 EXECUTION_NOT_RUNNING, ORDER_NOT_PROCESSING 으로 멈춘다. */
    @Test void requiresARunningExecutionAndAProcessingOrder(){
        order.reserved();
        when(evidence.isRunningFor(execution,order.getId())).thenReturn(false);
        assertThatThrownBy(()->service.stopByRule(order.getId(),execution,"payment",ProcessingStepType.PAYMENT,"hold","PREPAID_ONLY"))
            .isInstanceOf(AgentException.class).hasMessage("EXECUTION_NOT_RUNNING");
        when(evidence.isRunningFor(execution,order.getId())).thenReturn(true);
        ReflectionTestUtils.setField(order,"status",OrderStatus.HOLD);
        assertThatThrownBy(()->service.stopByRule(order.getId(),execution,"payment",ProcessingStepType.PAYMENT,"hold","PREPAID_ONLY"))
            .isInstanceOf(AgentException.class).hasMessage("ORDER_NOT_PROCESSING");
        assertThat(order.getSteps()).isEmpty();
        assertThat(order.isInventoryReserved()).isTrue();
        verifyNoInteractions(reservations);
    }
    /** 엔티티의 규칙 종료는 HOLD 와 REJECTED 만 받고 처리 중인 주문에만 허용한다. */
    @Test void entityRuleStopAcceptsOnlyHoldOrRejectedWhileProcessing(){
        assertThatThrownBy(()->order.stopByRule(OrderStatus.SHIPPED,"X")).isInstanceOf(IllegalArgumentException.class);
        order.stopByRule(OrderStatus.HOLD,"X");
        assertThat(order.getStatus()).isEqualTo(OrderStatus.HOLD);
        assertThatThrownBy(()->order.stopByRule(OrderStatus.REJECTED,"Y")).isInstanceOf(IllegalStateException.class);
        assertThat(order.getResultCode()).isEqualTo("X");
    }

    /** 주문 단계 기록을 '단계 상태 결과코드 메시지' 줄로 바꾼다. */
    List<String> steps(){
        return order.getSteps().stream().map(s->s.getStep()+" "+s.getStatus()+" "+s.getResultCode()+" "+s.getMessage()).toList();
    }
}
