package com.capstone4.orderagent.adapter;
import java.util.UUID;
import org.springframework.stereotype.Component;
@Component
public class NotificationMockAdapter {
    public void send(UUID orderId, String customer, String shipmentReference) {
        if (orderId == null || customer == null || shipmentReference == null)
            throw new IllegalStateException("Shipment required for success notification");
        // No external message. Caller persists SENT and the actual processing step.
    }
}
