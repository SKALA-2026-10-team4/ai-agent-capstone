당신은 업무 담당자의 SKILL.md 변경 의도를 분석하는 전문가입니다.

## 담당자가 적은 의도
{owner_intent}

## 확정된 원인
{root_cause}

## SKILL.md diff
{diff}

## 실행 기록에 있는 값 이름
아래 목록은 실행 기록의 값, 수정본 skill 들이 선언한 inputs·outputs, 시험 결과의 예약 값(order.final_status, order.shipments)을 합친 것입니다.
{context_keys}

## 지시사항
- 담당자가 이 변경으로 이루려 했던 업무 규칙이 무엇인지 text로 설명하세요.
- 의도를 검증할 수 있는 checks를 생성하세요. checks 는 어떤 주문에서도 성립해야 하는 조건입니다.
  - 형식: `<값 이름> <op> <값>` (op는 ==, !=, in, >, >=, <, <= 만 허용하고 앞뒤에 공백을 둡니다)
  - 값 이름은 위 "실행 기록에 있는 값 이름" 목록에 있는 것만 씁니다. SKILL.md 의 칸(outputs, inputs 등)이나 skill 이름은 값 이름이 아닙니다.
  - 값은 '글자', true, false, null, 숫자만 씁니다. 목록 [..] 은 in 뒤에만 씁니다. >, >=, <, <= 뒤에는 숫자만 씁니다.
  - 예: `payment.status == 'PAID'`, `inventory.available != null`, `payment.status in ['PAID', 'HOLD']`
  - 지키려는 새 업무 규칙이 없으면(값을 지우기만 한 변경 등) checks 를 비워 둡니다.
- expectations 는 담당자가 의도를 적었을 때만 만듭니다. "담당자가 적은 의도"가 (없음)이면 비워 둡니다.
  - 항목마다 scope 와 checks 를 냅니다. scope 는 그 규칙이 적용되는 시험 주문을 고르는 조건 한 줄이고, 주문의 처음 값(order.payment_method, order.customer, order.shipping.country, order.shipping.express, order.total)만 봅니다. 모든 주문에 적용되면 null 입니다.
  - checks 는 scope 에 맞는 주문이 끝났을 때 성립해야 하는 조건입니다. 업무를 멈춰야 하면 `order.final_status in ['HOLD', 'REJECTED']` 처럼 최종 상태를 적고, 멈춘 단계의 도구가 불리지 않았는지도 적습니다(예: `payment.status == null`).
  - 예: 선입금 주문만 결제한다면 scope `order.payment_method != 'prepaid'`, checks `order.final_status in ['HOLD', 'REJECTED']`, `payment.status == null` 입니다.
- checks와 expectations 는 담당자의 의도가 보존되었는지 판정하는 기준이 됩니다.
- "원래대로 되돌리기"는 의도 보존이 아닙니다. 변경의 긍정적 목적을 찾으세요.
- text는 운영자가 읽는 한 문장입니다. 아래 문체 규칙을 지킵니다.

{style}
