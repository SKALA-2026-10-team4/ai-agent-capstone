// 실행 증거 패널 order-agent/frontend/src/components/ExecutionInspector.vue 를 실제 order-agent backend 와 함께 검사하는
// Playwright e2e 다. 실제 Skill 로드 trace 와 snapshot 파일 표시, 준비된 결함본이 도구 실행 전에 실패하고 수정본이 성공하는
// 흐름, 새로고침 뒤 이력 조회를 보호한다.
// 실제 데모 DB 에 주문을 넣으므로 이 spec 만 실행해도 캠핑 의자 재고 2개를 쓴다(order-agent/README.md).
// order-agent/compose.yml 로 앱을 띄운 뒤 order-agent/frontend/playwright.config.js 설정으로 실행한다.
import { test, expect } from '@playwright/test'

// 화면이 상품 목록을 다 읽어 입력이 열릴 때까지 기다린 뒤, 첫 상품을 캠핑 의자로 바꾼다.
test.beforeEach(async ({ page }) => {
  await page.goto('/')
  await expect(page.getByLabel('상품 1', { exact: true })).toBeEnabled()
  await page.getByLabel('상품 1', { exact: true }).selectOption('CAMP-CHAIR-FOLD')
})

test('actual Skill loads and immutable related files are visible', async ({ page }) => {
  await page.getByRole('button', { name: '주문 실행', exact: true }).click()
  await expect(page.locator('.pill.shipped')).toBeVisible()
  await expect(page.getByText('order-flow → order-flow', { exact: true })).toBeVisible()
  await expect(page.getByText('고정 응답 · 실제 모델 호출 없음', { exact: false })).toBeVisible()
  await expect(page.locator('.trace-events details').filter({ hasText: '실제 파일 로드' })).toHaveCount(6)
  await page.getByRole('button', { name: 'Snapshot 보기', exact: true }).click()
  await page.getByLabel('고정된 파일 · 관련 자원 포함').selectOption('skills/inventory-check/resources/policy.md')
  await expect(page.locator('.snapshot-content')).toContainText('부분 예약하지 않습니다')
  await page.screenshot({ path: 'test-results/agent-evidence.png', fullPage: true })
})

test('prepared broken fixture fails before tools, prepared repaired fixture completes', async ({ page }) => {
  await page.locator('.skill-demo > summary').click()
  await expect(page.locator('.skill-demo')).toContainText('SkillOps가 생성하거나 자동 수정한 후보가 아닙니다')
  await page.getByRole('button', { name: '준비된 결함본 고정' }).click()
  await expect(page.locator('.skill-demo .notice')).toContainText('broken-contract')
  // 결함본은 계약 검사에서 실패해 주문 요청이 오류로 끝나므로, 화면은 결과 확인이 필요한 상태가 되고
  // "상태 다시 확인"으로 FAILED 주문을 읽는다.
  await page.getByRole('button', { name: '주문 실행', exact: true }).click()
  await expect(page.getByRole('button', { name: '상태 다시 확인', exact: true })).toBeVisible()
  await page.getByRole('button', { name: '상태 다시 확인', exact: true }).click()
  await expect(page.locator('.pill.failed')).toBeVisible()
  await expect(page.locator('.evidence-identifiers')).toContainText('CONTRACT_VIOLATION')
  await expect(page.locator('.trace-events details').filter({ hasText: '도구 요청' })).toHaveCount(0)
  await page.getByRole('button', { name: '새 주문 작성', exact: true }).click()
  await page.getByLabel('상품 1', { exact: true }).selectOption('CAMP-CHAIR-FOLD')
  await page.getByRole('button', { name: '준비된 수정본 고정' }).click()
  await expect(page.locator('.skill-demo .notice')).toContainText('repaired')
  await page.getByRole('button', { name: '주문 실행', exact: true }).click()
  await expect(page.locator('.pill.shipped')).toBeVisible()
  await expect(page.getByText('order-flow → order-flow', { exact: true })).toBeVisible()
})

// 주문 없이 화면을 열어도 최근 실행이 자동으로 열리는지, 새로고침 뒤에도 그대로인지 확인한다.
// 데모 DB 에 실행 기록이 하나 이상 있어야 하며, 전체 실행에서는 앞선 테스트가 그 기록을 남긴다.
test('history remains inspectable after page reload', async ({ page }) => {
  await expect(page.locator('#execution-select option')).not.toHaveCount(1)
  await expect(page.locator('.evidence-identifiers')).toContainText('실행 ID')
  await page.reload()
  await expect(page.locator('.evidence-identifiers')).toContainText('실행 ID')
  await page.getByRole('button', { name: 'Snapshot 보기', exact: true }).click()
  await expect(page.locator('#snapshot-file')).toBeVisible()
})
