// Vite 설정이다. frontend/package.json 의 dev, dev:mock, build, preview 스크립트가 읽는다.
// mock 모드 분기는 여기 없고 frontend/src/api/client.js 의 USE_MOCK 이 import.meta.env.MODE 와
// VITE_USE_MOCK 으로 판단한다. frontend/eslint.config.js 가 이 파일도 브라우저 전역으로 검사하므로 process 는
// node:process 에서 직접 가져온다.
import process from 'node:process'
import { fileURLToPath, URL } from 'node:url'

import { defineConfig } from 'vite'
import vue from '@vitejs/plugin-vue'
import vueDevTools from 'vite-plugin-vue-devtools'

// https://vite.dev/config/
export default defineConfig({
  // Vue SFC 컴파일과 개발용 Vue DevTools 를 켠다.
  plugins: [
    vue(),
    vueDevTools(),
  ],
  // '@' 로 시작하는 import 를 frontend/src 로 푼다. frontend/jsconfig.json 이 편집기에 같은
  // 별칭을 알린다. node --test 는 이 별칭을 모르므로 frontend/unit 이 불러오는 utils 는 상대
  // 경로로만 import 한다.
  resolve: {
    alias: {
      '@': fileURLToPath(new URL('./src', import.meta.url)),
    },
  },
  // 개발 서버 포트다. frontend/playwright.config.js 의 baseURL 이 이 포트를 전제로 한다.
  server: {
    port: 5173,
    // order-agent 와 service 는 CORS 설정이 없으므로 같은 origin 프록시로만 부른다.
    proxy: {
      // /api 로 시작하는 요청은 경로를 그대로 order-agent backend(주문, 실행 trace, snapshot)로
      // 넘긴다. order-agent 는 로컬 데모 앱이라 인증을 받지 않는다. 주소는 ORDER_AGENT_URL 로
      // 바꿀 수 있다.
      '/api': process.env.ORDER_AGENT_URL || 'http://localhost:8081',
      // /service 로 시작하는 요청은 접두어를 떼고 SkillOps service(로그인, 제안서, 승인,
      // 진단 조회)로 넘긴다. 진단 Agent 는 서비스 토큰이 필요하므로 브라우저가 직접 부르지 않고
      // service 가 중계한다. 주소는 SKILLOPS_SERVICE_URL 로 바꿀 수 있다.
      '/service': {
        target: process.env.SKILLOPS_SERVICE_URL || 'http://localhost:8080',
        rewrite: (path) => path.replace(/^\/service/, ''),
      },
    },
  },
})
