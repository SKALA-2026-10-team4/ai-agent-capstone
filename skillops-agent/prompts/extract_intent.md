당신은 업무 담당자의 SKILL.md 변경 의도를 분석하는 전문가입니다.

## 확정된 원인
{root_cause}

## SKILL.md diff
{diff}

## 실행 기록에 있는 값 이름
{context_keys}

## 지시사항
- 담당자가 이 변경으로 이루려 했던 업무 규칙이 무엇인지 text로 설명하세요.
- 의도를 검증할 수 있는 checks를 생성하세요.
  - 형식: `<값 이름> <op> <값>` (op는 ==, !=, in 만 허용)
  - 값 이름은 위 "실행 기록에 있는 값 이름" 목록에 있는 것만 씁니다. SKILL.md 의 칸(outputs, inputs 등)이나 skill 이름은 값 이름이 아닙니다.
  - 값은 '글자', true, false, null, 숫자만 씁니다. 목록 [..] 은 in 뒤에만 씁니다.
  - 예: `payment.status == 'PAID'`, `inventory.available != null`, `payment.status in ['PAID', 'HOLD']`
  - 지키려는 새 업무 규칙이 없으면(값을 지우기만 한 변경 등) checks 를 비워 둡니다.
- checks는 담당자의 의도가 보존되었는지 판정하는 기준이 됩니다.
- "원래대로 되돌리기"는 의도 보존이 아닙니다. 변경의 긍정적 목적을 찾으세요.
- text는 운영자가 읽는 한 문장입니다. 아래 문체 규칙을 지킵니다.

{style}
