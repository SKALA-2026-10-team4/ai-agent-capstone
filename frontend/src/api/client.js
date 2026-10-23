// 모든 HTTP 호출이 지나가는 입구다. frontend/src/api 의 auth.js, orderAgent.js, proposals.js,
// diagnoses.js 가 get 과 post 로 이 모듈을 부른다. 경로는 상대 경로만 쓰고,
// frontend/vite.config.js 의 프록시가 /api 요청을 order-agent 로, /service 요청을 SkillOps
// service 로 넘긴다. 로그인 토큰은 /service 요청에만 붙인다. order-agent 는 인증이 없는 별도
// 앱이라 토큰을 보내지 않는다. npm run dev:mock(vite --mode mock)으로 띄우면 서버 없이
// frontend/src/mocks/index.js 가 같은 형식으로 응답한다. HTTP 오류와 연결 실패는
// frontend/src/api/error.js 의 ApiError 로 바꿔 던지므로 화면은 오류 모양 하나만 다루면 된다.
import { ApiError } from './error'
import { HTTP_TIMEOUT_FALLBACK_MS, HTTP_TIMEOUT_MS } from '@/config'

// mock 모드인지 나타낸다. vite --mode mock 으로 띄웠거나 VITE_USE_MOCK 이 true 이면 참이다.
// frontend/src/stores/workspace.js(저장 막기와 저장소 키),
// frontend/src/components/AppSidebar.vue(mock 표시), frontend/src/views/SkillDraftView.vue(저장
// 버튼), frontend/src/views/DiagnosisView.vue(조회 생략)가 이 값으로 분기한다.
export const USE_MOCK =
  import.meta.env.MODE === 'mock' || import.meta.env.VITE_USE_MOCK === 'true'

// 토큰 공급자와 401 처리기다. frontend/src/main.js 가 connectAuth 로
// frontend/src/stores/auth.js 의 token() 과, 로그아웃한 뒤 로그인 화면으로 보내는 처리기를 넘긴다.
// 이 모듈이 스토어를 직접 import 하면 스토어가 frontend/src/api/auth.js 를 거쳐 다시 이 모듈을
// import 하는 순환 참조가 생기므로 바깥에서 주입한다. 주입하기 전에는 토큰이 없고 401 도 무시한다.
let auth = { token: () => null, onUnauthorized: () => {} }
export const connectAuth = (handlers) => (auth = handlers)
// /service/ 로 시작하는 요청만 로그인 토큰이 필요하다.
const needsToken = (url) => url.startsWith('/service/')

// 경로 템플릿의 보간 값마다 encodeURIComponent 를 적용하는 태그 함수다. 예를 들어
// apiPath`/api/orders/${orderId}` 처럼 쓴다. id 에 슬래시나 물음표가 섞여도 다른
// 경로로 새지 않는다. frontend/src/api 의 orderAgent.js, proposals.js, diagnoses.js 가
// 보간이 있는 경로를 모두 이 함수로 만들고, frontend/src/mocks/index.js 의
// handleMock 은 잡은 조각을 decodeURIComponent 로 되돌린다.
export const apiPath = (strings, ...values) =>
  strings.reduce((path, part, i) => path + part + (i < values.length ? encodeURIComponent(values[i]) : ''), '')

/**
 * /service 요청이면 로그인 토큰을 Authorization 헤더(Bearer)로 붙인다.
 * 토큰이 없거나 /api 요청이면 headers 를 그대로 둔다. 함께 돌려주는
 * token 은 401 을 받았을 때 토큰을 실어 보낸 요청이었는지 가리는 데 쓴다.
 */
function attachToken(url, headers) {
  const token = needsToken(url) ? auth.token() : null
  return { token, headers: token ? { ...headers, Authorization: `Bearer ${token}` } : headers }
}

/**
 * mock 모드에서 서버 대신 frontend/src/mocks/index.js 의 handleMock 으로 응답을 받는다.
 * 토큰을 실은 /service 요청이 401 을 받으면 실서버 모드와 같이 onUnauthorized 를 부른다.
 */
async function requestMock(method, url, body, headers, token) {
  // 동적 import 라 실서버 모드에서는 mock 청크를 내려받지 않는다.
  const { handleMock } = await import('@/mocks')
  try {
    return await handleMock(method, url, body, headers)
  } catch (e) {
    if (e.status === 401 && needsToken(url) && token) auth.onUnauthorized()
    throw e
  }
}

/**
 * fetch 로 요청을 보낸다. 시간 제한은 frontend/src/config.js 의 HTTP_TIMEOUT_MS(메서드별)이고 응답
 * 헤더를 받을 때까지만 잰다. 본문이 있으면 JSON 으로 보내고 Content-Type 을 붙인다. 연결 실패와
 * 시간 초과는 status 0, code NETWORK_ERROR 인 ApiError 로 바꾼다.
 * frontend/src/api/orderAgent.js 의 createOrder 는 이 status 0 을 보고 주문 결과를 다시 조회한다.
 */
async function send(method, url, body, headers) {
  const controller = new AbortController()
  const timer = setTimeout(() => controller.abort(), HTTP_TIMEOUT_MS[method] ?? HTTP_TIMEOUT_FALLBACK_MS)
  const init = { method, headers: { ...headers }, signal: controller.signal }
  if (body !== undefined) {
    init.headers['Content-Type'] = 'application/json'
    init.body = JSON.stringify(body)
  }

  try {
    return await fetch(url, init)
  } catch (e) {
    const message = e.name === 'AbortError' ? '응답 시간이 초과되었습니다.' : '서버에 연결할 수 없습니다.'
    throw new ApiError(0, message, { code: 'NETWORK_ERROR' })
  } finally {
    clearTimeout(timer)
  }
}

// 응답 본문을 JSON 으로 읽는다. 본문이 비었거나 JSON 이 아니면 null 을
// 돌려주므로, 오류 응답이 HTML 이어도 여기서 예외가 나지 않는다.
async function readJson(res) {
  const text = await res.text()
  try {
    return text ? JSON.parse(text) : null
  } catch {
    return null
  }
}

/**
 * 실패한 HTTP 응답을 ApiError 로 바꾼다. 문구는 본문의 message 를 먼저 쓰고,
 * 없으면 detail 을, 둘 다 없으면 메서드와 경로와 상태 숫자를 이은 글을 쓴다.
 * code, order_id, field_errors 는 order-agent 의 오류 본문인
 * order-agent/backend/src/main/java/com/capstone4/orderagent/dto/common/ErrorResponse.java 의
 * 칸이다. service 의 오류 본문인
 * service/src/main/java/com/capstone4/skillops/common/ApiError.java 는
 * code 와 message 만 채운다. 본문 전체는 body 에 남긴다.
 */
function httpError(method, url, status, data) {
  return new ApiError(status, data?.message ?? data?.detail ?? `${method} ${url} → ${status}`, {
    code: data?.code ?? null,
    orderId: data?.order_id ?? null,
    fieldErrors: data?.field_errors ?? [],
    body: data,
  })
}

/**
 * 요청 하나를 보내고 성공하면 JSON 본문을 돌려준다. 본문이 비었으면 null 이다.
 * get 과 post 가 이 함수를 부르므로 frontend/src/api 의 모든 HTTP 호출이 이 함수를 거친다.
 * 순서는 토큰 붙이기, mock 분기, fetch, 본문 읽기, 오류 변환이고 실패하면 ApiError 를 던진다.
 */
export async function request(method, url, { body, headers = {} } = {}) {
  const signed = attachToken(url, headers)
  if (USE_MOCK) return requestMock(method, url, body, signed.headers, signed.token)

  const res = await send(method, url, body, signed.headers)
  const data = await readJson(res)
  if (!res.ok) {
    // 토큰을 실은 요청의 401 은 만료되거나 위조된 토큰이므로 로그아웃시킨다. 로그인
    // 요청 자체의 401(비밀번호 틀림)은 토큰이 없으므로 그대로 화면에 보여 준다.
    if (res.status === 401 && signed.token) auth.onUnauthorized()
    throw httpError(method, url, res.status, data)
  }
  return data
}

// GET 요청을 보낸다.
export const get = (url) => request('GET', url)
// POST 요청을 보낸다. 본문이 없으면 빈 객체를 JSON 으로 보낸다.
// headers 에는 Idempotency-Key 같은 추가 헤더를 넣는다.
export const post = (url, body, headers) => request('POST', url, { body: body ?? {}, headers })
