// frontend/src/utils/runRecord.js 가 실행 번호와 trace 를 담당자가 읽는 실행 기록으로 바꾸는 규칙,
// Skill 세트 이름, 동시 요청 제한과 캐시를 고정한다. 시각은 지역 시간으로 쓰므로 기대 값도 지역 시간
// 부품(new Date(년, 월, 일, …))으로 만들어 실행 환경의 시간대와 상관없이 같게 한다.
import { test } from 'node:test'
import assert from 'node:assert/strict'
import {
  createCache,
  createLimiter,
  executionIdOf,
  failureLabel,
  isRunId,
  orderInputOf,
  orderSummary,
  outcomeOf,
  runIdsIn,
  snapshotLabelText,
  summarizeTrace,
  uuidsIn,
  withoutIds,
} from '../src/utils/runRecord.js'

const RUN = 'r_e0000000000000000000000000000001'
const EXEC = 'e0000000-0000-0000-0000-000000000001'
const SNAP = '77777777-7777-4777-8777-777777777777'
// 2026년 10월 26일 14:32:05(지역 시간)다.
const AT = new Date(2026, 9, 26, 14, 32, 5).toISOString()

let seq = 0
const ev = (kind, skill_id, data = {}) => ({ sequence: ++seq, kind, skill_id, data, at: AT })
const CTX = {
  'order.id': 'o-1',
  'order.customer': 'c_1001',
  'order.items': [{ sku: 'CAMP-LANTERN-LED', qty: 1 }],
  'order.payment_method': 'card',
  'order.shipping': { country: 'KR', express: false },
}
// 실행 규칙 항목(when · then · reason)을 만든다. 칸 이름 then 을 객체 글자로 쓰면 lint(unicorn/no-thenable)가
// 막으므로 이름 목록으로 만든다. extra 는 앞에 덧붙일 칸이다.
const RULE_FIELDS = ['when', 'then', 'reason']
const ruleOf = (when, then, reason, extra = {}) => ({ ...extra, ...Object.fromEntries(RULE_FIELDS.map((k, i) => [k, [when, then, reason][i]])) })
// 실행 기록 trace 를 만든다. over 로 칸을 바꾸고 events 를 덧붙인다.
const traceOf = (over = {}, events = []) => ({
  execution_id: EXEC,
  run_id: RUN,
  snapshot_id: SNAP,
  completion: 'COMPLETED',
  final_status: 'SHIPPED',
  started_at: AT,
  events: [
    ev('EXECUTION_STARTED', null, { order_id: 'o-1', mode: 'fixed' }),
    ev('DECISION_INPUT', null, { purpose: 'select', inputs: CTX }),
    ...events,
  ],
  ...over,
})

test('isRunId · executionIdOf: run_id 와 UUID 만 execution_id 로 바꾼다', () => {
  assert.equal(isRunId(RUN), true)
  assert.equal(isRunId(EXEC), false)
  assert.equal(isRunId(null), false)
  assert.equal(executionIdOf(RUN), EXEC)
  assert.equal(executionIdOf(EXEC), EXEC)
  assert.equal(executionIdOf(` ${RUN} `), EXEC)
  assert.equal(executionIdOf('p_mockw1'), null)
  assert.equal(executionIdOf(undefined), null)
})

test('runIdsIn · uuidsIn: ", " 로 이은 글이 모두 같은 모양일 때만 목록이다', () => {
  assert.deepStrictEqual(runIdsIn(`${RUN}, r_e0000000000000000000000000000002`), [RUN, 'r_e0000000000000000000000000000002'])
  assert.equal(runIdsIn(`${RUN}, 그 밖`), null)
  assert.equal(runIdsIn(EXEC), null)
  assert.equal(runIdsIn(''), null)
  assert.equal(runIdsIn(42), null)
  assert.deepStrictEqual(uuidsIn(SNAP), [SNAP])
  assert.deepStrictEqual(uuidsIn(`${SNAP}, ${EXEC}`), [SNAP, EXEC])
  assert.equal(uuidsIn('c_20261026_02'), null)
})

test('orderInputOf: SKILL_STARTED input 을 먼저, 없으면 선택 DECISION_INPUT inputs 를 쓴다', () => {
  const started = { ...CTX, 'order.payment_method': 'prepaid' }
  const t = traceOf({}, [ev('SKILL_STARTED', 'order-intake', { step_no: 1, input: started })])
  // 선택 입력이 먼저 오므로 그 값이 쓰인다.
  assert.equal(orderInputOf(t).payment_method, 'card')
  const onlyStarted = { events: [ev('SKILL_STARTED', 'order-intake', { input: started })] }
  assert.deepStrictEqual(orderInputOf(onlyStarted), {
    items: CTX['order.items'],
    payment_method: 'prepaid',
    shipping: CTX['order.shipping'],
  })
  assert.equal(orderInputOf({ events: [ev('EXECUTION_STARTED', null, {})] }), null)
  assert.equal(orderInputOf(null), null)
})

test('orderSummary: 상품 이름 · 결제 · 배송을 한 줄로 쓴다', () => {
  const input = orderInputOf(traceOf())
  assert.equal(orderSummary(input), 'CAMP-LANTERN-LED 1개 · 카드 · 국내 일반 배송')
  assert.equal(orderSummary(input, { 'CAMP-LANTERN-LED': 'LED 캠핑 랜턴' }), 'LED 캠핑 랜턴 1개 · 카드 · 국내 일반 배송')
  assert.equal(
    orderSummary({ items: [{ sku: 'A', name: '의자', qty: 2 }], payment_method: 'prepaid', shipping: { country: 'US', express: true } }),
    '의자 2개 · 선입금 · 미국 빠른 배송',
  )
  assert.equal(
    orderSummary({ items: [{ sku: 'A', qty: 1 }, { sku: 'B', qty: 1 }, { sku: 'C', qty: 3 }], payment_method: 'bank' }),
    'A 1개 외 2건 · bank',
  )
  assert.equal(orderSummary({ items: [{ sku: 'A', qty: 1 }, { sku: 'B', qty: 2 }] }), 'A 1개, B 2개')
  assert.equal(orderSummary({ items: [] }), null)
  assert.equal(orderSummary(null), null)
})

test('outcomeOf: 완료 · 규칙 멈춤 · 업무 거절 · 진행 중', () => {
  assert.deepStrictEqual(outcomeOf(traceOf()), { text: '배송 완료', tone: 'ok' })
  const rule = ev('RULE_MATCHED', 'payment', ruleOf('x', 'hold', 'PREPAID_ONLY', { skill_id: 'payment', rule_index: 0 }))
  assert.deepStrictEqual(outcomeOf(traceOf({ final_status: 'HOLD' }, [rule])), { text: '규칙 PREPAID_ONLY 로 보류', tone: 'warn' })
  const reject = ev('RULE_MATCHED', 'payment', ruleOf('y', 'reject', 'NO_EXPORT'))
  assert.deepStrictEqual(outcomeOf(traceOf({ final_status: 'REJECTED' }, [reject])), { text: '규칙 NO_EXPORT 로 거절', tone: 'warn' })
  assert.deepStrictEqual(outcomeOf(traceOf({ final_status: 'HOLD' })), { text: '보류 · 결제 거절', tone: 'warn' })
  assert.deepStrictEqual(outcomeOf(traceOf({ final_status: 'REJECTED' })), { text: '거절 · 재고 부족', tone: 'warn' })
  assert.deepStrictEqual(outcomeOf(traceOf({ completion: 'RUNNING', final_status: null })), { text: '실행 중', tone: 'info' })
  assert.deepStrictEqual(outcomeOf(traceOf({ completion: 'INTERRUPTED', final_status: 'FAILED' })), {
    text: '중단됨 · 서버 재시작',
    tone: 'warn',
  })
  assert.deepStrictEqual(outcomeOf(traceOf({ final_status: 'ODD' })), { text: 'ODD', tone: 'muted' })
  assert.deepStrictEqual(outcomeOf({}), { text: '상태 없음', tone: 'muted' })
})

test('outcomeOf: 실패는 단계와 사유를 쓴다', () => {
  const failed = (data, extra = []) =>
    outcomeOf(traceOf({ completion: 'FAILED', final_status: 'FAILED' }, [...extra, ev('EXECUTION_ERROR', data.skill_id ?? null, data)]))
  assert.deepStrictEqual(failed({ category: 'CONTRACT_VIOLATION', skill_id: 'payment', missing_inputs: ['inventory.reserved'] }), {
    text: '실패 · payment 단계 입력 누락',
    tone: 'err',
  })
  assert.equal(failed({ category: 'CONTRACT_VIOLATION', skill_id: 'notify', reason: 'RULE_KEY_NOT_INPUT' }).text, '실패 · notify 단계 규칙 입력 없음')
  assert.equal(failed({ category: 'CONTRACT_VIOLATION', skill_id: 'payment', missing_dependencies: ['x'] }).text, '실패 · payment 단계 앞 단계 누락')
  assert.equal(failed({ category: 'INCOMPLETE_FLOW' }).text, '실패 · 알림 단계 없음')
  // skill_id 없는 오류는 로드됐지만 끝나지 못한 마지막 단계(traceRun 판정)에 붙인다.
  const flow = [ev('FLOW_VALIDATED', 'order-flow', { steps: ['order-intake', 'payment'] }), ev('SKILL_LOADED', 'payment', { path: 'p' })]
  assert.equal(failed({ category: 'MISSING_SKILL_OUTPUT' }, flow).text, '실패 · payment 단계 출력 누락')
  // 오류 이벤트가 없으면 trace 의 error_category 를 쓴다.
  assert.deepStrictEqual(outcomeOf(traceOf({ completion: 'FAILED', final_status: 'FAILED', error_category: 'WEIRD' })), {
    text: '실패 · WEIRD',
    tone: 'err',
  })
  assert.equal(failureLabel(null), '오류')
})

test('summarizeTrace: 시각 · 결과 · 주문 요약 · 세트 종류', () => {
  const sameDayNow = new Date(2026, 9, 26, 23, 0, 0)
  assert.deepStrictEqual(summarizeTrace(traceOf(), { products: { 'CAMP-LANTERN-LED': 'LED 캠핑 랜턴' }, now: sameDayNow, kindOf: () => '내 수정본' }), {
    executionId: EXEC,
    path: `/executions/${EXEC}`,
    at: AT,
    time: '14:32:05',
    date: null,
    outcome: '배송 완료',
    tone: 'ok',
    order: 'LED 캠핑 랜턴 1개 · 카드 · 국내 일반 배송',
    snapshotId: SNAP,
    snapshotKind: '내 수정본',
  })
  const later = summarizeTrace(traceOf({ snapshot_id: null }), { now: new Date(2026, 9, 27, 9, 0, 0), kindOf: () => 'x' })
  assert.equal(later.date, '10월 26일')
  assert.equal(later.snapshotKind, null)
  // 시작 시각이 없으면 첫 이벤트 시각을 쓴다.
  assert.equal(summarizeTrace(traceOf({ started_at: undefined }), { now: sameDayNow }).time, '14:32:05')
  const empty = summarizeTrace({})
  assert.equal(empty.path, null)
  assert.equal(empty.time, null)
  assert.equal(empty.order, null)
})

test('snapshotLabelText: 역할 · 종류 · 만든 시각으로 세트 이름을 쓴다', () => {
  const created = new Date(2026, 9, 26, 6, 41, 30).toISOString()
  assert.equal(snapshotLabelText({ role: 'baseline' }), '기준본')
  assert.equal(snapshotLabelText({ role: 'baseline', kind: '게시본', createdAt: created }), '기준본 · 10월 26일 06:41')
  assert.equal(snapshotLabelText({ role: 'candidate', createdAt: created }), '수정본 · 10월 26일 06:41')
  assert.equal(snapshotLabelText({ role: 'candidate', kind: '내 수정본' }), '내 수정본')
  assert.equal(snapshotLabelText({ kind: '정상본', createdAt: created }), '정상본 · 10월 26일 06:41')
  assert.equal(snapshotLabelText({ createdAt: 'not-a-date' }), 'Skill 세트')
  assert.equal(snapshotLabelText(), 'Skill 세트')
})

test('createLimiter: 동시에 max 개까지만 돌리고, 실패해도 자리를 비운다', async () => {
  const limit = createLimiter(4)
  let active = 0
  let peak = 0
  const task = (i) => async () => {
    active++
    peak = Math.max(peak, active)
    await new Promise((r) => setTimeout(r, 5))
    active--
    if (i === 3) throw new Error('boom')
    return i
  }
  const results = await Promise.allSettled(Array.from({ length: 10 }, (_, i) => limit(task(i))))
  assert.equal(peak, 4)
  assert.deepStrictEqual(
    results.map((r) => (r.status === 'fulfilled' ? r.value : r.reason.message)),
    [0, 1, 2, 'boom', 4, 5, 6, 7, 8, 9],
  )
})

test('createCache: 같은 키는 한 번만 읽고, 실패하면 다음에 다시 읽는다', async () => {
  const calls = []
  let fail = true
  const cache = createCache(async (key) => {
    calls.push(key)
    if (key === 'bad' && fail) throw new Error('no')
    return `v:${key}`
  })
  const [a, b] = await Promise.all([cache.get('a'), cache.get('a')])
  assert.equal(a, 'v:a')
  assert.equal(b, 'v:a')
  await assert.rejects(cache.get('bad'), /no/)
  await new Promise((r) => setTimeout(r, 0))
  fail = false
  assert.equal(await cache.get('bad'), 'v:bad')
  cache.forget('a')
  await cache.get('a')
  assert.deepStrictEqual(calls, ['a', 'bad', 'bad', 'a'])
  cache.clear()
  await cache.get('a')
  assert.equal(calls.length, 5)
})

test('withoutIds: 자유 글 속 내부 번호와 앞의 구분자를 지운다', () => {
  assert.equal(withoutIds('제안서 저장: p_1fa6fa13f7354b2f8c7fcf4334ed0baa'), '제안서 저장')
  assert.equal(withoutIds(`실제 snapshot ${SNAP} 수집`), '실제 snapshot 수집')
  assert.equal(withoutIds(`근거 ${RUN}, ${RUN} 확인`), '근거, 확인')
  assert.equal(withoutIds('원인 실험 통과: order-intake'), '원인 실험 통과: order-intake')
  assert.equal(withoutIds('가설 1개 생성 (retry=0)'), '가설 1개 생성 (retry=0)')
  assert.equal(withoutIds(null), '')
})
