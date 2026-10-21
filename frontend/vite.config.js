import process from 'node:process'
import { fileURLToPath, URL } from 'node:url'

import { defineConfig } from 'vite'
import vue from '@vitejs/plugin-vue'
import vueDevTools from 'vite-plugin-vue-devtools'

// https://vite.dev/config/
export default defineConfig({
  plugins: [
    vue(),
    vueDevTools(),
  ],
  resolve: {
    alias: {
      '@': fileURLToPath(new URL('./src', import.meta.url)),
    },
  },
  server: {
    port: 5173,
    // order-agent 와 service 는 CORS 설정이 없으므로 같은 origin 프록시로만 부른다.
    proxy: {
      // /api/* → order-agent backend (주문 · 실행 trace · snapshot). 인증 없음(로컬 데모)
      '/api': process.env.ORDER_AGENT_URL || 'http://localhost:8081',
      // /service/* → SkillOps service (로그인 · 승인). prefix 를 떼고 넘긴다.
      // 진단 Agent 는 서비스 토큰이 필요하므로 브라우저가 직접 부르지 않고 service 가 중계한다.
      '/service': {
        target: process.env.SKILLOPS_SERVICE_URL || 'http://localhost:8080',
        rewrite: (path) => path.replace(/^\/service/, ''),
      },
    },
  },
})
