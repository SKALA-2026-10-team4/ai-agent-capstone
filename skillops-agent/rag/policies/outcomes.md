---
id: policy-outcomes
applies_to_skills: [inventory-check, payment]
source: [order-agent/docs/team-scenarios.md, order-agent/backend/src/main/java/com/capstone4/orderagent/service/OrderToolService.java]
version: v1
---
재고 부족 → REJECTED, 결제·배송·알림 안 함. 결제 거절 → HOLD, 재고 예약 해제, 배송·알림 안 함.
이 두 결과는 정상 업무 결과이지 Skill 결함이 아니다.
