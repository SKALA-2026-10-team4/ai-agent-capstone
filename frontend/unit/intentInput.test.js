// frontend/src/utils/intentInput.js 가 Skill 편집 화면의 변경 의도 입력을 저장 요청의 intent
// (docs/plans/2026-10-26-behavior-edit-root-fix.md 2.3 절)로 바꾸는 지금 동작을 고정한다. 한도(500자,
// 10줄, 한 줄 10개), 범위 없음(* 나 빈 칸), 따옴표 안의 구분자, 문구를 확인한다.
import { test } from 'node:test'
import assert from 'node:assert/strict'
import {
  INTENT_CHECKS_MAX,
  INTENT_EXPECTATIONS_MAX,
  INTENT_TEXT_MAX,
  SCOPE_KEYS,
  buildIntent,
  expectationLines,
  intentHasProblems,
  parseExpectationLine,
  parseExpectations,
} from '../src/utils/intentInput.js'

const CARD = "order.payment_method == 'card'"
const HOLD = "order.final_status in ['HOLD', 'REJECTED']"
const UNPAID = 'payment.status == null'

test('한도와 범위 값 이름', () => {
  assert.equal(INTENT_TEXT_MAX, 500)
  assert.equal(INTENT_EXPECTATIONS_MAX, 10)
  assert.equal(INTENT_CHECKS_MAX, 10)
  assert.deepStrictEqual(SCOPE_KEYS, [
    'order.payment_method',
    'order.customer',
    'order.shipping.country',
    'order.shipping.express',
    'order.total',
  ])
})

test('buildIntent: 의도 글이 비면 intent 를 보내지 않는다', () => {
  for (const text of ['', '   ', null, undefined]) {
    assert.deepStrictEqual(buildIntent(text, ''), { intent: null, textError: null, lineErrors: [] })
  }
  assert.equal(intentHasProblems(buildIntent('', '\n  \n')), false)
})

test('buildIntent: 의도 글만 있으면 expectations 는 빈 목록이다(SkillOps 가 추정)', () => {
  assert.deepStrictEqual(buildIntent('  선입금 주문만 결제한다  ', ''), {
    intent: { text: '선입금 주문만 결제한다', expectations: [] },
    textError: null,
    lineErrors: [],
  })
})

test('buildIntent: 계획 2.3 절 예시를 그대로 만든다', () => {
  const lines = [`${CARD} => ${HOLD}; ${UNPAID}`, '', "order.payment_method == 'prepaid' => order.final_status == 'SHIPPED'"].join('\n')
  assert.deepStrictEqual(buildIntent('선입금 주문만 결제하고 카드 주문은 결제하지 않는다', lines).intent, {
    text: '선입금 주문만 결제하고 카드 주문은 결제하지 않는다',
    expectations: [
      { scope: CARD, checks: [HOLD, UNPAID] },
      { scope: "order.payment_method == 'prepaid'", checks: ["order.final_status == 'SHIPPED'"] },
    ],
  })
})

test('parseExpectationLine: 범위가 비었거나 * 면 null, 끝의 ; 와 빈 조각은 버린다', () => {
  assert.deepStrictEqual(parseExpectationLine(`=> ${UNPAID}`), { expectation: { scope: null, checks: [UNPAID] }, error: null })
  assert.deepStrictEqual(parseExpectationLine(` * =>${UNPAID};; `), { expectation: { scope: null, checks: [UNPAID] }, error: null })
  assert.deepStrictEqual(parseExpectationLine("order.total >= 100000 => order.final_status == 'HOLD';").expectation, {
    scope: 'order.total >= 100000',
    checks: ["order.final_status == 'HOLD'"],
  })
  // 따옴표 안의 ; 와 => 는 나누지 않는다.
  assert.deepStrictEqual(parseExpectationLine("order.customer == 'a=>b' => notify.channel == 'x;y'").expectation, {
    scope: "order.customer == 'a=>b'",
    checks: ["notify.channel == 'x;y'"],
  })
})

test('parseExpectationLine: 틀린 줄의 문구', () => {
  const err = (line) => parseExpectationLine(line).error
  assert.equal(err(UNPAID), "'범위 => 확인 조건' 모양으로 적어 주세요. 범위가 없으면 '* => …' 로 시작해요.")
  assert.equal(err(`order.total>=1 => ${UNPAID}`), '범위 조건을 읽을 수 없어요: order.total>=1 (예: order.payment_method == \'card\')')
  assert.equal(
    err(`payment.status == 'PAID' => ${UNPAID}`),
    `범위의 값 이름 payment.status 는 쓸 수 없어요. 시험 주문의 처음 값(${SCOPE_KEYS.join(', ')}) 가운데 하나를 그대로 적어 주세요.`,
  )
  assert.equal(err(`${CARD} =>  ; `), '확인 조건을 하나 이상 적어 주세요.')
  assert.equal(err(`${CARD} => ${UNPAID}; payment.status`), '확인 조건을 읽을 수 없어요: payment.status (예: payment.status == null)')
  // => 가 두 번이면 뒤쪽은 확인 조건 글로 남아 읽지 못한다.
  assert.equal(err(`${CARD} => ${UNPAID} => x == 1`), `확인 조건을 읽을 수 없어요: ${UNPAID} => x == 1 (예: payment.status == null)`)
  const many = Array.from({ length: 11 }, (_, i) => `x == ${i}`).join('; ')
  assert.equal(err(`* => ${many}`), '확인 조건은 한 줄에 10개까지예요(지금 11개).')
  assert.equal(parseExpectationLine(`* => ${Array.from({ length: 10 }, (_, i) => `x == ${i}`).join('; ')}`).error, null)
})

test('parseExpectationLine: 범위의 값 이름은 다섯 키와 정확히 같아야 한다(계획 2.10 V3-2)', () => {
  const scopes = {
    'order.payment_method': "order.payment_method == 'card'",
    'order.customer': "order.customer == 'c_declined'",
    'order.shipping.country': "order.shipping.country != 'KR'",
    'order.shipping.express': 'order.shipping.express == true',
    'order.total': 'order.total >= 100000',
  }
  assert.deepStrictEqual(Object.keys(scopes), SCOPE_KEYS)
  for (const scope of Object.values(scopes)) {
    assert.deepStrictEqual(parseExpectationLine(`${scope} => ${UNPAID}`), { expectation: { scope, checks: [UNPAID] }, error: null }, scope)
  }
  // 앞부분(order.shipping), 하위 경로, 대소문자 차이, 예약 키, 다른 단계의 값은 모두 막는다.
  for (const key of ['order.shipping', 'order.shipping.country.code', 'order.total.amount', 'Order.total', 'order.final_status', 'order.items', 'payment.status']) {
    const { expectation, error } = parseExpectationLine(`${key} == null => ${UNPAID}`)
    assert.equal(expectation, null, key)
    assert.ok(error.startsWith(`범위의 값 이름 ${key} 는 쓸 수 없어요.`), error)
  }
})

test('buildIntent: 범위 값 이름이 틀린 줄은 그 줄 번호로 알리고 저장을 막는다', () => {
  const built = buildIntent('해외 배송은 받지 않는다', `order.shipping.country != 'KR' => ${UNPAID}\norder.shipping != null => ${UNPAID}`)
  assert.equal(built.intent, null)
  assert.equal(built.textError, null)
  assert.deepStrictEqual(built.lineErrors.map((e) => e.line), [2])
  assert.ok(built.lineErrors[0].message.startsWith('범위의 값 이름 order.shipping 는 쓸 수 없어요.'))
  assert.equal(intentHasProblems(built), true)
})

test('parseExpectations: 줄 번호는 빈 줄을 포함해 1부터, 줄 수가 넘으면 맨 앞에 알린다', () => {
  const { expectations, errors } = parseExpectations(`* => ${UNPAID}\r\n\nbad line\n${CARD} => nope`)
  assert.deepStrictEqual(expectations, [{ scope: null, checks: [UNPAID] }])
  assert.deepStrictEqual(errors.map((e) => e.line), [3, 4])
  const eleven = Array.from({ length: 11 }, () => `* => ${UNPAID}`).join('\n')
  const over = parseExpectations(eleven)
  assert.equal(over.expectations.length, 11)
  assert.deepStrictEqual(over.errors, [{ line: null, message: '기대 결과는 10줄까지 적을 수 있어요(지금 11줄).' }])
  assert.deepStrictEqual(parseExpectations(undefined), { expectations: [], errors: [] })
})

test('buildIntent: 문제가 있으면 intent 는 null 이고 문구를 돌려준다', () => {
  const noText = buildIntent('', `* => ${UNPAID}`)
  assert.deepStrictEqual(noText, { intent: null, textError: '기대 결과를 보내려면 의도를 한 줄로 먼저 적어 주세요.', lineErrors: [] })
  assert.equal(intentHasProblems(noText), true)
  // 틀린 줄만 적고 의도 글이 없어도 의도 글을 먼저 적게 한다.
  assert.equal(buildIntent('', 'bad').textError, '기대 결과를 보내려면 의도를 한 줄로 먼저 적어 주세요.')

  const long = buildIntent('가'.repeat(501), '')
  assert.equal(long.intent, null)
  assert.equal(long.textError, '의도는 500자 이하로 적어 주세요(지금 501자).')
  assert.ok(buildIntent('가'.repeat(500), '').intent)

  const badLine = buildIntent('의도', 'bad')
  assert.equal(badLine.intent, null)
  assert.equal(badLine.textError, null)
  assert.equal(badLine.lineErrors.length, 1)
  assert.equal(intentHasProblems(badLine), true)
})

test('expectationLines: 기대 결과를 입력 모양으로 되돌리고 다시 읽으면 같다', () => {
  const expectations = [
    { scope: CARD, checks: [HOLD, UNPAID] },
    { scope: null, checks: ['order.final_status != null'] },
  ]
  const lines = expectationLines(expectations)
  assert.deepStrictEqual(lines, [`${CARD} => ${HOLD}; ${UNPAID}`, '* => order.final_status != null'])
  assert.deepStrictEqual(parseExpectations(lines.join('\n')).expectations, expectations)
  assert.deepStrictEqual(expectationLines(null), [])
})
