// frontend/src/router/nav.js 의 메뉴 켜짐 규칙과 구역 번호를 고정한다. 위쪽 막대
// (frontend/src/components/AppTopBar.vue)와 화면 머리(frontend/src/components/PageHeader.vue)가 이 규칙을 쓴다.
import { test } from 'node:test'
import assert from 'node:assert/strict'
import { NAV_GROUPS, isNavActive, sectionOf } from '../src/router/nav.js'

const item = (to) => NAV_GROUPS.flatMap((g) => g.items).find((it) => it.to === to)

test('isNavActive: 하위 주소도 켜고, 이름만 비슷한 주소는 켜지 않는다', () => {
  assert.equal(isNavActive(item('/proposals'), '/proposals'), true)
  assert.equal(isNavActive(item('/proposals'), '/proposals/p_mockw1'), true)
  assert.equal(isNavActive(item('/skills'), '/skills/payment'), true)
  assert.equal(isNavActive(item('/skills'), '/skillsx'), false)
  assert.equal(isNavActive(item('/orders'), '/executions/e0000000-0000-0000-0000-000000000001'), true)
  assert.equal(isNavActive(item('/diagnoses'), '/diagnoses?change_id=c_1'), true)
  assert.equal(isNavActive(item('/compare'), undefined), false)
})

test('sectionOf: 묶음 순서를 두 자리 번호로 쓴다', () => {
  assert.deepStrictEqual(sectionOf('/diagnoses/abc'), { no: '01', group: '개선안', label: '검증·진단 기록' })
  assert.deepStrictEqual(sectionOf('/proposals'), { no: '01', group: '개선안', label: '받은 개선안' })
  assert.deepStrictEqual(sectionOf('/compare'), { no: '02', group: 'Skill', label: '버전 비교' })
  assert.deepStrictEqual(sectionOf('/executions/x'), { no: '03', group: '실행', label: '주문 실행 · 기록' })
  assert.equal(sectionOf('/login'), null)
})
