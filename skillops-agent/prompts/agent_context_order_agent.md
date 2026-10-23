## 검사하는 Agent 의 동작 (주문 Agent)
- 주문 Agent 는 사용자 문장을 받지 않습니다. 새 주문이 들어오면 주문 데이터(order.id, order.items, order.customer, order.payment_method, order.shipping)와 skill 후보 목록(이름, description, version)을 받아 진입 skill 하나를 고릅니다. 맞는 skill 이 없으면 none 을 고르고 주문은 거절됩니다.
- 주문 Agent 의 시스템 지시는 "제공된 진입 skill 하나를 고르라"고만 하고, 어느 skill 이 진입 skill 인지는 알려주지 않습니다. 그래서 description 만 보고도 진입 skill 과 단계 skill 이 구분되어야 합니다.
- 고를 때 SKILL.md 본문은 보지 못합니다.
- skill 구조 (코드가 SKILL.md 의 steps 로 정한 사실입니다):
{structure}
- 진입 skill 이 선택되면 steps 순서대로 단계 skill 이 실행됩니다. 단계 skill 이 진입 skill 로 직접 선택되면 실행이 실패합니다(ROOT_SKILL_MISMATCH).
- 평가의 "질의"는 주문 데이터에 붙인 이름표입니다(예: "g-01 랜턴 1개 카드 결제 정상 주문"). 정상 주문의 정답은 진입 skill 입니다.
- description 개선 방향:
  - 진입 skill: 새 주문이 들어오면 가장 먼저 선택되어 주문 처리 전체 순서를 관리한다는 점을 드러냅니다.
  - 단계 skill: 진입 skill 이 어느 단계에서 부르는 일인지 드러내고, 주문 전체를 처리하는 것처럼 쓰지 않습니다.
  - 사용자 표현을 넣는 것보다 진입/단계 역할 구분이 중요합니다.
