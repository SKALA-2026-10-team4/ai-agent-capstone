---
name: order-flow
description: 캠핑용품 주문의 처리 순서
steps: [order-intake, inventory-check, payment, shipping-reserve, notify]
owner: 주문운영팀
version: 1
---
# 캠핑용품 주문의 처리 순서

위 steps 순서대로 처리합니다. 재고가 부족하면 결제와 배송을 중단합니다. 결제가 거절되면 예약을 해제하고 배송과 성공 알림을 중단합니다.
