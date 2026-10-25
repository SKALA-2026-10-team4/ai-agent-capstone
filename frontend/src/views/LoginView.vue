<script setup>
// 운영 화면의 로그인 화면이다. frontend/src/router/index.js 가
// /login(frontend/src/router/paths.js 의 LOGIN_PATH)에 연결하고 meta.public 을 붙이므로,
// 로그인 없이 열리며 메뉴 없이 그려진다.
// frontend/src/router/index.js 의 로그인 가드, frontend/src/main.js 의 401 처리기,
// frontend/src/components/AppTopBar.vue 의 logout 이 이 화면으로 보낸다.
// 로그인은 frontend/src/stores/auth.js 의 login 이 frontend/src/api/auth.js 로
// POST /service/api/auth/login 을 보내고,
// service/src/main/java/com/capstone4/skillops/auth/AuthController.java 의 login() 이 받는다.
// 받은 토큰과 사용자는 auth store 가 sessionStorage 에 둔다.
// 데모 계정은 owner(업무 담당자)와 operator(운영자)이며,
// service/src/main/resources/application.yml 의 skillops.auth.users 에 있다.
// mock 모드에서는 frontend/src/mocks/index.js 가 같은 계정으로 로그인을 흉내 낸다.
// frontend/tests/diagnosis.spec.js 가 '아이디', '비밀번호' 칸과 '로그인' 버튼 이름으로 로그인한다.
// 화면 전체를 남색 첫 화면(hero)으로 쓰고, 가는 선의 큰 원 안에 한 문장을 크게 둔 뒤 그 아래에 로그인 카드를
// 겹쳐 놓는다. 원 뒤의 궤도 선은 frontend/src/components/OrbitArt.vue 가 그린다.
import { ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { safeNext } from '@/router/paths'
import { useAuthStore } from '@/stores/auth'
import OrbitArt from '@/components/OrbitArt.vue'

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
  <div class="login-page">
    <header class="top">
      <span class="brand">
        <svg class="glyph" viewBox="0 0 24 24" aria-hidden="true">
          <circle cx="12" cy="12" r="9.5" />
          <ellipse cx="12" cy="12" rx="4" ry="9.5" />
          <circle class="dot" cx="20.2" cy="7.2" r="2" />
        </svg>
        skillops
      </span>
      <span class="tag">Skill 개선안 운영 콘솔</span>
    </header>

    <section class="hero" aria-labelledby="hero-title">
      <div class="ring">
        <OrbitArt class="orbit" />
        <p class="kicker">Skill Operations</p>
        <h1 id="hero-title" class="statement">실행으로 증명한<br />수정안만 운영으로</h1>
      </div>
    </section>

    <form class="card login" @submit.prevent="submit">
      <div class="form-head">
        <h2>로그인</h2>
        <p class="muted small">수정본을 실제 주문 흐름에서 먼저 돌려 보고, 통과한 안만 검토해요.</p>
      </div>
      <label>
        아이디
        <input v-model.trim="username" autocomplete="username" required autofocus />
      </label>
      <label>
        비밀번호
        <input v-model="password" type="password" autocomplete="current-password" required />
      </label>
      <p v-if="error" class="error-text" role="alert">{{ error }}</p>
      <button class="primary lg" :disabled="busy">{{ busy ? '확인 중…' : '로그인' }}</button>
      <p class="muted hint">데모 계정 <code>owner</code> 담당자 · <code>operator</code> 운영자(승인 가능)</p>
    </form>
  </div>
</template>

<style scoped>
.login-page {
  position: relative;
  display: flex;
  flex-direction: column;
  align-items: center;
  min-height: 100vh;
  padding: 0 16px 48px;
  overflow: hidden;
  background:
    radial-gradient(800px 520px at 50% 34%, rgb(250 255 220 / 5%), transparent 70%),
    var(--bg);
}

.top {
  display: flex;
  align-items: center;
  justify-content: space-between;
  width: 100%;
  max-width: 1320px;
  height: 64px;
  padding: 0 16px;
}

.brand {
  display: inline-flex;
  align-items: center;
  gap: 9px;
  font-family: var(--display);
  font-size: 19px;
  font-weight: 800;
  letter-spacing: -0.04em;
}

.glyph {
  width: 22px;
  height: 22px;
  fill: none;
  stroke: rgb(255 255 255 / 55%);
  stroke-width: 1.2;
}

.glyph .dot {
  fill: var(--accent);
  stroke: var(--bg);
  stroke-width: 1.5;
}

.tag {
  font-size: var(--fs-xs);
  color: var(--muted);
}

.hero {
  display: grid;
  place-items: center;
  margin-top: 8px;
}

/* 큰 원: 가는 선과 아주 옅은 안쪽 빛, 그 안에 문장 하나 */
.ring {
  --size: min(500px, 56vh, 88vw);
  position: relative;
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  width: var(--size);
  height: var(--size);
  border: 1px solid rgb(255 255 255 / 12%);
  border-radius: 50%;
  background: radial-gradient(circle at 50% 40%, rgb(255 255 255 / 3%), transparent 62%);
  text-align: center;
}

.orbit {
  position: absolute;
  inset: -9%;
  width: 118%;
  height: 118%;
  z-index: 0;
  opacity: 0.75;
}

.kicker {
  position: relative;
  margin: 0 0 18px;
  font-family: var(--mono);
  font-size: 11px;
  letter-spacing: 0.24em;
  text-transform: uppercase;
  color: var(--muted);
}

.statement {
  position: relative;
  margin: 0;
  font-family: var(--display);
  font-size: calc(var(--size) * 0.098);
  font-weight: 800;
  line-height: 1.22;
  letter-spacing: -0.04em;
  color: var(--text);
}

.login {
  position: relative;
  z-index: 1;
  display: flex;
  flex-direction: column;
  gap: 12px;
  width: min(380px, 100%);
  margin-top: -52px;
  padding: 24px 28px 20px;
  border-color: var(--border-strong);
  background: rgb(23 37 50 / 88%);
  backdrop-filter: blur(12px);
  box-shadow: var(--shadow-lg);
}

.form-head h2 {
  margin: 0 0 4px;
  font-size: 20px;
  font-weight: 800;
  letter-spacing: -0.02em;
}

.form-head p {
  margin: 0;
}

label {
  display: flex;
  flex-direction: column;
  gap: 6px;
  font-size: var(--fs-sm);
  font-weight: 600;
  color: var(--text-2);
}

.error-text {
  margin: 0;
  font-size: var(--fs-sm);
}

.hint {
  margin: 2px 0 0;
  font-size: var(--fs-xs);
  line-height: 1.7;
}

@media (max-width: 760px) {
  .top {
    padding: 0;
  }

  .tag {
    display: none;
  }

  .hero {
    margin-top: 12px;
  }

  .login {
    margin-top: -36px;
    padding: 22px 20px 18px;
  }
}
</style>
