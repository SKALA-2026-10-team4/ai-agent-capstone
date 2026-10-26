// order-agent/frontend/src/utils/format.js 의 money 와 timestamp 포맷, 그리고 주문 상태, 처리 단계, 단계 상태 라벨표의
// 값과 순서, 그리고 업무 종료 사유와 동작 라벨표의 값과 순서를 보호한다. 라벨 문구는 화면과 e2e 가 그대로 쓰므로, 표를 바꾸면 이 테스트의 기대값도 함께 고친다.
import test from "node:test";
import assert from "node:assert/strict";
import {
  money,
  timestamp,
  statusText,
  stopReasonText,
  stopActionText,
  stepText,
  stepStatus,
} from "../src/utils/format.js";

test("money formats whole won amounts in ko-KR currency style", () => {
  assert.equal(money(60000), "₩60,000");
  assert.equal(money(0), "₩0");
  assert.equal(money(1234.5), "₩1,235");
  assert.equal(money(-500), "-₩500");
});

test("timestamp shows a dash for empty values and local ko-KR time otherwise", () => {
  assert.equal(timestamp(null), "—");
  assert.equal(timestamp(undefined), "—");
  assert.equal(timestamp(""), "—");
  assert.equal(
    timestamp("2026-10-22T01:00:00Z"),
    new Date("2026-10-22T01:00:00Z").toLocaleString("ko-KR"),
  );
});

test("order status, step and step status label tables keep every value and order", () => {
  assert.deepEqual(Object.entries(statusText), [
    ["CREATED", "주문 접수"],
    ["PROCESSING", "처리 중"],
    ["SHIPPED", "배송 예약 완료"],
    ["REJECTED", "재고 부족 · 주문 거절"],
    ["HOLD", "결제 거절 · 주문 보류"],
    ["FAILED", "처리 오류"],
  ]);
  assert.deepEqual(Object.entries(stopReasonText), [
    ["OUT_OF_STOCK", "재고 부족"],
    ["PAYMENT_DECLINED", "결제 거절"],
    ["NO_SELECTION", "Skill 미선택"],
  ]);
  assert.deepEqual(Object.entries(stopActionText), [
    ["REJECTED", "주문 거절"],
    ["HOLD", "주문 보류"],
  ]);
  assert.deepEqual(Object.entries(stepText), [
    ["INTAKE", "주문 접수"],
    ["INVENTORY", "재고 예약"],
    ["PAYMENT", "결제"],
    ["RELEASE", "재고 예약 해제"],
    ["SHIPPING", "배송 예약"],
    ["NOTIFICATION", "알림"],
    ["RECOVERY", "중단된 실행 확인"],
  ]);
  assert.deepEqual(Object.entries(stepStatus), [
    ["SUCCESS", "완료"],
    ["REJECTED", "업무 거절"],
    ["FAILED", "오류"],
    ["SKIPPED", "미실행"],
  ]);
});
