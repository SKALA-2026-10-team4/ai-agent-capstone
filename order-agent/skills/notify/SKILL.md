---
name: notify
description: 배송 예약 결과를 고객에게 알린다
inputs: [order.id, order.customer, payment.status, shipping.id, shipping.eta]
outputs: [notify.sent, notify.channel]
depends_on: [shipping-reserve]
owner: 주문운영팀
version: 1
---
# 배송 예약 결과를 고객에게 알린다

배송이 예약된 주문만 성공 알림 도구를 호출합니다. 주문번호와 고객 식별자를 전달합니다. 실제 알림 결과를 기록합니다.
