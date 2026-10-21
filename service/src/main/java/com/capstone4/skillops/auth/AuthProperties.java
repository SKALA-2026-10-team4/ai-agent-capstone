package com.capstone4.skillops.auth;

import java.time.Duration;
import java.util.List;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** application.yml 의 skillops.auth. 계정은 로컬 데모용으로 설정에만 둔다(DB 없음). */
@ConfigurationProperties("skillops.auth")
public record AuthProperties(String jwtSecret, Duration tokenTtl, List<User> users) {

    public AuthProperties {
        tokenTtl = tokenTtl == null ? Duration.ofHours(2) : tokenTtl;
        users = users == null ? List.of() : List.copyOf(users);
    }

    public record User(String username, String displayName, List<String> roles, String passwordHash) {
    }
}
