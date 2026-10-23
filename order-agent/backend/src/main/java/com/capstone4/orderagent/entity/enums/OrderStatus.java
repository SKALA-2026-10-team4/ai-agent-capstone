package com.capstone4.orderagent.entity.enums;

/**
 * 주문 상태다. CREATED(접수), PROCESSING(처리 중), SHIPPED(정상 완료), REJECTED(재고 부족이나 엔트리 미선택으로 거절),
 * HOLD(결제 거절로 보류), FAILED(실행 오류나 재시작 중단) 가운데 하나다. 앞의 둘은 아직 끝나지 않은 상태이고 나머지 넷은 끝난 상태다.
 * orders.status 에 이름 문자열로 저장하며 order-agent/backend/src/main/resources/schema.sql 의 CHECK 가 같은 여섯 값만 허용한다.
 * 이름은 모듈 안팎에서 문자열로 비교한다. order-agent/backend/src/main/java/com/capstone4/orderagent/repository/OrderRepository.java 의 claim JPQL,
 * order-agent/backend/src/main/java/com/capstone4/orderagent/harness/OrderFlowPolicy.java 의 CONTINUE_STATUSES,
 * frontend/src/api/orderAgent.js 의 UNFINISHED(CREATED 와 PROCESSING 이면 다시 조회), frontend/src/components/StatusBadge.vue 의 색 표,
 * order-agent/frontend/src/utils/format.js 의 statusText 가 그렇다. 값을 더하거나 바꾸면 이 소비자와 schema.sql 을 함께 고친다.
 */
public enum OrderStatus { CREATED, PROCESSING, SHIPPED, REJECTED, HOLD, FAILED }
