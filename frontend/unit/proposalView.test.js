// frontend/src/utils/proposalView.js 의 수정안 목록 규칙(상태 이름, 상태 필터, 카드 핵심 요약)을 보호한다.
import { test } from 'node:test'
import assert from 'node:assert/strict'
import { STATUS, STATUS_FILTERS, matchesStatusFilter, proposalHighlights } from '../src/utils/proposalView.js'

const ALL = ['proposed', 'no_valid_fix', 'approved', 'rejected'].map((status) => ({ status }))
const pick = (filter) => ALL.filter((p) => matchesStatusFilter(p, filter)).map((p) => p.status)

test('상태 이름: no_valid_fix 는 통과한 후보 없음', () => {
  assert.equal(STATUS.no_valid_fix.label, '통과한 후보 없음')
})

test('상태 필터: 전체 · 검토 대기 · 검토 완료', () => {
  assert.deepStrictEqual(STATUS_FILTERS.map((f) => f.value), ['all', 'pending', 'done'])
  assert.deepStrictEqual(pick('all'), ['proposed', 'no_valid_fix', 'approved', 'rejected'])
  // 검토 대기는 사이드바 숫자와 같은 awaitsDecision 규칙이다.
  assert.deepStrictEqual(pick('pending'), ['proposed', 'no_valid_fix'])
  assert.deepStrictEqual(pick('done'), ['approved', 'rejected'])
  // 표에 없는 값은 전체로 본다.
  assert.equal(pick('unknown').length, ALL.length)
})

test('핵심 요약: 원인과 시험 결과 두 줄, 추천 후보는 시험 결과 끝에', () => {
  const p = { root_cause_skill: 'inventory-check', candidate_count: 2, valid_candidate_nos: [1], recommended_candidate_no: 1 }
  assert.deepStrictEqual(proposalHighlights(p), [
    { label: '원인', value: 'inventory-check Skill 변경' },
    { label: '시험 결과', value: '후보 2개 중 1개 통과 · 추천 후보 1번' },
  ])
  // 원인 Skill 을 모르고 통과한 후보가 없을 때다. 추천 후보도 붙지 않는다.
  const none = proposalHighlights({ root_cause_skill: null, candidate_count: 3, valid_candidate_nos: [], recommended_candidate_no: null })
  assert.deepStrictEqual(none.map((h) => h.value), ['확인 중', '후보 3개 모두 통과하지 못함'])
})
