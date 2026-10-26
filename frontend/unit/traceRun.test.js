// 특성 테스트다. frontend/src/utils/traceRun.js 가 trace 이벤트로 Skill 별 실행 상태를
// 계산하는 traceRun, 단계 상태 이름표(RUN_LABEL), 실행 라벨(executionLabel), 실행 화면
// 경로(executionPath)의 지금 동작을 고정한다. frontend/src/views/ExecutionView.vue 의 단계별
// 결과가 이 계산을 그대로 보여 준다.
import { test } from 'node:test'
import assert from 'node:assert/strict'
import { RUN_LABEL, executionLabel, executionPath, traceRun } from '../src/utils/traceRun.js'

// sequence 를 하나씩 올리며 이벤트를 만든다.
let seq = 0
const ev = (kind, skill_id, data = {}) => ({ sequence: ++seq, kind, skill_id, data, at: '2026-10-22T01:00:00.000Z' })
// traceRun 이 Skill 마다 만드는 상태 객체의 기본 모양이다.
const entry = (over = {}) => ({
  status: null,
  loaded: null,
  started: null,
  tools: [],
  declared_output: null,
  duration_ms: null,
  error: null,
  final_status: null,
  rule: null,
  ...over,
})
// SKILL_LOADED 이벤트의 data 모양이다.
const loaded = (id, version = 1) => ({ path: `skills/${id}/SKILL.md`, version, hash: `hash-${id}` })

// 실행 규칙 항목(when · then · reason)을 만든다. 칸 이름 then 을 객체 글자로 쓰면 lint
// (unicorn/no-thenable)가 막으므로 이름 목록으로 만든다. extra 는 앞에 덧붙일 칸이다.
const RULE_FIELDS = ['when', 'then', 'reason']
const ruleOf = (when, then, reason, extra = {}) => {
  const values = [when, then, reason]
  return { ...extra, ...Object.fromEntries(RULE_FIELDS.map((k, i) => [k, values[i]])) }
}

test('RUN_LABEL: 단계 상태 → 담당자가 읽는 말', () => {
  assert.deepStrictEqual(RUN_LABEL, {
    ok: '완료',
    stopped: '업무 규칙으로 멈춤',
    error: '오류',
    running: '실행 중',
    blocked: '검사에서 막혀 실행 안 됨',
    skipped: '실행 안 됨',
  })
})

test('정상 완료: 단계마다 로드·시작·도구·결과·시간을 모으고 ok 가 된다', () => {
  const flow = loaded('order-flow')
  const intake = loaded('order-intake')
  const started = { step_no: 1, input: { 'order.id': 'o-1' }, version: 1, hash: 'hash-order-intake' }
  const args = { order_id: 'o-1' }
  const result = { context: { 'order.id': 'o-1', 'order.total': 30000 }, final_status: 'PROCESSING' }
  const output = { 'order.id': 'o-1', 'order.total': 30000 }
  const trace = {
    completion: 'COMPLETED',
    events: [
      ev('EXECUTION_STARTED', null, { order_id: 'o-1', mode: 'fixed' }),
      ev('SKILL_LOADED', 'order-flow', flow),
      ev('FLOW_VALIDATED', 'order-flow', { steps: ['order-intake'] }),
      ev('SKILL_LOADED', 'order-intake', intake),
      ev('SKILL_STARTED', 'order-intake', started),
      ev('DECISION_INPUT', 'order-intake', { purpose: 'tool' }),
      ev('TOOL_REQUEST', 'order-intake', { tool: 'order.inspect', arguments: args }),
      ev('TOOL_RESULT', 'order-intake', { tool: 'order.inspect', arguments: args, result, elapsed_ms: 3 }),
      ev('SKILL_COMPLETED', 'order-intake', { declared_output: output }),
      ev('SKILL_FINISHED', 'order-intake', { step_no: 1, version: 1, status: 'ok', duration_ms: 5, output }),
      ev('EXECUTION_FINISHED', null, { completion: 'COMPLETED', final_status: 'SHIPPED' }),
    ],
  }
  assert.deepStrictEqual(traceRun(trace, ['order-intake']), {
    bySkill: {
      'order-flow': entry({ loaded: flow }),
      'order-intake': entry({
        status: 'ok',
        loaded: intake,
        started,
        tools: [{ tool: 'order.inspect', arguments: args, reason: null, result, elapsed_ms: 3 }],
        declared_output: output,
        duration_ms: 5,
      }),
    },
    error: null,
  })
})

test('업무 규칙 종료: 멈춘 단계는 stopped·final_status, 뒤 단계는 skipped', () => {
  const output = { 'inventory.reserved': false }
  const trace = {
    completion: 'COMPLETED',
    events: [
      ev('SKILL_LOADED', 'inventory-check', loaded('inventory-check')),
      ev('SKILL_FINISHED', 'inventory-check', {
        step_no: 2,
        status: 'ok',
        duration_ms: 5,
        business_stop: true,
        final_status: 'REJECTED',
        output,
      }),
      ev('BUSINESS_STOP', 'inventory-check', { final_status: 'REJECTED' }),
    ],
  }
  const run = traceRun(trace, ['inventory-check', 'payment'])
  assert.deepStrictEqual(run.bySkill['inventory-check'], {
    ...entry({ loaded: loaded('inventory-check') }),
    status: 'stopped',
    declared_output: output,
    duration_ms: 5,
    final_status: 'REJECTED',
  })
  assert.deepStrictEqual(run.bySkill.payment, entry({ status: 'skipped' }))
  assert.equal(run.error, null)
})

test('BUSINESS_STOP 만 있어도 stopped 가 되고 final_status 를 남긴다', () => {
  const run = traceRun({ events: [ev('BUSINESS_STOP', 'payment', { final_status: 'HOLD' })] }, ['payment'])
  assert.deepStrictEqual(run.bySkill.payment, entry({ status: 'stopped', final_status: 'HOLD' }))
})

test('정적 검사 차단: 오류 단계는 error, 나머지 단계는 blocked', () => {
  const error = {
    category: 'CONTRACT_VIOLATION',
    skill_id: 'payment',
    phase: 'static_validation',
    missing_inputs: ['inventory.reserved'],
    missing_dependencies: [],
    message: 'Skill에 필요한 입력 또는 선행 단계가 충족되지 않았습니다.',
  }
  const trace = {
    completion: 'FAILED',
    events: [
      ev('SKILL_LOADED', 'order-flow', loaded('order-flow')),
      ev('EXECUTION_ERROR', 'payment', error),
      ev('EXECUTION_FINISHED', null, { completion: 'FAILED', final_status: 'FAILED', error_category: 'CONTRACT_VIOLATION' }),
    ],
  }
  assert.deepStrictEqual(traceRun(trace, ['order-intake', 'inventory-check', 'payment', 'shipping-reserve']), {
    bySkill: {
      'order-flow': entry({ loaded: loaded('order-flow') }),
      payment: entry({ status: 'error', error }),
      'order-intake': entry({ status: 'blocked' }),
      'inventory-check': entry({ status: 'blocked' }),
      'shipping-reserve': entry({ status: 'blocked' }),
    },
    error,
  })
})

test('skill_id 없는 실행 오류: 로드됐지만 끝나지 않은 마지막 단계가 error 가 된다', () => {
  const error = { category: 'INVALID_TOOL_ARGUMENTS', message: 'm' }
  const trace = {
    completion: 'FAILED',
    events: [
      ev('SKILL_LOADED', 'a', loaded('a')),
      ev('SKILL_COMPLETED', 'a', { declared_output: { x: 1 } }),
      ev('SKILL_LOADED', 'b', loaded('b')),
      ev('SKILL_STARTED', 'b', { step_no: 2 }),
      ev('EXECUTION_ERROR', null, error),
    ],
  }
  assert.deepStrictEqual(traceRun(trace, ['a', 'b', 'c']), {
    bySkill: {
      a: entry({ status: 'ok', loaded: loaded('a'), declared_output: { x: 1 } }),
      b: entry({ status: 'error', loaded: loaded('b'), started: { step_no: 2 }, error }),
      c: entry({ status: 'skipped' }),
    },
    error,
  })
})

test('skill_id 없는 실행 오류: 시작만 한 단계도 대상이고, 그 앞의 끝나지 않은 단계는 skipped 로 남는다', () => {
  const error = { category: 'MISSING_SKILL_OUTPUT', skill_id: null }
  const trace = {
    events: [
      ev('SKILL_LOADED', 'a', loaded('a')),
      ev('SKILL_STARTED', 'b', { step_no: 2 }),
      ev('EXECUTION_ERROR', null, error),
    ],
  }
  const run = traceRun(trace, ['a', 'b', 'c'])
  assert.equal(run.bySkill.a.status, 'skipped')
  assert.deepStrictEqual(run.bySkill.b, entry({ status: 'error', started: { step_no: 2 }, error }))
  assert.equal(run.bySkill.c.status, 'skipped')
})

test('skill_id 없는 실행 오류: 끝나지 않은 단계가 없으면 아무 단계에도 붙이지 않는다', () => {
  const error = { category: 'INCOMPLETE_FLOW' }
  const trace = {
    events: [ev('SKILL_LOADED', 'a', loaded('a')), ev('SKILL_COMPLETED', 'a', { declared_output: {} }), ev('EXECUTION_ERROR', null, error)],
  }
  const run = traceRun(trace, ['a'])
  assert.deepStrictEqual(run.bySkill.a, entry({ status: 'ok', loaded: loaded('a'), declared_output: {} }))
  assert.equal(run.error, error)
})

test('실행 오류가 여러 번이면 마지막 것이 이기고, data 가 없으면 error 는 undefined 가 된다', () => {
  const first = { category: 'A', skill_id: 'a' }
  const second = { category: 'B' }
  const run = traceRun(
    {
      events: [
        ev('SKILL_LOADED', 'a', loaded('a')),
        ev('EXECUTION_ERROR', 'a', first),
        ev('SKILL_LOADED', 'b', loaded('b')),
        ev('EXECUTION_ERROR', null, second),
      ],
    },
    ['a', 'b'],
  )
  assert.deepStrictEqual(run.bySkill.a, entry({ status: 'error', loaded: loaded('a'), error: first }))
  assert.deepStrictEqual(run.bySkill.b, entry({ status: 'error', loaded: loaded('b'), error: second }))
  assert.equal(run.error, second)

  const noData = traceRun({ events: [ev('EXECUTION_ERROR', null, first), { kind: 'EXECUTION_ERROR', skill_id: null }] }, [])
  assert.deepStrictEqual(noData, { bySkill: {}, error: undefined })
})

test('실행 중(RUNNING): 로드만 된 단계는 running, 시작만 했거나 로드 전이면 skipped', () => {
  const trace = {
    completion: 'RUNNING',
    events: [ev('SKILL_LOADED', 'a', loaded('a')), ev('SKILL_STARTED', 'b', { step_no: 2 })],
  }
  const run = traceRun(trace, ['a', 'b', 'c'])
  assert.deepStrictEqual(
    ['a', 'b', 'c'].map((id) => run.bySkill[id].status),
    ['running', 'skipped', 'skipped'],
  )
})

test('도구 요청·결과 짝맞춤: 같은 도구의 결과 없는 마지막 요청과 짝짓고, 짝 없는 결과는 새 항목이 된다', () => {
  const trace = {
    events: [
      ev('TOOL_REQUEST', 'p', { tool: 't', arguments: { n: 1 } }),
      ev('TOOL_REQUEST', 'p', { tool: 't', arguments: { n: 2 }, reason: 'retry' }),
      ev('TOOL_RESULT', 'p', { tool: 't', result: { r: 1 }, elapsed_ms: 1 }),
      ev('TOOL_RESULT', 'p', { tool: 't', result: { r: 2 } }),
      ev('TOOL_REPLAY', 'p', { tool: 'u', arguments: { m: 1 } }),
      ev('TOOL_RESULT', 'p', { tool: 'v' }),
    ],
  }
  assert.deepStrictEqual(traceRun(trace, ['p']).bySkill.p, {
    ...entry(),
    status: 'skipped',
    tools: [
      { tool: 't', arguments: { n: 1 }, reason: null, result: { r: 2 }, elapsed_ms: null },
      { tool: 't', arguments: { n: 2 }, reason: 'retry', result: { r: 1 }, elapsed_ms: 1 },
      { tool: 'u', arguments: { m: 1 }, result: { replayed: true }, elapsed_ms: null },
      { tool: 'v', arguments: undefined, result: { replayed: true }, elapsed_ms: null },
    ],
  })
})

test('도구 오류와 단계 오류: 먼저 남은 오류 내용을 유지한다', () => {
  const toolError = { tool: 'payment.charge', category: 'TIMEOUT' }
  const withTool = traceRun(
    {
      events: [
        ev('TOOL_ERROR', 'p', toolError),
        ev('SKILL_FINISHED', 'p', { step_no: 3, status: 'error', category: 'TOOL_FAILED', duration_ms: 9 }),
      ],
    },
    ['p'],
  )
  assert.deepStrictEqual(withTool.bySkill.p, entry({ status: 'error', error: toolError, duration_ms: 9 }))
  const alone = traceRun({ events: [ev('SKILL_FINISHED', 'p', { status: 'error', category: 'TOOL_FAILED' })] }, ['p'])
  assert.deepStrictEqual(alone.bySkill.p, entry({ status: 'error', error: { category: 'TOOL_FAILED' } }))
  assert.equal(alone.error, null)
})

test('SKILL_FINISHED 의 output 이 없으면 앞서 전달한 값을 유지한다', () => {
  const run = traceRun(
    {
      events: [
        ev('SKILL_COMPLETED', 'a', { declared_output: { x: 1 } }),
        ev('SKILL_FINISHED', 'a', { status: 'ok' }),
        ev('SKILL_COMPLETED', 'b', { declared_output: { y: 1 } }),
        ev('SKILL_FINISHED', 'b', { status: 'ok', output: { y: 2 }, duration_ms: 0 }),
      ],
    },
    ['a', 'b'],
  )
  assert.deepStrictEqual(run.bySkill.a, entry({ status: 'ok', declared_output: { x: 1 } }))
  assert.deepStrictEqual(run.bySkill.b, entry({ status: 'ok', declared_output: { y: 2 }, duration_ms: 0 }))
})

test('skill_id 가 없는 단계 이벤트는 무시하고, 처리하지 않는 종류도 그 Skill 의 항목은 만든다', () => {
  const run = traceRun(
    {
      events: [
        ev('SKILL_LOADED', null, loaded('a')),
        ev('TOOL_RESULT', null, { tool: 't' }),
        ev('SKILL_COMPLETED', null, { declared_output: {} }),
        ev('DECISION_INPUT', 'x', { purpose: 'tool' }),
      ],
    },
    [],
  )
  assert.deepStrictEqual(run, { bySkill: { x: entry() }, error: null })
})

test('trace 가 없으면 모든 단계가 skipped', () => {
  assert.deepStrictEqual(traceRun(null, ['a']), { bySkill: { a: entry({ status: 'skipped' }) }, error: null })
  assert.deepStrictEqual(traceRun(undefined, []), { bySkill: {}, error: null })
  assert.deepStrictEqual(traceRun({}, ['a']), { bySkill: { a: entry({ status: 'skipped' }) }, error: null })
})

test('executionLabel: 시각 · 상태 · 오류 분류 · 실행 번호 앞 8자', () => {
  const base = {
    started_at: '2026-10-22T10:05:09.123Z',
    final_status: 'SHIPPED',
    completion: 'COMPLETED',
    execution_id: 'abcdef12-3456-4789-8abc-def012345678',
  }
  assert.equal(executionLabel(base), '10-22 10:05 · SHIPPED · abcdef12')
  assert.equal(
    executionLabel({ ...base, final_status: 'FAILED', completion: 'FAILED', error_category: 'CONTRACT_VIOLATION' }),
    '10-22 10:05 · FAILED · CONTRACT_VIOLATION · abcdef12',
  )
  assert.equal(executionLabel({ ...base, final_status: null, completion: 'RUNNING' }), '10-22 10:05 · RUNNING · abcdef12')
  assert.equal(executionLabel({ ...base, started_at: undefined }), 'undefined · SHIPPED · abcdef12')
  assert.equal(executionLabel({ ...base, execution_id: 'short' }), '10-22 10:05 · SHIPPED · short')
})

test('executionPath: r_ + 32자리 16진수와 UUID 만 실행 화면 주소로 바꾼다', () => {
  assert.equal(executionPath('r_0123456789abcdef0123456789abcdef'), '/executions/01234567-89ab-cdef-0123-456789abcdef')
  assert.equal(executionPath('R_0123456789ABCDEF0123456789ABCDEF'), '/executions/01234567-89AB-CDEF-0123-456789ABCDEF')
  assert.equal(executionPath('3f2a9c1e-7b4d-4e8a-9c2f-1a2b3c4d5e6f'), '/executions/3f2a9c1e-7b4d-4e8a-9c2f-1a2b3c4d5e6f')
  assert.equal(executionPath('3F2A9C1E-7B4D-4E8A-9C2F-1A2B3C4D5E6F'), '/executions/3F2A9C1E-7B4D-4E8A-9C2F-1A2B3C4D5E6F')
  assert.equal(executionPath('r_0123456789abcdef0123456789abcde'), null)
  assert.equal(executionPath(' 3f2a9c1e-7b4d-4e8a-9c2f-1a2b3c4d5e6f'), null)
  assert.equal(executionPath('수정 전 Skill 세트'), null)
  assert.equal(executionPath(''), null)
  assert.equal(executionPath(undefined), null)
  assert.equal(executionPath(null), null)
})

test('실행 규칙 종료: RULE_MATCHED 단계는 결제 거절처럼 stopped·HOLD·rule 이 되고, 뒤 단계는 skipped', () => {
  const rule = ruleOf("order.payment_method != 'prepaid'", 'hold', 'PREPAID_ONLY', { skill_id: 'payment', rule_index: 0 })
  const release = { order_id: 'o-1' }
  const trace = {
    completion: 'COMPLETED',
    events: [
      ev('SKILL_LOADED', 'inventory-check', loaded('inventory-check')),
      ev('SKILL_COMPLETED', 'inventory-check', { declared_output: { 'inventory.reserved': true } }),
      ev('SKILL_LOADED', 'payment', loaded('payment')),
      ev('SKILL_STARTED', 'payment', { step_no: 3 }),
      ev('RULE_MATCHED', 'payment', rule),
      ev('TOOL_REQUEST', 'payment', { tool: 'inventory.release', arguments: release, reason: 'PREPAID_ONLY' }),
      ev('TOOL_RESULT', 'payment', { tool: 'inventory.release', arguments: release, result: { 'inventory.reserved': false } }),
      ev('SKILL_FINISHED', 'payment', {
        step_no: 3,
        status: 'ok',
        business_stop: true,
        final_status: 'HOLD',
        rule_reason: 'PREPAID_ONLY',
        duration_ms: 4,
        output: {},
      }),
      ev('BUSINESS_STOP', 'payment', { final_status: 'HOLD' }),
      ev('EXECUTION_FINISHED', null, { completion: 'COMPLETED', final_status: 'HOLD', error_category: null }),
    ],
  }
  const run = traceRun(trace, ['inventory-check', 'payment', 'shipping-reserve'])
  assert.deepStrictEqual(run.bySkill.payment, {
    ...entry({ loaded: loaded('payment'), started: { step_no: 3 } }),
    status: 'stopped',
    final_status: 'HOLD',
    rule,
    tools: [{ tool: 'inventory.release', arguments: release, reason: 'PREPAID_ONLY', result: { 'inventory.reserved': false }, elapsed_ms: null }],
    declared_output: {},
    duration_ms: 4,
  })
  assert.equal(run.bySkill['inventory-check'].status, 'ok')
  assert.deepStrictEqual(run.bySkill['shipping-reserve'], entry({ status: 'skipped' }))
  assert.equal(run.error, null)
  assert.equal(RUN_LABEL[run.bySkill.payment.status], '업무 규칙으로 멈춤')
})

test('실행 규칙 종료: RULE_MATCHED 만 있어도 stopped 이고 reject 는 REJECTED, 이벤트에 skill_id 가 없으면 data 의 값으로 찾는다', () => {
  const rule = ruleOf("order.shipping.country != 'KR'", 'reject', 'DOMESTIC_ONLY', { skill_id: 'order-intake', rule_index: 0 })
  const run = traceRun({ events: [ev('RULE_MATCHED', null, rule)] }, ['order-intake', 'payment'])
  assert.deepStrictEqual(run.bySkill['order-intake'], entry({ status: 'stopped', final_status: 'REJECTED', rule }))
  assert.equal(run.bySkill.payment.status, 'skipped')
  // 이벤트 칸과 data 에 모두 skill_id 가 없으면 어느 단계에도 붙이지 않는다.
  assert.deepStrictEqual(traceRun({ events: [ev('RULE_MATCHED', null, ruleOf(undefined, 'hold', undefined))] }, []), { bySkill: {}, error: null })
})
