---
name: order-intake
description: 접수된 주문의 내용과 서버 계산 금액을 검수한다
inputs: [order.id, order.customer, order.items, order.payment_method, order.shipping]
outputs: [order.id, order.total]
depends_on: []
owner: 주문운영팀
version: 1
---
# 접수된 주문의 내용과 서버 계산 금액을 검수한다

주문 조회 도구로 이미 접수된 주문과 서버가 계산한 금액을 확인합니다. 새 주문을 생성하지 않습니다. 확인된 주문번호와 총액을 기록합니다.
