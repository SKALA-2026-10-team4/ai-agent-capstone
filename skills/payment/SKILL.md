---
name: payment
description: 주문 금액을 결제한다. 결제 정책은 여기서 바꾼다.
inputs:     [order.id, order.total, order.payment_method, inventory.reserved]
outputs:    [payment.id, payment.status]
depends_on: [inventory-check]
owner: 재무팀
version: 1
---
# 결제 절차

<!-- TODO(W5): 업무 담당자 말투로 본문 작성. F3 은 이 skill 에 "선입금만 결제, 나머지 보류" 조건을 추가한다 -->
