package com.capstone4.orderagent.dto.order;

import java.time.Instant;
import java.util.*;
import java.util.function.Consumer;
import com.capstone4.orderagent.entity.Order;
import com.capstone4.orderagent.entity.enums.OrderStatus;
import org.junit.jupiter.api.*;
import org.springframework.test.util.ReflectionTestUtils;
import static org.assertj.core.api.Assertions.*;

/**
 * order-agent/backend/src/main/java/com/capstone4/orderagent/dto/order/OrderResponse.java 의 from 이 만드는 message 를 DB 없이 고정한다.
 * 재고 부족(OUT_OF_STOCK)과 결제 거절(PAYMENT_DECLINED)은 예전 문구 그대로이고, 엔트리 미선택(NO_SELECTION)은 재고 부족 문구를 쓰지 않으며,
 * 실행 규칙 종료(Order.stopByRule)는 result_code 인 규칙 reason 을 넣은 업무 규칙 문구여야 한다. 주문 엔티티는 실제 객체이고
 * PROCESSING 전이는 원래 OrderRepository.claim 의 UPDATE 가 하므로 칼럼 값을 직접 넣는다.
 */
class OrderResponseMessageTest {
    /** 처리 중인 카드 주문 하나에 업무 종료 전이를 적용해 응답으로 바꾼다. */
    static OrderResponse stopped(Consumer<Order> transition){
        var order=new Order(UUID.randomUUID(),"h".repeat(64),"c_1001","card","KR",false,Instant.parse("2026-10-26T00:00:00Z"));
        ReflectionTestUtils.setField(order,"status",OrderStatus.PROCESSING);
        transition.accept(order);
        return OrderResponse.from(order);
    }

    /** 도구가 끝낸 재고 부족과 결제 거절은 result_code 와 예전 문구를 그대로 낸다. */
    @Test void toolStopsKeepTheirMessages(){
        var rejected=stopped(Order::reject);
        assertThat(rejected.status()).isEqualTo(OrderStatus.REJECTED);
        assertThat(rejected.resultCode()).isEqualTo("OUT_OF_STOCK");
        assertThat(rejected.message()).isEqualTo("재고가 부족하여 주문을 거절했습니다.");
        var hold=stopped(Order::hold);
        assertThat(hold.status()).isEqualTo(OrderStatus.HOLD);
        assertThat(hold.resultCode()).isEqualTo("PAYMENT_DECLINED");
        assertThat(hold.message()).isEqualTo("결제가 거절되어 주문을 보류했습니다. 예약 재고는 복원되었습니다.");
    }
    /** 엔트리 미선택 거절은 재고 부족이라고 말하지 않는다. */
    @Test void noSelectionDoesNotClaimOutOfStock(){
        var response=stopped(Order::noSelection);
        assertThat(response.resultCode()).isEqualTo("NO_SELECTION");
        assertThat(response.message()).isEqualTo("처리할 Skill 을 고르지 못해 주문을 거절했습니다.");
    }
    /** 실행 규칙 종료는 상태와 상관없이 규칙 reason 을 넣은 문구를 내고, 결제 거절이나 재고 복원을 말하지 않는다. */
    @Test void ruleStopsNameTheRuleReason(){
        var hold=stopped(o->o.stopByRule(OrderStatus.HOLD,"PREPAID_ONLY"));
        assertThat(hold.status()).isEqualTo(OrderStatus.HOLD);
        assertThat(hold.resultCode()).isEqualTo("PREPAID_ONLY");
        assertThat(hold.message()).isEqualTo("업무 규칙(PREPAID_ONLY)에 따라 주문을 보류했습니다.");
        var rejected=stopped(o->o.stopByRule(OrderStatus.REJECTED,"DOMESTIC_ONLY"));
        assertThat(rejected.status()).isEqualTo(OrderStatus.REJECTED);
        assertThat(rejected.resultCode()).isEqualTo("DOMESTIC_ONLY");
        assertThat(rejected.message()).isEqualTo("업무 규칙(DOMESTIC_ONLY)에 따라 주문을 거절했습니다.");
    }
}
