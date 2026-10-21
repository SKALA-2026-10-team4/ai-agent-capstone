// 모든 HTTP 호출의 입구. 상대 경로만 쓰고 vite 프록시가 /api → order-agent, /service → SkillOps service 로 넘긴다.
// 로그인 토큰은 /service 요청에만 붙인다(order-agent 는 인증이 없는 별도 앱이라 토큰을 보내지 않는다).
// `npm run dev:mock` (vite --mode mock) 이면 서버 없이 src/mocks 가 같은 형식으로 응답한다.
import { ApiError } from './error'

export const USE_MOCK =
  import.meta.env.MODE === 'mock' || import.meta.env.VITE_USE_MOCK === 'true'

const TIMEOUT_MS = { GET: 5000, POST: 15000 }

// main.js 가 auth store 와 연결한다(스토어를 직접 import 하면 순환 참조가 생긴다).
let auth = { token: () => null, onUnauthorized: () => {} }
export const connectAuth = (handlers) => (auth = handlers)
const needsToken = (url) => url.startsWith('/service/')

export async function request(method, url, { body, headers = {} } = {}) {
  const token = needsToken(url) ? auth.token() : null
  if (token) headers = { ...headers, Authorization: `Bearer ${token}` }

  if (USE_MOCK) {
    // 동적 import 라 실서버 모드에서는 mock 청크를 내려받지 않는다.
    const { handleMock } = await import('@/mocks')
    try {
      return await handleMock(method, url, body, headers)
    } catch (e) {
      if (e.status === 401 && needsToken(url) && token) auth.onUnauthorized()
      throw e
    }
  }

  const controller = new AbortController()
  const timer = setTimeout(() => controller.abort(), TIMEOUT_MS[method] ?? 5000)
  const init = { method, headers: { ...headers }, signal: controller.signal }
  if (body !== undefined) {
    init.headers['Content-Type'] = 'application/json'
    init.body = JSON.stringify(body)
  }

  let res
  try {
    res = await fetch(url, init)
  } catch (e) {
    const message = e.name === 'AbortError' ? '응답 시간이 초과되었습니다.' : '서버에 연결할 수 없습니다.'
    throw new ApiError(0, message, { code: 'NETWORK_ERROR' })
  } finally {
    clearTimeout(timer)
  }

  const text = await res.text()
  let data
  try {
    data = text ? JSON.parse(text) : null
  } catch {
    data = null
  }
  if (!res.ok) {
    // 만료·위조된 토큰. 로그인 요청 자체의 401(비밀번호 틀림)은 그대로 화면에 보여 준다.
    if (res.status === 401 && token) auth.onUnauthorized()
    throw new ApiError(res.status, data?.message ?? data?.detail ?? `${method} ${url} → ${res.status}`, {
      code: data?.code ?? null,
      orderId: data?.order_id ?? null,
      fieldErrors: data?.field_errors ?? [],
      body: data,
    })
  }
  return data
}

export const get = (url) => request('GET', url)
export const post = (url, body, headers) => request('POST', url, { body: body ?? {}, headers })
