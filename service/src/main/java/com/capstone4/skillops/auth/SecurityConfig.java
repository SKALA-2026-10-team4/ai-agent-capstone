package com.capstone4.skillops.auth;

import java.io.IOException;
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
 * 운영 화면용 Bearer JWT 보안 설정이다. 세션과 CSRF 없이 요청마다 Authorization 헤더의 HS256 토큰만 본다.
 * 로그인이 필요한 경로는 제안서 결정(OPERATOR 역할만), 제안서 목록과 본문, 승인 기록, 진단 기록, 로그인 사용자 조회뿐이다.
 * 나머지는 인증 없이 둔다. MCP(/mcp)와 다른 담당자의 API 가 인증 때문에 갑자기 막히지 않게 하기 위해서다.
 * 경로별 권한은 securityFilterChain 한 곳에 모여 있다. 새 API 에 로그인을 요구하려면 그 메서드의 규칙 목록에 한 줄을 더한다.
 * 화면도 같은 규칙을 따른다. frontend/src/views/ProposalView.vue 는 frontend/src/stores/auth.js 의 hasRole('OPERATOR') 가
 * 참일 때만 승인과 거절 단추를 쓸 수 있게 한다.
 * 같은 패키지(service/src/main/java/com/capstone4/skillops/auth/)의 AuthController 가 passwordEncoder 빈을, TokenService 가
 * jwtEncoder 빈을 쓴다.
 * 이 설정은 모든 @WebMvcTest slice 에도 자동으로 적용된다.
 * service/src/test/resources/META-INF/spring/org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureWebMvc.imports
 * 파일이 이 클래스를 등록하기 때문이다.
 */
@Configuration
@EnableConfigurationProperties(AuthProperties.class)
public class SecurityConfig {

    private static final Logger log = LoggerFactory.getLogger(SecurityConfig.class);
    // HS256 서명 키는 256비트 이상이어야 하므로 32바이트보다 짧은 키는 받지 않는다. 임시 키도 이 길이로 만든다.
    private static final int MIN_SECRET_BYTES = 32;
    // 서명 키에 붙이는 JCA 알고리즘 이름이다. 임시 키와 설정 키가 같은 이름을 쓴다.
    private static final String HMAC_SHA256 = "HmacSHA256";

    /**
     * 경로별 권한과 401, 403 응답 방식을 정한다. 권한 규칙은 위에서부터 처음 맞는 한 줄이 적용되므로 순서가 의미를 가진다.
     * 서버 사이 중계 경로인 /internal/skill-changes 도 여기서는 열려 있다. 그 경로는
     * service/src/main/java/com/capstone4/skillops/diagnosis/DiagnosisController.java 가 X-Service-Token 헤더로 따로 막는다.
     * 토큰이 없는 요청과 토큰 검증에 실패한 요청은 응답을 만드는 곳이 다르다. 그래서 oauth2ResourceServer 와 exceptionHandling 두 곳에
     * 같은 처리기를 넣어 어느 쪽이든 같은 {code, message} 본문을 쓰게 한다.
     * 화면에서는 frontend/src/api/client.js 가 토큰을 실은 요청의 401 을 받으면 frontend/src/main.js 가 넘긴 onUnauthorized 로
     * 로그아웃하고 로그인 화면으로 보낸다.
     */
    @Bean
    SecurityFilterChain securityFilterChain(HttpSecurity http, JwtAuthenticationConverter jwtConverter) throws Exception {
        AuthenticationEntryPoint entryPoint = unauthorized();
        AccessDeniedHandler denied = forbidden();
        http
                .csrf(csrf -> csrf.disable())
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth
                        // 결정(승인과 거절)은 OPERATOR 역할만 할 수 있다.
                        .requestMatchers(HttpMethod.POST, "/api/proposals/*/approve", "/api/proposals/*/reject")
                        .hasRole("OPERATOR")
                        // 제안서 목록과 본문은 로그인한 사람이면 누구나 읽는다.
                        .requestMatchers(HttpMethod.GET, "/api/proposals", "/api/proposals/*").authenticated()
                        // 로그인 사용자 조회, 승인 기록, 진단 기록의 목록과 상세도 로그인한 사람만 읽는다.
                        .requestMatchers("/api/auth/me", "/api/approvals", "/api/diagnoses", "/api/diagnoses/**").authenticated()
                        // 그 밖의 경로(로그인, health, MCP /mcp, Swagger, 다른 담당자의 API)는 지금처럼 인증 없이 둔다.
                        // 새 API 에 로그인이 필요하면 이 줄 위에 규칙을 한 줄 더한다.
                        .anyRequest().permitAll())
                .oauth2ResourceServer(o -> o
                        .jwt(jwt -> jwt.jwtAuthenticationConverter(jwtConverter))
                        .authenticationEntryPoint(entryPoint)
                        .accessDeniedHandler(denied))
                .exceptionHandling(e -> e
                        .authenticationEntryPoint(entryPoint)
                        .accessDeniedHandler(denied));
        return http.build();
    }

    /**
     * 토큰의 roles claim(예: ["OPERATOR"])을 ROLE_OPERATOR 같은 권한으로 바꾸는 변환기다. securityFilterChain 의
     * hasRole("OPERATOR") 규칙이 이 권한을 본다. 사용자 이름은 sub claim 에서 온다. claim 이름은 TokenService.ROLES_CLAIM 을 쓴다.
     */
    @Bean
    JwtAuthenticationConverter jwtAuthenticationConverter() {
        JwtGrantedAuthoritiesConverter authorities = new JwtGrantedAuthoritiesConverter();
        authorities.setAuthoritiesClaimName(TokenService.ROLES_CLAIM);
        authorities.setAuthorityPrefix("ROLE_");
        JwtAuthenticationConverter converter = new JwtAuthenticationConverter();
        converter.setJwtGrantedAuthoritiesConverter(authorities);
        return converter;
    }

    /**
     * JWT 를 서명하고 검증하는 HS256 비밀 키를 만든다. 값은 skillops.auth.jwt-secret(SKILLOPS_JWT_SECRET)에서 읽는다.
     * 값이 비어 있으면 경고를 남기고 기동할 때마다 임시 키를 만든다. 그래서 재시작하면 이전 토큰이 모두 무효가 되어 다시 로그인해야 한다.
     * 값이 32바이트보다 짧으면 IllegalStateException 으로 기동을 멈춘다. jwtEncoder 와 jwtDecoder 가 이 키를 함께 쓴다.
     */
    @Bean
    SecretKey jwtSigningKey(AuthProperties properties) {
        String secret = properties.jwtSecret();
        if (secret == null || secret.isBlank()) {
            log.warn("SKILLOPS_JWT_SECRET 이 없어 임시 서명 키를 만들었습니다. 재시작하면 기존 토큰은 무효가 됩니다.");
            byte[] random = new byte[MIN_SECRET_BYTES];
            new SecureRandom().nextBytes(random);
            return new SecretKeySpec(random, HMAC_SHA256);
        }
        byte[] bytes = secret.getBytes(StandardCharsets.UTF_8);
        if (bytes.length < MIN_SECRET_BYTES) {
            throw new IllegalStateException("SKILLOPS_JWT_SECRET 은 32바이트 이상이어야 합니다.");
        }
        return new SecretKeySpec(bytes, HMAC_SHA256);
    }

    /** jwtSigningKey 로 토큰에 서명하는 인코더다. TokenService.issue 가 로그인 때 이 인코더로 access token 을 만든다. */
    @Bean
    JwtEncoder jwtEncoder(SecretKey key) {
        return new NimbusJwtEncoder(new ImmutableSecret<>(key));
    }

    /**
     * 요청의 Bearer 토큰을 jwtSigningKey 로 검증하는 디코더다. HS256 서명만 받는다. securityFilterChain 의 oauth2ResourceServer 가
     * 이 빈으로 서명과 만료 시각을 확인한다.
     */
    @Bean
    JwtDecoder jwtDecoder(SecretKey key) {
        return NimbusJwtDecoder.withSecretKey(key).macAlgorithm(MacAlgorithm.HS256).build();
    }

    /** 데모 계정 비밀번호를 BCrypt 해시와 비교하는 인코더다. AuthController 가 로그인 때 이 빈을 쓴다. */
    @Bean
    PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    /**
     * 토큰 없이 로그인이 필요한 경로에 온 요청과 Bearer 토큰 검증에 실패한 요청에 401 UNAUTHORIZED 를 돌려주는 처리기다.
     * WWW-Authenticate: Bearer 헤더를 붙이고, 본문은 writeError 로 쓴다.
     */
    private static AuthenticationEntryPoint unauthorized() {
        return (request, response, e) -> {
            response.setHeader("WWW-Authenticate", "Bearer");
            writeError(response, HttpServletResponse.SC_UNAUTHORIZED, "UNAUTHORIZED", "로그인이 필요합니다.");
        };
    }

    /** 로그인은 했지만 역할이 맞지 않는 요청(예: OWNER 의 승인)에 403 FORBIDDEN 을 돌려주는 처리기다. 본문은 writeError 로 쓴다. */
    private static AccessDeniedHandler forbidden() {
        return (request, response, e) ->
                writeError(response, HttpServletResponse.SC_FORBIDDEN, "FORBIDDEN", "이 작업을 할 권한이 없습니다.");
    }

    /**
     * 401 과 403 의 오류 본문을 서블릿 응답에 직접 쓴다. 모양은
     * service/src/main/java/com/capstone4/skillops/common/ApiError.java 와 같은 {"code":"...","message":"..."} 이고 키 순서도 같다.
     * order-agent 의 ErrorResponse(order-agent/backend/src/main/java/com/capstone4/orderagent/dto/common/ErrorResponse.java)와도
     * 앞의 두 칸이 같아서, frontend/src/api/client.js 의 httpError 가 이 오류도 다른 오류와 같은 방식으로 읽는다.
     * 이 처리기들은 DispatcherServlet 보다 앞의 Spring Security 필터 안에서 실행된다. 그래서 컨트롤러처럼 ApiError 를 돌려주고
     * Spring MVC 가 JSON 으로 바꾸게 할 수 없으므로, JSON 문자열을 직접 이어 붙인다.
     * 큰따옴표나 역슬래시를 이스케이프하지 않으므로 code 와 message 에는 그런 글자가 없는 고정 문구만 넣는다.
     * service/src/test/java/com/capstone4/skillops/api/ProposalApiContractTest.java 의 UNAUTHORIZED_BODY 와 FORBIDDEN_BODY 가
     * 이 문자열을 그대로 비교한다.
     */
    private static void writeError(HttpServletResponse response, int status, String code, String message)
            throws IOException {
        response.setStatus(status);
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.getWriter().write("{\"code\":\"" + code + "\",\"message\":\"" + message + "\"}");
    }
}
