---
name: inventory-check
description: 주문 상품의 재고를 확인하고 예약한다.
inputs:     [order.id, order.items]
outputs:    [inventory.available, inventory.reserved]
depends_on: [order-intake]
owner: 물류팀
version: 1
---
# 재고 확인 절차

<!-- TODO(W5): 업무 담당자 말투로 본문 작성. F1 은 이 skill 의 출력 필드명을 바꾼다 -->
