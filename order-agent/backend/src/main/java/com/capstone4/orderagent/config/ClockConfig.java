package com.capstone4.orderagent.config;

import java.time.Clock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 모든 시각 기록과 배송 예정일 계산이 같은 기준을 쓰도록 UTC Clock 빈을 하나 제공한다. 시각을 코드에서 직접 읽지 않고 주입받게 해
 * 테스트가 고정 Clock 으로 바꿀 수 있다. order-agent/backend/src/main/java/com/capstone4/orderagent/service 의 OrderToolService,
 * OrderLifecycleService, InventoryRecovery, DryRunService 와 order-agent/backend/src/main/java/com/capstone4/orderagent/trace/TraceService.java,
 * order-agent/backend/src/main/java/com/capstone4/orderagent/workspace/SnapshotService.java,
 * order-agent/backend/src/main/java/com/capstone4/orderagent/adapter/ShippingMockAdapter.java 가 주입받는다.
 * 실행 경과 시간은 이 Clock 이 아니라 order-agent/backend/src/main/java/com/capstone4/orderagent/harness/ExecutionControl.java 가
 * System.nanoTime 으로 잰다.
 */
@Configuration
public class ClockConfig {
    /** Spring 이 Clock 빈으로 등록하는 UTC 시스템 시계다. */
    @Bean
    Clock clock() { return Clock.systemUTC(); }
}
