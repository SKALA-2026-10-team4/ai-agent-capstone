package com.capstone4.orderagent.adapter;
import java.util.UUID;
import org.springframework.stereotype.Component;
/**
 * 알림 Mock 이다. 배송 참조가 있는 주문만 성공 알림 대상으로 인정하고 실제 메시지는 보내지 않는다.
 * order-agent/backend/src/main/java/com/capstone4/orderagent/service/OrderToolService.java 의 notify.send 처리가 부른다.
 */
@Component
public class NotificationMockAdapter {
    /** 성공 알림을 요청한다. 주문 id, 고객, 배송 참조 가운데 하나라도 없으면 IllegalStateException 을 던진다. */
    public void send(UUID orderId, String customer, String shipmentReference) {
        if (orderId == null || customer == null || shipmentReference == null)
            throw new IllegalStateException("Shipment required for success notification");
        // 외부 메시지는 보내지 않는다. SENT 상태와 실제 처리 단계 기록은 호출자가 저장한다.
    }
}
