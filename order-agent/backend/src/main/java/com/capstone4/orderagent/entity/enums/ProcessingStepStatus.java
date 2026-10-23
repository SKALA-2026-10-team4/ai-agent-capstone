package com.capstone4.orderagent.entity.enums;

/**
 * 주문 처리 단계 기록의 상태다. SUCCESS(완료), REJECTED(재고 부족이나 결제 거절 같은 업무 거절), FAILED(실행 오류),
 * SKIPPED(앞 단계의 거절이나 오류로 실행하지 않음) 가운데 하나이며 order_processing_steps.status 에 이름 문자열로 저장한다.
 * SKIPPED 의 결과 코드는 거절 뒤면 UPSTREAM_REJECTED(order-agent/backend/src/main/java/com/capstone4/orderagent/service/OrderToolService.java 의 skip),
 * 오류 뒤면 UPSTREAM_FAILED(order-agent/backend/src/main/java/com/capstone4/orderagent/service/OrderLifecycleService.java 의 fail)다.
 * order-agent/frontend/src/utils/format.js 의 stepStatus 와 stepIcon 이 화면 문구와 아이콘을 정한다.
 */
public enum ProcessingStepStatus { SUCCESS, REJECTED, FAILED, SKIPPED }
