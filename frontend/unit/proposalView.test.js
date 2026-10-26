// frontend/src/utils/proposalView.js 의 제안서 이름표와 판정 표시를 고정한다. 후보 종류(encode_rule 포함),
// 원인 종류(incomplete_edit), 의도 기대 결과의 출처, 후보 경고(is_revert, weakens_contract), service 가
// 다시 계산한 판정(server_overrides) 줄을 확인한다. mock 제안서 frontend/src/mocks/proposal-b01.json 이
// 이 칸들을 모두 가진 예시다.
import { test } from 'node:test'
import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import {
  CANDIDATE_KIND_LABEL,
  INTENT_SOURCE_LABEL,
  ROOT_CAUSE_KIND_LABEL,
  STATUS,
  awaitsDecision,
  candidateFlags,
  candidateKindLabel,
  intentSourceLabel,
  overrideRows,
  rootCauseKind,
  rootCauseKindLabel,
  verdictText,
} from '../src/utils/proposalView.js'

const b01 = JSON.parse(readFileSync(new URL('../src/mocks/proposal-b01.json', import.meta.url), 'utf8'))
const f1 = JSON.parse(readFileSync(new URL('../src/mocks/proposal-f1.json', import.meta.url), 'utf8'))
const w1 = JSON.parse(readFileSync(new URL('../src/mocks/proposal-w1.json', import.meta.url), 'utf8'))

test('기존 상태 이름표와 검토 대기 판정은 그대로다', () => {
  assert.deepStrictEqual(Object.keys(STATUS), ['proposed', 'no_valid_fix', 'approved', 'rejected'])
  assert.equal(awaitsDecision({ status: 'no_valid_fix' }), true)
  assert.equal(awaitsDecision({ status: 'approved' }), false)
})

test('후보 종류: service 본문과 같은 이름, encode_rule 은 실행 규칙으로 옮기기', () => {
  assert.deepStrictEqual(CANDIDATE_KIND_LABEL, {
    same_skill: '바뀐 Skill만 고치기',
    linked_skills: '주고받는 값까지 함께 고치기',
    run_order: '실행 순서 다시 짜기',
    encode_rule: '실행 규칙으로 옮기기',
  })
  assert.equal(candidateKindLabel('encode_rule'), '실행 규칙으로 옮기기')
  assert.equal(candidateKindLabel('something_new'), 'something_new')
  assert.equal(candidateKindLabel(undefined), null)
  // mock 제안서의 후보 카드 제목이 같은 이름을 쓴다.
  const parts = b01.document.sections.find((s) => s.key === 'candidates').parts
  for (const c of b01.proposal.candidates) {
    const part = parts.find((p) => p.candidate_no === c.candidate_no)
    assert.ok(part.heading.endsWith(candidateKindLabel(c.kind)), part.heading)
  }
})

test('원인 종류: 칸이 없으면 change, incomplete_edit 는 의도를 실행하지 못하는 수정', () => {
  assert.deepStrictEqual(Object.keys(ROOT_CAUSE_KIND_LABEL), ['change', 'incomplete_edit'])
  assert.equal(rootCauseKind(null), 'change')
  assert.equal(rootCauseKind(f1.proposal.root_cause), 'change')
  assert.equal(rootCauseKind(b01.proposal.root_cause), 'incomplete_edit')
  assert.equal(rootCauseKindLabel(b01.proposal.root_cause), '의도를 실행하지 못하는 수정(덜 된 수정)')
  assert.equal(rootCauseKindLabel(undefined), '되돌리면 실패가 사라지는 변경')
  assert.equal(rootCauseKindLabel({ kind: 'other' }), 'other')
})

test('의도 출처: owner 와 inferred, 없으면 null', () => {
  assert.deepStrictEqual(INTENT_SOURCE_LABEL, { owner: '담당자가 적은 기대 결과', inferred: 'SkillOps 가 추정한 기대 결과' })
  assert.equal(intentSourceLabel(b01.proposal.intent.source), '담당자가 적은 기대 결과')
  assert.equal(intentSourceLabel('inferred'), 'SkillOps 가 추정한 기대 결과')
  assert.equal(intentSourceLabel(undefined), null)
})

test('candidateFlags: 참인 경고만 순서대로', () => {
  assert.deepStrictEqual(candidateFlags(null), [])
  assert.deepStrictEqual(candidateFlags({ is_revert: false, weakens_contract: false }), [])
  assert.deepStrictEqual(
    candidateFlags({ is_revert: true, weakens_contract: true }).map((f) => [f.key, f.label]),
    [
      ['is_revert', '담당자 수정 되돌림'],
      ['weakens_contract', '규칙·입력 약화'],
    ],
  )
  assert.deepStrictEqual(
    b01.proposal.candidates.map((c) => candidateFlags(c).map((f) => f.key)),
    [[], ['is_revert'], ['weakens_contract']],
  )
})

test('verdictText: 판정 값 하나를 짧은 글로', () => {
  assert.equal(verdictText(true), '예')
  assert.equal(verdictText(false), '아니오')
  assert.equal(verdictText(null), '없음')
  assert.equal(verdictText(undefined), '없음')
  assert.equal(verdictText({ passed: 5, total: 6 }), '5/6')
  assert.equal(verdictText([1, 2]), '[1,2]')
  assert.equal(verdictText(3), '3')
})

test('overrideRows: 상세 맨 위 칸을 먼저, 없으면 원본 제안서 칸, results. 접두어도 같은 이름', () => {
  assert.deepStrictEqual(overrideRows(b01), [
    { candidate_no: 2, field: 'keeps_intent', label: '의도 지킴', agent: '예', server: '아니오' },
    { candidate_no: 2, field: 'valid', label: '합격 여부', agent: '예', server: '아니오' },
  ])
  const top = { server_overrides: [{ field: 'results.keeps_old_orders', agent_value: { passed: 6, total: 6 }, server_value: { passed: 5, total: 6 } }], proposal: b01.proposal }
  assert.deepStrictEqual(overrideRows(top), [
    { candidate_no: null, field: 'results.keeps_old_orders', label: '기준 주문 유지', agent: '6/6', server: '5/6' },
  ])
  assert.deepStrictEqual(overrideRows({ server_overrides: [{ candidate_no: 1, field: 'status' }] })[0].label, 'status')
  assert.deepStrictEqual(overrideRows(f1), [])
  assert.deepStrictEqual(overrideRows(null), [])
})

test('mock 제안서 b01: 계약의 새 칸을 모두 가진다', () => {
  const p = b01.proposal
  assert.equal(p.root_cause.kind, 'incomplete_edit')
  assert.deepStrictEqual(p.root_cause.repro_scenario_ids, ['g-01', 'g-03', 'g-05', 'g-06'])
  assert.equal(p.intent.source, 'owner')
  assert.equal(p.intent.expectations.length, 2)
  assert.equal(p.candidates[0].kind, 'encode_rule')
  assert.ok(p.candidates[0].patches[0].patch.includes('reason: PREPAID_ONLY'))
  assert.deepStrictEqual(b01.valid_candidate_nos, [1])
})

test('mock 제안서 w1(개발 요청서): 검토 대기로 보이고 덮은 판정·후보 꼬리표가 없다', () => {
  assert.equal(STATUS[w1.status].label, '쓸 수 있는 안 없음')
  assert.equal(awaitsDecision(w1), true)
  assert.equal(rootCauseKind(w1.proposal.root_cause), 'change')
  assert.deepStrictEqual(overrideRows(w1), [])
  assert.equal(w1.recommended_candidate_no, null)
  // 본문은 service 가 쓴 '개발 요청'·'임시 대안' 절을 가진다(2.11 절).
  const headings = w1.document.sections.map((s) => s.heading)
  assert.ok(headings.includes('개발 요청') && headings.includes('임시 대안'), headings.join(', '))
  assert.ok(w1.document.summary.includes('SKILL.md 수정만으로는 해결할 수 없어 개발 요청서를 만들었습니다'))
})
