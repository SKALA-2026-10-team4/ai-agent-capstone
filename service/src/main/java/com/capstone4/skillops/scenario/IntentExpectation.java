package com.capstone4.skillops.scenario;

import java.util.List;

/**
 * 담당자 의도의 기대 결과 하나다(docs/plans/2026-10-26-behavior-edit-root-fix.md 2.3 절). run_scenarios 의 선택 입력
 * intent_expectations 의 항목이며 JSON 으로는 {scope, checks} 다. 두 칸 모두 한 단어라서 이름 규칙 어노테이션이 필요 없다.
 * service/src/main/java/com/capstone4/skillops/mcp/WorkspaceTools.java 의 runScenarios 가 받고,
 * service/src/main/java/com/capstone4/skillops/scenario/IntentPlan.java 가 검사해 채점 기준으로 바꾼다.
 * save_proposal 재판정(service/src/main/java/com/capstone4/skillops/proposal/ProposalJudge.java)은 제안서의 intent.expectations 를
 * 이 모양으로 읽어 같은 지문(IntentPlan.checksHash)을 만든다.
 *
 * @param scope  조건 문법 v2 한 줄 또는 null 이다. 시험 주문의 처음 값(order.payment_method, order.customer, order.shipping.country,
 *               order.shipping.express, order.total)으로 평가한다. null 이면 모든 시험 주문에 붙는다
 * @param checks 조건 문법 v2 의 확인 조건 1~10개다. 실행 문맥(단계 입력·출력)과 예약 키 order.final_status, order.shipments 로 채점한다
 */
public record IntentExpectation(String scope, List<String> checks) {
}
