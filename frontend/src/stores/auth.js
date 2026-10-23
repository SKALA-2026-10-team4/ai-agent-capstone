// 운영 화면의 로그인 상태를 담는 Pinia 스토어다. access token 만 쓰고 refresh token 은 없다. 탭을
// 닫으면 로그인이 사라지도록 sessionStorage 에 둔다. 토큰은 service 의
// service/src/main/java/com/capstone4/skillops/auth/TokenService.java 가 발급한다. 연결부는
// frontend/src/main.js(토큰 공급과 401 로그아웃), frontend/src/router/index.js(beforeEach 의
// loggedIn), frontend/src/views/LoginView.vue(login), frontend/src/components/AppSidebar.vue(user,
// loggedIn, logout), frontend/src/views/ProposalView.vue(hasRole)다.
import { computed, ref } from 'vue'
import { defineStore } from 'pinia'
import { login as loginApi } from '@/api/auth'

// 세션을 넣어 두는 sessionStorage 키다.
const STORAGE_KEY = 'skillops.auth'

// 새로고침 뒤 sessionStorage 에서 세션을 되살린다. 만료됐거나 읽을 수 없으면 null 이다.
function restore() {
  try {
    const saved = JSON.parse(sessionStorage.getItem(STORAGE_KEY))
    return saved && saved.expiresAt > Date.now() ? saved : null
  } catch {
    return null
  }
}

// 세션을 sessionStorage 에 쓰고, value 가 null 이면 지운다.
function persist(value) {
  try {
    if (value) sessionStorage.setItem(STORAGE_KEY, JSON.stringify(value))
    else sessionStorage.removeItem(STORAGE_KEY)
  } catch {
    // 저장소를 못 쓰면 새로고침 때 다시 로그인할 뿐이다.
  }
}

// 로그인 세션 스토어다. session 은 { token, user, expiresAt } 이고, user 는
// service 가 준 { username, display_name, roles } 다. expiresAt 은 밀리초 시각이다.
export const useAuthStore = defineStore('auth', () => {
  const session = ref(restore())

  const user = computed(() => session.value?.user ?? null)
  // 세션이 있고 만료 전이면 참이다. computed 는 session 이 바뀔 때만 다시 계산하므로 만료 시각이
  // 지나도 곧바로 거짓이 되지는 않는다. 만료된 세션은 다음 /service 요청에서 token() 이 지운다.
  const loggedIn = computed(() => !!session.value && session.value.expiresAt > Date.now())
  // 사용자가 role 을 가졌는지 본다. frontend/src/views/ProposalView.vue 가
  // OPERATOR 로 승인과 거절을 연다.
  const hasRole = (role) => !!user.value?.roles.includes(role)

  /**
   * 요청에 붙일 토큰을 돌려준다. 세션이 없으면 null 이고, 만료됐으면
   * 로그아웃한 뒤 null 을 돌려준다. frontend/src/main.js 가
   * frontend/src/api/client.js 의 connectAuth 에 토큰 공급자로 넘긴다.
   */
  function token() {
    if (!session.value) return null
    if (session.value.expiresAt <= Date.now()) {
      logout()
      return null
    }
    return session.value.token
  }

  /**
   * frontend/src/api/auth.js 의 login 으로 로그인하고 토큰, 사용자, 만료
   * 시각(지금부터 expires_in 초 뒤)을 세션에 저장한다. 실패하면 ApiError 를
   * 그대로 던지고 frontend/src/views/LoginView.vue 가 문구를 보여 준다.
   */
  async function login(username, password) {
    const res = await loginApi(username, password)
    session.value = {
      token: res.access_token,
      user: res.user,
      expiresAt: Date.now() + res.expires_in * 1000,
    }
    persist(session.value)
  }

  // 세션을 지운다. frontend/src/main.js 의 401 처리기, token() 의 만료
  // 처리, frontend/src/components/AppSidebar.vue 의 로그아웃 버튼이 부른다.
  function logout() {
    session.value = null
    persist(null)
  }

  return { session, user, loggedIn, hasRole, token, login, logout }
})
