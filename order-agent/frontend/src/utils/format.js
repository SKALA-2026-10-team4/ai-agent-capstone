export const money = (value) =>
  new Intl.NumberFormat("ko-KR", {
    style: "currency",
    currency: "KRW",
    maximumFractionDigits: 0,
  }).format(value);
export const statusText = {
  CREATED: "주문 접수",
  PROCESSING: "처리 중",
  SHIPPED: "배송 예약 완료",
  REJECTED: "재고 부족 · 주문 거절",
  HOLD: "결제 거절 · 주문 보류",
  FAILED: "처리 오류",
};
export const stepText = {
  INTAKE: "주문 접수",
  INVENTORY: "재고 예약",
  PAYMENT: "결제",
  RELEASE: "재고 예약 해제",
  SHIPPING: "배송 예약",
  NOTIFICATION: "알림",
  RECOVERY: "중단된 실행 확인",
};
export const stepStatus = {
  SUCCESS: "완료",
  REJECTED: "업무 거절",
  FAILED: "오류",
  SKIPPED: "미실행",
};
export const timestamp = (value) =>
  value ? new Date(value).toLocaleString("ko-KR") : "—";
