// 특성 테스트다. frontend/src/utils/eventView.js 가 trace 이벤트를 사람이 읽는
// 문장(describe), 값 형식(formatValue, fieldsOf), 이름표(keyLabel, toolLabel,
// EVENT_LABELS)와 오류 판정(isErrorEvent)으로 바꾸는 지금 동작을 고정한다. 이 문장은
// frontend/src/components/EventTimeline.vue 에 그대로 보인다. note 가 없는 경우,
// null 인 경우, 빈 글인 경우를 구분하려고 결과 객체 전체를 deepStrictEqual 로 비교한다.
import { test } from 'node:test'
import assert from 'node:assert/strict'
import {
  EVENT_LABELS,
  describe as describeEvent,
  fieldsOf,
  formatValue,
  isErrorEvent,
  keyLabel,
  toolLabel,
} from '../src/utils/eventView.js'

// UUID 줄이기를 보려고 쓰는 주문 id 와 snapshot id 다. won 은 원 표기 기대값을
// 실행 환경 locale 로 만들고, ev 는 kind 와 data 만 있는 이벤트를 만든다.
const ORDER = '3f2a9c1e-7b4d-4e8a-9c2f-1a2b3c4d5e6f'
const SNAP = 'f47e7062-1111-4222-8333-444455556666'
const won = (n) => `${n.toLocaleString()}원`
const ev = (kind, data) => ({ kind, data })

test('keyLabel·toolLabel: 아는 이름은 화면 이름으로, 모르는 이름은 그대로', () => {
  assert.equal(keyLabel('order.id'), '주문 번호')
  assert.equal(keyLabel('inventory.reservable_quantity'), '예약 가능 수량')
  assert.equal(keyLabel('express'), '빠른 배송')
  assert.equal(keyLabel('unknown.key'), 'unknown.key')
  assert.equal(toolLabel('inventory.reserve'), '재고 예약 (inventory.reserve)')
  assert.equal(toolLabel('notify.send'), '고객 알림 (notify.send)')
  assert.equal(toolLabel('x.y'), 'x.y')
  assert.equal(toolLabel(undefined), undefined)
})

test('formatValue: 값 종류별 짧은 글', () => {
  assert.equal(formatValue('any', null), '없음')
  assert.equal(formatValue('any', undefined), '없음')
  assert.equal(formatValue('order.items', null), '없음')
  assert.equal(formatValue('notify.sent', true), '예')
  assert.equal(formatValue('notify.sent', false), '아니오')
  assert.equal(formatValue('order.items', [{ sku: 'A', qty: 1 }, { sku: 'B', qty: 2 }]), 'A × 1, B × 2')
  assert.equal(formatValue('items', [{ sku: 'A', qty: 3 }]), 'A × 3')
  assert.equal(formatValue('order.shipping', { country: 'KR', express: false }), 'KR · 일반 배송')
  assert.equal(formatValue('order.shipping', { country: 'US', express: true }), 'US · 빠른 배송')
  assert.equal(formatValue('order.total', 30000), won(30000))
  assert.equal(formatValue('amount', 1234567), won(1234567))
  assert.equal(formatValue('order.total', '30000'), '30000')
  assert.equal(formatValue('shipping.id', `ship_mock_${ORDER}`), 'ship_mock_3f2a9c1e…')
  assert.equal(formatValue('x', `${ORDER.toUpperCase()} 와 ${SNAP}`), '3F2A9C1E… 와 f47e7062…')
  assert.equal(formatValue('x', 'r_0123'), 'r_0123')
  assert.equal(formatValue('x', { a: 1, b: [2] }), '{"a":1,"b":[2]}')
  assert.equal(formatValue('x', [1, 2]), '[1,2]')
  assert.equal(formatValue('x', 42), '42')
  assert.equal(formatValue('x', 0), '0')
})

test('fieldsOf: { key: value } → [{ label, value }]', () => {
  assert.deepStrictEqual(fieldsOf(null), [])
  assert.deepStrictEqual(fieldsOf(undefined), [])
  assert.deepStrictEqual(fieldsOf({ 'order.id': ORDER, 'payment.status': 'PAID', extra: 1 }), [
    { label: '주문 번호', value: '3f2a9c1e…' },
    { label: '결제 상태', value: 'PAID' },
    { label: 'extra', value: '1' },
  ])
})

test('describe: 실행 시작·snapshot 고정·후보', () => {
  assert.deepStrictEqual(describeEvent(ev('EXECUTION_STARTED', { order_id: ORDER, mode: 'fixed' })), {
    title: '주문 3f2a9c1e… 처리를 시작했다',
    note: 'fixed 모드',
    fields: [],
  })
  assert.deepStrictEqual(describeEvent(ev('EXECUTION_STARTED', { order_id: 'o-1' })), {
    title: '주문 o-1 처리를 시작했다',
    note: 'undefined 모드',
    fields: [],
  })
  assert.deepStrictEqual(describeEvent(ev('SNAPSHOT_FIXED', { snapshot_id: SNAP, hash: 'abcdef0123456789abcdef' })), {
    title: 'Skill 파일 세트 f47e7062… 를 고정했다',
    fields: [{ label: '해시', value: 'abcdef0123456789…' }],
  })
  assert.deepStrictEqual(describeEvent(ev('SNAPSHOT_FIXED', {})), {
    title: 'Skill 파일 세트 undefined 를 고정했다',
    fields: [{ label: '해시', value: 'undefined…' }],
  })
  assert.deepStrictEqual(
    describeEvent(
      ev('CANDIDATES_OFFERED', {
        candidates: [
          { skill_id: 'order-flow', version: 1, description: '처리 순서' },
          { skill_id: 'payment', version: 2, description: '결제' },
        ],
      }),
    ),
    {
      title: '모델에게 Skill 후보 2개를 보여 줬다',
      fields: [
        { label: 'order-flow', value: 'v1 · 처리 순서' },
        { label: 'payment', value: 'v2 · 결제' },
      ],
    },
  )
  assert.deepStrictEqual(describeEvent(ev('CANDIDATES_OFFERED', {})), { title: '모델에게 Skill 후보 0개를 보여 줬다', fields: [] })
})

test('describe: 모델 입력·호출·응답·오류', () => {
  const inputs = {
    'order.id': ORDER,
    'order.customer': 'c_1001',
    'order.items': [{ sku: 'CAMP-LANTERN-LED', qty: 1 }],
    'order.payment_method': 'card',
    'order.shipping': { country: 'KR', express: false },
  }
  const fields = [
    { label: '주문 번호', value: '3f2a9c1e…' },
    { label: '고객', value: 'c_1001' },
    { label: '상품', value: 'CAMP-LANTERN-LED × 1' },
    { label: '결제 방식', value: 'card' },
    { label: '배송', value: 'KR · 일반 배송' },
  ]
  assert.deepStrictEqual(describeEvent(ev('DECISION_INPUT', { purpose: 'select', inputs })), {
    title: '모델에게 시작할 Skill 을 고르게 했다',
    fields,
  })
  assert.deepStrictEqual(
    describeEvent(ev('DECISION_INPUT', { purpose: 'tool', inputs, tool_definition: { name: 'payment.charge' } })),
    { title: '모델에게 결제 (payment.charge) 호출을 판단하게 했다', note: '이 단계가 받은 입력', fields },
  )
  assert.deepStrictEqual(describeEvent(ev('DECISION_INPUT', { purpose: 'tool' })), {
    title: '모델에게 undefined 호출을 판단하게 했다',
    note: '이 단계가 받은 입력',
    fields: [],
  })
  assert.deepStrictEqual(describeEvent(ev('MODEL_REQUEST', { attempt: 2 })), { title: '모델 API 호출 (2번째 시도)', fields: [] })
  assert.deepStrictEqual(
    describeEvent(ev('MODEL_RESPONSE', { raw_response: '{"skill":"order-flow"}', input_tokens: null, output_tokens: null })),
    { title: "모델이 'order-flow' 를 골랐다", note: '토큰 미관측', fields: [] },
  )
  const raw = JSON.stringify({ arguments: { order_id: ORDER, amount: 30000, method: 'card' }, tool: 'payment.charge' })
  assert.deepStrictEqual(describeEvent(ev('MODEL_RESPONSE', { raw_response: raw, input_tokens: 10, output_tokens: 5 })), {
    title: '모델이 결제 (payment.charge) 호출을 정했다',
    note: '토큰 10/5',
    fields: [
      { label: '주문 번호', value: '3f2a9c1e…' },
      { label: '금액', value: won(30000) },
      { label: '결제 방식', value: 'card' },
    ],
  })
  assert.deepStrictEqual(describeEvent(ev('MODEL_RESPONSE', { raw_response: 'garbage', input_tokens: 0, output_tokens: 0 })), {
    title: '모델 원래 응답',
    note: '토큰 0/0',
    fields: [{ label: '응답', value: 'garbage' }],
  })
  assert.deepStrictEqual(describeEvent(ev('MODEL_RESPONSE', { raw_response: '{"x":1}' })), {
    title: '모델 원래 응답',
    note: '토큰 미관측',
    fields: [{ label: '응답', value: '{"x":1}' }],
  })
  assert.deepStrictEqual(describeEvent(ev('MODEL_RESPONSE', { raw_response: null })), {
    title: '모델 원래 응답',
    note: '토큰 미관측',
    fields: [{ label: '응답', value: null }],
  })
  assert.deepStrictEqual(describeEvent(ev('DECISION_ERROR', { category: 'INVALID_JSON', attempt: 1 })), {
    title: '모델 응답 오류 INVALID_JSON (1번째 시도)',
    fields: [],
  })
})

test('describe: Skill 선택·해석·로드', () => {
  assert.deepStrictEqual(describeEvent(ev('SKILL_SELECTED', { raw_name: 'order-flow', resolved_skill_id: null })), {
    title: "선택된 이름: 'order-flow'",
    fields: [],
  })
  assert.deepStrictEqual(describeEvent(ev('SKILL_RESOLVED', { raw_name: 'order-flow', resolved_skill_id: 'order-flow' })), {
    title: "'order-flow' → order-flow 로 확인했다",
    fields: [],
  })
  assert.deepStrictEqual(describeEvent(ev('SKILL_LOAD_REJECTED', { requested_name: 'x', category: 'NOT_FOUND' })), {
    title: "'x' 파일을 불러오지 않았다",
    note: 'NOT_FOUND',
    fields: [],
  })
  assert.deepStrictEqual(describeEvent(ev('SKILL_LOAD_REJECTED', { requested_name: 'x' })), {
    title: "'x' 파일을 불러오지 않았다",
    note: undefined,
    fields: [],
  })
  assert.deepStrictEqual(
    describeEvent(ev('SKILL_LOADED', { path: 'skills/payment/SKILL.md', version: 1, hash: '0123456789abcdef0123' })),
    { title: 'skills/payment/SKILL.md (v1) 를 불러왔다', fields: [{ label: '파일 해시', value: '0123456789abcdef…' }] },
  )
})

test('describe: 단계 시작·끝', () => {
  assert.deepStrictEqual(
    describeEvent(ev('SKILL_STARTED', { step_no: 2, version: 1, input: { 'order.id': 'o-1', 'order.total': 5000 } })),
    {
      title: '2단계를 시작했다 (v1)',
      note: '이 단계가 받은 입력',
      fields: [
        { label: '주문 번호', value: 'o-1' },
        { label: '주문 금액', value: won(5000) },
      ],
    },
  )
  assert.deepStrictEqual(describeEvent(ev('SKILL_STARTED', { step_no: 1, version: 3 })), {
    title: '1단계를 시작했다 (v3)',
    note: '이 단계가 받은 입력',
    fields: [],
  })
  assert.deepStrictEqual(
    describeEvent(
      ev('SKILL_FINISHED', { step_no: 3, status: 'error', category: 'TOOL_FAILED', duration_ms: 5, output: { 'payment.status': 'DECLINED' } }),
    ),
    { title: '3단계가 오류로 끝났다: TOOL_FAILED', note: '5 ms', fields: [{ label: '결제 상태', value: 'DECLINED' }] },
  )
  assert.deepStrictEqual(
    describeEvent(
      ev('SKILL_FINISHED', {
        step_no: 2,
        status: 'ok',
        business_stop: true,
        final_status: 'REJECTED',
        duration_ms: 5,
        output: { 'inventory.reserved': false },
      }),
    ),
    { title: '2단계에서 업무 규칙대로 멈췄다: REJECTED', note: '5 ms', fields: [{ label: '재고 예약됨', value: '아니오' }] },
  )
  assert.deepStrictEqual(describeEvent(ev('SKILL_FINISHED', { step_no: 1, status: 'ok', duration_ms: 0, output: {} })), {
    title: '1단계를 마쳤다',
    note: '0 ms',
    fields: [],
  })
  assert.deepStrictEqual(describeEvent(ev('SKILL_FINISHED', { step_no: 1, status: 'ok' })), {
    title: '1단계를 마쳤다',
    note: null,
    fields: [],
  })
  assert.deepStrictEqual(describeEvent(ev('SKILL_FINISHED', { step_no: 4, status: 'error', category: 'X' })), {
    title: '4단계가 오류로 끝났다: X',
    note: null,
    fields: [],
  })
})

test('describe: 흐름 검사·도구 요청·결과·오류·재사용', () => {
  assert.deepStrictEqual(describeEvent(ev('FLOW_VALIDATED', { steps: ['a', 'b', 'c'] })), {
    title: '흐름 계약 검사를 통과했다',
    note: 'a → b → c',
    fields: [],
  })
  assert.deepStrictEqual(describeEvent(ev('FLOW_VALIDATED', {})), { title: '흐름 계약 검사를 통과했다', note: '', fields: [] })
  const args = { order_id: ORDER, items: [{ sku: 'A', qty: 1 }] }
  const argFields = [
    { label: '주문 번호', value: '3f2a9c1e…' },
    { label: '상품', value: 'A × 1' },
  ]
  assert.deepStrictEqual(
    describeEvent(ev('TOOL_REQUEST', { tool: 'inventory.release', arguments: { order_id: ORDER }, reason: 'PAYMENT_DECLINED' })),
    {
      title: '재고 예약 해제 (inventory.release) 요청',
      note: '사유: PAYMENT_DECLINED',
      fields: [{ label: '주문 번호', value: '3f2a9c1e…' }],
    },
  )
  assert.deepStrictEqual(describeEvent(ev('TOOL_REQUEST', { tool: 'inventory.reserve', arguments: args })), {
    title: '재고 예약 (inventory.reserve) 요청',
    note: null,
    fields: argFields,
  })
  assert.deepStrictEqual(describeEvent(ev('TOOL_REQUEST', { tool: 'x.y' })), { title: 'x.y 요청', note: null, fields: [] })
  assert.deepStrictEqual(
    describeEvent(
      ev('TOOL_RESULT', {
        tool: 'payment.charge',
        arguments: args,
        result: { context: { 'payment.status': 'PAID', 'payment.id': `pay_mock_${ORDER}` }, final_status: 'PROCESSING' },
        elapsed_ms: 3,
      }),
    ),
    {
      title: '결제 (payment.charge) 결과',
      note: '주문 상태 PROCESSING · 3 ms',
      fields: [
        { label: '결제 상태', value: 'PAID' },
        { label: '결제 번호', value: 'pay_mock_3f2a9c1e…' },
      ],
    },
  )
  assert.deepStrictEqual(
    describeEvent(ev('TOOL_RESULT', { tool: 'inventory.release', arguments: args, result: { 'inventory.reserved': false } })),
    { title: '재고 예약 해제 (inventory.release) 결과', note: '', fields: [{ label: '재고 예약됨', value: '아니오' }] },
  )
  assert.deepStrictEqual(describeEvent(ev('TOOL_RESULT', { tool: 'payment.charge', result: { final_status: 'HOLD' }, elapsed_ms: 0 })), {
    title: '결제 (payment.charge) 결과',
    note: '주문 상태 HOLD · 0 ms',
    fields: [],
  })
  assert.deepStrictEqual(describeEvent(ev('TOOL_RESULT', { tool: 'notify.send', elapsed_ms: 7 })), {
    title: '고객 알림 (notify.send) 결과',
    note: '7 ms',
    fields: [],
  })
  assert.deepStrictEqual(describeEvent(ev('TOOL_ERROR', { tool: 'payment.charge', category: 'TIMEOUT', arguments: args })), {
    title: '결제 (payment.charge) 실패',
    note: 'TIMEOUT',
    fields: argFields,
  })
  assert.deepStrictEqual(describeEvent(ev('TOOL_REPLAY', { tool: 'payment.charge', arguments: args })), {
    title: '결제 (payment.charge) — 이미 실행한 결과를 다시 썼다',
    fields: argFields,
  })
})

test('describe: 단계 결과 전달·업무 종료·실행 오류·중단·끝', () => {
  assert.deepStrictEqual(describeEvent(ev('SKILL_COMPLETED', { declared_output: { 'order.total': 30000, 'order.id': 'o-1' } })), {
    title: '다음 단계로 넘긴 값',
    fields: [
      { label: '주문 금액', value: won(30000) },
      { label: '주문 번호', value: 'o-1' },
    ],
  })
  assert.deepStrictEqual(describeEvent(ev('BUSINESS_STOP', { final_status: 'REJECTED' })), {
    title: '업무 규칙대로 멈췄다: REJECTED',
    note: '재고 부족 등 정상적인 거절',
    fields: [],
  })
  assert.deepStrictEqual(describeEvent(ev('BUSINESS_STOP', { final_status: 'HOLD' })), {
    title: '업무 규칙대로 멈췄다: HOLD',
    note: '결제 거절 등 정상적인 보류',
    fields: [],
  })
  assert.deepStrictEqual(
    describeEvent(
      ev('EXECUTION_ERROR', {
        category: 'CONTRACT_VIOLATION',
        skill_id: 'payment',
        phase: 'static_validation',
        missing_inputs: ['inventory.reserved', 'order.total'],
        missing_dependencies: ['inventory-check'],
        reason: '사유 문장',
        message: 'Skill에 필요한 입력 또는 선행 단계가 충족되지 않았습니다.',
      }),
    ),
    {
      title: '실행 오류: CONTRACT_VIOLATION',
      note: 'Skill에 필요한 입력 또는 선행 단계가 충족되지 않았습니다.',
      fields: [
        { label: '검사 단계', value: '실행 전 정적 검사' },
        { label: '못 받은 입력', value: 'inventory.reserved, order.total' },
        { label: '끝나지 않은 앞 단계', value: 'inventory-check' },
        { label: '사유', value: '사유 문장' },
      ],
    },
  )
  assert.deepStrictEqual(describeEvent(ev('EXECUTION_ERROR', { category: 'MISSING_SKILL_OUTPUT', phase: 'runtime', missing_inputs: [] })), {
    title: '실행 오류: MISSING_SKILL_OUTPUT',
    note: undefined,
    fields: [{ label: '검사 단계', value: '실행 중 입력 검사' }],
  })
  assert.deepStrictEqual(describeEvent(ev('EXECUTION_INTERRUPTED', {})), {
    title: '서버 재시작으로 중단된 실행을 정리했다',
    fields: [],
  })
  assert.deepStrictEqual(describeEvent(ev('EXECUTION_FINISHED', { completion: 'COMPLETED', final_status: 'SHIPPED' })), {
    title: '실행 끝: SHIPPED',
    note: 'COMPLETED',
    fields: [],
  })
  assert.deepStrictEqual(
    describeEvent(ev('EXECUTION_FINISHED', { completion: 'FAILED', final_status: 'FAILED', error_category: 'CONTRACT_VIOLATION' })),
    { title: '실행 끝: FAILED', note: 'FAILED · CONTRACT_VIOLATION', fields: [] },
  )
  assert.deepStrictEqual(describeEvent(ev('EXECUTION_FINISHED', { completion: 'COMPLETED', final_status: null, error_category: null })), {
    title: '실행 끝: 상태 없음',
    note: 'COMPLETED',
    fields: [],
  })
})

test('describe: 모르는 종류는 종류 이름을 제목으로, data 가 없으면 빈 객체로 읽는다', () => {
  assert.deepStrictEqual(describeEvent(ev('SOMETHING_NEW', { a: 1 })), { title: 'SOMETHING_NEW', fields: [] })
  assert.deepStrictEqual(describeEvent({ kind: 'MODEL_REQUEST' }), { title: '모델 API 호출 (undefined번째 시도)', fields: [] })
  assert.deepStrictEqual(describeEvent({ kind: 'BUSINESS_STOP', data: null }), {
    title: '업무 규칙대로 멈췄다: undefined',
    note: '결제 거절 등 정상적인 보류',
    fields: [],
  })
})

test('EVENT_LABELS: 이벤트 종류 23개의 한글 라벨', () => {
  assert.deepStrictEqual(EVENT_LABELS, {
    EXECUTION_STARTED: '실행 시작',
    SNAPSHOT_FIXED: '실행 파일 고정',
    CANDIDATES_OFFERED: '실제 제공한 후보',
    DECISION_INPUT: '실행기에 제공한 입력',
    MODEL_REQUEST: 'API 호출',
    MODEL_RESPONSE: '원래 응답',
    DECISION_ERROR: '응답 오류',
    SKILL_SELECTED: 'Skill 선택',
    SKILL_LOAD_REJECTED: '파일 로드 거절',
    SKILL_RESOLVED: 'Skill 이름 해석',
    SKILL_LOADED: '실제 파일 로드',
    SKILL_STARTED: '단계 시작',
    SKILL_FINISHED: '단계 끝',
    FLOW_VALIDATED: '흐름 계약 검사',
    TOOL_REQUEST: '도구 요청',
    TOOL_RESULT: '도구 실행 결과',
    TOOL_ERROR: '도구 오류',
    TOOL_REPLAY: '멱등 결과 재사용',
    SKILL_COMPLETED: '단계 결과 전달',
    BUSINESS_STOP: '정상 업무 종료',
    EXECUTION_ERROR: '실행 오류',
    EXECUTION_INTERRUPTED: '중단 실행 복구',
    EXECUTION_FINISHED: '실행 종료',
  })
})

test('isErrorEvent: 종류 이름에 ERROR 나 REJECTED 가 들어 있으면 오류 이벤트', () => {
  const errors = Object.keys(EVENT_LABELS)
    .filter((kind) => isErrorEvent({ kind }))
    .sort()
  assert.deepStrictEqual(errors, ['DECISION_ERROR', 'EXECUTION_ERROR', 'SKILL_LOAD_REJECTED', 'TOOL_ERROR'])
  assert.equal(isErrorEvent({ kind: 'BUSINESS_STOP' }), false)
  assert.equal(isErrorEvent({ kind: 'CUSTOM_REJECTED_X' }), true)
})
