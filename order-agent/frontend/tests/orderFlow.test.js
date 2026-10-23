// order-agent/frontend/src/composables/useOrder.js 의 세부 상태 전이를 보호한다.
// 기본 polling 간격, GET timeout 상한, polling 한도, 한도 뒤의 recoverable 상태, polling 중 오류, 응답 형식 오류,
// 접수 전 거절의 필드 오류 변환과 잠금 해제, 접수 뒤일 수 있는 오류의 잠금, 클라이언트 검증, 제출 본문 고정,
// preset 과 newOrder 의 초기화, dispose 의 polling 대기 해제, 상품 조회 경쟁, recover 의 무시 조건과 조회 방식,
// 재전송 규칙을 고정한다.
// 기본 polling 테스트는 order-agent/frontend/src/utils/orderPolicy.js 의 ORDER_TIMING 에 기대므로, 그 값을 바꾸면
// 기대 시각과 timeout 도 함께 고친다. 가짜 API 를 주입해 node --test 에서 서버 없이 돈다.
import test from "node:test";
import assert from "node:assert/strict";
import { useOrder } from "../src/composables/useOrder.js";
import { ApiError } from "../src/api/client.js";
import { defaultForm } from "../src/utils/validation.js";

const products = [
  { sku: "CAMP-LANTERN-LED", unit_price: 30000, stock_quantity: 10 },
];
/** 가짜 주문 응답을 만든다. 기본 상태는 SHIPPED 이고 extra 로 필드를 더한다. */
const result = (status = "SHIPPED", extra = {}) => ({
  order_id: "order-1",
  status,
  steps: [],
  ...extra,
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
/** 대기 중인 promise 후속 작업이 한 바퀴 돌도록 기다린다. */
const flush = () => new Promise((resolve) => setImmediate(resolve));
// order-agent/frontend/src/composables/useOrder.js 가 보여주는 안내 문구다. 문구를 바꾸면 여기도 함께 고친다.
const STILL_PROCESSING =
  "아직 처리 중입니다. “상태 다시 확인”으로 결과를 확인해주세요.";
const RESEND_ALLOWED =
  "접수된 주문을 찾지 못했습니다. 같은 요청으로 다시 전송할 수 있습니다.";

test("default polling waits 1s per check, caps each GET at 5s and stops after 10s", async (t) => {
  t.mock.timers.enable({ apis: ["setTimeout", "Date"], now: 0 });
  const polls = [];
  const state = make({
    createOrder: async () => result("PROCESSING"),
    getOrder: async (id, options) => {
      polls.push({ id, at: Date.now(), options });
      return result("PROCESSING");
    },
  });
  const pending = state.submit();
  await flush();
  assert.equal(state.requestState.value, "polling");
  t.mock.timers.tick(999);
  await flush();
  assert.equal(polls.length, 0);
  t.mock.timers.tick(1);
  await flush();
  assert.equal(polls.length, 1);
  for (let i = 0; i < 9; i++) {
    t.mock.timers.tick(1000);
    await flush();
  }
  await pending;
  assert.deepEqual(
    polls.map((p) => p.at),
    [1000, 2000, 3000, 4000, 5000, 6000, 7000, 8000, 9000],
  );
  assert.deepEqual(
    polls.map((p) => p.options.timeout),
    [5000, 5000, 5000, 5000, 5000, 4000, 3000, 2000, 1000],
  );
  assert.ok(polls.every((p) => p.id === "order-1"));
  assert.ok(polls.every((p) => p.options.signal instanceof AbortSignal));
  assert.equal(state.requestState.value, "recoverable");
  assert.equal(state.requestError.value.message, STILL_PROCESSING);
  state.dispose();
});

test("polling applies a later terminal status, then reloads products", async () => {
  const statuses = ["PROCESSING", "SHIPPED"];
  const polls = [];
  let productLoads = 0;
  const state = make(
    {
      listProducts: async () => {
        productLoads++;
        return { items: products };
      },
      createOrder: async () => result("CREATED"),
      getOrder: async (id, options) => {
        polls.push({ id, options });
        return result(statuses.shift());
      },
    },
    { pollMs: 1, pollLimit: 60000 },
  );
  await state.submit();
  assert.equal(state.order.value.status, "SHIPPED");
  assert.equal(state.requestState.value, "settled");
  assert.equal(state.requestError.value, null);
  assert.equal(state.canResend.value, false);
  assert.equal(polls.length, 2);
  assert.equal(polls[0].id, "order-1");
  assert.deepEqual(Object.keys(polls[0].options), ["signal", "timeout"]);
  assert.equal(polls[0].options.timeout, 5000);
  assert.equal(productLoads, 1);
  state.dispose();
});

test("polling GET timeout never exceeds the remaining polling time", async () => {
  const timeouts = [];
  const statuses = ["PROCESSING", "SHIPPED"];
  const state = make(
    {
      createOrder: async () => result("PROCESSING"),
      getOrder: async (id, options) => {
        timeouts.push(options.timeout);
        return result(statuses.shift());
      },
    },
    { pollMs: 1, pollLimit: 1000 },
  );
  await state.submit();
  assert.equal(state.requestState.value, "settled");
  assert.ok(timeouts.every((timeout) => timeout > 0 && timeout <= 1000));
  state.dispose();
});

test("polling limit leaves a recoverable state without claiming failure", async () => {
  let polls = 0;
  const state = make(
    {
      createOrder: async () => result("PROCESSING"),
      getOrder: async () => {
        polls++;
        return result("PROCESSING");
      },
    },
    { pollMs: 1, pollLimit: 30 },
  );
  await state.submit();
  assert.ok(polls >= 1);
  assert.equal(state.requestState.value, "recoverable");
  assert.equal(state.locked.value, true);
  assert.equal(state.canResend.value, false);
  assert.equal(state.order.value.status, "PROCESSING");
  assert.ok(state.requestError.value instanceof ApiError);
  assert.equal(state.requestError.value.message, STILL_PROCESSING);
  assert.equal(state.requestError.value.code, "NETWORK_ERROR");
  state.dispose();
});

test("a polling error keeps the order recoverable with that error", async () => {
  const offline = new ApiError("offline");
  const state = make(
    {
      createOrder: async () => result("PROCESSING"),
      getOrder: async () => {
        throw offline;
      },
    },
    { pollMs: 1, pollLimit: 1000 },
  );
  await state.submit();
  assert.equal(state.requestState.value, "recoverable");
  assert.equal(state.requestError.value, offline);
  assert.equal(state.order.value.status, "PROCESSING");
  state.dispose();
});

test("unknown response shapes are treated as recoverable format errors", async () => {
  for (const bad of [
    { status: "SHIPPED" },
    { order_id: "x", status: "UNKNOWN" },
    null,
  ]) {
    const state = make({ createOrder: async () => bad });
    await state.submit();
    assert.equal(state.requestState.value, "recoverable");
    assert.equal(
      state.requestError.value.message,
      "주문 응답 형식을 확인할 수 없습니다.",
    );
    assert.equal(state.order.value, null);
    state.dispose();
  }
});

test("rejections before acceptance map server field errors and unlock input", async () => {
  let productLoads = 0;
  const error = new ApiError("없는 상품", {
    status: 404,
    code: "PRODUCT_NOT_FOUND",
    field_errors: [
      { field: "items[0].qty", message: "a" },
      { field: "paymentMethod", message: "b" },
      { field: "items[0].qty", message: "c" },
      { field: "shipping.paymentMethodpaymentMethod", message: "d" },
    ],
  });
  const state = make({
    listProducts: async () => {
      productLoads++;
      return { items: products };
    },
    createOrder: async () => {
      throw error;
    },
  });
  await state.submit();
  assert.equal(state.requestState.value, "settled");
  assert.equal(state.submittedRequest.value, null);
  assert.equal(state.locked.value, false);
  assert.equal(state.requestError.value, error);
  assert.equal(
    JSON.stringify(state.fieldErrors.value),
    JSON.stringify({
      "items[0].qty": "c",
      payment_method: "b",
      "shipping.payment_methodpaymentMethod": "d",
    }),
  );
  assert.equal(productLoads, 1);
  state.dispose();

  let plainLoads = 0;
  const plain = make({
    listProducts: async () => {
      plainLoads++;
      return { items: products };
    },
    createOrder: async () => {
      throw new ApiError("bad", { status: 400, field_errors: null });
    },
  });
  await plain.submit();
  assert.equal(plain.requestState.value, "settled");
  assert.deepEqual(plain.fieldErrors.value, {});
  assert.equal(plainLoads, 0);
  plain.dispose();
});

test("errors that may follow acceptance lock input as recoverable", async () => {
  const cases = [
    new ApiError("c", { status: 409, code: "IDEMPOTENCY_CONFLICT" }),
    new ApiError("accepted", { status: 400, order_id: "order-9" }),
    new ApiError("missing", { status: 404, code: "ORDER_NOT_FOUND" }),
    new ApiError("server", { status: 500 }),
  ];
  for (const error of cases) {
    const state = make({
      createOrder: async () => {
        throw error;
      },
    });
    await state.submit();
    assert.equal(state.requestState.value, "recoverable", error.message);
    assert.equal(state.locked.value, true);
    assert.equal(state.canResend.value, false);
    assert.equal(state.requestError.value, error);
    assert.deepEqual(state.fieldErrors.value, {});
    assert.equal(state.submittedRequest.value.key, "fixed-key");
    state.dispose();
  }
});

test("client validation errors stop submission before any request", async () => {
  let calls = 0;
  const state = make({
    createOrder: async () => {
      calls++;
      return result();
    },
  });
  state.form.value.items[0].qty = 0;
  await state.submit();
  assert.equal(calls, 0);
  assert.deepEqual(state.fieldErrors.value, {
    "items[0].qty": "수량은 1~99 정수로 입력해주세요.",
  });
  assert.equal(state.requestState.value, "idle");
  assert.equal(state.submittedRequest.value, null);
  assert.equal(state.locked.value, false);
  state.dispose();
});

test("submit freezes a deep copy of the form with the generated key", async () => {
  let complete;
  const calls = [];
  const state = make({
    createOrder: (key, body, options) => {
      calls.push({ key, body, options });
      return new Promise((resolve) => {
        complete = resolve;
      });
    },
  });
  state.fieldErrors.value = { customer: "old" };
  const pending = state.submit();
  state.form.value.items[0].qty = 5;
  state.form.value = { ...state.form.value, customer: "c_other" };
  assert.equal(calls.length, 1);
  assert.equal(calls[0].key, "fixed-key");
  assert.equal(JSON.stringify(calls[0].body), JSON.stringify(defaultForm()));
  assert.deepEqual(Object.keys(calls[0].options), ["signal"]);
  assert.ok(calls[0].options.signal instanceof AbortSignal);
  assert.deepEqual(state.fieldErrors.value, {});
  assert.equal(state.submittedRequest.value.key, "fixed-key");
  assert.equal(state.submittedRequest.value.body, calls[0].body);
  assert.equal(state.requestState.value, "submitting");
  assert.equal(state.order.value, null);
  complete(result());
  await pending;
  assert.equal(state.requestState.value, "settled");
  state.dispose();
});

test("presets rebuild the default form with one change and clear errors and snapshot", () => {
  const state = make();
  const expected = (change) => {
    const form = defaultForm();
    change(form);
    return JSON.stringify(form);
  };
  state.form.value = { ...state.form.value, snapshot_id: "snap-1" };
  state.fieldErrors.value = { customer: "x" };
  state.preset("normal");
  assert.equal(
    JSON.stringify(state.form.value),
    expected((f) => {
      f.items[0].qty = 2;
    }),
  );
  assert.deepEqual(state.fieldErrors.value, {});
  state.preset("soldout");
  assert.equal(
    JSON.stringify(state.form.value),
    expected((f) => {
      f.items[0].sku = "CAMP-MUG-STEEL";
    }),
  );
  state.preset("declined");
  assert.equal(
    JSON.stringify(state.form.value),
    expected((f) => {
      f.customer = "c_declined";
    }),
  );
  state.form.value = { ...state.form.value, snapshot_id: "snap-2" };
  state.preset("unknown");
  assert.equal(JSON.stringify(state.form.value), JSON.stringify(defaultForm()));
  state.dispose();
});

test("newOrder resets the form and result after a settled order", async () => {
  const state = make();
  await state.submit();
  state.form.value = { ...state.form.value, snapshot_id: "snap-1" };
  state.fieldErrors.value = { customer: "x" };
  state.newOrder();
  assert.equal(JSON.stringify(state.form.value), JSON.stringify(defaultForm()));
  assert.equal(state.order.value, null);
  assert.equal(state.submittedRequest.value, null);
  assert.equal(state.requestState.value, "idle");
  assert.equal(state.requestError.value, null);
  assert.deepEqual(state.fieldErrors.value, {});
  assert.equal(state.locked.value, false);
  state.dispose();
});

test("dispose releases a pending polling wait at once", async () => {
  let polls = 0;
  const state = make(
    {
      createOrder: async () => result("PROCESSING"),
      getOrder: async () => {
        polls++;
        return result("PROCESSING");
      },
    },
    { pollMs: 60000, pollLimit: 120000 },
  );
  const pending = state.submit();
  await flush();
  assert.equal(state.requestState.value, "polling");
  const started = Date.now();
  state.dispose();
  await pending;
  assert.ok(Date.now() - started < 1000);
  assert.equal(polls, 0);
  assert.equal(state.requestState.value, "polling");
});

test("product list must be an array and only the latest load is applied", async () => {
  const bad = make({ listProducts: async () => ({ items: null }) });
  await bad.loadProducts();
  assert.equal(bad.productStatus.value, "error");
  assert.equal(bad.productError.value, "상품 목록 형식이 올바르지 않습니다.");
  bad.dispose();

  const pending = [];
  const seen = [];
  const state = make({
    listProducts: (options) => {
      seen.push(options);
      return new Promise((resolve, reject) => pending.push({ resolve, reject }));
    },
  });
  const first = state.loadProducts();
  const second = state.loadProducts();
  assert.equal(state.productStatus.value, "loading");
  assert.deepEqual(Object.keys(seen[0]), ["signal"]);
  pending[1].resolve({ items: [{ sku: "B" }] });
  await second;
  pending[0].reject(new ApiError("late"));
  await first;
  assert.deepEqual(state.products.value, [{ sku: "B" }]);
  assert.equal(state.productStatus.value, "ready");
  assert.equal(state.productError.value, "");

  const third = state.loadProducts();
  pending[2].reject(new ApiError("offline"));
  await third;
  assert.equal(state.productStatus.value, "error");
  assert.equal(state.productError.value, "offline");
  assert.deepEqual(state.products.value, [{ sku: "B" }]);

  const fourth = state.loadProducts();
  state.dispose();
  pending[3].resolve({ items: [{ sku: "C" }] });
  await fourth;
  assert.equal(state.productStatus.value, "loading");
  assert.deepEqual(state.products.value, [{ sku: "B" }]);
});

test("recover is ignored without a submission, while in flight and after dispose", async () => {
  const lookups = [];
  const lookupApi = {
    getOrder: async (id) => {
      lookups.push(["getOrder", id]);
      return result();
    },
    getByRequest: async (key) => {
      lookups.push(["getByRequest", key]);
      return result();
    },
  };
  const fresh = make(lookupApi);
  await fresh.recover();
  assert.deepEqual(lookups, []);
  assert.equal(fresh.requestState.value, "idle");
  fresh.dispose();

  let complete;
  const inFlight = make({
    ...lookupApi,
    createOrder: () =>
      new Promise((resolve) => {
        complete = resolve;
      }),
  });
  const pending = inFlight.submit();
  await inFlight.recover();
  assert.deepEqual(lookups, []);
  complete(result());
  await pending;
  inFlight.dispose();

  const disposed = make({
    ...lookupApi,
    createOrder: async () => {
      throw new ApiError("lost");
    },
  });
  await disposed.submit();
  disposed.dispose();
  await disposed.recover();
  assert.deepEqual(lookups, []);
});

test("recover looks up by request key or by a known order id with the 5s status timeout", async () => {
  const lookups = [];
  const byKey = make({
    createOrder: async () => {
      throw new ApiError("lost");
    },
    getByRequest: async (key, options) => {
      lookups.push(["getByRequest", key, options]);
      return result();
    },
  });
  await byKey.submit();
  await byKey.recover();
  assert.equal(byKey.requestState.value, "settled");
  byKey.dispose();

  const byId = make({
    createOrder: async () => {
      throw new ApiError("failed", { status: 500, order_id: "known-id" });
    },
    getOrder: async (id, options) => {
      lookups.push(["getOrder", id, options]);
      return result();
    },
  });
  await byId.submit();
  await byId.recover();
  assert.equal(byId.requestState.value, "settled");
  byId.dispose();

  assert.deepEqual(
    lookups.map(([name, value]) => [name, value]),
    [
      ["getByRequest", "fixed-key"],
      ["getOrder", "known-id"],
    ],
  );
  for (const [, , options] of lookups) {
    assert.ok(options.signal instanceof AbortSignal);
    assert.equal(options.timeout ?? 5000, 5000);
    assert.ok(Object.keys(options).every((k) => k === "signal" || k === "timeout"));
  }
});

test("recover keeps the previous error visible while it checks", async () => {
  let complete;
  let lookups = 0;
  const lost = new ApiError("lost");
  const state = make({
    createOrder: async () => {
      throw lost;
    },
    getByRequest: () => {
      lookups++;
      return new Promise((resolve) => {
        complete = resolve;
      });
    },
  });
  await state.submit();
  const pending = state.recover();
  assert.equal(state.requestState.value, "polling");
  assert.equal(state.requestError.value, lost);
  assert.equal(state.canResend.value, false);
  await state.recover();
  assert.equal(lookups, 1);
  complete(result());
  await pending;
  assert.equal(state.requestState.value, "settled");
  assert.equal(state.requestError.value, null);
  state.dispose();
});

test("recover after the polling limit checks the known order again", async () => {
  let finished = false;
  const state = make(
    {
      createOrder: async () => result("PROCESSING"),
      getOrder: async (id) => {
        assert.equal(id, "order-1");
        return result(finished ? "SHIPPED" : "PROCESSING");
      },
    },
    { pollMs: 1, pollLimit: 20 },
  );
  await state.submit();
  assert.equal(state.requestState.value, "recoverable");
  finished = true;
  await state.recover();
  assert.equal(state.requestState.value, "settled");
  assert.equal(state.order.value.status, "SHIPPED");
  state.dispose();
});

test("a missing order while polling an order found by request key still permits resend", async () => {
  const state = make(
    {
      createOrder: async () => {
        throw new ApiError("lost");
      },
      getByRequest: async () => result("PROCESSING"),
      getOrder: async () => {
        throw new ApiError("missing", { status: 404, code: "ORDER_NOT_FOUND" });
      },
    },
    { pollMs: 1, pollLimit: 1000 },
  );
  await state.submit();
  await state.recover();
  assert.equal(state.requestState.value, "recoverable");
  assert.equal(state.canResend.value, true);
  assert.equal(state.requestError.value.message, RESEND_ALLOWED);
  state.dispose();
});

test("resend reuses the frozen key and body and clears the previous error", async () => {
  const requests = [];
  let complete;
  const state = make({
    createOrder: (key, body, options) => {
      requests.push({ key, body, options });
      if (requests.length === 1) return Promise.reject(new ApiError("lost"));
      return new Promise((resolve) => {
        complete = resolve;
      });
    },
    getByRequest: async () => {
      throw new ApiError("missing", { status: 404, code: "ORDER_NOT_FOUND" });
    },
  });
  await state.submit();
  await state.recover();
  assert.equal(state.canResend.value, true);
  assert.equal(state.requestError.value.message, RESEND_ALLOWED);
  state.form.value = { ...state.form.value, customer: "c_changed" };
  const pending = state.resend();
  assert.equal(state.requestState.value, "submitting");
  assert.equal(state.requestError.value, null);
  assert.equal(state.canResend.value, false);
  assert.equal(requests.length, 2);
  assert.equal(requests[1].key, requests[0].key);
  assert.equal(requests[1].body, requests[0].body);
  assert.deepEqual(Object.keys(requests[1].options), ["signal"]);
  complete(result());
  await pending;
  assert.equal(state.requestState.value, "settled");
  await state.resend();
  assert.equal(requests.length, 2);
  state.dispose();
});
