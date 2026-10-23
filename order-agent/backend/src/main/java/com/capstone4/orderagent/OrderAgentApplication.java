package com.capstone4.orderagent;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * 주문 Agent 백엔드의 Spring Boot 진입점이다. 주문 Agent 는 SkillOps 가 진단하는 대상 Agent 로, 캠핑용품 주문을 snapshot 에 고정한
 * SKILL.md 묶음으로 처리하고 모든 관찰을 실행 기록(trace)으로 남긴다. SkillOps 본체(service)와 코드를 공유하지 않는 별도 Gradle 프로젝트라
 * 다른 모듈과는 HTTP 로만 연결된다.
 * EnableScheduling 은 Skill 수정 저장 이벤트를 service 로 보내는
 * order-agent/backend/src/main/java/com/capstone4/orderagent/workspace/SkillChangeDispatcher.java 의 dispatch 를 주기 실행하려고 켠다.
 * order-agent/backend/Dockerfile 의 ENTRYPOINT(java -jar app.jar)가 이 클래스를 띄우고, 통합 테스트의 SpringBootTest 도 이 설정을 찾아 쓴다.
 * 서버 포트는 8080 이며 로컬 데모에서는 order-agent/compose.yml 이 호스트의 8081 로 연다. 두 화면(frontend, order-agent/frontend)의
 * vite 프록시는 기본으로 /api 요청을 localhost:8081 로 보낸다.
 */
@org.springframework.scheduling.annotation.EnableScheduling
@SpringBootApplication
public class OrderAgentApplication {
    /** Spring 애플리케이션 컨텍스트를 띄운다. */
    public static void main(String[] args) {
        SpringApplication.run(OrderAgentApplication.class, args);
    }
}
