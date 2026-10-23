// order-agent/frontend/src/utils/orderPolicy.js 의 흐름 튜닝 값과 판정 함수, order-agent/frontend/src/utils/demoPresets.js 의
// preset 표와 presetForm, order-agent/frontend/src/composables/useOrder.js 의 previousOrder, selectSnapshot,
// statusTimeoutMs 옵션을 보호한다. 튜닝 값을 일부러 바꿀 때는 첫 테스트의 기대값을 함께 고친다.
import test from "node:test";
import assert from "node:assert/strict";
import { useOrder } from "../src/composables/useOrder.js";
import { ApiError } from "../src/api/client.js";
import { defaultForm } from "../src/utils/validation.js";
import { DEMO_PRESETS, presetForm } from "../src/utils/demoPresets.js";
import {
  IN_FLIGHT_REQUEST_STATES,
  KNOWN_STATUSES,
  LOCKED_REQUEST_STATES,
  ORDER_TIMING,
  TERMINAL_STATUSES,
  isOrderNotFound,
  isOrderResponse,
  isRejectedBeforeAcceptance,
  isTerminalStatus,
  toFieldErrors,
} from "../src/utils/orderPolicy.js";

/** 가짜 주문 응답을 만든다. 기본 상태는 SHIPPED 다. */
const result = (status = "SHIPPED") => ({
  order_id: "order-1",
  status,
  steps: [],
});
/** 모든 호출이 성공하는 가짜 API 와 fixed-key 요청 키로 useOrder 를 만든다. overrides 로 필요한 함수만 바꾼다. */
const make = (overrides = {}, options = {}) =>
  useOrder(
    {
      listProducts: async () => ({ items: [] }),
      createOrder: async () => result(),
      getOrder: async () => result(),
      getByRequest: async () => result(),
      ...overrides,
    },
    { uuid: () => "fixed-key", ...options },
  );

test("order timing and state sets keep the current flow values", () => {
  assert.deepEqual(ORDER_TIMING, {
    createTimeoutMs: 15000,
    statusTimeoutMs: 5000,
    pollIntervalMs: 1000,
    pollLimitMs: 10000,
  });
  assert.deepEqual([...TERMINAL_STATUSES], ["SHIPPED", "REJECTED", "HOLD", "FAILED"]);
  assert.deepEqual([...KNOWN_STATUSES], [
    "SHIPPED",
    "REJECTED",
    "HOLD",
    "FAILED",
    "CREATED",
    "PROCESSING",
  ]);
  assert.deepEqual(LOCKED_REQUEST_STATES, ["submitting", "polling", "recoverable"]);
  assert.deepEqual(IN_FLIGHT_REQUEST_STATES, ["submitting", "polling"]);
});

test("order responses need an order id and a known status", () => {
  assert.equal(isOrderResponse(result("CREATED")), true);
  assert.equal(isOrderResponse(result("PROCESSING")), true);
  assert.equal(isOrderResponse({ status: "SHIPPED" }), false);
  assert.equal(isOrderResponse({ order_id: "", status: "SHIPPED" }), false);
  assert.equal(isOrderResponse(result("UNKNOWN")), false);
  assert.equal(isOrderResponse(null), false);
  assert.equal(isOrderResponse(undefined), false);
  for (const status of ["SHIPPED", "REJECTED", "HOLD", "FAILED"])
    assert.equal(isTerminalStatus(status), true);
  for (const status of ["CREATED", "PROCESSING", "UNKNOWN", undefined])
    assert.equal(isTerminalStatus(status), false);
});

test("error classification follows the acceptance and resend rules", () => {
  const error = (status, code, order_id = null) =>
    new ApiError("e", { status, code, order_id });
  assert.equal(isRejectedBeforeAcceptance(error(400)), true);
  assert.equal(isRejectedBeforeAcceptance(error(404, "PRODUCT_NOT_FOUND")), true);
  assert.equal(isRejectedBeforeAcceptance(error(400, "X", "order-1")), false);
  assert.equal(
    isRejectedBeforeAcceptance(error(404, "PRODUCT_NOT_FOUND", "order-1")),
    false,
  );
  assert.equal(isRejectedBeforeAcceptance(error(404, "ORDER_NOT_FOUND")), false);
  assert.equal(isRejectedBeforeAcceptance(error(409, "IDEMPOTENCY_CONFLICT")), false);
  assert.equal(isRejectedBeforeAcceptance(error(0)), false);
  assert.equal(isOrderNotFound(error(404, "ORDER_NOT_FOUND")), true);
  assert.equal(isOrderNotFound(error(404, "PRODUCT_NOT_FOUND")), false);
  assert.equal(isOrderNotFound(error(410, "ORDER_NOT_FOUND")), false);
});

test("server field errors map to form keys, later duplicates win", () => {
  assert.deepEqual(toFieldErrors(undefined), {});
  assert.deepEqual(toFieldErrors(null), {});
  assert.equal(
    JSON.stringify(
      toFieldErrors([
        { field: "items[0].qty", message: "a" },
        { field: "paymentMethod", message: "b" },
        { field: "items[0].qty", message: "c" },
        { field: "paymentMethodpaymentMethod", message: "d" },
      ]),
    ),
    JSON.stringify({
      "items[0].qty": "c",
      payment_method: "b",
      payment_methodpaymentMethod: "d",
    }),
  );
});

test("demo presets keep button order and build a fresh default form each time", () => {
  assert.deepEqual(
    DEMO_PRESETS.map(({ name, label }) => [name, label]),
    [
      ["normal", "정상 주문"],
      ["soldout", "재고 부족"],
      ["declined", "결제 거절"],
    ],
  );
  const expected = (change) => {
    const form = defaultForm();
    change(form);
    return JSON.stringify(form);
  };
  assert.equal(
    JSON.stringify(presetForm("normal")),
    expected((f) => {
      f.items[0].qty = 2;
    }),
  );
  assert.equal(
    JSON.stringify(presetForm("soldout")),
    expected((f) => {
      f.items[0].sku = "CAMP-MUG-STEEL";
    }),
  );
  assert.equal(
    JSON.stringify(presetForm("declined")),
    expected((f) => {
      f.customer = "c_declined";
    }),
  );
  assert.equal(JSON.stringify(presetForm("unknown")), JSON.stringify(defaultForm()));
  assert.notEqual(presetForm("normal"), presetForm("normal"));
});

test("previousOrder compares the current form with the submitted body", async () => {
  const state = make();
  assert.equal(state.previousOrder.value, false);
  await state.submit();
  assert.equal(state.previousOrder.value, false);
  state.form.value = {
    ...state.form.value,
    items: [{ sku: "CAMP-LANTERN-LED", qty: 2 }],
  };
  assert.equal(state.previousOrder.value, true);
  state.form.value = {
    ...state.form.value,
    items: [{ sku: "CAMP-LANTERN-LED", qty: 1 }],
  };
  assert.equal(state.previousOrder.value, false);
  state.newOrder();
  assert.equal(state.previousOrder.value, false);
  state.dispose();
});

test("selectSnapshot appends the snapshot id unless input is locked", async () => {
  const state = make();
  state.selectSnapshot("snap-1");
  assert.equal(
    JSON.stringify(state.form.value),
    JSON.stringify({ ...defaultForm(), snapshot_id: "snap-1" }),
  );
  state.selectSnapshot("snap-2");
  assert.deepEqual(Object.keys(state.form.value), [
    "customer",
    "items",
    "payment_method",
    "shipping",
    "snapshot_id",
  ]);
  assert.equal(state.form.value.snapshot_id, "snap-2");
  state.dispose();

  let complete;
  const busy = make({
    createOrder: () =>
      new Promise((resolve) => {
        complete = resolve;
      }),
  });
  const pending = busy.submit();
  busy.selectSnapshot("snap-3");
  assert.equal(busy.form.value.snapshot_id, undefined);
  complete(result());
  await pending;
  busy.dispose();
});

test("statusTimeoutMs option sets the recover lookup and polling GET timeouts", async () => {
  const timeouts = [];
  const statuses = ["PROCESSING", "SHIPPED"];
  const state = make(
    {
      createOrder: async () => {
        throw new ApiError("lost");
      },
      getByRequest: async (key, options) => {
        timeouts.push(["getByRequest", options.timeout]);
        return result("PROCESSING");
      },
      getOrder: async (id, options) => {
        timeouts.push(["getOrder", options.timeout]);
        return result(statuses.shift());
      },
    },
    { pollMs: 1, pollLimit: 60000, statusTimeoutMs: 1234 },
  );
  await state.submit();
  await state.recover();
  assert.equal(state.requestState.value, "settled");
  assert.deepEqual(timeouts, [
    ["getByRequest", 1234],
    ["getOrder", 1234],
    ["getOrder", 1234],
  ]);
  state.dispose();
});
