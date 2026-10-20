---
name: notify
description: 고객에게 주문 결과를 알린다.
inputs:     [order.id, order.customer, payment.status, shipping.id, shipping.eta]
outputs:    [notify.sent, notify.channel]
depends_on: [shipping-reserve]
owner: CS팀
version: 1
---
# 알림 절차

<!-- TODO(W5): 업무 담당자 말투로 본문 작성 -->
