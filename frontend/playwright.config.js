// Playwright e2e 설정이다. frontend/package.json 의 test:e2e(playwright test)가 읽고
// frontend/tests 의 테스트를 돌린다. webServer 항목이 없으므로 개발 서버(npm run dev,
// 5173 포트)와 order-agent, service, skillops-agent 를 먼저 띄워 두어야 한다.
// 단위 테스트(frontend/unit 의 *.test.js)는 testDir 밖에 있어 Playwright 가 집어 가지 않는다.
// frontend/tests/diagnosis.spec.js 는 실제 진단 결과를 기다리므로 제한 시간을 60초로 길게 둔다.
// 테스트가 실서버에 snapshot 을 만들고 진단을 시작하므로 worker 를 하나로 두어 차례로 돌린다.
// 실패한 테스트만 스크린샷과 trace 를 남긴다.
import { defineConfig } from '@playwright/test'

export default defineConfig({
  testDir: './tests',
  timeout: 60000,
  workers: 1,
  use: { baseURL: 'http://127.0.0.1:5173', screenshot: 'only-on-failure', trace: 'retain-on-failure' },
})
