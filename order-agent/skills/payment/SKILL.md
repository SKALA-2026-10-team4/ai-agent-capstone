---
name: payment
description: 예약된 주문 금액을 결제한다
inputs: [order.id, order.total, order.payment_method, inventory.reserved]
outputs: [payment.id, payment.status]
depends_on: [inventory-check]
owner: 주문운영팀
version: 1
---
# 예약된 주문 금액을 결제한다

재고 예약 성공을 확인합니다. 결제 도구에 주문번호·서버 총액·결제 방식을 전달합니다. 승인 시 PAID와 결제 참조를 기록합니다. 거절 시 DECLINED로 종료하고 서버의 재고 해제 정책을 따릅니다.
