// 주문 생성과 주문 조회를 맡는 order-agent REST 호출 모음이다.
// 세 경로 모두 order-agent/backend/src/main/java/com/capstone4/orderagent/controller/OrderController.java 가 받아
// order-agent/backend/src/main/java/com/capstone4/orderagent/service/OrderService.java 로 넘긴다.
// order-agent/frontend/src/composables/useOrder.js 가 기본 api 로 이 함수들을 쓰고,
// order-agent/frontend/tests/api.test.js 가 경로, 헤더, 본문, timeout 을 고정한다.
import { postJson, request } from "./client.js";
import { ORDER_TIMING } from "../utils/orderPolicy.js";
/**
 * POST /api/orders 로 주문을 만들고 주문 응답을 받는다.
 * order-agent/backend/src/main/java/com/capstone4/orderagent/controller/OrderController.java 의 create() 가 받는다.
 * key 는 Idempotency-Key 헤더로 간다. 응답을 잃고 같은 key 와 본문으로 다시 보내도 서버가 주문을 하나만 만들고,
 * 같은 key 에 다른 본문이 오면 409 IDEMPOTENCY_CONFLICT 로 거절한다. 서버는 key 를 UUID 형식으로 검사한다.
 * body 는 화면 폼(customer, items, payment_method, shipping, 선택적인 snapshot_id)을 그대로 보낸다.
 * 서버 Jackson 이 모르는 키와 소수점이 있는 수량을 400 으로 거절하므로 폼에 화면 전용 키를 넣지 않는다.
 * 서버가 Agent 실행까지 마친 뒤 응답하므로 제한 시간은 order-agent/frontend/src/utils/orderPolicy.js 의
 * ORDER_TIMING.createTimeoutMs 로 길게 잡는다. options 를 마지막에 펼쳐 timeout 도 덮어쓸 수 있다.
 */
export const createOrder = (key, body, options) =>
  postJson("/orders", body, {
    timeout: ORDER_TIMING.createTimeoutMs,
    headers: { "Idempotency-Key": key },
    ...options,
  });
/**
 * GET /api/orders/{id} 로 주문 하나의 현재 상태를 읽는다.
 * order-agent/backend/src/main/java/com/capstone4/orderagent/controller/OrderController.java 의 get() 이 받는다.
 * order-agent/frontend/src/composables/useOrder.js 의 pollUntilTerminal 이 polling 에 쓰고,
 * lookupSubmittedOrder 가 주문 ID 를 아는 상태의 결과 확인에 쓴다.
 */
export const getOrder = (id, options) =>
  request(`/orders/${encodeURIComponent(id)}`, options);
/**
 * GET /api/orders/by-request/{key} 로 Idempotency-Key 가 key 인 주문을 찾는다.
 * order-agent/backend/src/main/java/com/capstone4/orderagent/controller/OrderController.java 의 byRequest() 가 받는다.
 * POST 응답을 잃어 주문 ID 를 모를 때 order-agent/frontend/src/composables/useOrder.js 의 recover 가
 * lookupSubmittedOrder 를 거쳐 부른다.
 * 404 ORDER_NOT_FOUND 면 서버가 요청을 받지 못한 것이므로 같은 요청을 다시 보낼 수 있다.
 */
export const getByRequest = (key, options) =>
  request(`/orders/by-request/${encodeURIComponent(key)}`, options);
