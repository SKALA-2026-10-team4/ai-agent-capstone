// 주문 화면이 쓰는 금액과 시각 포맷 함수, 그리고 주문 상태, 업무 종료 사유, 처리 단계, 단계 상태, 결제 상태 라벨표를 모은 모듈이다.
// 라벨표의 키는 order-agent/backend/src/main/java/com/capstone4/orderagent/entity/enums/ 의 OrderStatus, ProcessingStepType,
// ProcessingStepStatus, PaymentStatus 값과 같아야 한다. 표에 없는 값은 labelOf 가 원래 값으로 보여주므로,
// 백엔드가 새 값을 더해도 화면은 깨지지 않고 영문 이름이 보인다.
// 화면 문구를 바꾸려면 이 표를 고친다. order-agent/frontend/tests/format.test.js 와 order-agent/frontend/tests/formatLabels.test.js 가
// 표의 값과 순서를 고정하고, order-agent/frontend/tests/e2e/order.spec.js 가 "배송 예약 완료", "승인 완료", "재고 예약 해제" 문구를 확인한다.
// order-agent/frontend/src/components/OrderForm.vue, order-agent/frontend/src/components/OrderResult.vue,
// order-agent/frontend/src/components/OrderSteps.vue, order-agent/frontend/src/utils/evidenceFormat.js 가 쓴다.

/** 원화 금액을 ko-KR 통화 형식(예: ₩60,000)으로 바꾼다. 원 단위 아래는 반올림해 보이지 않는다. */
export const money = (value) =>
  new Intl.NumberFormat("ko-KR", {
    style: "currency",
    currency: "KRW",
    maximumFractionDigits: 0,
  }).format(value);
// 주문 상태 라벨표다. order-agent/frontend/src/components/OrderResult.vue 의 상태 배지에 보인다.
// REJECTED 와 HOLD 의 라벨은 result_code 를 모를 때(과거 주문)만 쓰고, result_code 가 있으면 orderStatusLabel 이
// stopReasonText 와 stopActionText 로 라벨을 만든다.
export const statusText = {
  CREATED: "주문 접수",
  PROCESSING: "처리 중",
  SHIPPED: "배송 예약 완료",
  REJECTED: "재고 부족 · 주문 거절",
  HOLD: "결제 거절 · 주문 보류",
  FAILED: "처리 오류",
};
// 업무 종료 주문(REJECTED, HOLD)의 result_code 별 사유 라벨표다. 키는 도구나 Harness 가 정하는 결과 코드
// (order-agent/backend/src/main/java/com/capstone4/orderagent/entity/Order.java 의 reject, hold, noSelection)이다.
// 표에 없는 result_code 는 단계 Skill 실행 규칙(rules)의 reason(Order.stopByRule)이라 "업무 규칙(REASON)" 으로 보인다.
// order-agent/backend/src/main/java/com/capstone4/orderagent/dto/order/OrderResponse.java 의 NON_RULE_CODES 와 같은 구분이다.
export const stopReasonText = {
  OUT_OF_STOCK: "재고 부족",
  PAYMENT_DECLINED: "결제 거절",
  NO_SELECTION: "Skill 미선택",
};
// 업무 종료 주문 상태 라벨의 뒷부분이다. 실행 규칙의 hold 는 HOLD, reject 는 REJECTED 다.
export const stopActionText = {
  REJECTED: "주문 거절",
  HOLD: "주문 보류",
};
// 처리 단계 이름 라벨표다. order-agent/frontend/src/components/OrderSteps.vue 의 타임라인에 보인다.
export const stepText = {
  INTAKE: "주문 접수",
  INVENTORY: "재고 예약",
  PAYMENT: "결제",
  RELEASE: "재고 예약 해제",
  SHIPPING: "배송 예약",
  NOTIFICATION: "알림",
  RECOVERY: "중단된 실행 확인",
};
// 처리 단계 상태 라벨표다. REJECTED 는 재고 부족이나 결제 거절 같은 업무 거절이고, FAILED 는 처리 오류다.
export const stepStatus = {
  SUCCESS: "완료",
  REJECTED: "업무 거절",
  FAILED: "오류",
  SKIPPED: "미실행",
};
// 결제 상태 라벨표다. order-agent/frontend/src/components/OrderResult.vue 의 결제 요약에 보인다.
export const paymentStatusText = {
  PAID: "승인 완료",
  DECLINED: "결제 거절",
  NOT_ATTEMPTED: "미실행",
};
/** 서버 시각 문자열을 브라우저 지역 시간의 ko-KR 형식으로 바꾼다. 값이 비어 있으면 "—" 를 돌려준다. */
export const timestamp = (value) =>
  value ? new Date(value).toLocaleString("ko-KR") : "—";
/**
 * labels 표에서 value 의 라벨을 찾는다. 표에 없거나 라벨이 빈 문자열이면 원래 값을 그대로 돌려준다.
 * 이 파일의 라벨 함수와 order-agent/frontend/src/utils/evidenceFormat.js 의 eventKindLabel 이 이 폴백 규칙을 함께 쓴다.
 */
export const labelOf = (labels, value) => labels[value] || value;
/**
 * 주문 상태 라벨을 돌려준다. order-agent/frontend/src/components/OrderResult.vue 의 상태 배지가 주문 응답의 status 와 result_code 로 부른다.
 * REJECTED 나 HOLD 이고 result_code 가 있으면 "사유 · 주문 거절/보류" 이며, 사유는 stopReasonText 에 있으면 그 라벨,
 * 없으면 실행 규칙 reason 으로 보고 "업무 규칙(REASON)" 이다. 그래서 OUT_OF_STOCK 과 PAYMENT_DECLINED 는 statusText 와 같은 문구다.
 * 그 밖의 상태나 result_code 가 없으면 statusText 를 labelOf 로 읽는다.
 */
export const orderStatusLabel = (status, resultCode) =>
  Object.hasOwn(stopActionText, status) && resultCode
    ? `${stopReasonText[resultCode] || `업무 규칙(${resultCode})`} · ${stopActionText[status]}`
    : labelOf(statusText, status);
/** 처리 단계 이름 라벨을 돌려준다. order-agent/frontend/src/components/OrderSteps.vue 가 쓴다. */
export const stepLabel = (step) => labelOf(stepText, step);
/** 처리 단계 상태 라벨을 돌려준다. order-agent/frontend/src/components/OrderSteps.vue 가 쓴다. */
export const stepStatusLabel = (status) => labelOf(stepStatus, status);
/** 결제 상태 라벨을 돌려준다. order-agent/frontend/src/components/OrderResult.vue 의 결제 요약이 쓴다. */
export const paymentStatusLabel = (status) =>
  labelOf(paymentStatusText, status);
/**
 * 성공 알림 문구를 돌려준다. 알림은 실제로 보내지 않는 Mock 이라 SENT 면 기록 완료로, 그 밖의 값은 미실행으로 보인다.
 * order-agent/frontend/src/components/OrderResult.vue 가 쓴다.
 */
export const notificationLabel = (status) =>
  status === "SENT" ? "Mock 기록 완료" : "미실행";
/**
 * 처리 단계 상태 아이콘을 돌려준다. SUCCESS 는 체크 표시, SKIPPED 는 빼기 기호, 그 밖의 상태는 느낌표다.
 * order-agent/frontend/src/components/OrderSteps.vue 가 단계 점 안에 그린다.
 */
export const stepIcon = (status) =>
  status === "SUCCESS" ? "✓" : status === "SKIPPED" ? "−" : "!";
