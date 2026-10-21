/**
 * A · 운영 화면 로그인: 데모 계정 확인 → JWT(HS256) 발급, 요청마다 Bearer 토큰 검증과 역할(OWNER·OPERATOR) 확인.
 * /mcp 와 /api/health 는 인증 대상이 아니다(Agent↔service MCP 연결은 그대로).
 */
package com.capstone4.skillops.auth;
