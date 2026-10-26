// 운영 화면(SkillOps frontend)의 진입점이다.
// frontend/index.html 의 module script 가 이 파일을 불러온다.
// Vue 앱을 만들고 Pinia 와 router 를 설치한 뒤, frontend/src/api/client.js 의 connectAuth 로
// 로그인 토큰 공급자와 401 처리기를 연결하고 #app 에 mount 한다.
// 전역 스타일(색, 글꼴, 공용 class)을 담은 frontend/src/assets/base.css 는 여기서 한 번만 불러온다.
// mount 전에 frontend/src/utils/viewportZoom.js 의 installViewportZoom 으로 창 폭에 맞는 화면 배율을 정한다.
import { createApp } from 'vue'
import { createPinia } from 'pinia'

import App from './App.vue'
import router from './router'
import { isLoginPath, loginRedirect } from './router/paths'
import { connectAuth } from './api/client'
import { useAuthStore } from './stores/auth'
import { installViewportZoom } from './utils/viewportZoom'
import './assets/base.css'

const app = createApp(App)

app.use(createPinia())
app.use(router)

// client.js 는 순환 참조를 피하려고 auth store 를 직접 import 하지 않으므로 여기서 둘을 연결한다.
// useAuthStore 는 활성 Pinia 가 있어야 하므로 이 블록은 app.use(createPinia()) 뒤에 둔다.
// 토큰은 /service 요청에만 붙는다. 만료되거나 위조된 토큰으로 401 이 오면 로그아웃하고,
// 지금 주소를 next 로 담아 로그인 화면으로 보낸다. 이미 로그인 화면에 있으면 다시 보내지 않는다.
// 로그인 요청 자체의 401(비밀번호 틀림)은 토큰을 붙이지 않은 요청이므로
// client.js 가 이 처리기를 부르지 않는다.
const auth = useAuthStore()
connectAuth({
  token: () => auth.token(),
  onUnauthorized: () => {
    auth.logout()
    const next = router.currentRoute.value.fullPath
    if (!isLoginPath(next)) router.push(loginRedirect(next))
  },
})

installViewportZoom(window, document.documentElement)
app.mount('#app')
