import { computed, ref } from 'vue'
import { defineStore } from 'pinia'
import { login as loginApi } from '@/api/auth'

// 운영 화면 로그인 상태. access token 만 쓰고(refresh 없음) 탭을 닫으면 사라지도록 sessionStorage 에 둔다.
const STORAGE_KEY = 'skillops.auth'

function restore() {
  try {
    const saved = JSON.parse(sessionStorage.getItem(STORAGE_KEY))
    return saved && saved.expiresAt > Date.now() ? saved : null
  } catch {
    return null
  }
}

function persist(value) {
  try {
    if (value) sessionStorage.setItem(STORAGE_KEY, JSON.stringify(value))
    else sessionStorage.removeItem(STORAGE_KEY)
  } catch {
    // 저장소를 못 쓰면 새로고침 때 다시 로그인할 뿐이다.
  }
}

export const useAuthStore = defineStore('auth', () => {
  const session = ref(restore()) // { token, user, expiresAt }

  const user = computed(() => session.value?.user ?? null)
  const loggedIn = computed(() => !!session.value && session.value.expiresAt > Date.now())
  const hasRole = (role) => !!user.value?.roles.includes(role)

  function token() {
    if (!session.value) return null
    if (session.value.expiresAt <= Date.now()) {
      logout()
      return null
    }
    return session.value.token
  }

  async function login(username, password) {
    const res = await loginApi(username, password)
    session.value = {
      token: res.access_token,
      user: res.user,
      expiresAt: Date.now() + res.expires_in * 1000,
    }
    persist(session.value)
  }

  function logout() {
    session.value = null
    persist(null)
  }

  return { session, user, loggedIn, hasRole, token, login, logout }
})
