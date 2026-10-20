---
name: shipping-reserve
description: 결제 완료 주문의 배송을 예약한다
inputs: [order.id, order.shipping, payment.id, payment.status, inventory.reserved]
outputs: [shipping.id, shipping.eta]
depends_on: [payment]
owner: 주문운영팀
version: 1
---
# 결제 완료 주문의 배송을 예약한다

결제 PAID와 재고 예약을 확인합니다. 배송 예약 도구에 주문번호·배송 국가·빠른 배송 여부를 전달합니다. 실제 배송 참조와 예정일을 기록합니다.
