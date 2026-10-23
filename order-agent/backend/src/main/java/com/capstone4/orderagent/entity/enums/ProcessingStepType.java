package com.capstone4.orderagent.entity.enums;

import java.util.List;

/**
 * 주문 처리 단계의 종류다. INTAKE(접수), INVENTORY(재고 예약), PAYMENT(결제), RELEASE(재고 예약 해제), SHIPPING(배송 예약),
 * NOTIFICATION(성공 알림), RECOVERY(어느 단계인지 모르는 실패와 재시작 복구) 가운데 하나이며 order_processing_steps.step 에 이름 문자열로 저장한다.
 * 선언 순서(ordinal)는 업무 순서가 아니다. RELEASE 가 PAYMENT 와 SHIPPING 사이에 있으므로 순서가 필요하면 BUSINESS_FLOW 와 after 를 쓴다.
 * 단계 Skill 과 단계 종류의 연결은 order-agent/backend/src/main/java/com/capstone4/orderagent/harness/ToolCatalog.java 의 BINDINGS 와 stepTypeFor 에 있다.
 * 값을 더하면 BUSINESS_FLOW 의 알맞은 자리에 넣고 order-agent/frontend/src/utils/format.js 의 stepText 에 화면 이름을 더한다.
 * order-agent/backend/src/test/java/com/capstone4/orderagent/entity/enums/ProcessingStepTypeTest.java 가 선언 순서와 업무 순서를 고정한다.
 */
public enum ProcessingStepType { INTAKE, INVENTORY, PAYMENT, RELEASE, SHIPPING, NOTIFICATION, RECOVERY;
    /**
     * 업무 단계의 실행 순서다. 거절이나 실패 뒤 SKIPPED 로 남길 단계를 이 순서로 정하며,
     * order-agent/backend/src/main/java/com/capstone4/orderagent/service/OrderToolService.java 의 거절과 미선택 처리,
     * order-agent/backend/src/main/java/com/capstone4/orderagent/service/OrderLifecycleService.java 의 fail 이 이 목록이나 after 를 쓴다.
     * 순서를 바꾸면 주문 응답의 SKIPPED 줄 순서와
     * order-agent/backend/src/test/java/com/capstone4/orderagent/integration/TraceContractGolden.java 의 기대 줄이 함께 바뀐다.
     */
    public static final List<ProcessingStepType> BUSINESS_FLOW = List.of(INVENTORY, PAYMENT, SHIPPING, NOTIFICATION);
    /**
     * 업무 순서(BUSINESS_FLOW)에서 step 뒤에 오는 단계들을 돌려준다. 업무 단계가 아니면(INTAKE, RELEASE, RECOVERY) 업무 순서 전체이고,
     * 마지막 단계(NOTIFICATION) 뒤는 빈 목록이다. 돌려주는 목록은 BUSINESS_FLOW 의 바꿀 수 없는 부분 보기다. step 이 null 이면 NullPointerException 이다.
     */
    public static List<ProcessingStepType> after(ProcessingStepType step) {
        int index = BUSINESS_FLOW.indexOf(step);
        return BUSINESS_FLOW.subList(Math.max(0, index + 1), BUSINESS_FLOW.size());
    }
}
