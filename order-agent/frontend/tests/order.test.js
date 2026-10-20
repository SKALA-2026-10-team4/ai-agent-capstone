import test from "node:test";
import assert from "node:assert/strict";
import { useOrder } from "../src/composables/useOrder.js";
import { ApiError } from "../src/api/client.js";
import { defaultForm, estimate, validate } from "../src/utils/validation.js";

const products = [
  { sku: "CAMP-LANTERN-LED", unit_price: 30000, stock_quantity: 10 },
];
const result = (status = "SHIPPED") => ({
  order_id: "order-1",
  status,
  steps: [],
});
const api = (overrides = {}) => ({
  listProducts: async () => ({ items: products }),
  createOrder: async () => result(),
  getOrder: async () => result(),
  getByRequest: async () => result(),
  ...overrides,
});
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
