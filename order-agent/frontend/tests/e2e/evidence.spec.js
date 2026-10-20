import { test, expect } from '@playwright/test'

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

test('history remains inspectable after page reload', async ({ page }) => {
  await expect(page.locator('#execution-select option')).not.toHaveCount(1)
  await expect(page.locator('.evidence-identifiers')).toContainText('실행 ID')
  await page.reload()
  await expect(page.locator('.evidence-identifiers')).toContainText('실행 ID')
  await page.getByRole('button', { name: 'Snapshot 보기', exact: true }).click()
  await expect(page.locator('#snapshot-file')).toBeVisible()
})
