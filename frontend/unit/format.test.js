// frontend/src/utils/format.js 의 표시 형식 도우미를 보호한다. 예전에
// frontend/src/utils/eventView.js, frontend/src/utils/traceRun.js,
// frontend/src/stores/workspace.js, frontend/src/views/OrderView.vue 에
// 흩어져 있던 식과 같은 결과를 내는지 확인한다.
import { test } from 'node:test'
import assert from 'node:assert/strict'
import { UUID_RE, dateTime, isUuid, shortId, shortUuid, shortenUuidsIn, won } from '../src/utils/format.js'

// 옮기기 전 코드를 그대로 복사한 비교 기준이다.
const OLD_UUID = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i
const oldShort = (id) => (typeof id === 'string' && OLD_UUID.test(id) ? `${id.slice(0, 8)}…` : id)
const oldShortenIn = (value) =>
  value.replace(/[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}/gi, (m) => oldShort(m))
const oldWon = (n) => `${n.toLocaleString()}원`

// UUID, 대문자 UUID, UUID 가 든 글, UUID 와 닮았지만 아닌 글을 섞은 입력이다.
const UUID = '3f2a9c1e-7b4d-4e8a-9c2f-1a2b3c4d5e6f'
const SAMPLES = [
  UUID,
  UUID.toUpperCase(),
  `pay_mock_${UUID}`,
  `${UUID} 와 ${UUID.toUpperCase()}`,
  ` ${UUID}`,
  `${UUID}x`,
  'r_0123456789abcdef0123456789abcdef',
  '3f2a9c1e-7b4d-4e8a-9c2f-1a2b3c4d5e6',
  '',
  'order-flow',
]

test('UUID_RE·isUuid: 문자열 전체가 UUID 일 때만 참(대소문자 무시)', () => {
  for (const s of SAMPLES) {
    assert.equal(UUID_RE.test(s), OLD_UUID.test(s), s)
    assert.equal(isUuid(s), OLD_UUID.test(s), s)
  }
  assert.equal(isUuid(undefined), false)
  assert.equal(isUuid(null), false)
  assert.equal(isUuid(123), false)
})

test('shortUuid: UUID 면 앞 8자 + …, 아니면 값을 그대로 돌려준다', () => {
  for (const s of [...SAMPLES, undefined, null, 42, { id: UUID }]) assert.deepStrictEqual(shortUuid(s), oldShort(s))
  assert.equal(shortUuid(UUID), '3f2a9c1e…')
  assert.equal(shortUuid(UUID.toUpperCase()), '3F2A9C1E…')
})

test('shortenUuidsIn: 문자열 속 UUID 를 모두 줄인다', () => {
  for (const s of SAMPLES) assert.equal(shortenUuidsIn(s), oldShortenIn(s), s)
  assert.equal(shortenUuidsIn(`pay_mock_${UUID}`), 'pay_mock_3f2a9c1e…')
  assert.equal(shortenUuidsIn(`${UUID} 와 ${UUID.toUpperCase()}`), '3f2a9c1e… 와 3F2A9C1E…')
  // 전역 정규식을 다시 써도 결과가 같다.
  assert.equal(shortenUuidsIn(UUID), shortenUuidsIn(UUID))
})

test('shortId: 앞 8자', () => {
  assert.equal(shortId(UUID), '3f2a9c1e')
  assert.equal(shortId('abc'), 'abc')
  for (const s of SAMPLES) assert.equal(shortId(s), s.slice(0, 8))
})

test('won: 실행 환경 locale 의 자릿수 구분 + 원', () => {
  for (const n of [0, 12000, 30000, 1234567, -500, 1.5]) assert.equal(won(n), oldWon(n))
  assert.equal(won(30000), `${(30000).toLocaleString()}원`)
})

test('dateTime: 실행 환경 시간대의 yyyy.mm.dd HH:mm', () => {
  // 시간대와 상관없이 맞도록 로컬 시각으로 만든 값을 넣는다.
  assert.equal(dateTime(new Date(2026, 9, 21, 5, 7).toISOString()), '2026.10.21 05:07')
  assert.equal(dateTime(new Date(2026, 0, 1, 23, 59).toISOString()), '2026.01.01 23:59')
  // 값이 없거나 읽을 수 없으면 빈 글자다.
  for (const v of [null, undefined, '', 'not-a-date']) assert.equal(dateTime(v), '', String(v))
})
