package com.capstone4.orderagent.config;

import com.capstone4.orderagent.service.OrderLifecycleService;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

/** Single-instance demo: recover unfinished local transactions before serving healthy traffic. */
@Component
public class InterruptedOrderRecovery implements ApplicationRunner {
    private final OrderLifecycleService lifecycle;
    public InterruptedOrderRecovery(OrderLifecycleService lifecycle) { this.lifecycle = lifecycle; }
    @Override public void run(ApplicationArguments args) { lifecycle.recoverInterrupted(); }
}
