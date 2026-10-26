package com.capstone4.skillops.evaluation;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;

/** 평가 작업자와 새 경계의 메서드 권한을 활성화한다. */
@Configuration
@EnableScheduling
@EnableMethodSecurity
@EnableConfigurationProperties(EvaluationConfiguration.Settings.class)
public class EvaluationConfiguration {
    /** 실제 주문 실행은 격리된 fixed 서버를 명시적으로 설정한 경우에만 허용한다. */
    @ConfigurationProperties("skillops.evaluation")
    public record Settings(boolean fixedHarnessEnabled, String isolatedOrderAgentUrl, String environmentRef) {}
}
