// order-agent/frontend/src/utils/validation.js 의 입력 선택지(데모 고객, 결제 방식, 배송 국가), 수량 범위 상수,
// itemFieldKey 형식을 보호한다. order-agent/frontend/src/components/OrderForm.vue 가 이 상수로 선택지를 그리므로,
// 목록에 있는 모든 값과 수량 경계값이 validate 를 통과하는지도 함께 확인한다.
import test from "node:test";
import assert from "node:assert/strict";
import {
  DECLINED_CUSTOMER,
  DEMO_CUSTOMERS,
  PAYMENT_METHODS,
  QTY_MAX,
  QTY_MIN,
  SHIPPING_COUNTRIES,
  defaultForm,
  itemFieldKey,
  validate,
} from "../src/utils/validation.js";

/** 기본 폼을 만들고 change 로 한 곳만 바꾼다. */
const formWith = (change) => {
  const form = defaultForm();
  change(form);
  return form;
};

test("input option lists keep the form choices and labels in screen order", () => {
  assert.equal(DECLINED_CUSTOMER, "c_declined");
  assert.deepEqual(DEMO_CUSTOMERS, [
    { value: "c_1001", label: "일반 고객 · c_1001" },
    { value: "c_declined", label: "결제 거절 고객 · c_declined" },
  ]);
  assert.deepEqual(PAYMENT_METHODS, [
    { value: "card", label: "카드 · Mock" },
    { value: "prepaid", label: "선입금 · Mock" },
  ]);
  assert.deepEqual(SHIPPING_COUNTRIES, [
    { value: "KR", label: "대한민국" },
    { value: "US", label: "미국" },
    { value: "JP", label: "일본" },
  ]);
  assert.deepEqual([QTY_MIN, QTY_MAX], [1, 99]);
});

test("every listed option and quantity bound passes validation", () => {
  for (const { value } of DEMO_CUSTOMERS)
    assert.deepEqual(validate(formWith((f) => (f.customer = value))), {});
  for (const { value } of PAYMENT_METHODS)
    assert.deepEqual(validate(formWith((f) => (f.payment_method = value))), {});
  for (const { value } of SHIPPING_COUNTRIES)
    assert.deepEqual(
      validate(formWith((f) => (f.shipping.country = value))),
      {},
    );
  for (const qty of [QTY_MIN, QTY_MAX])
    assert.deepEqual(validate(formWith((f) => (f.items[0].qty = qty))), {});
  assert.ok(
    validate(formWith((f) => (f.items[0].qty = QTY_MAX + 1)))[
      itemFieldKey(0, "qty")
    ],
  );
});

test("item field keys use the server field_errors path format", () => {
  assert.equal(itemFieldKey(0, "sku"), "items[0].sku");
  assert.equal(itemFieldKey(12, "qty"), "items[12].qty");
});
