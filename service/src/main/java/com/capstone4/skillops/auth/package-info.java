/**
 * 운영 화면(frontend/)의 로그인과 요청 인증을 맡는 패키지다. 담당은 A 다.
 * AuthController 가 설정에 있는 데모 계정의 비밀번호를 확인하면 TokenService 가 HS256 JWT 를 서명해 준다. 그 뒤로는 요청마다
 * SecurityConfig 가 Bearer 토큰을 검증하고, roles claim 의 역할(OWNER, OPERATOR)로 경로별 권한을 확인한다.
 * /mcp 와 /api/health 는 인증 대상이 아니다. SkillOps Agent 와 service 사이의 MCP 연결은 로그인을 붙이기 전과 똑같이 둔다.
 * 화면 쪽 연결부는 frontend/src/api/auth.js 와 frontend/src/stores/auth.js 다. frontend/src/api/client.js 가 /service 로 시작하는
 * 요청에만 토큰을 붙이고, frontend/vite.config.js 의 프록시가 /service 접두어를 떼어 이 서버로 넘긴다.
 */
package com.capstone4.skillops.auth;
