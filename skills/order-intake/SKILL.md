---
name: order-intake
description: 들어온 주문을 검수하고 주문번호를 확정한다.
inputs:     [order.customer, order.items, order.payment_method, order.shipping]
outputs:    [order.id, order.total]
depends_on: []
owner: 운영팀
version: 1
---
# 주문 접수 절차

<!-- TODO(W5): 업무 담당자 말투로 본문 작성 -->
