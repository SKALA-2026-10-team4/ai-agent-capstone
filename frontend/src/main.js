import { createApp } from 'vue'
import { createPinia } from 'pinia'

import App from './App.vue'
import router from './router'
import { connectAuth } from './api/client'
import { useAuthStore } from './stores/auth'
import './assets/base.css'

const app = createApp(App)

app.use(createPinia())
app.use(router)

// 토큰은 /service 요청에만 붙이고, 만료·위조로 401 이 오면 로그인 화면으로 보낸다.
const auth = useAuthStore()
connectAuth({
  token: () => auth.token(),
  onUnauthorized: () => {
    auth.logout()
    const next = router.currentRoute.value.fullPath
    if (!next.startsWith('/login')) router.push({ path: '/login', query: { next } })
  },
})

app.mount('#app')
