---
id: policy-outcomes
applies_to_skills: [order-intake, inventory-check, payment, shipping-reserve, notify]
source: [order-agent/docs/team-scenarios.md, order-agent/docs/skill-flow.md, order-agent/backend/src/main/java/com/capstone4/orderagent/service/OrderToolService.java]
version: v2
---
재고 부족 → REJECTED, 결제·배송·알림 안 함. 결제 거절 → HOLD, 재고 예약 해제, 배송·알림 안 함.
이 두 결과는 정상 업무 결과이지 Skill 결함이 아니다.
단계 skill 의 rules 가 맞으면 then 이 hold 면 HOLD, reject 면 REJECTED 로 끝나고 주문 result_code 는 규칙 reason 이다.
그 단계와 뒤 단계는 SKIPPED, 결제 전이면 예약 재고를 해제하고, 실행은 COMPLETED 다(BUSINESS_STOP 의 rule_reason).
이 규칙 종료도 정상 업무 결과이지 Skill 결함이 아니다. 결제 뒤 단계의 규칙으로 멈추면 승인된 결제가 남으므로 규칙은
결제 전 단계에 둔다.
