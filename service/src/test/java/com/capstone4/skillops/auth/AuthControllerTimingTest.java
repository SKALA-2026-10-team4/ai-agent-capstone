package com.capstone4.skillops.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

import com.capstone4.skillops.auth.AuthController.LoginRequest;
import com.capstone4.skillops.auth.AuthController.LoginResponse;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * 리팩토링 전 로그인 동작을 고정하는 특성 테스트다. 대상은 service/src/main/java/com/capstone4/skillops/auth/AuthController.java 의
 * login 이다.
 * 없는 계정에도 DUMMY_HASH 와 한 번 비교하는지 본다. 이 비교가 있어야 응답 시간으로 계정 유무가 드러나지 않는다. 틀린 비밀번호는
 * 계정 해시와 한 번만 비교하는지, 같은 아이디가 둘이면 첫 계정으로 토큰을 발급하는지도 본다.
 * 실제 응답 시간은 재지 않는다. Spring 없이 컨트롤러를 직접 만들고, PasswordEncoder 를 spy 로 감싸 비교 횟수와 인자를 센다.
 */
class AuthControllerTimingTest {

    static final JsonMapper JSON = JsonMapper.builder().build();
    /** AuthController.DUMMY_HASH 를 복사한 값이다. 두 값이 달라지면 unknownAccountIsStillComparedWithTheDummyHash 가 실패한다. */
    static final String DUMMY_HASH = "$2a$10$MYFWOp34jBq3hErMezLSzuMBGK4MaW2dSjmHNG3eJFkY/qpdDVF5.";
    /** service/src/main/resources/application.yml 에 있는 operator 기본 비밀번호(operator1234)의 해시다. */
    static final String OPERATOR_HASH = "$2a$10$3vF955z/KEJ7Y2qSrqJWZu9hJ0ssv8ZTh/s/v.BzCymd0vlwgrmdW";
    static final AuthProperties.User OPERATOR = new AuthProperties.User("operator", "운영자", List.of("OPERATOR"),
            OPERATOR_HASH);

    // 비교 횟수를 세려고 실제 BCrypt 인코더를 spy 로 감싸고, 토큰 발급은 가짜로 바꾼다.
    PasswordEncoder passwords = spy(new BCryptPasswordEncoder());
    TokenService tokens = mock(TokenService.class);
    AuthController controller = new AuthController(new AuthProperties(null, null, List.of(OPERATOR)), passwords, tokens);

    /** 응답이 401 이고 본문이 INVALID_CREDENTIALS 의 code 와 message 인지 본다. */
    static void assertInvalidCredentials(ResponseEntity<?> response) {
        assertThat(response.getStatusCode().value()).isEqualTo(401);
        JsonNode body = JSON.valueToTree(response.getBody());
        assertThat(body).isEqualTo(JSON.createObjectNode()
                .put("code", "INVALID_CREDENTIALS").put("message", "아이디 또는 비밀번호가 올바르지 않습니다."));
    }

    @Test
    void unknownAccountIsStillComparedWithTheDummyHash() {
        ResponseEntity<?> response = controller.login(new LoginRequest("ghost", "operator1234"));

        then(passwords).should(times(1)).matches(anyString(), anyString());
        then(passwords).should().matches("operator1234", DUMMY_HASH);
        then(tokens).shouldHaveNoInteractions();
        assertInvalidCredentials(response);
    }

    @Test
    void wrongPasswordIsComparedOnceWithTheAccountHash() {
        ResponseEntity<?> response = controller.login(new LoginRequest("operator", "nope"));

        then(passwords).should(times(1)).matches(anyString(), anyString());
        then(passwords).should().matches("nope", OPERATOR_HASH);
        then(tokens).shouldHaveNoInteractions();
        assertInvalidCredentials(response);
    }

    @Test
    void rightPasswordIssuesATokenForTheFirstMatchingAccount() {
        AuthProperties.User shadow = new AuthProperties.User("operator", "그림자", List.of("OWNER"), DUMMY_HASH);
        AuthController twoAccounts = new AuthController(new AuthProperties(null, null, List.of(OPERATOR, shadow)),
                passwords, tokens);
        given(tokens.issue(OPERATOR)).willReturn(new TokenService.Issued("token-1", 7200));

        ResponseEntity<?> response = twoAccounts.login(new LoginRequest("operator", "operator1234"));

        then(passwords).should(times(1)).matches(eq("operator1234"), anyString());
        then(tokens).should().issue(OPERATOR);
        assertThat(response.getStatusCode().value()).isEqualTo(200);
        assertThat(response.getBody()).isEqualTo(new LoginResponse("token-1", "Bearer", 7200,
                new AuthUser("operator", "운영자", List.of("OPERATOR"))));
    }
}
