package com.capstone4.orderagent.service;

import java.util.UUID;
import com.capstone4.orderagent.harness.OrderHarness;
import org.springframework.stereotype.Service;

/** Shared execution entry; deliberately no transaction spans a model request. */
@Service
public class OrderProcessingService {
    private final OrderHarness harness;
    public OrderProcessingService(OrderHarness harness){this.harness=harness;}
    public void process(UUID orderId){harness.execute(orderId);}
}
