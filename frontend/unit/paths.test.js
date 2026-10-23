// frontend/src/router/paths.js 의 첫 화면과 로그인 경로 규칙을 보호한다. 예전
// frontend/src/router/index.js, frontend/src/main.js,
// frontend/src/views/LoginView.vue 에 있던 식과 같은지 확인한다.
import { test } from 'node:test'
import assert from 'node:assert/strict'
import { HOME_PATH, LOGIN_PATH, isLoginPath, loginRedirect, safeNext } from '../src/router/paths.js'

test('경로 상수', () => {
  assert.equal(HOME_PATH, '/proposals')
  assert.equal(LOGIN_PATH, '/login')
})

test('loginRedirect: 로그인 화면 + next', () => {
  assert.deepStrictEqual(loginRedirect('/orders?x=1'), { path: '/login', query: { next: '/orders?x=1' } })
})

test('isLoginPath: /login 으로 시작하는 주소', () => {
  for (const p of ['/login', '/login?next=/orders', '/loginx', '/proposals', '/', '/x/login']) {
    assert.equal(isLoginPath(p), p.startsWith('/login'), p)
  }
})

test('safeNext: / 로 시작하는 글자만 믿고 아니면 첫 화면', () => {
  // 예전 frontend/src/views/LoginView.vue 의 식이다.
  const old = (next) => (typeof next === 'string' && next.startsWith('/') ? next : '/proposals')
  for (const next of ['/orders', '/skills/inventory-check', '//evil.example', 'https://evil.example', '', undefined, null, ['/orders'], 42]) {
    assert.deepStrictEqual(safeNext(next), old(next))
  }
  assert.equal(safeNext(undefined), '/proposals')
})
