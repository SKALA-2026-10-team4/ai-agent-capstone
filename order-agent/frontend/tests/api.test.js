// order-agent/frontend/src/api/client.js 의 request, postJson, ApiError 와, 그 위의 REST 함수
// (order-agent/frontend/src/api/orders.js, order-agent/frontend/src/api/products.js, order-agent/frontend/src/api/evidence.js)를 보호한다.
// 지키는 동작은 /api 접두사, fetch 에 넘기는 옵션, 서버 오류 본문의 ApiError 변환, JSON 이 아니거나 빈 본문의 처리,
// 네트워크와 timeout 문구, 기본 5초와 주문 생성 15초 timeout, 외부 취소 연결과 해제, 경로 인코딩, POST 본문과 헤더다.
// globalThis.fetch 를 가짜로 바꾸고 node:test 의 mock timers 로 시간을 흘리므로 서버 없이 돈다.
import test from "node:test";
import assert from "node:assert/strict";
import { ApiError, request } from "../src/api/client.js";
import { createOrder, getOrder, getByRequest } from "../src/api/orders.js";
import { listProducts } from "../src/api/products.js";
import {
  listExecutions,
  getTrace,
  getSnapshot,
  getSnapshotFile,
  publishSnapshot,
} from "../src/api/evidence.js";

// order-agent/frontend/src/api/client.js 가 만들어 화면에 보여주는 오류 문구다. 문구를 바꾸면 여기도 함께 고친다.
const TIMEOUT_MESSAGE =
  "응답 확인 시간이 초과되었습니다. 주문이 접수됐을 수 있습니다.";
const NETWORK_MESSAGE =
  "네트워크 연결을 확인해주세요. 주문이 접수됐을 수 있습니다.";
const SERVER_MESSAGE = "서버에 연결할 수 없습니다. 주문 결과를 확인해주세요.";

/** JSON 본문과 상태 코드를 가진 Response 를 만든다. */
const jsonResponse = (body, status = 200) =>
  new Response(JSON.stringify(body), {
    status,
    headers: { "Content-Type": "application/json" },
  });
/** 응답하지 않다가 signal 이 abort 되면 실제 fetch 처럼 AbortError 로 끝나는 가짜 요청이다. timeout 과 취소를 확인할 때 쓴다. */
const pendingUntilAbort = (url, init) =>
  new Promise((resolve, reject) => {
    const fail = () => reject(new DOMException("aborted", "AbortError"));
    if (init.signal.aborted) fail();
    init.signal.addEventListener("abort", fail);
  });
/** globalThis.fetch 를 respond 로 바꾸고 호출 기록(url, init)을 모은다. 테스트가 끝나면 node:test 가 원래 fetch 로 되돌린다. */
const stubFetch = (t, respond) => {
  const calls = [];
  t.mock.method(globalThis, "fetch", async (url, init) => {
    calls.push({ url, init });
    return respond(url, init);
  });
  return calls;
};
/** promise 가 ApiError 로 거절되고 expected 의 각 속성 값이 같은지 확인한다. */
const rejectsWith = (promise, expected) =>
  assert.rejects(promise, (error) => {
    assert.ok(error instanceof ApiError);
    for (const [key, value] of Object.entries(expected))
      assert.deepEqual(error[key], value, key);
    return true;
  });

test("ApiError keeps server fields under camelCase names with defaults", () => {
  const plain = new ApiError("m");
  assert.ok(plain instanceof Error);
  assert.equal(plain.name, "ApiError");
  assert.equal(plain.message, "m");
  assert.equal(plain.status, 0);
  assert.equal(plain.code, "NETWORK_ERROR");
  assert.equal(plain.orderId, null);
  assert.deepEqual(plain.fieldErrors, []);
  const full = new ApiError("x", {
    status: 404,
    code: "C",
    order_id: "o",
    field_errors: [{ field: "f", message: "m" }],
  });
  assert.deepEqual(
    { status: full.status, code: full.code, orderId: full.orderId },
    { status: 404, code: "C", orderId: "o" },
  );
  assert.deepEqual(full.fieldErrors, [{ field: "f", message: "m" }]);
});

test("request prefixes /api, passes options through and returns the JSON body", async (t) => {
  const calls = stubFetch(t, () => jsonResponse({ ok: 1 }));
  assert.deepEqual(await request("/x"), { ok: 1 });
  assert.equal(calls[0].url, "/api/x");
  assert.deepEqual(Object.keys(calls[0].init), ["signal"]);
  assert.ok(calls[0].init.signal instanceof AbortSignal);

  const external = new AbortController();
  await request("/y", {
    method: "PUT",
    headers: { a: "1" },
    body: "b",
    timeout: 1000,
    signal: external.signal,
  });
  assert.equal(calls[1].url, "/api/y");
  assert.deepEqual(Object.keys(calls[1].init), [
    "method",
    "headers",
    "body",
    "signal",
  ]);
  assert.deepEqual(calls[1].init.headers, { a: "1" });
  assert.notEqual(calls[1].init.signal, external.signal);
});

test("HTTP errors become ApiError with the server body fields", async (t) => {
  stubFetch(t, () =>
    jsonResponse(
      {
        message: "없음",
        code: "ORDER_NOT_FOUND",
        order_id: "o-1",
        field_errors: [{ field: "f", message: "m" }],
        status: 999,
      },
      404,
    ),
  );
  await rejectsWith(request("/x"), {
    message: "없음",
    status: 404,
    code: "ORDER_NOT_FOUND",
    orderId: "o-1",
    fieldErrors: [{ field: "f", message: "m" }],
  });
});

test("HTTP errors without a message or JSON body use the connection message", async (t) => {
  const bodies = [
    () => jsonResponse({ code: "X" }, 500),
    () => new Response("<html>bad gateway</html>", { status: 502 }),
  ];
  stubFetch(t, () => bodies.shift()());
  await rejectsWith(request("/x"), {
    message: SERVER_MESSAGE,
    status: 500,
    code: "X",
  });
  await rejectsWith(request("/x"), {
    message: SERVER_MESSAGE,
    status: 502,
    code: "NETWORK_ERROR",
    orderId: null,
    fieldErrors: [],
  });
});

test("successful responses without a JSON body are invalid responses", async (t) => {
  const bodies = [
    () => new Response("", { status: 200 }),
    () => jsonResponse(null),
    () => new Response(null, { status: 204 }),
  ];
  stubFetch(t, () => bodies.shift()());
  for (let i = 0; i < 3; i++) {
    await rejectsWith(request("/x"), {
      message: "서버 응답을 확인할 수 없습니다.",
      status: 0,
      code: "INVALID_RESPONSE",
    });
  }
});

test("fetch failures become network errors", async (t) => {
  stubFetch(t, () => {
    throw new TypeError("fetch failed");
  });
  await rejectsWith(request("/x"), {
    message: NETWORK_MESSAGE,
    status: 0,
    code: "NETWORK_ERROR",
  });
});

test("default timeout is 5s and reports the timeout message", async (t) => {
  t.mock.timers.enable({ apis: ["setTimeout"] });
  const calls = stubFetch(t, pendingUntilAbort);
  const pending = request("/x");
  t.mock.timers.tick(4999);
  assert.equal(calls[0].init.signal.aborted, false);
  t.mock.timers.tick(1);
  assert.equal(calls[0].init.signal.aborted, true);
  await rejectsWith(pending, { message: TIMEOUT_MESSAGE, status: 0 });
});

test("custom timeout and external abort both end with the timeout message", async (t) => {
  t.mock.timers.enable({ apis: ["setTimeout"] });
  const calls = stubFetch(t, pendingUntilAbort);
  const custom = request("/x", { timeout: 50 });
  t.mock.timers.tick(49);
  assert.equal(calls[0].init.signal.aborted, false);
  t.mock.timers.tick(1);
  await rejectsWith(custom, { message: TIMEOUT_MESSAGE });

  const external = new AbortController();
  const aborted = request("/x", { signal: external.signal });
  external.abort();
  assert.equal(calls[1].init.signal.aborted, true);
  await rejectsWith(aborted, { message: TIMEOUT_MESSAGE });

  const already = new AbortController();
  already.abort();
  const early = request("/x", { signal: already.signal });
  assert.equal(calls[2].init.signal.aborted, true);
  await rejectsWith(early, { message: TIMEOUT_MESSAGE });
});

test("a finished request releases its timer and external abort listener", async (t) => {
  t.mock.timers.enable({ apis: ["setTimeout"] });
  const calls = stubFetch(t, () => jsonResponse({ ok: 1 }));
  const external = new AbortController();
  await request("/x", { signal: external.signal });
  t.mock.timers.tick(10000);
  external.abort();
  assert.equal(calls[0].init.signal.aborted, false);
});

test("createOrder posts JSON with the idempotency key and a 15s timeout", async (t) => {
  t.mock.timers.enable({ apis: ["setTimeout"] });
  const calls = stubFetch(t, pendingUntilAbort);
  const external = new AbortController();
  const body = {
    customer: "c_1001",
    items: [{ sku: "A", qty: 1 }],
    payment_method: "card",
    shipping: { country: "KR", express: false },
    snapshot_id: "snap-1",
  };
  const pending = createOrder("key-1", body, { signal: external.signal });
  const { url, init } = calls[0];
  assert.equal(url, "/api/orders");
  assert.deepEqual(Object.keys(init), ["method", "headers", "body", "signal"]);
  assert.equal(init.method, "POST");
  assert.deepEqual(Object.entries(init.headers), [
    ["Content-Type", "application/json"],
    ["Idempotency-Key", "key-1"],
  ]);
  assert.equal(init.body, JSON.stringify(body));
  t.mock.timers.tick(14999);
  assert.equal(init.signal.aborted, false);
  t.mock.timers.tick(1);
  assert.equal(init.signal.aborted, true);
  await rejectsWith(pending, { message: TIMEOUT_MESSAGE });

  const shorter = createOrder("key-2", body, { timeout: 100 });
  t.mock.timers.tick(100);
  await rejectsWith(shorter, { message: TIMEOUT_MESSAGE });
});

test("order, product and evidence GETs encode path segments and use the 5s default", async (t) => {
  t.mock.timers.enable({ apis: ["setTimeout"] });
  const calls = stubFetch(t, () => jsonResponse({ ok: 1 }));
  const signal = new AbortController().signal;
  await getOrder("a/b", { signal });
  await getByRequest("k y", { signal });
  await listProducts({ signal });
  await listExecutions({ signal });
  await getTrace("e/1", { signal });
  await getSnapshot("s 1", { signal });
  await getSnapshotFile("s/1", "skills/x y.md", { signal });
  await getOrder("plain");
  assert.deepEqual(
    calls.map((call) => call.url),
    [
      "/api/orders/a%2Fb",
      "/api/orders/by-request/k%20y",
      "/api/products",
      "/api/executions",
      "/api/executions/e%2F1/trace",
      "/api/snapshots/s%201",
      "/api/snapshots/s%2F1/files?path=skills%2Fx%20y.md",
      "/api/orders/plain",
    ],
  );
  assert.ok(calls.every((call) => Object.keys(call.init).join() === "signal"));

  calls.length = 0;
  t.mock.method(globalThis, "fetch", async (url, init) => {
    calls.push({ url, init });
    return pendingUntilAbort(url, init);
  });
  const pending = getTrace("e-2");
  t.mock.timers.tick(4999);
  assert.equal(calls[0].init.signal.aborted, false);
  t.mock.timers.tick(1);
  await rejectsWith(pending, { message: TIMEOUT_MESSAGE });
});

test("publishSnapshot posts the fixture name as JSON with the 5s default", async (t) => {
  t.mock.timers.enable({ apis: ["setTimeout"] });
  const calls = stubFetch(t, pendingUntilAbort);
  const external = new AbortController();
  const pending = publishSnapshot("baseline", { signal: external.signal });
  const { url, init } = calls[0];
  assert.equal(url, "/api/snapshots");
  assert.deepEqual(Object.keys(init), ["method", "headers", "body", "signal"]);
  assert.equal(init.method, "POST");
  assert.deepEqual(Object.entries(init.headers), [
    ["Content-Type", "application/json"],
  ]);
  assert.equal(init.body, '{"fixture":"baseline"}');
  t.mock.timers.tick(4999);
  assert.equal(init.signal.aborted, false);
  t.mock.timers.tick(1);
  await rejectsWith(pending, { message: TIMEOUT_MESSAGE });
});
