package com.capstone4.skillops.auth;

import java.util.List;

import tools.jackson.databind.PropertyNamingStrategies;
import tools.jackson.databind.annotation.JsonNaming;

/**
 * 로그인한 사용자를 화면과 승인 기록에 넘기는 레코드다. JSON 칸 이름은 username, display_name, roles 다.
 * 같은 패키지(service/src/main/java/com/capstone4/skillops/auth/)의 TokenService.userOf 가 검증된 토큰에서 만들고,
 * AuthController.loginResponse 는 설정의 계정 값으로 직접 만든다. AuthController 가 로그인 응답의 user 와 GET /api/auth/me 응답에
 * 이 값을 담는다.
 * service/src/main/java/com/capstone4/skillops/api/ApprovalController.java 는 이 값을
 * service/src/main/java/com/capstone4/skillops/approval/ApprovalService.java 로 넘겨 결정한 사람의 아이디와 이름을 기록하게 한다.
 * 화면에서는 frontend/src/stores/auth.js 가 세션의 user 로 보관하고 hasRole 이 roles 를 본다.
 * frontend/src/components/AppSidebar.vue 는 display_name 을 보여 준다.
 */
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public record AuthUser(String username, String displayName, List<String> roles) {
}
