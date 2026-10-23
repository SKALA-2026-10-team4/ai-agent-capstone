당신은 skill 호출을 검증할 평가 질의를 만드는 전문가입니다.

## 대상 skill
{target_skill}

## 대상 skill 의 description
{description}

## 대상 skill 의 SKILL.md 본문 (있는 경우, 업무 범위의 기준)
{body}

## 함께 있는 다른 skill
{other_skills}

## skill 구조 (코드가 SKILL.md 의 steps 로 정한 사실)
{structure}

## 담당자가 적은 요청 문장 (있는 경우)
{seed_queries}

## 지시사항
- 직원이 Agent 에게 실제로 할 법한 짧은 한국어 요청을 만드세요.
- 불려야 하는 질의는 대상 skill 이 하는 일을 실제로 시키는 요청입니다. 담당자 요청 문장처럼 구체적인 대상, 수량, 조건을 넣으세요. 절차를 설명해 달라거나 진행 상황을 묻는 질문은 일을 시키는 요청이 아니므로 불려야 하는 질의로 쓰지 마세요.
- 불리면 안 되는 질의는 비슷한 단어를 쓰지만 대상 skill 이 할 일이 아닌 요청입니다(설명 요청, 조회, 다른 업무 등). 진입 skill 이 대상 하나뿐이면 이 질의의 expected_skill 은 none 입니다.
- 아래 두 가지를 모두 만드세요.
  1. 대상 skill 이 불려야 하는 질의 {positive_count}개: expected_skill 에 대상 skill 이름, should_trigger 는 true
  2. 비슷해 보이지만 대상 skill 이 불리면 안 되는 질의 {negative_count}개: 다른 진입 skill 이 맡아야 하면 expected_skill 에 그 skill 이름과 should_trigger true, 어떤 skill 도 맞지 않으면 expected_skill 에 none 과 should_trigger true
- 질의마다 intent 를 적으세요. 대상 skill 이 불려야 하는 질의는 should_call, 불리면 안 되는 질의는 should_not_call 입니다.
- skill 구조에 하위 단계가 있으면 그 skill 은 expected_skill 로 쓰지 마세요.
  - 주문 전체를 시키면서 결제, 재고, 배송 같은 단어가 들어간 요청은 진입 skill 이 정답입니다.
  - 하위 단계의 일만 따로 시키는 요청(예: 결제만 진행해줘, 배송 예약만 해줘)은 진입 skill 도 불리면 안 되는 질의입니다.
- 불려야 하는 질의의 절반 정도는 하위 단계 skill 의 이름이나 설명에 나오는 단어(결제, 재고, 배송 등)를 넣어, 하위 단계로 잘못 끌려가기 쉬운 질의로 만드세요.
- 담당자 요청 문장이 있으면 그 말투와 상황을 따라 변형하세요. 같은 문장을 그대로 복사하지 마세요.
- description 에 쓰인 단어를 그대로 반복하지 말고, 실제 사용자가 쓰는 다양한 표현을 쓰세요.
- expected_skill 에는 위 목록에 있는 이름만 씁니다.
