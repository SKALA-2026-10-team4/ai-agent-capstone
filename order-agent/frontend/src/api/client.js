// order-agent 주문 데모 화면의 모든 REST 호출이 거치는 fetch 래퍼다.
// 경로 앞에 /api 를 붙이고, 요청마다 timeout 과 호출자의 취소 신호를 묶고, HTTP 오류, JSON 이 아닌 응답,
// 네트워크 실패, timeout 을 모두 ApiError 하나로 정규화한다. /api 요청은 order-agent/frontend/vite.config.js 의
// 프록시가 order-agent backend 로 넘긴다.
// 정규화 덕분에 order-agent/frontend/src/composables/useOrder.js 와 order-agent/frontend/src/utils/orderPolicy.js 는
// 실패 종류와 관계없이 같은 status, code, orderId 속성으로 오류를 나눈다.
// order-agent/frontend/src/api/orders.js, order-agent/frontend/src/api/products.js, order-agent/frontend/src/api/evidence.js 가
// request 와 postJson 을 부르고, order-agent/frontend/tests/api.test.js 가 오류 정규화, timeout, 취소 연결을 고정한다.

/**
 * 화면이 다루는 모든 API 실패를 나타내는 오류다.
 * 생성자 옵션은 서버 오류 본문을 그대로 펼쳐 받기 때문에 order_id, field_errors 처럼 snake_case 이고,
 * 속성은 orderId, fieldErrors 처럼 camelCase 로 바꿔 둔다. 서버 응답이 없으면 status 는 0, code 는 NETWORK_ERROR 다.
 * order-agent/frontend/src/utils/orderPolicy.js 의 오류 분류 함수와 order-agent/frontend/src/composables/useOrder.js 가
 * 이 속성을 읽고 테스트도 이 형태로 오류를 만들므로 이름을 바꾸지 않는다.
 * 서버 오류 본문은 order-agent/backend/src/main/java/com/capstone4/orderagent/exception/GlobalExceptionHandler.java 가 만든다.
 */
export class ApiError extends Error {
  constructor(
    message,
    {
      status = 0,
      code = "NETWORK_ERROR",
      order_id = null,
      field_errors = [],
    } = {},
  ) {
    super(message);
    this.name = "ApiError";
    Object.assign(this, {
      status,
      code,
      orderId: order_id,
      fieldErrors: field_errors,
    });
  }
}

// timeout 을 따로 주지 않은 호출의 제한 시간(ms)이다. 상품 목록, 실행 증거 조회, 시연 후보 고정이 이 값을 쓴다.
// 주문 생성과 주문 상태 조회의 제한 시간은 order-agent/frontend/src/utils/orderPolicy.js 의 ORDER_TIMING 이 따로 정한다.
const DEFAULT_TIMEOUT_MS = 5000;

/**
 * 호출자의 취소 신호와 timeout 을 AbortSignal 하나로 묶고, 타이머와 listener 를 지우는 release 를 함께 돌려준다.
 * 이미 취소된 신호를 받으면 곧바로 abort 한다.
 * 요청이 끝나면 request 의 finally 가 release 를 불러, 끝난 요청에 타이머와 listener 가 남지 않게 한다.
 */
function linkAbort(signal, timeout) {
  const controller = new AbortController();
  const abort = () => controller.abort();
  signal?.addEventListener("abort", abort, { once: true });
  if (signal?.aborted) controller.abort();
  const timer = setTimeout(abort, timeout);
  return {
    signal: controller.signal,
    release() {
      clearTimeout(timer);
      signal?.removeEventListener("abort", abort);
    },
  };
}

/**
 * 응답 본문을 JSON 으로 읽고, HTTP 오류와 빈 본문을 ApiError 로 바꾼다.
 * 오류 응답은 서버 본문을 펼친 뒤 status 를 실제 응답 코드로 덮어쓴다. 본문에 message 가 없으면 연결 오류 문구를 쓰고,
 * 프록시가 돌려준 502 HTML 처럼 본문이 JSON 이 아닌 경우도 같은 문구가 된다.
 * 성공 응답인데 JSON 본문이 없으면 INVALID_RESPONSE 로 본다. 화면은 모든 성공 응답이 JSON 객체라고 가정하기 때문이다.
 */
async function readBody(response) {
  const body = await response.json().catch(() => null);
  if (!response.ok) {
    throw new ApiError(
      body?.message || "서버에 연결할 수 없습니다. 주문 결과를 확인해주세요.",
      {
        ...body,
        status: response.status,
      },
    );
  }
  if (!body)
    throw new ApiError("서버 응답을 확인할 수 없습니다.", {
      code: "INVALID_RESPONSE",
    });
  return body;
}

/**
 * 요청 중 생긴 실패를 화면 문구가 담긴 ApiError 로 바꾼다. 이미 ApiError 이면 그대로 둔다.
 * 신호가 abort 됐으면 timeout 문구를, 그 밖의 실패는 네트워크 문구를 쓴다.
 * 호출자가 취소한 경우(화면 정리 때의 dispose)도 abort 이므로 timeout 문구가 된다.
 * 두 문구 모두 주문이 접수됐을 수 있다고 알려, 사용자가 새 주문 대신 결과 확인을 고르게 한다.
 */
function toTransportError(error, aborted) {
  if (error instanceof ApiError) return error;
  return new ApiError(
    aborted
      ? "응답 확인 시간이 초과되었습니다. 주문이 접수됐을 수 있습니다."
      : "네트워크 연결을 확인해주세요. 주문이 접수됐을 수 있습니다.",
  );
}

/**
 * order-agent backend 에 요청을 보내고 JSON 본문을 돌려준다. 실패하면 ApiError 를 던진다.
 * path 는 /api 를 뺀 경로다. options 에서 timeout 과 signal 을 뺀 나머지(method, headers, body)는 fetch 에 그대로 넘긴다.
 * try 안에서 readBody 를 await 해야 본문을 읽는 동안에도 timeout 이 걸리고, finally 의 release 가 그 뒤에 실행된다.
 */
export async function request(
  path,
  { timeout = DEFAULT_TIMEOUT_MS, signal, ...options } = {},
) {
  const link = linkAbort(signal, timeout);
  try {
    const response = await fetch(`/api${path}`, {
      ...options,
      signal: link.signal,
    });
    return await readBody(response);
  } catch (error) {
    throw toTransportError(error, link.signal.aborted);
  } finally {
    link.release();
  }
}

/**
 * payload 를 JSON 본문으로 바꿔 POST 로 보낸다.
 * 호출자의 headers 는 Content-Type 기본값 뒤에 합쳐 같은 이름이면 호출자 값이 이긴다.
 * 나머지 options 는 마지막에 펼쳐 method, body 같은 기본값도 덮어쓸 수 있다.
 * order-agent/frontend/src/api/orders.js 의 createOrder 와 order-agent/frontend/src/api/evidence.js 의 publishSnapshot 이 부른다.
 */
export function postJson(path, payload, { headers, ...options } = {}) {
  return request(path, {
    method: "POST",
    headers: { "Content-Type": "application/json", ...headers },
    body: JSON.stringify(payload),
    ...options,
  });
}
