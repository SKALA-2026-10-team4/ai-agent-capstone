// order-agent/frontend/src/utils/format.js 의 라벨 helper(labelOf, orderStatusLabel, stepLabel, stepStatusLabel,
// paymentStatusLabel, notificationLabel, stepIcon)를 보호한다.
// 표에 없거나 빈 라벨은 원래 값을 보여주는 폴백 규칙과, result_code 로 나누는 주문 상태 라벨(재고 부족, 결제 거절, 업무 규칙),
// 결제 라벨표, 알림 문구, 단계 아이콘 규칙을 고정한다.
import test from "node:test";
import assert from "node:assert/strict";
import {
  labelOf,
  notificationLabel,
  orderStatusLabel,
  paymentStatusLabel,
  paymentStatusText,
  statusText,
  stepIcon,
  stepLabel,
  stepStatus,
  stepStatusLabel,
  stepText,
} from "../src/utils/format.js";

test("labelOf falls back to the raw value for unknown or empty labels", () => {
  assert.equal(labelOf({ A: "에이" }, "A"), "에이");
  assert.equal(labelOf({ A: "에이" }, "B"), "B");
  assert.equal(labelOf({ A: "" }, "A"), "A");
  assert.equal(labelOf({ A: "에이" }, undefined), undefined);
});

test("label helpers read their tables and keep unknown values", () => {
  for (const [value, label] of Object.entries(statusText))
    assert.equal(orderStatusLabel(value), label);
  for (const [value, label] of Object.entries(stepText))
    assert.equal(stepLabel(value), label);
  for (const [value, label] of Object.entries(stepStatus))
    assert.equal(stepStatusLabel(value), label);
  assert.deepEqual(Object.entries(paymentStatusText), [
    ["PAID", "승인 완료"],
    ["DECLINED", "결제 거절"],
    ["NOT_ATTEMPTED", "미실행"],
  ]);
  for (const [value, label] of Object.entries(paymentStatusText))
    assert.equal(paymentStatusLabel(value), label);
  assert.equal(orderStatusLabel("NEW_STATUS"), "NEW_STATUS");
  assert.equal(stepLabel("CUSTOM_STEP"), "CUSTOM_STEP");
  assert.equal(stepStatusLabel("WEIRD"), "WEIRD");
  assert.equal(paymentStatusLabel("PENDING"), "PENDING");
});

test("order status label names the stop reason from result_code", () => {
  // 도구가 끝낸 재고 부족과 결제 거절은 예전 문구 그대로다.
  assert.equal(orderStatusLabel("REJECTED", "OUT_OF_STOCK"), "재고 부족 · 주문 거절");
  assert.equal(orderStatusLabel("HOLD", "PAYMENT_DECLINED"), "결제 거절 · 주문 보류");
  assert.equal(orderStatusLabel("REJECTED", "OUT_OF_STOCK"), statusText.REJECTED);
  assert.equal(orderStatusLabel("HOLD", "PAYMENT_DECLINED"), statusText.HOLD);
  // 엔트리 미선택 거절은 재고 부족이라고 하지 않는다.
  assert.equal(orderStatusLabel("REJECTED", "NO_SELECTION"), "Skill 미선택 · 주문 거절");
  // 그 밖의 result_code 는 실행 규칙 reason 이다.
  assert.equal(orderStatusLabel("HOLD", "PREPAID_ONLY"), "업무 규칙(PREPAID_ONLY) · 주문 보류");
  assert.equal(orderStatusLabel("REJECTED", "DOMESTIC_ONLY"), "업무 규칙(DOMESTIC_ONLY) · 주문 거절");
  // result_code 가 없는 과거 주문과 업무 종료가 아닌 상태는 statusText 를 그대로 쓴다.
  assert.equal(orderStatusLabel("REJECTED", null), "재고 부족 · 주문 거절");
  assert.equal(orderStatusLabel("HOLD", undefined), "결제 거절 · 주문 보류");
  assert.equal(orderStatusLabel("SHIPPED", null), "배송 예약 완료");
  assert.equal(orderStatusLabel("FAILED", "CONTRACT_VIOLATION"), "처리 오류");
  assert.equal(orderStatusLabel("NEW_STATUS", "SOME_CODE"), "NEW_STATUS");
});

test("notification label and step icon follow the result screen rules", () => {
  assert.equal(notificationLabel("SENT"), "Mock 기록 완료");
  assert.equal(notificationLabel("NOT_ATTEMPTED"), "미실행");
  assert.equal(notificationLabel(undefined), "미실행");
  assert.equal(stepIcon("SUCCESS"), "✓");
  assert.equal(stepIcon("SKIPPED"), "−");
  assert.equal(stepIcon("REJECTED"), "!");
  assert.equal(stepIcon("FAILED"), "!");
  assert.equal(stepIcon("WEIRD"), "!");
});
