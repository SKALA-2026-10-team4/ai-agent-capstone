package com.capstone4.skillops.common;

import java.util.Objects;

import org.springframework.http.ResponseEntity;

/**
 * service REST API 의 오류 본문 {code, message} 다. code 는 오류 종류를 가리키는 고정 이름이고, message 는 화면에 그대로 보여 주는
 * 한국어 문구다. JSON 은 언제나 code, message 순서로 나가며
 * service/src/test/java/com/capstone4/skillops/api/ApiErrorBodyTest.java 가 응답 문자열로 이 순서를 확인한다.
 * 화면에서는 frontend/src/api/client.js 의 httpError 가 이 두 칸을 frontend/src/api/error.js 의 ApiError 객체로 옮긴다.
 * frontend/src/views/ProposalView.vue 와 frontend/src/views/DiagnosisView.vue 는 message 를 그대로 보여 주고,
 * frontend/src/views/LoginView.vue 만 401 에 자체 문구를 쓴다. 지금 code 값으로 분기하는 화면 코드는 없다.
 * order-agent 의 ErrorResponse(order-agent/backend/src/main/java/com/capstone4/orderagent/dto/common/ErrorResponse.java)와
 * 앞의 두 칸이 같아서, frontend/src/api/client.js 의 httpError 하나가 두 서버의 오류를 함께 읽는다.
 * 이 레코드로 응답을 만드는 곳은 service/src/main/java/com/capstone4/skillops/auth/AuthController.java 의 로그인 실패,
 * service/src/main/java/com/capstone4/skillops/api/ProposalApiExceptionHandler.java,
 * service/src/main/java/com/capstone4/skillops/diagnosis/DiagnosisController.java 의 Agent 오류 처리기다.
 * Spring Security 필터 안에서 나는 401 과 403 은 service/src/main/java/com/capstone4/skillops/auth/SecurityConfig.java 의
 * writeError 가 이 레코드를 쓰지 않고 같은 모양의 JSON 을 서블릿 응답에 직접 쓴다.
 */
public record ApiError(String code, String message) {

    /** code 와 message 에는 null 을 받지 않는다. null 이 들어오면 NullPointerException 을 던지고, 빈 문자열은 검사하지 않는다. */
    public ApiError {
        Objects.requireNonNull(code);
        Objects.requireNonNull(message);
    }

    /** status 상태 코드와 이 오류 본문을 담은 응답을 만든다. 컨트롤러와 예외 처리기가 오류 응답을 만들 때 이 메서드를 쓴다. */
    public static ResponseEntity<ApiError> response(int status, String code, String message) {
        return ResponseEntity.status(status).body(new ApiError(code, message));
    }
}
