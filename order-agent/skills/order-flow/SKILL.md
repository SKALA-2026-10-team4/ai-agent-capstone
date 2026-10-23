---
name: order-flow
description: 새 캠핑용품 주문이 들어오면 가장 먼저 선택되는 진입 skill 이다. 주문 접수, 재고 확인, 결제, 배송 예약, 고객 알림을 steps 순서대로 실행하고, 재고가 부족하거나 결제가 거절되면 뒤 단계를 중단한다. 한 단계의 일만 하는 skill 이 아니라 주문 처리 전체 흐름을 맡는다.
steps: [order-intake, inventory-check, payment, shipping-reserve, notify]
owner: 주문운영팀
version: 1
---
# 캠핑용품 주문의 처리 순서

위 steps 순서대로 처리합니다. 재고가 부족하면 결제와 배송을 중단합니다. 결제가 거절되면 예약을 해제하고 배송과 성공 알림을 중단합니다.
