// SkillOps service 의 로그인 API 를 부르는 모듈이다. 요청은 /service/api/auth 로 가고
// frontend/vite.config.js 의 프록시가 접두어 /service 를 떼어
// service/src/main/java/com/capstone4/skillops/auth/AuthController.java 로 넘긴다. mock
// 모드에서는 frontend/src/mocks/index.js 의 serviceRoutes 가 같은 경로를 흉내 낸다.
import { get, post } from './client'

// 아이디와 비밀번호로 로그인한다. POST /service/api/auth/login 은 AuthController 의
// login() 이 받고, 응답은 { access_token, token_type, expires_in, user: { username,
// display_name, roles } } 다. frontend/src/stores/auth.js 의 login() 이 부르고 토큰,
// 사용자, 만료 시각을 세션에 저장한다. 아이디나 비밀번호가 틀리면 401 INVALID_CREDENTIALS 인
// ApiError 를 던진다. mock 에서는 mockLogin 이 답한다.
export const login = (username, password) => post('/service/api/auth/login', { username, password })

// 지금 토큰의 사용자 { username, display_name, roles } 를 읽는다.
// GET /service/api/auth/me 는 AuthController 의 me() 가 받고, mock 에서는
// mockUser 가 답한다. 지금 이 함수를 부르는 화면 코드는 없다.
export const me = () => get('/service/api/auth/me')
