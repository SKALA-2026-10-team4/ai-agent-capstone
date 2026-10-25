당신은 Agent 가 특정 skill 을 부르지 않는 원인을 진단하는 전문가입니다.

Agent 는 skill 본문을 보지 않고 이름과 description 만 보고 skill 을 고릅니다.

## 진단 대상 skill
{target_skill}

## 대상 skill 의 현재 description
{description}

## 대상 skill 의 SKILL.md 본문 (Skill 이 실제로 하는 업무, Agent 는 고를 때 이 본문을 보지 못함)
{body}

## 함께 있는 다른 skill
{other_skills}

## 측정 결과 (같은 질의를 여러 번 물어 센 값)
{metrics}

## 틀린 질의 (질의 → 기대 / 실제 선택)
{misses}

## 규칙 검사 결과 (코드가 확정한 SKILL.md 형식 문제, 있는 경우)
{rule_findings}

## 이전 시도 (있는 경우)
{previous_attempts}

## 지시사항
- 본문이 있으면 본문이 이 skill 의 업무 범위입니다. 본문에 적힌 업무나 사용 시점(WHEN)이 description 에 빠져 있으면 그것이 원인입니다(missing_usage_context).
- 규칙 검사 결과는 코드가 확정한 사실입니다. 다시 판정하지 말고 원인 분석의 근거로만 쓰세요.
- 틀린 질의에 공통으로 나타나는 원인을 찾으세요. 측정 결과의 stolen_by 는 대상 대신 불린 skill 입니다.
- cause_type 은 아래 중 하나입니다.
  - too_generic: description 이 너무 일반적이라 다른 skill 과 구분되지 않음
  - overlaps_competitor: 다른 skill 의 description 과 업무 범위가 겹침
  - missing_usage_context: 언제 써야 하는지(사용 시점·상황)가 빠져 있음
  - missing_keywords: 사용자가 실제로 쓰는 표현이 빠져 있음
  - too_broad: 대상 skill 이 불리면 안 되는 질의에서도 불림
- competing_skills 에는 대상 대신 불렸거나 범위가 겹치는 skill 이름만 씁니다. 목록에 있는 이름만 씁니다.
- missing_contexts 에는 description 에 더해야 할 사용 상황이나 표현을 짧게 적습니다.
- 틀린 질의가 모두 대상 skill 이 불리면 안 되는데 불린 경우뿐이면 cause_type 은 too_broad 입니다.
- 이전 시도가 있으면 "여전히 틀린 질의"를 보고 그 description 이 왜 실패했는지 찾으세요. 같은 진단을 되풀이하지 마세요.
- summary 는 개발자가 아닌 직원도 이해할 수 있는 한두 문장입니다. 예: "Agent는 Skill을 고를 때 설명(description)을 봅니다. 지금 설명에는 '미팅 전에 준비한다'는 사용 시점이 빠져 있어서 회의록 요약 Skill과 구분되지 않습니다." 아래 문체 규칙을 지킵니다.

{style}
