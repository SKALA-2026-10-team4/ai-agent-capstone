// frontend/src/utils/condition.js 가 조건 문법 v2 를 service Check, 주문 Agent RuleCondition 과 같게
// 읽고 평가하는지 공용 사례 docs/contracts/examples/condition-cases.json 으로 확인한다. 사례 파일은
// 조정 담당이 고치므로 여기서는 읽기만 한다.
import { test } from 'node:test'
import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import { holds, isReadableCondition, lookup, readCondition, splitOutsideQuotes } from '../src/utils/condition.js'

const cases = JSON.parse(readFileSync(new URL('../../docs/contracts/examples/condition-cases.json', import.meta.url), 'utf8'))

test('공용 사례: evaluate 의 조건은 모두 읽히고 holds 가 같다', () => {
  assert.ok(cases.evaluate.length > 0)
  for (const c of cases.evaluate) {
    const read = readCondition(c.text)
    assert.ok(read, `읽혀야 함: ${c.text}`)
    assert.equal(holds(read, cases.context), c.holds, c.text)
  }
})

test('공용 사례: unreadable 의 조건은 읽지 않는다', () => {
  assert.ok(cases.unreadable.length > 0)
  for (const text of cases.unreadable) assert.equal(readCondition(text), null, JSON.stringify(text))
})

test('readCondition: 값 이름·연산자·값을 나누고 값 종류를 읽는다', () => {
  assert.deepStrictEqual(readCondition("order.payment_method != 'prepaid'"), {
    text: "order.payment_method != 'prepaid'",
    key: 'order.payment_method',
    op: '!=',
    value: 'prepaid',
  })
  assert.deepStrictEqual(readCondition('order.total >= -12.5').value, -12.5)
  assert.deepStrictEqual(readCondition("x in ['a, b', 1, true, null]").value, ['a, b', 1, true, null])
  assert.deepStrictEqual(readCondition('x in [ ]').value, [])
  assert.equal(readCondition("x in ['a', ]"), null)
  assert.equal(readCondition('x == [1]'), null)
  assert.equal(readCondition(null), null)
  assert.equal(readCondition(3), null)
  assert.equal(isReadableCondition('payment.status == null'), true)
  assert.equal(isReadableCondition('payment.status'), false)
})

test('lookup: 정확한 이름 → 가장 긴 앞부분의 객체 경로, 없으면 null', () => {
  const ctx = { 'order.shipping': { country: 'KR', nested: { a: 1 } }, 'order.shipping.country': 'US', 'a.b': 5 }
  assert.equal(lookup(ctx, 'order.shipping.country'), 'US')
  assert.equal(lookup(ctx, 'order.shipping.nested.a'), 1)
  assert.equal(lookup(ctx, 'order.shipping.missing'), null)
  assert.equal(lookup(ctx, 'a.b.c'), null)
  assert.equal(lookup(ctx, 'nothing'), null)
  // 가장 긴 앞부분(a.b)이 객체가 아니면 더 짧은 앞부분으로 다시 찾지 않는다.
  assert.equal(lookup({ a: { b: { c: 1 } }, 'a.b': 2 }, 'a.b.c'), null)
})

test('splitOutsideQuotes: 따옴표 안의 구분자는 나누지 않는다', () => {
  assert.deepStrictEqual(splitOutsideQuotes("a; 'b; c'; d", ';'), ['a', " 'b; c'", ' d'])
  assert.deepStrictEqual(splitOutsideQuotes("x == 'a=>b' => y", '=>'), ["x == 'a=>b' ", ' y'])
  assert.deepStrictEqual(splitOutsideQuotes('', ','), [''])
})
