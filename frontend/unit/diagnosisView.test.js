// frontend/src/utils/diagnosisView.js 를 보호한다. 진단 상태 라벨, 다시 조회할지
// 판단, 후보와 원인 선택, 세트 id 선택, 후보 검증 문구가 예전
// frontend/src/views/DiagnosisView.vue 안의 식과 같은 결과를 내는지 확인한다.
// 라벨 가운데 둘은 frontend/tests/diagnosis.spec.js 가 화면에서 찾는 문구다.
import { test } from 'node:test'
import assert from 'node:assert/strict'
import {
  DIAGNOSIS_LABEL,
  IN_PROGRESS_STATUSES,
  baselineSnapshotOf,
  candidateSnapshotOf,
  candidatesOf,
  candidateVerdict,
  diagnosisLabel,
  rootCauseOf,
  shouldPollAgain,
} from '../src/utils/diagnosisView.js'

// 예전 frontend/src/views/DiagnosisView.vue 의 식을 그대로 복사한 비교 기준이다.
const labels = { BASELINE_FAILED: '기준 주문 검증 불가', VALIDATION_FAILED: '기대 결과 불일치', QUEUED: '검증 대기', RUNNING: '검증·진단 중', VALIDATED: '기본 회귀 검증 통과', EVALUATED: '진단·후보 평가 완료', INTENT_VIOLATION: '변경 의도 위반 발견', ERROR: '검증·진단 오류', NO_SKILL_CHANGES: 'Skill 변경 없음' }
const oldPoll = (run, error) => !!(!run || ['QUEUED', 'RUNNING'].includes(run.diagnosis_status) || error)
const oldCandidates = (run) => run?.proposal?.candidates ?? run?.candidates ?? []
const oldRoot = (run) => run?.proposal?.root_cause ?? run?.root_cause
const oldVerdict = (valid) => (valid === true ? '검증 통과' : valid === false ? '검증 미통과' : '검증 미완료')

// 라벨이 있는 상태, 모르는 상태, 상태가 없는 경우를 모두 넣은 입력이다.
const STATUSES = [...Object.keys(labels), 'SOMETHING_NEW', undefined, null]

test('진단 상태 라벨 9개(e2e 가 단언하는 문구 포함)와 모르는 상태 통과', () => {
  assert.deepStrictEqual(DIAGNOSIS_LABEL, labels)
  for (const s of STATUSES) assert.equal(diagnosisLabel(s), labels[s] ?? s)
  assert.equal(diagnosisLabel('VALIDATED'), '기본 회귀 검증 통과')
  assert.equal(diagnosisLabel('BASELINE_FAILED'), '기준 주문 검증 불가')
  assert.equal(diagnosisLabel('SOMETHING_NEW'), 'SOMETHING_NEW')
})

test('shouldPollAgain: 결과 없음·진행 중·오류면 다시 조회한다', () => {
  assert.deepStrictEqual(IN_PROGRESS_STATUSES, ['QUEUED', 'RUNNING'])
  for (const error of [null, undefined, '', '시간 초과']) {
    assert.equal(shouldPollAgain(null, error), true)
    for (const s of STATUSES) {
      const run = { diagnosis_status: s }
      assert.equal(shouldPollAgain(run, error), oldPoll(run, error), `${s} ${error}`)
    }
  }
  assert.equal(shouldPollAgain({ diagnosis_status: 'QUEUED' }, null), true)
  assert.equal(shouldPollAgain({ diagnosis_status: 'RUNNING' }, null), true)
  assert.equal(shouldPollAgain({ diagnosis_status: 'VALIDATED' }, null), false)
  assert.equal(shouldPollAgain({ diagnosis_status: 'EVALUATED' }, '오류'), true)
})

test('candidatesOf·rootCauseOf: 저장된 제안서 값을 먼저 쓴다', () => {
  const runs = [
    null,
    undefined,
    {},
    { candidates: [{ candidate_no: 1 }], root_cause: { skill: 'a' } },
    { proposal: {}, candidates: [{ candidate_no: 2 }], root_cause: { skill: 'b' } },
    { proposal: { candidates: [{ candidate_no: 3 }], root_cause: { skill: 'c' } }, candidates: [{ candidate_no: 4 }], root_cause: { skill: 'd' } },
    { proposal: { candidates: null, root_cause: null }, candidates: [{ candidate_no: 5 }] },
  ]
  for (const run of runs) {
    assert.deepStrictEqual(candidatesOf(run), oldCandidates(run))
    assert.deepStrictEqual(rootCauseOf(run), oldRoot(run))
  }
  assert.deepStrictEqual(candidatesOf(runs[5]), [{ candidate_no: 3 }])
  assert.deepStrictEqual(rootCauseOf(runs[5]), { skill: 'c' })
  assert.deepStrictEqual(candidatesOf(null), [])
})

test('기준·수정 세트 id: 진단 결과 값, 없으면 trigger 값(trigger 가 없으면 예전처럼 TypeError)', () => {
  const trigger = { baseline_snapshot_id: 'tb', candidate_snapshot_id: 'tc' }
  assert.equal(baselineSnapshotOf({ baseline_snapshot_id: 'b', trigger }), 'b')
  assert.equal(baselineSnapshotOf({ baseline_snapshot_id: null, trigger }), 'tb')
  assert.equal(candidateSnapshotOf({ snapshot_id: 's', trigger }), 's')
  assert.equal(candidateSnapshotOf({ trigger }), 'tc')
  assert.equal(baselineSnapshotOf({ baseline_snapshot_id: 'b' }), 'b')
  assert.throws(() => baselineSnapshotOf({}), TypeError)
  assert.throws(() => candidateSnapshotOf({ snapshot_id: null }), TypeError)
})

test('candidateVerdict: true 통과 · false 미통과 · 그 밖 미완료', () => {
  for (const v of [true, false, null, undefined, 1, 0, 'true']) assert.equal(candidateVerdict(v), oldVerdict(v))
  assert.equal(candidateVerdict(true), '검증 통과')
  assert.equal(candidateVerdict(false), '검증 미통과')
  assert.equal(candidateVerdict(undefined), '검증 미완료')
})
