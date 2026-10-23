// order-agent 주문 데모 화면의 Playwright e2e 설정이다.
// tests/e2e 의 spec 은 mock 이 아니라 실제 order-agent backend 와 데모 DB 에 주문을 넣는다.
// 그래서 order-agent/compose.yml 로 앱을 먼저 띄운 뒤 order-agent/frontend/package.json 의 test:e2e 스크립트로 실행한다.
// 전체 실행이 데모 재고를 쓰므로 반복 실행 전에 남은 재고를 확인한다. 소모량은 order-agent/README.md 에 적혀 있다.
import { defineConfig } from "@playwright/test";
export default defineConfig({
  testDir: "./tests/e2e",
  // spec 들이 같은 데모 DB 와 재고를 함께 쓰므로 worker 하나가 순서대로 실행한다.
  workers: 1,
  // 재시도는 실패를 가리고 재고를 더 쓰므로 하지 않는다.
  retries: 0,
  // 테스트 하나가 주문 실행부터 결과 확인까지 끝내야 하는 제한 시간(ms)이다.
  timeout: 25000,
  use: {
    // 기본 주소는 order-agent/compose.yml 이 호스트에 연 화면(127.0.0.1:5174)이다.
    // 로컬 Vite 개발 서버처럼 다른 화면을 검사하려면 ORDER_DEMO_UI_URL 로 주소를 바꾼다.
    baseURL: process.env.ORDER_DEMO_UI_URL || "http://127.0.0.1:5174",
    // 실패한 테스트만 화면을 남긴다. spec 이 직접 찍는 test-results 의 화면과는 따로 남는다.
    screenshot: "only-on-failure",
  },
});
