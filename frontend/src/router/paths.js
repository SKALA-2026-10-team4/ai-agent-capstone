// 첫 화면과 로그인 화면의 경로 규칙이다. 첫 화면이나 로그인 흐름을 바꿀 때 이 파일만 고친다.
// frontend/src/router/index.js(라우트 표와 로그인 가드), frontend/src/main.js(401 처리),
// frontend/src/views/LoginView.vue(로그인 뒤 이동),
// frontend/src/components/AppSidebar.vue(로고 링크와 로그아웃)가 쓴다.
// vue 와 router 를 import 하지 않으므로 frontend/unit/paths.test.js 가 Node 에서 바로 불러
// 이 규칙을 고정한다.

// 로그인 뒤 기본으로 가는 첫 화면(받은 제안서 목록)이다. '/' 주소와 없는 주소도 이 화면으로 보낸다.
export const HOME_PATH = '/proposals'
// 로그인 화면 주소다.
export const LOGIN_PATH = '/login'

/**
 * 로그인 화면으로 가는 router 위치 객체를 만든다.
 * next 에 지금 주소를 담아 두면 로그인 뒤 그 주소로 돌아온다.
 * frontend/src/router/index.js 의 로그인 가드와 frontend/src/main.js 의 401 처리기가 쓴다.
 */
export const loginRedirect = (next) => ({ path: LOGIN_PATH, query: { next } })

/**
 * 주소가 로그인 화면인지 본다. startsWith 로 보므로 '/login?next=…' 도 로그인 화면으로 본다.
 * frontend/src/main.js 의 401 처리기가 이미 로그인 화면에 있을 때 다시 보내지 않으려고 쓴다.
 */
export const isLoginPath = (path) => path.startsWith(LOGIN_PATH)

/**
 * 로그인 뒤 갈 주소를 고른다. next 가 '/' 로 시작하는 글자일 때만 그대로 쓰고,
 * 아니면 첫 화면으로 보낸다. 'https://…' 처럼 앱 밖을 가리키는 값이나
 * 글자가 아닌 쿼리 값(배열 등)을 따르지 않으려는 규칙이다.
 * frontend/src/views/LoginView.vue 의 submit 이 쓴다.
 */
export const safeNext = (next) => (typeof next === 'string' && next.startsWith('/') ? next : HOME_PATH)
