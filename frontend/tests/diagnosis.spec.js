// 실서버 e2e 테스트다. Skill 수정본 저장부터 자동 검증 결과 확인까지 운영 화면의 핵심 경로를
// 보호한다. 로그인, 정상본 고정, inventory-check 편집과 전체 수정본 저장, 진단 화면 이동과 상태
// 제목, 새로고침 뒤 같은 주소 유지, 전체 기록 목록을 차례로 확인한다. 고정 버튼은
// frontend/src/components/SnapshotPicker.vue 에, 저장 버튼은
// frontend/src/views/SkillDraftView.vue 에 있다. 저장 요청은 frontend/src/api/orderAgent.js 의
// saveSkillEdits 가 POST /api/snapshots/edits 로 보내며 201 과 validation_status QUEUED 를 받아야
// 한다. 상태 제목은 frontend/src/views/DiagnosisView.vue 가
// frontend/src/utils/diagnosisView.js 의 DIAGNOSIS_LABEL 로 보여 준다. 화면은 Skill 세트 id 를 글로 찍지 않고
// frontend/src/components/SnapshotLabel.vue 의 이름('수정본 · 10월 26일 06:41')으로 보여 주므로, 저장한 세트는
// 그 이름표의 data-snapshot-id 로 찾는다.
//
// order-agent, service, skillops-agent 와 개발 서버(npm run dev)가 모두 떠 있어야 한다. mock
// 모드에서는 저장 버튼이 꺼져 있어 돌릴 수 없다. 화면에 페이지 오류가 하나라도 나면 실패한다.
import { test, expect } from '@playwright/test'

test('save actual edit, auto-validate, and retain progress after reload', async ({ page }) => {
  const errors = []
  page.on('pageerror', (error) => errors.push(error.message))
  // 로그인하지 않은 채 Skill 편집 화면을 열면 로그인 화면으로 갔다가
  // 로그인 뒤 next 주소로 돌아온다.
  await page.goto('/skills/inventory-check')
  await page.getByLabel('아이디').fill('owner')
  await page.getByLabel('비밀번호').fill('owner1234')
  await page.getByRole('button', { name: '로그인', exact: true }).click()
  await expect(page).toHaveURL(/\/skills\/inventory-check/)
  // 정상본을 새 snapshot 으로 고정하고, 버튼이 다시 켜질 때까지 기다려 고정이 끝난 것을 확인한다.
  const pin = page.getByRole('button', { name: '정상본 고정', exact: true })
  await pin.click()
  await expect(pin).toBeEnabled()
  const editor = page.locator('textarea')
  await expect(editor).toBeVisible()
  await expect(editor).toHaveValue(/description: 상품 전체 재고를 확인하고 예약한다/)
  // description 에 지금 시각을 넣어 실행할 때마다 내용이 다른 수정본이 생기게 한다.
  const original = await editor.inputValue()
  await editor.fill(original.replace('description: 상품 전체 재고를 확인하고 예약한다', `description: 전체 상품 재고를 확인하고 예약한다 (${Date.now()})`))
  // 저장 요청의 응답을 기다려 201 과 검증 대기 상태(QUEUED)를 확인한다.
  const response = page.waitForResponse((r) => r.url().endsWith('/api/snapshots/edits') && r.request().method() === 'POST')
  await page.getByRole('button', { name: '전체 수정본 저장', exact: true }).click()
  const savedResponse = await response
  expect(savedResponse.status()).toBe(201)
  const saved = await savedResponse.json()
  expect(saved.validation_status).toBe('QUEUED')
  // 저장 뒤 링크로 진단 화면에 가면 change_id 전달을 기다렸다가 진단
  // thread 주소로 바뀐다. 진단이 끝나 상태 제목이 나올 때까지 30초까지 기다린다.
  await page.getByRole('link', { name: '자동 검증·진단 진행 확인' }).click()
  await expect(page).toHaveURL(/\/diagnoses\//, { timeout: 30000 })
  await expect(page.getByRole('heading', { name: /기본 회귀 검증 통과|기준 주문 검증 불가/ })).toBeVisible({ timeout: 30000 })
  const savedSet = page.locator(`[data-snapshot-id="${saved.snapshot_id}"]`).first()
  await expect(savedSet).toBeVisible()
  await expect(page.getByRole('heading', { name: '진행 로그' })).toBeVisible()
  // 새로고침해도 같은 진단 주소와 상태 제목이 남는지 본다.
  const detailUrl = page.url()
  await page.reload()
  await expect(page.getByRole('heading', { name: /기본 회귀 검증 통과|기준 주문 검증 불가/ })).toBeVisible()
  expect(page.url()).toBe(detailUrl)
  // 전체 기록 목록에 방금 저장한 snapshot 이 보이는지 본다.
  await page.getByRole('link', { name: '전체 기록', exact: true }).click()
  await expect(page.locator(`[data-snapshot-id="${saved.snapshot_id}"]`).first()).toBeVisible()
  expect(errors).toEqual([])
})
