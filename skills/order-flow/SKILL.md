---
name: order-flow
description: 주문 한 건이 거치는 단계와 순서. 순서를 바꾸는 사람은 운영팀.
steps: [order-intake, inventory-check, payment, shipping-reserve, notify]
owner: 운영팀
version: 1
---
# 주문 처리 순서

들어온 주문은 위 steps 순서대로 처리한다. 앞 단계가 FAILED 면 뒤 단계는 실행하지 않는다.

<!-- TODO(W5): 업무 담당자 말투로 본문 작성 -->
