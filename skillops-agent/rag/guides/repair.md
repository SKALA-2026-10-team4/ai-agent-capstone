---
id: guide-repair
applies_to_errors: [CONTRACT_VIOLATION, MISSING_SKILL_OUTPUT, INCOMPLETE_FLOW]
owner: AI 플랫폼팀
version: v1
---
도구가 만들지 않는 값을 선언에서 빼는 것, 하위 skill 이 요구하는 값을 원인 skill 의 outputs 에 되살리는 것,
필수 순서·단계를 복구하는 것은 '되돌리기'가 아니라 계약 복구다. 변경 의도는 실행 가능한 범위에서만 보존한다.
의도를 지키려면 도구나 코드 변경이 필요하면 Skill 문서 수정만으로는 불가능하다고 적고, 계약을 복구하는 후보를 낸다.
결함을 하위 skill 의 inputs·depends_on 수정으로 퍼뜨리지 않는다.
