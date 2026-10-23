package com.capstone4.skillops.auth;

import java.util.Optional;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.capstone4.skillops.common.ApiError;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import tools.jackson.databind.PropertyNamingStrategies;
import tools.jackson.databind.annotation.JsonNaming;

/**
 * 운영 화면 로그인 REST API 다. POST /api/auth/login 은 데모 계정의 아이디와 비밀번호를 확인해 access token 을 발급하고,
 * GET /api/auth/me 는 요청에 실린 토큰의 사용자를 돌려준다.
 * 계정은 DB 가 아니라 service/src/main/resources/application.yml 의 skillops.auth.users 에만 있고, 같은 패키지
 * (service/src/main/java/com/capstone4/skillops/auth/)의 AuthProperties 가 그 값을 담는다. 로그인 실패는 계정이 없을 때와
 * 비밀번호가 틀렸을 때 모두 같은 401 INVALID_CREDENTIALS 본문을 비슷한 응답 시간에 돌려줘 어느 쪽인지 드러나지 않게 한다.
 * 화면은 frontend/src/api/auth.js 의 login() 으로 /service/api/auth/login 을, me() 로 /service/api/auth/me 를 부른다.
 * login() 은 frontend/src/stores/auth.js 의 login() 이 쓰고, me() 를 부르는 화면 코드는 지금 없다.
 * service/src/main/java/com/capstone4/skillops/auth/SecurityConfig.java 는 /api/auth/login 을 인증 없이 열어 두고 /api/auth/me 에는
 * 로그인을 요구한다.
 */
@RestController
@RequestMapping("/api/auth")
public class AuthController {

    // 없는 계정이어도 이 해시와 한 번 비교해, 있는 계정과 같은 시간만큼 비교하게 한다. 그래서 응답 시간으로 계정 존재 여부가
    // 드러나지 않는다. 데모 계정 해시와 같은 BCrypt 비용(10)이다.
    // service/src/test/java/com/capstone4/skillops/auth/AuthControllerTimingTest.java 가 같은 값을 복사해 이 비교를 확인한다.
    private static final String DUMMY_HASH = "$2a$10$MYFWOp34jBq3hErMezLSzuMBGK4MaW2dSjmHNG3eJFkY/qpdDVF5.";

    private final AuthProperties properties;
    private final PasswordEncoder passwords;
    private final TokenService tokens;

    /**
     * Spring 이 빈으로 만들 때 AuthProperties, PasswordEncoder(SecurityConfig.passwordEncoder), TokenService 를 넣는다.
     * service/src/test/java/com/capstone4/skillops/auth/AuthControllerTimingTest.java 는 이 생성자를 직접 불러 spy 인코더와
     * 가짜 TokenService 를 넣는다.
     */
    public AuthController(AuthProperties properties, PasswordEncoder passwords, TokenService tokens) {
        this.properties = properties;
        this.passwords = passwords;
        this.tokens = tokens;
    }

    /**
     * 아이디와 비밀번호를 확인해 로그인 응답(LoginResponse)을 돌려준다. 계정을 찾지 못해도 DUMMY_HASH 와 비교를 한 번 한 뒤에
     * 실패를 돌려준다. 실패 본문은 service/src/main/java/com/capstone4/skillops/common/ApiError.java 의 {code, message} 모양이다.
     * 빈 아이디나 빈 비밀번호는 @NotBlank 검증에서 400 으로 끝난다.
     * frontend/src/stores/auth.js 의 login() 이 응답의 access_token, expires_in, user 를 세션에 저장하고,
     * frontend/src/views/LoginView.vue 는 401 을 받으면 자체 문구를 보여 준다.
     */
    @PostMapping("/login")
    public ResponseEntity<?> login(@Valid @RequestBody LoginRequest request) {
        Optional<AuthProperties.User> user = findUser(request.username());
        boolean matches = passwords.matches(request.password(), user.map(AuthProperties.User::passwordHash).orElse(DUMMY_HASH));
        if (user.isEmpty() || !matches) {
            return ApiError.response(HttpStatus.UNAUTHORIZED.value(), "INVALID_CREDENTIALS",
                    "아이디 또는 비밀번호가 올바르지 않습니다.");
        }
        return ResponseEntity.ok(loginResponse(user.get()));
    }

    /**
     * 요청 토큰에 담긴 사용자(sub, name, roles)를 AuthUser 로 돌려준다. 토큰이 없거나 잘못되면 SecurityConfig 가 먼저 401 로
     * 막으므로 이 메서드에는 검증된 Jwt 만 들어온다.
     */
    @GetMapping("/me")
    public AuthUser me(@AuthenticationPrincipal Jwt jwt) {
        return TokenService.userOf(jwt);
    }

    /** 설정의 데모 계정 가운데 아이디가 같은 첫 계정을 찾는다. 같은 아이디가 둘이면 앞의 계정만 쓴다. */
    private Optional<AuthProperties.User> findUser(String username) {
        return properties.users().stream()
                .filter(u -> u.username().equals(username))
                .findFirst();
    }

    /** TokenService 로 토큰을 발급하고, token_type 을 Bearer 로, expires_in 을 토큰 수명(초)으로 둔 로그인 응답을 만든다. */
    private LoginResponse loginResponse(AuthProperties.User user) {
        TokenService.Issued issued = tokens.issue(user);
        return new LoginResponse(issued.token(), "Bearer", issued.expiresInSeconds(),
                new AuthUser(user.username(), user.displayName(), user.roles()));
    }

    /** 로그인 요청 본문 {username, password} 다. 두 칸 모두 비어 있으면 안 된다. */
    public record LoginRequest(@NotBlank String username, @NotBlank String password) {
    }

    /**
     * 로그인 응답 본문이다. JSON 칸 이름은 access_token, token_type, expires_in, user 다. frontend/src/stores/auth.js 의 login() 이
     * 이 가운데 access_token, expires_in, user 를 읽는다.
     */
    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record LoginResponse(String accessToken, String tokenType, long expiresIn, AuthUser user) {
    }
}
