// frontend/src/utils/eventView.js 의 EVENT_KINDS 표에서 만든 라벨(eventLabel),
// 숨김(isNoisy), 문장(describe)과 단계별 묶기(groupBySkill)를 보호한다. 예전
// frontend/src/components/EventTimeline.vue 와 frontend/src/utils/traceRun.js 에 흩어져
// 있던 규칙과 같은지 확인한다. 이벤트 종류를 더하면 이 파일의 종류 수 24 도 고친다.
import { test } from 'node:test'
import assert from 'node:assert/strict'
import { EVENT_LABELS, describe, eventLabel, groupBySkill, isErrorEvent, isNoisy } from '../src/utils/eventView.js'

// 예전 frontend/src/components/EventTimeline.vue 의 숨김 목록과 묶기
// 식을 그대로 복사한 비교 기준이다.
const OLD_NOISY = new Set(['DECISION_INPUT', 'MODEL_RESPONSE', 'MODEL_REQUEST', 'CANDIDATES_OFFERED'])
function oldGroups(shown) {
  const out = []
  for (const e of shown) {
    const key = e.skill_id ?? null
    if (!out.length || out.at(-1).skill !== key) out.push({ skill: key, events: [] })
    out.at(-1).events.push({ ...e, view: describe(e), error: isErrorEvent(e) })
  }
  return out
}

test('eventLabel: 표의 라벨, 없으면 종류 이름 그대로', () => {
  for (const [kind, label] of Object.entries(EVENT_LABELS)) assert.equal(eventLabel(kind), label)
  assert.equal(eventLabel('SOMETHING_NEW'), 'SOMETHING_NEW')
  assert.equal(Object.keys(EVENT_LABELS).length, 24)
})

test('isNoisy: 모델 입력·응답 네 종류만 숨긴다', () => {
  for (const kind of [...Object.keys(EVENT_LABELS), 'SOMETHING_NEW', 'constructor', 'toString']) {
    assert.equal(isNoisy(kind), OLD_NOISY.has(kind), kind)
  }
})

test('describe: 표에 없는 이름(Object 속성 이름 포함)은 종류 이름을 제목으로', () => {
  for (const kind of ['constructor', 'toString', '__proto__', 'hasOwnProperty', undefined]) {
    assert.deepStrictEqual(describe({ kind, data: {} }), { title: kind, fields: [] })
  }
})

test('groupBySkill: 연속된 같은 skill 을 한 묶음으로, skill 없는 이벤트는 null 묶음', () => {
  const events = [
    { sequence: 1, kind: 'EXECUTION_STARTED', skill_id: null, data: { order_id: 'o-1', mode: 'fixed' } },
    { sequence: 2, kind: 'SNAPSHOT_FIXED', data: { snapshot_id: 's', hash: 'h' } },
    { sequence: 3, kind: 'SKILL_LOADED', skill_id: 'a', data: { path: 'p', version: 1, hash: 'h' } },
    { sequence: 4, kind: 'TOOL_ERROR', skill_id: 'a', data: { tool: 't', category: 'X' } },
    { sequence: 5, kind: 'SKILL_LOADED', skill_id: 'b', data: {} },
    { sequence: 6, kind: 'EXECUTION_ERROR', skill_id: 'a', data: { category: 'Y' } },
    { sequence: 7, kind: 'EXECUTION_FINISHED', skill_id: null, data: { completion: 'FAILED' } },
  ]
  const groups = groupBySkill(events)
  assert.deepStrictEqual(groups, oldGroups(events))
  assert.deepStrictEqual(
    groups.map((g) => [g.skill, g.events.map((e) => e.sequence)]),
    [
      [null, [1, 2]],
      ['a', [3, 4]],
      ['b', [5]],
      ['a', [6]],
      [null, [7]],
    ],
  )
  assert.deepStrictEqual(groups[1].events[1], { ...events[3], view: describe(events[3]), error: true })
  assert.deepStrictEqual(groupBySkill([]), [])
})
