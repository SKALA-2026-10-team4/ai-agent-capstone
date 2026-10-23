// order-agent/frontend/src/composables/useOrder.js 의 핵심 주문 흐름과 order-agent/frontend/src/utils/validation.js 의
// estimate, validate 를 보호한다.
// 지키는 동작은 중복 제출 방지, 업무 거절을 HTTP 실패가 아닌 확정 결과로 다루는 것, 네트워크 오류 뒤 입력 잠금과 추가 POST 없는 복구,
// 404 ORDER_NOT_FOUND 를 확인한 뒤에만 허용하는 같은 key 와 body 의 재전송, 400 입력 거절의 잠금 해제,
// 상품 재조회 실패가 확정 주문을 지우지 않는 것, polling 한도에서 FAILED 를 만들지 않는 것, dispose 뒤 늦은 응답 무시다.
// 가짜 API 를 주입해 node --test 에서 서버 없이 돈다.
import test from "node:test";
import assert from "node:assert/strict";
import { useOrder } from "../src/composables/useOrder.js";
import { ApiError } from "../src/api/client.js";
import { defaultForm, estimate, validate } from "../src/utils/validation.js";

const products = [
  { sku: "CAMP-LANTERN-LED", unit_price: 30000, stock_quantity: 10 },
];
/** 가짜 주문 응답을 만든다. 기본 상태는 SHIPPED 다. */
const result = (status = "SHIPPED") => ({
  order_id: "order-1",
  status,
  steps: [],
});
/** 모든 호출이 성공하는 가짜 주문 API 를 만든다. overrides 로 테스트마다 필요한 함수만 바꾼다. */
const api = (overrides = {}) => ({
  listProducts: async () => ({ items: products }),
  createOrder: async () => result(),
  getOrder: async () => result(),
  getByRequest: async () => result(),
  ...overrides,
});
/** 요청 키를 fixed-key 로 고정한 useOrder 를 만든다. */
const make = (overrides = {}, options = {}) =>
  useOrder(api(overrides), { uuid: () => "fixed-key", ...options });

test("estimate and validation use exact quantities without blocking stock rejection demos", () => {
  const form = defaultForm();
  form.items[0].qty = 2;
  assert.equal(estimate(form, products), 60000);
  form.items[0].qty = 99;
  assert.deepEqual(validate(form), {});
  form.items[0].qty = 1.5;
  assert.ok(validate(form)["items[0].qty"]);
});
test("double submit sends one frozen request", async () => {
  let complete,
    calls = 0;
  const state = make({
    createOrder: () => {
      calls++;
      return new Promise((resolve) => {
        complete = resolve;
      });
    },
  });
  const first = state.submit();
  await state.submit();
  assert.equal(calls, 1);
  assert.equal(state.locked.value, true);
  complete(result());
  await first;
  assert.equal(state.order.value.status, "SHIPPED");
  assert.equal(state.locked.value, false);
  state.dispose();
});
test("business rejection is a settled result, not an HTTP failure", async () => {
  const state = make({ createOrder: async () => result("HOLD") });
  await state.submit();
  assert.equal(state.requestState.value, "settled");
  assert.equal(state.order.value.status, "HOLD");
  state.dispose();
});
test("network failure locks input and recovers existing order without another POST", async () => {
  let calls = 0;
  const state = make({
    createOrder: async () => {
      calls++;
      throw new ApiError("lost response");
    },
  });
  await state.submit();
  state.preset("soldout");
  state.newOrder();
  assert.equal(state.form.value.items[0].sku, "CAMP-LANTERN-LED");
  assert.equal(state.requestState.value, "recoverable");
  await state.recover();
  assert.equal(calls, 1);
  assert.equal(state.requestState.value, "settled");
  state.dispose();
});
test("only confirmed missing order permits identical-key-and-body resend", async () => {
  const requests = [];
  const state = make({
    createOrder: async (key, body) => {
      requests.push({ key, body });
      if (requests.length === 1) throw new ApiError("lost");
      return result();
    },
    getByRequest: async () => {
      throw new ApiError("missing", { status: 404, code: "ORDER_NOT_FOUND" });
    },
  });
  await state.submit();
  await state.resend();
  assert.equal(requests.length, 1);
  await state.recover();
  assert.equal(state.canResend.value, true);
  await state.resend();
  assert.equal(requests.length, 2);
  assert.deepEqual(requests[0], requests[1]);
  state.dispose();
});
test("failed recovery connection never permits an automatic new order", async () => {
  const state = make({
    createOrder: async () => {
      throw new ApiError("lost");
    },
    getByRequest: async () => {
      throw new ApiError("offline");
    },
  });
  await state.submit();
  await state.recover();
  assert.equal(state.canResend.value, false);
  assert.equal(state.locked.value, true);
  state.dispose();
});
test("known accepted order ID returning 404 does not permit resending", async () => {
  const state = make({
    createOrder: async () => {
      throw new ApiError("failed", { status: 500, order_id: "known-id" });
    },
    getOrder: async () => {
      throw new ApiError("missing", { status: 404, code: "ORDER_NOT_FOUND" });
    },
  });
  await state.submit();
  await state.recover();
  assert.equal(state.canResend.value, false);
  await state.recover();
  assert.equal(state.canResend.value, false);
  state.dispose();
});
test("validation errors unlock editing; product refresh errors do not erase final order", async () => {
  const bad = make({
    createOrder: async () => {
      throw new ApiError("bad input", {
        status: 400,
        field_errors: [{ field: "items[0].qty", message: "invalid" }],
      });
    },
  });
  await bad.submit();
  assert.equal(bad.locked.value, false);
  assert.ok(bad.fieldErrors.value["items[0].qty"]);
  bad.dispose();
  const state = make({
    listProducts: async () => {
      throw new ApiError("offline");
    },
  });
  await state.submit();
  await Promise.resolve();
  assert.equal(state.order.value.status, "SHIPPED");
  assert.equal(state.productStatus.value, "error");
  state.dispose();
});
test("polling reaches a limit without claiming FAILED; disposal ignores late responses", async () => {
  const state = make(
    { createOrder: async () => result("PROCESSING") },
    { pollLimit: 0 },
  );
  await state.submit();
  assert.equal(state.order.value.status, "PROCESSING");
  assert.equal(state.requestState.value, "recoverable");
  state.dispose();
  let resolve;
  const late = make({
    createOrder: () =>
      new Promise((r) => {
        resolve = r;
      }),
  });
  const pending = late.submit();
  late.dispose();
  resolve(result());
  await pending;
  assert.equal(late.order.value, null);
});
