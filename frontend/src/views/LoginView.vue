<script setup>
import { ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { Sparkles } from 'lucide-vue-next'
import { useAuthStore } from '@/stores/auth'

// 운영 화면 로그인. 데모 계정: owner(업무 담당자) · operator(운영자). 계정은 service 설정에 있다.
const route = useRoute()
const router = useRouter()
const auth = useAuthStore()

const username = ref('')
const password = ref('')
const busy = ref(false)
const error = ref(null)

async function submit() {
  busy.value = true
  error.value = null
  try {
    await auth.login(username.value, password.value)
    const next = typeof route.query.next === 'string' && route.query.next.startsWith('/') ? route.query.next : '/proposals'
    router.replace(next)
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
  color: #fff;
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
