당신은 skill 호출을 검증할 평가 질의를 만드는 전문가입니다.

## 대상 skill
{target_skill}

## 대상 skill 의 description
{description}

## 함께 있는 다른 skill
{other_skills}

## 담당자가 적은 요청 문장 (있는 경우)
{seed_queries}

## 지시사항
- 직원이 Agent 에게 실제로 할 법한 짧은 한국어 요청을 만드세요.
- 아래 두 가지를 모두 만드세요.
  1. 대상 skill 이 불려야 하는 질의 {positive_count}개: expected_skill 에 대상 skill 이름, should_trigger 는 true
  2. 비슷해 보이지만 대상 skill 이 불리면 안 되는 질의 {negative_count}개: 다른 skill 이 맡아야 하면 expected_skill 에 그 skill 이름과 should_trigger true, 어떤 skill 도 맞지 않으면 expected_skill 에 대상 skill 이름과 should_trigger false
- 담당자 요청 문장이 있으면 그 말투와 상황을 따라 변형하세요. 같은 문장을 그대로 복사하지 마세요.
- description 에 쓰인 단어를 그대로 반복하지 말고, 실제 사용자가 쓰는 다양한 표현을 쓰세요.
- expected_skill 에는 위 목록에 있는 이름만 씁니다.
