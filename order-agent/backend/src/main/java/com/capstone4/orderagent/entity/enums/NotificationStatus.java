package com.capstone4.orderagent.entity.enums;

/**
 * 성공 알림 기록 상태다. NOT_ATTEMPTED 는 알림 단계가 실행되지 않은 상태, SENT 는 notify.send 도구가 Mock 알림을 기록한 상태이며 외부로 보내지는 않는다.
 * orders.notification_status 에 이름 문자열로 저장하고 주문 응답의 notification.status 로 나간다.
 * order-agent/backend/src/main/java/com/capstone4/orderagent/entity/Order.java 의 notified 가 SENT 로 바꾸고, complete 는 SENT 일 때만 SHIPPED 로 확정한다.
 * order-agent/frontend/src/utils/format.js 의 notificationLabel 이 SENT 를 화면 문구로 바꾼다.
 */
public enum NotificationStatus { NOT_ATTEMPTED, SENT }
