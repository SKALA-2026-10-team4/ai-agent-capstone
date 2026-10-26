// frontend/src/utils/factNames.js 의 제안서 사실 칸 이름 규칙을 보호한다. 실행 번호와 Skill 세트 id 를 어느 칸에서 모으는지,
// 장부 줄로 'g-05 · 실패' 같은 이름표를 만드는지, 장부에 없거나 재현 주문이면 어떻게 줄이는지, 세트 종류를 어떻게 붙이는지 본다.
import { test } from 'node:test'
import assert from 'node:assert/strict'
import { groupRuns, idsInDocument, isRunFact, runTag, setTag } from '../src/utils/factNames.js'

const RUN_A = `r_${'a'.repeat(32)}`
const RUN_B = `r_${'b'.repeat(32)}`
const EXEC = '4b1ed627-97f5-4e34-9c2c-c5dea259fa64'
const BEFORE = 'c71401b1-ea30-4f64-93ea-f520d2f72833'
const AFTER = '567ee992-9cb8-4096-824e-15695bd3c167'
const THREAD = '55b7953d-031a-4c82-870b-51fdae416f42'

const document = {
  sections: [
    {
      facts: [
        { label: '수정 전 Skill 세트', value: BEFORE },
        { label: '수정 후 Skill 세트', value: AFTER },
        { label: '원인 확인 실행', value: `${RUN_A}, ${RUN_B}` },
        { label: '실패한 실행', value: EXEC },
        { label: '진단 번호', value: THREAD },
      ],
      parts: [{ facts: [{ label: '근거 실행', value: `${RUN_B}, ${RUN_A}` }] }],
    },
  ],
}

test('실행 칸과 Skill 세트 칸의 번호만 중복 없이 모은다', () => {
  assert.deepEqual(idsInDocument(document), { runIds: [RUN_A, RUN_B, EXEC], snapshotIds: [BEFORE, AFTER] })
})

test('진단 번호처럼 UUID 여도 실행 칸이 아니면 실행으로 보지 않는다', () => {
  assert.equal(isRunFact({ label: '진단 번호' }), false)
  assert.equal(isRunFact({ label: '수정 전 Skill 세트' }), false)
  assert.equal(isRunFact({ label: '근거 실행' }), true)
})

test('장부 줄이 있으면 시험 주문과 결과로 이름을 붙이고 설명에 주문 이름과 번호를 둔다', () => {
  assert.deepEqual(runTag(RUN_A, { scenario_id: 'g-05', scenario_name: '미국 배송 주문', passed: false }), {
    text: 'g-05 · 실패',
    title: `미국 배송 주문\n${RUN_A}`,
    passed: false,
  })
  assert.equal(runTag(RUN_A, { scenario_id: 'g-01', scenario_name: null, passed: true }).text, 'g-01 · 통과')
})

test('실패 재현 주문은 원래 실행 번호를 설명에 둔다', () => {
  const tag = runTag(RUN_A, { scenario_id: `f-${EXEC}`, scenario_name: null, passed: false })
  assert.equal(tag.text, '실패 주문 재현 · 실패')
  assert.equal(tag.title, `원래 실행 4b1ed627\n${RUN_A}`)
})

test('장부에 없는 번호는 앞 8자로 줄인다', () => {
  assert.deepEqual(runTag(RUN_A), { text: '실행 aaaaaaaa', title: RUN_A, passed: null })
  assert.equal(runTag(EXEC).text, '실행 4b1ed627')
})

test('Skill 세트는 metadata 의 종류 이름과 id 앞 8자로 부른다', () => {
  const labels = { baseline: '정상본', 'user-edit': '내 수정본' }
  assert.equal(setTag(BEFORE, { fixture: 'baseline', source: 'demo-fixture' }, labels).text, '정상본 · c71401b1')
  assert.equal(setTag(AFTER, { source: 'user-edit' }, labels).text, '내 수정본 · 567ee992')
  assert.equal(setTag(AFTER, null, labels).text, 'Skill 세트 · 567ee992')
})

test('같은 이름표의 실행은 처음 순서대로 묶고 설명에 번호를 모두 담는다', () => {
  const failed = { text: 'g-05 · 실패', title: `미국 배송 주문\n${RUN_A}`, passed: false }
  const passed = { text: 'g-01 · 통과', title: `정상 주문\n${RUN_B}`, passed: true }
  const groups = groupRuns([
    { id: RUN_B, to: '/b', tag: passed },
    { id: RUN_A, to: '/a', tag: failed },
    { id: EXEC, to: '/e', tag: { ...passed, title: `정상 주문\n${EXEC}` } },
  ])
  assert.deepEqual(groups, [
    { key: RUN_B, text: 'g-01 · 통과', to: '/b', passed: true, count: 2, title: `정상 주문\n${RUN_B}\n${EXEC}` },
    { key: RUN_A, text: 'g-05 · 실패', to: '/a', passed: false, count: 1, title: `미국 배송 주문\n${RUN_A}` },
  ])
})
