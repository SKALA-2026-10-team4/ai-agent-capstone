---
name: inventory-check
description: 상품 전체 재고를 확인하고 예약한다
inputs: [order.id, order.items]
outputs: [inventory.available, inventory.reserved]
depends_on: [order-intake]
owner: 주문운영팀
version: 1
---
# 상품 전체 재고를 확인하고 예약한다

재고 예약 도구에 주문번호와 모든 상품·수량을 전달합니다. 하나라도 부족하면 전체 거절하고 종료합니다. 충분하면 전체 수량을 예약하고 실제 예약 결과를 기록합니다.
