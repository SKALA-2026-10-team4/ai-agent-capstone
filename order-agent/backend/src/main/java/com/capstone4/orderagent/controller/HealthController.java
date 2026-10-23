package com.capstone4.orderagent.controller;
import java.util.Map;
import org.springframework.boot.availability.ApplicationAvailability;
import org.springframework.boot.availability.ReadinessState;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
/**
 * 주문 Agent 가 요청을 받을 준비가 됐는지 GET /api/health 로 알려 준다. 판단은 Spring Boot 의 readiness 상태만 본다.
 * 기동 직후 order-agent/backend/src/main/java/com/capstone4/orderagent/config/InterruptedOrderRecovery.java 가 중단된 주문을 정리하는 동안은
 * readiness 가 아직 ACCEPTING_TRAFFIC 이 아니어서 503 이 나간다. 그래서 이 검사를 따르는 쪽은 복구가 끝난 뒤에야 붙는다.
 * order-agent/compose.yml 의 backend healthcheck(curl)와 order-agent/scripts/smoke.py 가 부르고, compose 의 frontend 는 이 검사가 통과한 뒤에 뜬다.
 * 기동 뒤 DB 연결을 계속 검사하는 API 는 아니다. 같은 경로를 쓰는 service/src/main/java/com/capstone4/skillops/api/HealthController.java 는
 * SkillOps service 자신의 것이며 이 클래스와 관계가 없다.
 */
@RestController
public class HealthController {
    private final ApplicationAvailability availability;
    /** Spring 이 애플리케이션 가용성 상태를 주입해 빈으로 만든다. */
    public HealthController(ApplicationAvailability availability) { this.availability = availability; }
    /**
     * readiness 가 ACCEPTING_TRAFFIC 이면 200 과 {status: ok, service: order-agent} 를, 아니면 503 과 status starting 을 돌려준다.
     * smoke 스크립트는 service 값이 order-agent 인지로 주문 Agent 에 붙었는지 확인한다.
     */
    @GetMapping("/api/health")
    ResponseEntity<Map<String, String>> health() {
        boolean ready = availability.getReadinessState() == ReadinessState.ACCEPTING_TRAFFIC;
        return ResponseEntity.status(ready ? 200 : 503).body(Map.of("status", ready ? "ok" : "starting", "service", "order-agent"));
    }
}
