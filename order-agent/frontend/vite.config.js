// order-agent 주문 데모 화면의 Vite 설정이다.
// 화면 코드는 상대 경로 /api 로만 호출하고, 개발 서버가 그 요청을 order-agent backend 로 넘긴다.
// 그래서 브라우저 번들에는 backend 주소가 들어가지 않는다.
// order-agent/frontend/package.json 의 dev, build 스크립트와 order-agent/frontend/Dockerfile 의 npm run dev 가 이 설정을 읽는다.
import { defineConfig } from "vite";
import vue from "@vitejs/plugin-vue";

export default defineConfig({
  plugins: [vue()],
  // 컨테이너 밖에서도 접속하도록 0.0.0.0:5173 에 뜨고, 포트가 이미 쓰이면 다른 포트로 옮기지 않고 실패한다.
  // order-agent/compose.yml 이 이 포트를 호스트 127.0.0.1:5174 로 열고 healthcheck 로 5173 을 조회한다.
  // 포트를 바꾸려면 이 파일, order-agent/frontend/package.json 의 dev 스크립트, order-agent/frontend/Dockerfile 의 EXPOSE,
  // order-agent/compose.yml 을 함께 고친다.
  server: {
    host: "0.0.0.0",
    port: 5173,
    strictPort: true,
    // /api 요청을 받을 backend 주소다. compose 는 API_PROXY_TARGET 으로 컨테이너 안 주소(http://backend:8080)를 준다.
    // 값이 없으면 http://localhost:8081 로 보낸다. order-agent/compose.yml 이 backend 를 호스트 127.0.0.1:8081 에 열기 때문에,
    // 개발 서버를 compose 밖에서 띄워도 compose 의 backend 로 이어진다.
    proxy: { "/api": process.env.API_PROXY_TARGET || "http://localhost:8081" },
  },
});
