package com.capstone4.orderagent.entity.enums;

/**
 * 결제 상태다. NOT_ATTEMPTED(결제 전), PAID(Mock 승인), DECLINED(Mock 거절) 가운데 하나이며 orders.payment_status 에 이름 문자열로 저장한다.
 * PAID 는 자동으로 되돌리지 않는 확정 결과다. 그래서 실행 실패 보상
 * (order-agent/backend/src/main/java/com/capstone4/orderagent/service/OrderToolService.java 의 compensateUnpaid)과 재시작 복구
 * (order-agent/backend/src/main/java/com/capstone4/orderagent/service/InventoryRecovery.java 의 releaseUnpaid)는 PAID 주문의 재고 예약을 풀지 않는다.
 * 배송 예약도 PAID 일 때만 허용한다(order-agent/backend/src/main/java/com/capstone4/orderagent/entity/Order.java 의 shipped).
 * order-agent/frontend/src/utils/format.js 의 paymentStatusText 가 화면 문구로 바꾼다.
 */
public enum PaymentStatus { NOT_ATTEMPTED, PAID, DECLINED }
