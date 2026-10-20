# 계약 1 · SKILL.md frontmatter

버전: v0 (초안) · 동결 예정: 1일차 09시

## 단계 skill

```yaml
---
name: payment                       # 디렉토리 이름과 동일. kebab-case
description: 주문 금액을 결제한다. 결제 정책은 여기서 바꾼다.
inputs:     [order.id, order.total, inventory.reserved]
outputs:    [payment.id, payment.status]
depends_on: [inventory-check]
owner: 재무팀                        # 이 skill 을 고치는 업무 담당 조직
version: 4                          # 저장 시 서비스가 +1. 담당자는 건드리지 않는다
---
# 결제 절차                          ← 여기부터 본문. 담당자가 쓰는 자연어.
1. 재고 예약(inventory.reserved)이 true 인 주문만 진행한다.
2. 결제 어댑터를 호출하고 payment.id 를 받는다.
3. 실패하면 payment.status 를 FAILED 로 두고 끝낸다.
```

## orchestrator (`order-flow`)

```yaml
---
name: order-flow
description: 주문 한 건이 거치는 단계와 순서. 순서를 바꾸는 사람은 운영팀.
steps: [order-intake, inventory-check, payment, shipping-reserve, notify]
owner: 운영팀
version: 1
---
# 주문 처리 순서
들어온 주문은 위 steps 순서대로 처리한다. 앞 단계가 FAILED 면 뒤 단계는 실행하지 않는다.
```

## 컨텍스트 키 규칙

- 키는 `<네임스페이스>.<필드>`. 네임스페이스는 skill 이 `outputs` 에 선언한 접두어다.
  `order.*` 는 입력 주문에서 오고, 그 외는 모두 앞선 skill 의 출력이다.
- **계약 일관성 검사**(W1, 기준 4): `steps` 순서대로 각 skill 의 `inputs` ⊆ `order.*` ∪ (앞선 skill 들의 `outputs`).
- 로더는 frontmatter 만 파싱한다. 본문은 검사하지 않고 system prompt 에 그대로 주입한다.
  그래서 "본문은 바꿨는데 frontmatter 는 그대로"가 장애 유형 F1 이 된다.

## 위치

| 모드 | 경로 |
|---|---|
| live | `skills/<name>/SKILL.md` (저장소 루트, 서비스가 읽음) |
| sandbox | `<workspace 루트>/<ws_id>/skills/<name>/SKILL.md` |

## 상태 값 (final_status · payment.status 공용)

`SHIPPED` 정상 완료 · `REJECTED` 업무 규칙으로 거절(재고 부족 등) · `HOLD` 보류(F3 에서 신설) · `FAILED` harness/skill 오류
