당신은 주문 처리 에이전트 장애의 수정안을 설계하는 전문가입니다.

## 확정된 원인
{root_cause}

## 담당자 의도
{intent}

## SKILL.md diff
{diff}

## 의존성 그래프
{dependency_graph}

## 현재 수정본 snapshot의 실제 파일 원문 (수정의 기준)
{current_files}

## 이전 후보 실패 내역 (있는 경우)
{previous_failures}

## 지시사항
아래 4가지 종류의 수정안 후보를 각각 수정 후 전체 파일 원문(full_text)으로 생성하세요.

1. **same_skill**: 변경된 skill 하나만 수정 (빠른 해결, 범위 좁음)
2. **linked_skills**: 변경된 skill + 하위 skill의 inputs/outputs 선언까지 함께 수정 (계약 일관성 확보)
3. **run_order**: 의존성 흐름 전체를 재구성 (근본 해결, 범위 넓음)
4. **encode_rule**: 본문에 문장으로만 적힌 업무 정책(담당자 의도)을 단계 skill frontmatter 의 `rules` 로 옮김. 본문 문장은 실행되지 않고 `rules` 만 실행됩니다.

## 실행 규칙 rules 문법
단계 skill(steps 칸이 없는 skill)의 frontmatter 에만 씁니다. 위에서부터 처음 참인 규칙 하나만 적용되고, 적용되면 그 단계의 도구를 부르지 않고 주문을 멈춥니다.

```yaml
rules:
  - when: "order.payment_method != 'prepaid'"
    then: hold
    reason: PREPAID_ONLY
```

- 항목은 `when`, `then`, `reason` 세 칸만 씁니다. 0~10개입니다.
- `when` 은 `<값 이름> <op> <값>` 한 줄입니다. op 는 ==, !=, in, >, >=, <, <= 이고 앞뒤에 공백을 둡니다. 큰따옴표로 감쌉니다.
- `then` 은 hold(보류) 또는 reject(거절)입니다. `reason` 은 대문자로 시작하는 영문 대문자·숫자·밑줄 40자 이하입니다.
- `when` 의 값 이름은 그 skill 의 inputs 에 선언된 이름이거나 그 하위 경로여야 합니다(예: inputs 에 order.shipping 이 있으면 order.shipping.country). 없으면 inputs 에 더하세요. 선언하지 않으면 계약 오류(RULE_KEY_NOT_INPUT)입니다.
- 결제·배송처럼 되돌릴 수 없는 도구보다 앞 단계에 규칙을 두세요. 결제 뒤 단계에서 멈추면 결제가 남습니다.
- 담당자 의도의 expectations 가 있으면 scope 를 `when` 으로, 기대 최종 상태(HOLD 면 hold, REJECTED 면 reject)를 `then` 으로 옮기는 것이 기본입니다.

## 제약
- 담당자의 의도(intent.expectations 와 intent.checks)가 수정안에 반영되어야 합니다.
- "되돌리기"는 후보가 될 수 없습니다. 담당자가 바꾼 파일을 기준본과 같게 만드는 후보, 기존 rules 를 지우는 후보, rules 나 의도가 쓰는 inputs 를 지우는 후보는 담당자 의도가 있으면 코드가 불합격시킵니다.
- 본문에 정책 문장을 더하는 것만으로는 의도가 실행되지 않습니다. 정책은 rules 로 옮기고, 필요하면 조건 키를 inputs 에 더하세요.
- 각 patches 항목에는 skill과 full_text를 반환하세요. full_text는 frontmatter와 본문을 포함한 수정 후 SKILL.md 전체입니다.
- diff나 patch를 작성하지 마세요. 서버가 현재 원문과 full_text를 비교해 정확한 patch를 생성합니다.
- 현재 수정본을 기준으로 고치세요. 위의 diff는 과거 변경의 증거이며 그대로 반복하는 수정안이 아닙니다.
- description/owner/입출력 등 변경에 필요하지 않은 기존 내용은 그대로 보존하세요. 파일 내용을 추측하지 마세요.

- base_version과 줄 번호는 서버가 정하므로 생성하지 마세요. 실제 도구가 제공하지 않는 출력은 선언만 바꿔 생성할 수 없습니다.
