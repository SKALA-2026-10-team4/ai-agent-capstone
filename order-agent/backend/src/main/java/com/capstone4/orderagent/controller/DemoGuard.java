package com.capstone4.orderagent.controller;

import com.capstone4.orderagent.harness.AgentException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * 시연 전용 API 를 order-agent.demo-enabled 설정(환경 변수 ORDER_AGENT_DEMO_ENABLED, 기본 true)으로 막는 가드다.
 * 설정이 꺼져 있으면 SNAPSHOT_NOT_FOUND 를 던지고,
 * order-agent/backend/src/main/java/com/capstone4/orderagent/exception/GlobalExceptionHandler.java 가 이를 404 로 바꾼다.
 * 거절 이유를 따로 알리지 않고 없는 자원과 같은 응답으로 시연 API 를 감춘다.
 * order-agent/backend/src/main/java/com/capstone4/orderagent/controller/EvidenceController.java 의 publish(POST /api/snapshots)와
 * publishCandidate(POST /api/snapshots/candidates), order-agent/backend/src/main/java/com/capstone4/orderagent/controller/DryRunController.java 의
 * run(POST /api/dry-runs)이 메서드 본문 첫 줄에서 requireEnabled 를 부른다. 본문 검증(Valid)은 메서드보다 먼저 돌므로
 * 형식이 틀린 모의 실행 요청은 가드보다 먼저 400 이 된다. 조회, 주문, 수정 저장은 이 가드를 거치지 않는다.
 * order-agent/backend/src/test/java/com/capstone4/orderagent/integration/DemoDisabledIntegrationTest.java 가 이 경계를 고정한다.
 */
@Component
public class DemoGuard {
    private final boolean enabled;
    /** Spring 이 order-agent.demo-enabled 설정값을 주입해 가드를 만든다. */
    public DemoGuard(@Value("${order-agent.demo-enabled}") boolean enabled){this.enabled=enabled;}
    /** demo 기능이 꺼져 있으면 SNAPSHOT_NOT_FOUND 를 던지고, 켜져 있으면 아무것도 하지 않는다. */
    public void requireEnabled(){if(!enabled)throw new AgentException("SNAPSHOT_NOT_FOUND");}
}
