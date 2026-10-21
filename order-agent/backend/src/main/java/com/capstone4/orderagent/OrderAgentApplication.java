package com.capstone4.orderagent;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@org.springframework.scheduling.annotation.EnableScheduling
@SpringBootApplication
public class OrderAgentApplication {
    public static void main(String[] args) {
        SpringApplication.run(OrderAgentApplication.class, args);
    }
}
