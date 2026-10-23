package com.capstone4.orderagent.config;

import com.capstone4.orderagent.service.OrderLifecycleService;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

/**
 * 기동 직후 중단된 주문과 실행을 정리하는 ApplicationRunner 다. 인스턴스 하나만 도는 시연 환경을 전제로, 끝나지 않은 처리를
 * 정상 트래픽을 받기 전에 복구한다. Spring Boot 는 runner 가 끝난 뒤에 readiness 를 ACCEPTING_TRAFFIC 으로 바꾸므로,
 * order-agent/backend/src/main/java/com/capstone4/orderagent/controller/HealthController.java 의 GET /api/health 는
 * 복구가 끝날 때까지 503 을 돌려준다. 여러 인스턴스가 함께 돌면 다른 인스턴스가 처리 중인 주문까지 실패로 닫으므로 그 구성에는 맞지 않는다.
 * 정리 절차는 order-agent/backend/src/main/java/com/capstone4/orderagent/service/OrderLifecycleService.java 의 recoverInterrupted 에 있다.
 */
@Component
public class InterruptedOrderRecovery implements ApplicationRunner {
    private final OrderLifecycleService lifecycle;
    /** 수명 주기 서비스를 주입받는다. */
    public InterruptedOrderRecovery(OrderLifecycleService lifecycle) { this.lifecycle = lifecycle; }
    /** Spring Boot 가 컨텍스트를 띄운 직후 부른다. OrderLifecycleService.recoverInterrupted 에 맡긴다. */
    @Override public void run(ApplicationArguments args) { lifecycle.recoverInterrupted(); }
}
