// order-agent/frontend/src/utils/validation.js 의 defaultForm, validate, estimate 를 보호한다.
// 기본 폼의 JSON 모양과 키 순서, 고객 형식, 상품 줄 수와 중복, 수량 범위, 결제 방식과 배송 국가 선택지,
// 오류 키 순서와 문구, 예상 금액의 경계값을 고정한다. 검증 규칙은 서버
// order-agent/backend/src/main/java/com/capstone4/orderagent/dto/order/CreateOrderRequest.java 의 규칙과 맞춰 두었다.
import test from "node:test";
import assert from "node:assert/strict";
import { defaultForm, estimate, validate } from "../src/utils/validation.js";

// order-agent/frontend/src/utils/validation.js 의 validate 가 쓰는 수량 오류 문구다. QTY_MIN 과 QTY_MAX 가 바뀌면 이 문구도 바뀐다.
const QTY_MESSAGE = "수량은 1~99 정수로 입력해주세요.";
// estimate 에 넘길 가짜 상품 목록이다. 단가가 없는 NO-PRICE 는 그 줄을 0 원으로 계산하는지 보려고 둔다.
const products = [
  { sku: "CAMP-LANTERN-LED", unit_price: 30000 },
  { sku: "CAMP-CHAIR-FOLD", unit_price: 45000 },
  { sku: "NO-PRICE" },
];
/** 기본 폼을 만들고 change 로 한 곳만 바꾼다. */
const formWith = (change) => {
  const form = defaultForm();
  change(form);
  return form;
};

test("default form is a fresh valid request body with a stable key order", () => {
  assert.equal(
    JSON.stringify(defaultForm()),
    '{"customer":"c_1001","items":[{"sku":"CAMP-LANTERN-LED","qty":1}],"payment_method":"card","shipping":{"country":"KR","express":false}}',
  );
  assert.notEqual(defaultForm(), defaultForm());
  assert.notEqual(defaultForm().items, defaultForm().items);
  assert.deepEqual(validate(defaultForm()), {});
});

test("customer must be a trimmed c_ id of at most 64 characters", () => {
  const error = { customer: "데모 고객을 선택해주세요." };
  assert.deepEqual(validate(formWith((f) => (f.customer = "x"))), error);
  assert.deepEqual(validate(formWith((f) => (f.customer = "c_"))), error);
  assert.deepEqual(validate(formWith((f) => (f.customer = "c_a-b"))), error);
  assert.deepEqual(
    validate(formWith((f) => (f.customer = "c_" + "a".repeat(63)))),
    error,
  );
  assert.deepEqual(
    validate(formWith((f) => (f.customer = "c_" + "a".repeat(62)))),
    {},
  );
  assert.deepEqual(validate(formWith((f) => (f.customer = " c_1001 "))), {});
  assert.deepEqual(validate(formWith((f) => (f.customer = "c_declined"))), {});
});

test("items need 1 to 20 rows with distinct non-empty skus", () => {
  const countError = { items: "상품을 1개 이상 선택해주세요." };
  assert.deepEqual(validate(formWith((f) => (f.items = []))), countError);
  const rows = (n) =>
    Array.from({ length: n }, (_, i) => ({ sku: `SKU-${i}`, qty: 1 }));
  assert.deepEqual(validate(formWith((f) => (f.items = rows(20)))), {});
  assert.deepEqual(validate(formWith((f) => (f.items = rows(21)))), countError);
  assert.deepEqual(
    validate(
      formWith((f) => (f.items = [...f.items, { sku: f.items[0].sku, qty: 1 }])),
    ),
    { "items[1].sku": "서로 다른 상품을 선택해주세요." },
  );
  assert.deepEqual(validate(formWith((f) => (f.items[0].sku = ""))), {
    "items[0].sku": "서로 다른 상품을 선택해주세요.",
  });
});

test("quantity must be an integer from 1 to 99", () => {
  for (const qty of [0, 100, 1.5, "", "2", -1, NaN, null]) {
    assert.deepEqual(
      validate(formWith((f) => (f.items[0].qty = qty))),
      { "items[0].qty": QTY_MESSAGE },
      `qty ${String(qty)}`,
    );
  }
  for (const qty of [1, 2, 99]) {
    assert.deepEqual(validate(formWith((f) => (f.items[0].qty = qty))), {});
  }
});

test("payment method and shipping country must be listed demo options", () => {
  for (const method of ["card", "prepaid"]) {
    assert.deepEqual(
      validate(formWith((f) => (f.payment_method = method))),
      {},
    );
  }
  assert.deepEqual(validate(formWith((f) => (f.payment_method = "cash"))), {
    payment_method: "결제 방식을 선택해주세요.",
  });
  for (const country of ["KR", "US", "JP"]) {
    assert.deepEqual(
      validate(formWith((f) => (f.shipping.country = country))),
      {},
    );
  }
  assert.deepEqual(validate(formWith((f) => (f.shipping.country = "CN"))), {
    "shipping.country": "배송 국가를 선택해주세요.",
  });
});

test("all errors are reported together in a fixed key order", () => {
  const errors = validate({
    customer: "bad",
    items: [
      { sku: "A", qty: 0 },
      { sku: "A", qty: 1 },
      { sku: "", qty: 100 },
    ],
    payment_method: "cash",
    shipping: { country: "CN", express: false },
  });
  assert.deepEqual(Object.entries(errors), [
    ["customer", "데모 고객을 선택해주세요."],
    ["items[0].qty", QTY_MESSAGE],
    ["items[1].sku", "서로 다른 상품을 선택해주세요."],
    ["items[2].sku", "서로 다른 상품을 선택해주세요."],
    ["items[2].qty", QTY_MESSAGE],
    ["payment_method", "결제 방식을 선택해주세요."],
    ["shipping.country", "배송 국가를 선택해주세요."],
  ]);
  const tooMany = validate({
    customer: "c_1001",
    items: Array.from({ length: 21 }, () => ({ sku: "A", qty: 1 })),
    payment_method: "card",
    shipping: { country: "KR", express: false },
  });
  assert.deepEqual(Object.keys(tooMany).slice(0, 3), [
    "items",
    "items[1].sku",
    "items[2].sku",
  ]);
});

test("estimate multiplies unit prices by positive integer quantities only", () => {
  const form = defaultForm();
  form.items[0].qty = 2;
  assert.equal(estimate(form, products), 60000);
  form.items[0].qty = "";
  assert.equal(estimate(form, products), 0);
  form.items[0].qty = 150;
  assert.equal(estimate(form, products), 4500000);
  form.items[0].qty = 1.5;
  assert.equal(estimate(form, products), 0);
  form.items[0].qty = -1;
  assert.equal(estimate(form, products), 0);
  form.items = [
    { sku: "CAMP-LANTERN-LED", qty: 1 },
    { sku: "CAMP-CHAIR-FOLD", qty: 2 },
    { sku: "UNKNOWN", qty: 3 },
    { sku: "NO-PRICE", qty: 4 },
  ];
  assert.equal(estimate(form, products), 120000);
  assert.equal(estimate(form, []), 0);
});
