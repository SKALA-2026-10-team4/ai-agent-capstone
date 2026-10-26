package com.capstone4.orderagent.service;

import java.time.*;
import java.util.*;
import com.capstone4.orderagent.adapter.*;
import com.capstone4.orderagent.entity.Order;
import com.capstone4.orderagent.entity.Product;
import com.capstone4.orderagent.entity.enums.OrderStatus;
import com.capstone4.orderagent.harness.ToolCatalog;
import com.capstone4.orderagent.repository.OrderRepository;
import com.capstone4.orderagent.trace.ExecutionRepository;
import org.junit.jupiter.api.*;
import org.springframework.test.util.ReflectionTestUtils;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * order-agent/backend/src/main/java/com/capstone4/orderagent/harness/ToolCatalog.java 의 Binding 에 선언한 produces 가
 * order-agent/backend/src/main/java/com/capstone4/orderagent/service/OrderToolService.java 가 실제로 출력에 넣는 키와 같은지 DB 없이 고정한다.
 * GET /api/tools 가 이 produces 를 내보내고 SkillOps Agent 가 그것으로 도구 빈틈을 판단하므로(개발 요청서), 둘이 어긋나면 잘못된 개발 요청서가 나간다.
 * 저장소, 재고 예약, 실행 기록은 mock 이고 결제·배송·알림은 실제 Mock 어댑터이며 주문 엔티티는 실제 객체다.
 * 한 주문으로 흐름 순서대로 도구를 execute 해, 각 도구가 앞 도구의 실제 업무 효과 위에서 성공 경로를 지나게 한다.
 * 도구를 더하면 FLOW 에 더해야 첫 테스트가 통과한다.
 */
@SuppressWarnings("unchecked")
class OrderToolServiceProducesTest {
    /** 흐름 순서의 단계 Skill 과 도구다. BINDINGS 의 도구 전체와 같은 집합이어야 한다. */
    static final List<String> FLOW=List.of("order.inspect","inventory.reserve","payment.charge","shipping.reserve","notify.send");
    final Instant now=Instant.parse("2026-10-26T00:00:00Z");
    final Clock clock=Clock.fixed(now,ZoneOffset.UTC);
    final UUID execution=UUID.randomUUID();
    final OrderRepository orders=mock(OrderRepository.class);
    final InventoryReservations reservations=mock(InventoryReservations.class);
    final ExecutionRepository evidence=mock(ExecutionRepository.class);
    final OrderToolService service=new OrderToolService(orders,reservations,new PaymentMockAdapter(),new ShippingMockAdapter(clock),
        new NotificationMockAdapter(),evidence,clock);

    /** 처리 중인 주문 하나를 저장소와 RUNNING 실행에 연결한다. 재고는 넉넉해 전량 예약되고, 해제 mock 은 예약 표시를 지운다. */
    Order processingOrder(String customer,String method){
        var product=mock(Product.class);
        when(product.getSku()).thenReturn("CAMP-LANTERN-LED");when(product.getName()).thenReturn("LED 캠핑 랜턴");when(product.getUnitPrice()).thenReturn(30000L);
        var order=new Order(UUID.randomUUID(),"h".repeat(64),customer,method,"KR",false,now);
        order.addItem(product,2);
        ReflectionTestUtils.setField(order,"status",OrderStatus.PROCESSING);
        when(orders.lockById(order.getId())).thenReturn(Optional.of(order));
        when(orders.findById(order.getId())).thenReturn(Optional.of(order));
        when(evidence.isRunningFor(execution,order.getId())).thenReturn(true);
        when(evidence.cached(any(),any(),any())).thenReturn(Optional.empty());
        when(reservations.reserveAll(order)).thenReturn(new InventoryReservations.Reservation(true,10));
        doAnswer(inv->{order.released();return null;}).when(reservations).releaseAll(order);
        return order;
    }
    /** 서버가 만드는 것과 같은 인자로 도구 하나를 실행하고 출력(result.context)을 돌려준다. 인자는 OrderToolService.initial 의 컨텍스트로 만든다. */
    Map<String,Object> run(Order order,String tool){
        var arguments=ToolCatalog.arguments(tool,service.initial(order.getId()).context());
        var result=service.execute(execution,order.getId(),"step",tool,arguments);
        return (Map<String,Object>)result.get("context");
    }

    /** FLOW 는 ToolCatalog 의 도구 전체를 덮는다. 새 도구를 BINDINGS 에만 더하면 여기서 실패해 FLOW 에 넣도록 알린다. */
    @Test void flowCoversEveryCatalogTool(){
        assertThat(ToolCatalog.tools().stream().map(t->t.get("tool")).toList()).containsExactlyInAnyOrderElementsOf(FLOW);
    }

    /** 성공 경로에서 도구마다 실제 출력 키가 선언한 produces 와 정확히 같고, 주문은 끝까지 PROCESSING 으로 다음 단계에 넘어간다. */
    @Test void successPathOutputKeysEqualDeclaredProduces(){
        var order=processingOrder("c_1001","card");
        for(String tool:FLOW){
            var output=run(order,tool);
            assertThat(output.keySet()).as(tool).containsExactlyInAnyOrderElementsOf(ToolCatalog.produces(tool));
            assertThat(output.values()).as(tool).doesNotContainNull();
            assertThat(order.getStatus()).as(tool).isEqualTo(OrderStatus.PROCESSING);
        }
        verify(evidence,times(FLOW.size())).operation(eq(execution),anyString(),anyString(),any(),any());
    }

    /** 업무 거절 경로(재고 부족, 결제 거절)의 출력도 선언 produces 밖의 키를 내지 않는다. 결제 거절은 payment.id 없이 payment.status 만 낸다. */
    @Test void businessStopOutputsStayWithinDeclaredProduces(){
        var outOfStock=processingOrder("c_1001","card");
        when(reservations.reserveAll(outOfStock)).thenReturn(new InventoryReservations.Reservation(false,0));
        run(outOfStock,"order.inspect");
        var inventory=run(outOfStock,"inventory.reserve");
        assertThat(ToolCatalog.produces("inventory.reserve")).containsAll(inventory.keySet());
        assertThat(inventory).containsEntry("inventory.reserved",false);
        assertThat(outOfStock.getStatus()).isEqualTo(OrderStatus.REJECTED);

        var declined=processingOrder("c_declined","card");
        run(declined,"order.inspect");run(declined,"inventory.reserve");
        var payment=run(declined,"payment.charge");
        assertThat(ToolCatalog.produces("payment.charge")).containsAll(payment.keySet());
        assertThat(payment).containsOnlyKeys("payment.status").containsEntry("payment.status","DECLINED");
        assertThat(declined.getStatus()).isEqualTo(OrderStatus.HOLD);
    }
}
