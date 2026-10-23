package com.capstone4.skillops;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * SkillOps service 의 Spring Boot 진입점이다. @SpringBootApplication 이 com.capstone4.skillops 아래의 REST 컨트롤러,
 * 서비스, MCP 도구(@Component) 빈을 찾아 한 앱으로 띄운다. REST 경로(/api, /internal)와 MCP 엔드포인트(/mcp)는 모두
 * service/src/main/resources/application.yml 의 server.port 인 8080 포트에서 열린다.
 * service/build.gradle 의 org.springframework.boot 플러그인이 bootRun 때 이 클래스의 main 을 찾아 실행한다.
 * README.md 의 기동 절차도 cd service 뒤 ./gradlew bootRun 으로 이 클래스를 띄운다.
 * 테스트의 @WebMvcTest 도 이 클래스를 찾아 테스트 설정의 시작점으로 쓴다.
 */
@SpringBootApplication
public class SkillopsServiceApplication {

    /** 명령행 인자를 그대로 넘겨 Spring 애플리케이션을 시작한다. */
    public static void main(String[] args) {
        SpringApplication.run(SkillopsServiceApplication.class, args);
    }
}
