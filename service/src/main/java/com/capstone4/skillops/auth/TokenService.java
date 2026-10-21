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

/** 로그인한 데모 계정에 access token 을 서명해 준다. refresh token 은 두지 않는다(만료되면 다시 로그인). */
@Service
public class TokenService {

    public static final String ISSUER = "skillops-service";
    public static final String ROLES_CLAIM = "roles";
    public static final String NAME_CLAIM = "name";

    private final JwtEncoder encoder;
    private final AuthProperties properties;
    private final Clock clock;

    @Autowired
    public TokenService(JwtEncoder encoder, AuthProperties properties) {
        this(encoder, properties, Clock.systemUTC());
    }

    TokenService(JwtEncoder encoder, AuthProperties properties, Clock clock) {
        this.encoder = encoder;
        this.properties = properties;
        this.clock = clock;
    }

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

    public record Issued(String token, long expiresInSeconds) {
    }

    /** 토큰에 담긴 사용자. /api/auth/me 와 승인 기록에 쓴다. */
    public static AuthUser userOf(Jwt jwt) {
        List<String> roles = jwt.getClaimAsStringList(ROLES_CLAIM);
        return new AuthUser(jwt.getSubject(), jwt.getClaimAsString(NAME_CLAIM), roles == null ? List.of() : roles);
    }
}
