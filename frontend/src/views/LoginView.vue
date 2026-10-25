<script setup>
// 운영 화면의 로그인 화면이다. frontend/src/router/index.js 가
// /login(frontend/src/router/paths.js 의 LOGIN_PATH)에 연결하고 meta.public 을 붙이므로,
// 로그인 없이 열리며 메뉴 없이 그려진다.
// frontend/src/router/index.js 의 로그인 가드, frontend/src/main.js 의 401 처리기,
// frontend/src/components/AppSidebar.vue 의 logout 이 이 화면으로 보낸다.
// 로그인은 frontend/src/stores/auth.js 의 login 이 frontend/src/api/auth.js 로
// POST /service/api/auth/login 을 보내고,
// service/src/main/java/com/capstone4/skillops/auth/AuthController.java 의 login() 이 받는다.
// 받은 토큰과 사용자는 auth store 가 sessionStorage 에 둔다.
// 데모 계정은 owner(업무 담당자)와 operator(운영자)이며,
// service/src/main/resources/application.yml 의 skillops.auth.users 에 있다.
// mock 모드에서는 frontend/src/mocks/index.js 가 같은 계정으로 로그인을 흉내 낸다.
// frontend/tests/diagnosis.spec.js 가 '아이디', '비밀번호' 칸과 '로그인' 버튼 이름으로 로그인한다.
import { ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { Sparkles } from 'lucide-vue-next'
import { safeNext } from '@/router/paths'
import { useAuthStore } from '@/stores/auth'

const route = useRoute()
const router = useRouter()
const auth = useAuthStore()

const username = ref('')
const password = ref('')
// 요청 중이면 true 이고, 그동안 로그인 버튼을 끈다.
const busy = ref(false)
const error = ref(null)

/**
 * 로그인 폼을 보낸다. 폼의 submit 이벤트가 부른다.
 * 성공하면 next 쿼리의 주소로 돌아가되, frontend/src/router/paths.js 의
 * safeNext 가 '/' 로 시작하지 않는 값은 첫 화면으로 바꾼다.
 * replace 로 이동하므로 뒤로 가기를 눌러도 로그인 화면으로 돌아오지 않는다.
 * 401 은 아이디나 비밀번호가 틀린 경우라 고정 문구로 바꾸고,
 * 그 밖의 오류는 받은 문구를 그대로 보여 준다.
 */
async function submit() {
  busy.value = true
  error.value = null
  try {
    await auth.login(username.value, password.value)
    router.replace(safeNext(route.query.next))
  } catch (e) {
    error.value = e.status === 401 ? '아이디 또는 비밀번호가 올바르지 않습니다.' : e.message
  } finally {
    busy.value = false
  }
}
</script>

<template>
  <form class="card login" @submit.prevent="submit">
    <span class="logo"><Sparkles :size="22" /></span>
    <h1>SkillOps</h1>
    <p class="muted sub">Skill 개선안을 검토하고 다듬는 운영 화면</p>
    <label>
      아이디
      <input v-model.trim="username" autocomplete="username" required autofocus />
    </label>
    <label>
      비밀번호
      <input v-model="password" type="password" autocomplete="current-password" required />
    </label>
    <p v-if="error" class="error-text">{{ error }}</p>
    <button class="primary lg" :disabled="busy">{{ busy ? '확인 중…' : '로그인' }}</button>
    <p class="muted small">
      로컬 데모 계정: <code>owner</code>(업무 담당자) · <code>operator</code>(운영자, 승인 가능)
    </p>
  </form>
</template>

<style scoped>
.login {
  max-width: 380px;
  margin: 12vh auto 0;
  padding: 28px;
  box-shadow: var(--shadow-lg);
  display: flex;
  flex-direction: column;
  gap: 12px;
}

.login h1 {
  margin: 0;
}

.logo {
  display: grid;
  place-items: center;
  width: 44px;
  height: 44px;
  border-radius: 12px;
  background: var(--accent);
  color: var(--on-fill); /* 강조색 면 위 글자. 다크에서는 검정이 된다 */
}

.sub {
  margin: -6px 0 8px;
}

label {
  display: flex;
  flex-direction: column;
  gap: 4px;
  font-weight: 600;
}

input {
  font: inherit;
  padding: 8px 10px;
  border: 1px solid var(--border);
  border-radius: 6px;
  background: var(--bg);
  color: var(--text);
}

.small {
  font-size: 12px;
  margin: 0;
}
</style>
