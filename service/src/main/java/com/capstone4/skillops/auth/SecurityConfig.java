package com.capstone4.skillops.auth;

import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;

import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.oauth2.server.resource.authentication.JwtGrantedAuthoritiesConverter;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.access.AccessDeniedHandler;

import com.nimbusds.jose.jwk.source.ImmutableSecret;

import jakarta.servlet.http.HttpServletResponse;

/**
 * 운영 화면용 Bearer JWT. 세션·CSRF 없이 요청마다 토큰만 본다.
 * 보호하는 것은 승인·거절(OPERATOR)과 로그인 사용자 조회뿐이고, 나머지는 인증 없이 둔다 — MCP(/mcp)와
 * 다른 담당자의 API 가 인증 때문에 갑자기 막히지 않게 하려는 것. 테스트 slice 에도 자동으로 적용된다
 * (src/test/resources/META-INF/spring/…AutoConfigureWebMvc.imports).
 */
@Configuration
@EnableConfigurationProperties(AuthProperties.class)
public class SecurityConfig {

    private static final Logger log = LoggerFactory.getLogger(SecurityConfig.class);
    private static final int MIN_SECRET_BYTES = 32;

    @Bean
    SecurityFilterChain securityFilterChain(HttpSecurity http, JwtAuthenticationConverter jwtConverter) throws Exception {
        http
                .csrf(csrf -> csrf.disable())
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth
                        // 결정(승인·거절)은 운영자만
                        .requestMatchers(HttpMethod.POST, "/api/proposals/*/approve", "/api/proposals/*/reject")
                        .hasRole("OPERATOR")
                        .requestMatchers("/api/auth/me", "/api/approvals").authenticated()
                        // 그 밖(로그인, health, MCP /mcp, Swagger, 다른 담당자의 API)은 지금처럼 인증 없이 둔다.
                        // 새 API 에 로그인이 필요하면 위에 한 줄 추가한다.
                        .anyRequest().permitAll())
                .oauth2ResourceServer(o -> o
                        .jwt(jwt -> jwt.jwtAuthenticationConverter(jwtConverter))
                        .authenticationEntryPoint(unauthorized())
                        .accessDeniedHandler(forbidden()))
                .exceptionHandling(e -> e
                        .authenticationEntryPoint(unauthorized())
                        .accessDeniedHandler(forbidden()));
        return http.build();
    }

    /** roles claim(["OPERATOR"]) → ROLE_OPERATOR 권한. 사용자 이름은 sub. */
    @Bean
    JwtAuthenticationConverter jwtAuthenticationConverter() {
        JwtGrantedAuthoritiesConverter authorities = new JwtGrantedAuthoritiesConverter();
        authorities.setAuthoritiesClaimName(TokenService.ROLES_CLAIM);
        authorities.setAuthorityPrefix("ROLE_");
        JwtAuthenticationConverter converter = new JwtAuthenticationConverter();
        converter.setJwtGrantedAuthoritiesConverter(authorities);
        return converter;
    }

    @Bean
    SecretKey jwtSigningKey(AuthProperties properties) {
        String secret = properties.jwtSecret();
        if (secret == null || secret.isBlank()) {
            log.warn("SKILLOPS_JWT_SECRET 이 없어 임시 서명 키를 만들었습니다. 재시작하면 기존 토큰은 무효가 됩니다.");
            byte[] random = new byte[MIN_SECRET_BYTES];
            new SecureRandom().nextBytes(random);
            return new SecretKeySpec(random, "HmacSHA256");
        }
        byte[] bytes = secret.getBytes(StandardCharsets.UTF_8);
        if (bytes.length < MIN_SECRET_BYTES) {
            throw new IllegalStateException("SKILLOPS_JWT_SECRET 은 32바이트 이상이어야 합니다.");
        }
        return new SecretKeySpec(bytes, "HmacSHA256");
    }

    @Bean
    JwtEncoder jwtEncoder(SecretKey key) {
        return new NimbusJwtEncoder(new ImmutableSecret<>(key));
    }

    @Bean
    JwtDecoder jwtDecoder(SecretKey key) {
        return NimbusJwtDecoder.withSecretKey(key).macAlgorithm(MacAlgorithm.HS256).build();
    }

    @Bean
    PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    // 401/403 도 order-agent ErrorResponse 와 같은 {code, message} 모양으로 돌려준다.
    private static AuthenticationEntryPoint unauthorized() {
        return (request, response, e) -> {
            response.setHeader("WWW-Authenticate", "Bearer");
            writeError(response, HttpServletResponse.SC_UNAUTHORIZED, "UNAUTHORIZED", "로그인이 필요합니다.");
        };
    }

    private static AccessDeniedHandler forbidden() {
        return (request, response, e) ->
                writeError(response, HttpServletResponse.SC_FORBIDDEN, "FORBIDDEN", "이 작업을 할 권한이 없습니다.");
    }

    private static void writeError(HttpServletResponse response, int status, String code, String message)
            throws java.io.IOException {
        response.setStatus(status);
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.getWriter().write("{\"code\":\"" + code + "\",\"message\":\"" + message + "\"}");
    }
}
