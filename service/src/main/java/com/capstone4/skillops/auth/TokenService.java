package com.capstone4.skillops.auth;

import java.time.Clock;
import java.time.Instant;
import java.util.List;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.stereotype.Service;

/**
 * 로그인한 데모 계정에 access token(HS256 JWT)을 서명해 주고, 검증된 토큰에서 사용자를 꺼낸다.
 * refresh token 은 두지 않는다. 토큰이 만료되면 다시 로그인한다. frontend/src/stores/auth.js 가 만료 시각을 보고 세션을 지운다.
 * 같은 패키지(service/src/main/java/com/capstone4/skillops/auth/)의 AuthController 가 issue 와 userOf 를 부르고,
 * service/src/main/java/com/capstone4/skillops/api/ApprovalController.java 가 userOf 를 부른다. 상수 가운데 ROLES_CLAIM 은 같은
 * 패키지의 SecurityConfig 도 쓴다.
 */
@Service
public class TokenService {

    /**
     * 토큰의 iss claim 값이다. service/src/test/java/com/capstone4/skillops/api/ApprovalControllerTest.java 가 위조 토큰과 만료 토큰을
     * 만들 때도 이 값을 쓴다. 같은 패키지의 SecurityConfig.jwtDecoder 는 iss 값을 검사하지 않는다.
     */
    public static final String ISSUER = "skillops-service";
    /** 역할 목록을 담는 claim 이름이다. SecurityConfig.jwtAuthenticationConverter 가 이 이름으로 권한을 만든다. */
    public static final String ROLES_CLAIM = "roles";
    /** 화면에 보여 줄 표시 이름(display_name)을 담는 claim 이름이다. */
    public static final String NAME_CLAIM = "name";

    private final JwtEncoder encoder;
    private final AuthProperties properties;
    private final Clock clock;

    /**
     * Spring 이 빈을 만들 때 쓰는 생성자다. 시계는 UTC 시스템 시계를 쓴다.
     * 생성자가 둘이라 Spring 이 고를 수 있도록 @Autowired 를 붙였다. 이 표시를 지우면 빈을 만들지 못한다.
     */
    @Autowired
    public TokenService(JwtEncoder encoder, AuthProperties properties) {
        this(encoder, properties, Clock.systemUTC());
    }

    /** 시각을 고정할 수 있는 생성자다. 공개 생성자가 이 생성자에 위임하며, 지금 이 생성자를 직접 부르는 테스트는 없다. */
    TokenService(JwtEncoder encoder, AuthProperties properties, Clock clock) {
        this.encoder = encoder;
        this.properties = properties;
        this.clock = clock;
    }

    /**
     * 계정 하나에 access token 을 서명해 준다. claim 은 iss, sub(아이디), name(표시 이름), roles, iat, exp 이고, exp 는 지금 시각에
     * tokenTtl(기본 2시간)을 더한 값이다. AuthController.loginResponse 가 로그인에 성공했을 때 부른다.
     */
    public Issued issue(AuthProperties.User user) {
        Instant now = clock.instant();
        Instant expiresAt = now.plus(properties.tokenTtl());
        JwtClaimsSet claims = JwtClaimsSet.builder()
                .issuer(ISSUER)
                .subject(user.username())
                .claim(NAME_CLAIM, user.displayName())
                .claim(ROLES_CLAIM, user.roles())
                .issuedAt(now)
                .expiresAt(expiresAt)
                .build();
        JwsHeader header = JwsHeader.with(MacAlgorithm.HS256).build();
        Jwt jwt = encoder.encode(JwtEncoderParameters.from(header, claims));
        return new Issued(jwt.getTokenValue(), properties.tokenTtl().toSeconds());
    }

    /** 서명한 토큰 문자열과 수명(초)이다. 로그인 응답의 access_token 과 expires_in 이 된다. */
    public record Issued(String token, long expiresInSeconds) {
    }

    /**
     * 검증된 토큰에서 사용자(sub, name, roles)를 꺼낸다. roles 가 없으면 빈 목록으로 둔다.
     * AuthController.me 가 GET /api/auth/me 응답으로, ApprovalController 가 승인과 거절 기록의 결정자로 쓴다.
     */
    public static AuthUser userOf(Jwt jwt) {
        List<String> roles = jwt.getClaimAsStringList(ROLES_CLAIM);
        return new AuthUser(jwt.getSubject(), jwt.getClaimAsString(NAME_CLAIM), roles == null ? List.of() : roles);
    }
}
