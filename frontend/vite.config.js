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
    // /api/* 는 service(8080)로 넘긴다. 프론트 코드는 상대 경로 '/api/...' 만 쓴다.
    proxy: {
      '/api': 'http://localhost:8080',
    },
  },
})
