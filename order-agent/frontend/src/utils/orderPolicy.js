// 주문 흐름의 튜닝 지점이다. 시간 튜닝 값과 분기 규칙을 한곳에 모은 순수 모듈이다.
// order-agent/frontend/src/composables/useOrder.js 의 상태 기계가 이 파일의 상수와 판정 함수로 분기하므로,
// timeout, polling 간격과 한도, 최종 상태, 입력 잠금 상태, 오류 분류 규칙을 바꾸려면 이 파일을 고친다.
// 주문 생성 timeout 은 order-agent/frontend/src/api/orders.js 의 createOrder 도 읽는다.
// 주문 상태 이름은 order-agent/backend/src/main/java/com/capstone4/orderagent/entity/enums/OrderStatus.java 와 같아야 한다.
// 값을 바꾸면 그 값을 고정한 order-agent/frontend/tests/orderPolicy.test.js, order-agent/frontend/tests/orderFlow.test.js,
// order-agent/frontend/tests/api.test.js 의 기대값도 함께 고친다.

/**
 * 주문 흐름의 시간 튜닝 값이며 단위는 ms 다.
 * createTimeoutMs 는 POST /api/orders 의 제한 시간이다. 서버가 Agent 실행을 마친 뒤 응답하므로 조회보다 길게 둔다.
 * statusTimeoutMs 는 주문 상태 GET 하나의 제한 시간이다. recover 의 결과 확인 조회는 이 값을 그대로 쓰고,
 * polling 중 GET 은 이 값과 남은 polling 시간 중 짧은 쪽을 쓴다.
 * pollIntervalMs 는 polling 조회 사이의 대기 시간이고, pollLimitMs 는 polling 전체 한도다.
 * 한도를 넘기면 FAILED 로 단정하지 않고 사용자가 "상태 다시 확인" 버튼으로 다시 조회하게 한다.
 * order-agent/frontend/src/composables/useOrder.js 의 options(statusTimeoutMs, pollMs, pollLimit)로 테스트에서 덮어쓸 수 있다.
 */
export const ORDER_TIMING = {
  createTimeoutMs: 15000,
  statusTimeoutMs: 5000,
  pollIntervalMs: 1000,
  pollLimitMs: 10000,
};
// 더 조회하지 않는 최종 주문 상태다. 재고 부족(REJECTED)과 결제 거절(HOLD)도 업무 결과라서 최종 상태에 넣는다.
export const TERMINAL_STATUSES = new Set([
  "SHIPPED",
  "REJECTED",
  "HOLD",
  "FAILED",
]);
// 화면이 아는 모든 주문 상태다. 응답 status 가 여기에 없으면 응답 형식 오류로 본다.
// 최종 상태가 아닌 CREATED 와 PROCESSING 은 polling 으로 결과를 기다린다.
export const KNOWN_STATUSES = new Set([
  ...TERMINAL_STATUSES,
  "CREATED",
  "PROCESSING",
]);
// 입력을 잠그는 요청 상태다. 요청 상태는 idle, submitting, polling, recoverable, settled 다섯 가지다.
// recoverable 도 잠가서, 결과를 확인하기 전에 새 주문을 만들어 중복 접수하는 일을 막는다.
export const LOCKED_REQUEST_STATES = ["submitting", "polling", "recoverable"];
// 서버 응답을 기다리는 요청 상태다. 이 상태에서는 order-agent/frontend/src/composables/useOrder.js 의 recover 가
// 조회를 겹쳐 시작하지 않는다.
export const IN_FLIGHT_REQUEST_STATES = ["submitting", "polling"];
/**
 * 주문 ID 와 알려진 상태를 가진 주문 응답인지 판정한다.
 * order-agent/frontend/src/composables/useOrder.js 의 accept 가 응답을 반영하기 전에 부르고,
 * 거짓이면 accept 가 응답 형식 오류를 던져 결과 확인이 필요한 상태로 바뀐다.
 */
export const isOrderResponse = (result) =>
  !!result?.order_id && KNOWN_STATUSES.has(result.status);
/**
 * 더 조회할 필요가 없는 최종 상태인지 판정한다.
 * 참이면 order-agent/frontend/src/composables/useOrder.js 의 accept 가 결과를 확정하고, 거짓이면 polling 을 이어 간다.
 */
export const isTerminalStatus = (status) => TERMINAL_STATUSES.has(status);
/**
 * 서버가 주문을 접수하기 전에 입력을 거절한 오류인지 판정한다.
 * 주문 ID 가 없는 400(검증 실패, JSON 형식 오류)과 404 PRODUCT_NOT_FOUND 가 여기에 해당한다.
 * 주문이 생기지 않았으므로 order-agent/frontend/src/composables/useOrder.js 의 failed 가 showInputRejection 으로
 * 입력 잠금을 풀고 필드 오류를 보여준다.
 * 주문 ID 가 붙은 오류, 409, 5xx, 네트워크 오류는 이미 접수됐을 수 있어 여기에 들지 않고 결과 확인 대상이 된다.
 */
export const isRejectedBeforeAcceptance = (error) =>
  !error.orderId &&
  (error.status === 400 ||
    (error.status === 404 && error.code === "PRODUCT_NOT_FOUND"));
/**
 * 서버가 해당 주문을 찾지 못했다는 404 ORDER_NOT_FOUND 오류인지 판정한다.
 * order-agent/frontend/src/composables/useOrder.js 의 recover 는 조회 전에 주문 ID 를 몰랐을 때 이 오류를 받으면
 * 같은 요청의 재전송을 허용한다.
 */
export const isOrderNotFound = (error) =>
  error.status === 404 && error.code === "ORDER_NOT_FOUND";
/**
 * 서버 field_errors 배열을 화면 입력 키 기준의 { 키: 메시지 } 객체로 바꾼다.
 * 서버는 Java 속성 경로(paymentMethod, items[0].qty, shipping.country)로 field 를 주므로,
 * 화면 키와 다른 paymentMethod 만 payment_method 로 바꾼다. replace 는 처음 나온 paymentMethod 하나만 바꾸고,
 * 같은 field 가 여러 번 오면 나중 메시지가 남는다.
 * order-agent/backend/src/main/java/com/capstone4/orderagent/exception/GlobalExceptionHandler.java 가 field_errors 를 만들고,
 * order-agent/frontend/src/composables/useOrder.js 의 showInputRejection 이 결과를 fieldErrors 에 넣는다.
 */
export const toFieldErrors = (fieldErrors) =>
  Object.fromEntries(
    (fieldErrors || []).map((f) => [
      f.field.replace("paymentMethod", "payment_method"),
      f.message,
    ]),
  );
