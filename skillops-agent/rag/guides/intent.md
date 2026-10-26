---
id: guide-intent
applies_to_errors: [CONTRACT_VIOLATION, MISSING_SKILL_OUTPUT, INCOMPLETE_FLOW]
owner: AI 플랫폼팀
version: v2
---
담당자가 저장 요청에 준 의도(intent.expectations, source owner)가 있으면 그것이 기준이고, Agent 가 고치지 않는다.
의도가 없으면 계약 오류를 일으킨 선언 변경 자체를 담당자의 업무 의도로 확정하지 않는다. 값을 지우기만 했거나,
도구가 주지 않는 값을 추가했거나, 필수 단계를 뺀 변경은 지킬 새 업무 규칙이 없을 수 있다.
그때 의도 설명은 '추정'임을 밝히고 확인 조건(checks)을 비운다.
본문에 정책 문장만 더한 수정본은 오류 없이 돌아도 정책이 실행되지 않는다(incomplete_edit). Harness 는 단계 skill 의
rules 만 실행한다. 확인 조건과 scope 는 조건 문법 v2(==, !=, in, >, >=, <, <=)로 쓰고, scope 의 값 이름은 그 단계의
선언 inputs 이거나 그 아래 경로여야 한다.
