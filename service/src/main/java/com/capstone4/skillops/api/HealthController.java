package com.capstone4.skillops.api;

import java.util.Map;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * service 가 떠 있는지 확인하는 GET /api/health 응답을 준다. 본문은 status 와 service 두 칸으로 고정되어 있고, DB 나 Agent 연결은
 * 확인하지 않는다.
 * README.md 의 기동 절차가 bootRun 뒤 curl localhost:8080/api/health 로 확인하라고 안내한다. frontend/src 의 화면 코드는 이
 * 경로를 부르지 않는다.
 * service/src/main/java/com/capstone4/skillops/auth/SecurityConfig.java 는 이 경로를 인증 없이 열어 둔다.
 * order-agent/compose.yml 의 healthcheck 가 부르는 /api/health 는 주문 Agent 자신의
 * order-agent/backend/src/main/java/com/capstone4/orderagent/controller/HealthController.java 이고, 이 클래스가 아니다.
 */
@RestController
@RequestMapping("/api")
public class HealthController {

    /** 고정된 기동 확인 본문 {status: ok, service: skillops-service} 를 돌려준다. */
    @GetMapping("/health")
    public Map<String, String> health() {
        return Map.of("status", "ok", "service", "skillops-service");
    }
}
