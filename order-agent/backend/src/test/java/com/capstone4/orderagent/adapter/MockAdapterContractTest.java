package com.capstone4.orderagent.adapter;

import java.time.*;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

/**
 * order-agent/backend/src/main/java/com/capstone4/orderagent/adapter/PaymentMockAdapter.java 와
 * order-agent/backend/src/main/java/com/capstone4/orderagent/adapter/ShippingMockAdapter.java 의 정책을 보호하는 특성 테스트다.
 * 경계값, 참조 번호 형식, 예외 종류와 메시지를 고정하므로 어댑터의 튜닝 상수를 바꾸면 이 테스트의 기대값도 함께 바꾼다.
 */
class MockAdapterContractTest {
    final UUID id=UUID.fromString("11111111-2222-3333-4444-555555555555");

    /** 결제는 재고가 예약된 card 나 prepaid 주문만 받고, c_declined 고객의 card 결제만 거절한다. */
    @Test void paymentPolicy(){
        var mock=new PaymentMockAdapter();
        assertThat(mock.pay(id,"c_1001",30000,"card",true)).isEqualTo(new PaymentMockAdapter.Result(true,"pay_mock_"+id));
        assertThat(mock.pay(id,"c_1001",0,"prepaid",true)).isEqualTo(new PaymentMockAdapter.Result(true,"pay_mock_"+id));
        assertThat(mock.pay(id,"c_declined",30000,"card",true)).isEqualTo(new PaymentMockAdapter.Result(false,null));
        assertThat(mock.pay(id,"c_declined",30000,"prepaid",true).paid()).isTrue();
        assertThat(mock.pay(id,null,30000,"card",true).paid()).isTrue();
        for(String method:new String[]{null,"cash","CARD",""})
            assertThatThrownBy(()->mock.pay(id,"c_1001",30000,method,true)).isInstanceOf(IllegalStateException.class).hasMessage("Invalid payment request");
        assertThatThrownBy(()->mock.pay(id,"c_1001",-1,"card",true)).isInstanceOf(IllegalStateException.class).hasMessage("Invalid payment request");
        assertThatThrownBy(()->mock.pay(id,"c_declined",30000,"card",false)).isInstanceOf(IllegalStateException.class).hasMessage("Invalid payment request");
    }
    /** 배송은 결제와 재고 예약이 끝난 KR, US, JP 주문만 받고, 국내 2일이나 해외 7일에서 빠른 배송이면 하루를 뺀다. */
    @Test void shippingPolicy(){
        var mock=new ShippingMockAdapter(Clock.fixed(Instant.parse("2026-10-20T23:30:00Z"),ZoneOffset.UTC));
        assertThat(mock.reserve(id,"KR",false,true,true)).isEqualTo(new ShippingMockAdapter.Result("ship_mock_"+id,LocalDate.of(2026,10,22)));
        assertThat(mock.reserve(id,"KR",true,true,true).estimatedDeliveryDate()).isEqualTo(LocalDate.of(2026,10,21));
        assertThat(mock.reserve(id,"US",false,true,true).estimatedDeliveryDate()).isEqualTo(LocalDate.of(2026,10,27));
        assertThat(mock.reserve(id,"JP",true,true,true).estimatedDeliveryDate()).isEqualTo(LocalDate.of(2026,10,26));
        assertThat(mock.reserve(null,"JP",true,true,true).reference()).isEqualTo("ship_mock_null");
        for(String country:new String[]{"CN","kr",""})
            assertThatThrownBy(()->mock.reserve(id,country,false,true,true)).isInstanceOf(IllegalStateException.class).hasMessage("Paid and reserved order required");
        assertThatThrownBy(()->mock.reserve(id,"KR",false,false,true)).isInstanceOf(IllegalStateException.class).hasMessage("Paid and reserved order required");
        assertThatThrownBy(()->mock.reserve(id,"KR",false,true,false)).isInstanceOf(IllegalStateException.class).hasMessage("Paid and reserved order required");
        assertThatThrownBy(()->mock.reserve(id,null,false,false,true)).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(()->mock.reserve(id,null,false,true,true)).isInstanceOf(NullPointerException.class);
    }
}
