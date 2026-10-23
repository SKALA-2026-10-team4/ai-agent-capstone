package com.capstone4.skillops.auth;

import java.time.Duration;
import java.util.List;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * service/src/main/resources/application.yml 의 skillops.auth 설정을 담는 레코드다. jwtSecret 은 HS256 서명 키
 * (SKILLOPS_JWT_SECRET), tokenTtl 은 access token 수명, users 는 데모 계정 목록이다.
 * 계정은 로컬 데모용이라 DB 없이 설정에만 둔다. 비밀번호는 BCrypt 해시로만 적고 SKILLOPS_OWNER_PASSWORD_HASH,
 * SKILLOPS_OPERATOR_PASSWORD_HASH 환경 변수로 바꿀 수 있다.
 * 같은 패키지(service/src/main/java/com/capstone4/skillops/auth/)의 SecurityConfig 가 @EnableConfigurationProperties 로 이 레코드를
 * 빈으로 등록한다. SecurityConfig.jwtSigningKey 가 jwtSecret 을, TokenService.issue 가 tokenTtl 을, AuthController.findUser 가
 * users 를 읽는다.
 * 토큰 수명을 바꾸려면 application.yml 의 skillops.auth.token-ttl 을 고친다. 설정값이 아래 기본값보다 우선한다.
 */
@ConfigurationProperties("skillops.auth")
public record AuthProperties(String jwtSecret, Duration tokenTtl, List<User> users) {

    /** 설정이 빠졌을 때 기본값을 채운다. 토큰 수명은 2시간, 계정 목록은 빈 목록이고, 받은 목록은 바꿀 수 없는 사본으로 둔다. */
    public AuthProperties {
        tokenTtl = tokenTtl == null ? Duration.ofHours(2) : tokenTtl;
        users = users == null ? List.of() : List.copyOf(users);
    }

    /** 데모 계정 한 개다. roles 는 OWNER 나 OPERATOR 같은 역할 이름이고, passwordHash 는 BCrypt 해시다. */
    public record User(String username, String displayName, List<String> roles, String passwordHash) {
    }
}
