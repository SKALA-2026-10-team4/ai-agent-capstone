package com.capstone4.skillops.auth;

import java.util.Map;
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

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import tools.jackson.databind.PropertyNamingStrategies;
import tools.jackson.databind.annotation.JsonNaming;

@RestController
@RequestMapping("/api/auth")
public class AuthController {

    // 없는 계정이어도 같은 시간만큼 비교해 계정 존재 여부가 드러나지 않게 한다.
    private static final String DUMMY_HASH = "$2a$10$MYFWOp34jBq3hErMezLSzuMBGK4MaW2dSjmHNG3eJFkY/qpdDVF5.";

    private final AuthProperties properties;
    private final PasswordEncoder passwords;
    private final TokenService tokens;

    public AuthController(AuthProperties properties, PasswordEncoder passwords, TokenService tokens) {
        this.properties = properties;
        this.passwords = passwords;
        this.tokens = tokens;
    }

    @PostMapping("/login")
    public ResponseEntity<?> login(@Valid @RequestBody LoginRequest request) {
        Optional<AuthProperties.User> user = properties.users().stream()
                .filter(u -> u.username().equals(request.username()))
                .findFirst();
        boolean matches = passwords.matches(request.password(), user.map(AuthProperties.User::passwordHash).orElse(DUMMY_HASH));
        if (user.isEmpty() || !matches) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(Map.of("code", "INVALID_CREDENTIALS", "message", "아이디 또는 비밀번호가 올바르지 않습니다."));
        }
        AuthProperties.User u = user.get();
        TokenService.Issued issued = tokens.issue(u);
        return ResponseEntity.ok(new LoginResponse(issued.token(), "Bearer", issued.expiresInSeconds(),
                new AuthUser(u.username(), u.displayName(), u.roles())));
    }

    @GetMapping("/me")
    public AuthUser me(@AuthenticationPrincipal Jwt jwt) {
        return TokenService.userOf(jwt);
    }

    public record LoginRequest(@NotBlank String username, @NotBlank String password) {
    }

    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record LoginResponse(String accessToken, String tokenType, long expiresIn, AuthUser user) {
    }
}
