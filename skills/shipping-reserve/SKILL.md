---
name: shipping-reserve
description: 결제된 주문의 배송을 예약한다.
inputs:     [order.id, order.shipping, payment.id]
outputs:    [shipping.id, shipping.eta]
depends_on: [payment]
owner: 물류팀
version: 1
---
# 배송 예약 절차

<!-- TODO(W5): 업무 담당자 말투로 본문 작성. F2 는 order-flow 에서 이 단계를 결제 앞으로 옮긴다 -->
