---
id: guide-repair
applies_to_errors: [CONTRACT_VIOLATION, MISSING_SKILL_OUTPUT, INCOMPLETE_FLOW]
owner: AI 플랫폼팀
version: v2
---
도구가 만들지 않는 값을 선언에서 빼는 것, 하위 skill 이 요구하는 값을 원인 skill 의 outputs 에 되살리는 것,
필수 순서·단계를 복구하는 것은 계약 복구다. 결함을 하위 skill 의 inputs·depends_on 수정으로 퍼뜨리지 않는다.
담당자 의도가 있는 요청이면 서비스 재판정이 두 가지를 불합격시킨다. 담당자가 바꾼 SKILL.md 를 기준본과 바이트까지
같게 만드는 후보(is_revert), 그리고 rules 를 지우거나 rules[].when 을 바꾸거나 rules·의도 scope 가 쓰는 inputs 를
지우는 후보(weakens_contract)다. 그때는 깨진 계약만 고치고 담당자가 더한 내용은 남긴다. 본문에만 적은 정책은 rules 로
옮기는 encode_rule 후보를 낸다. 규칙을 더하거나 순서·then·reason 만 바꾸는 것은 약화가 아니다.
의도를 지키는 데 어떤 업무 도구도 만들지 않는 값이 필요하면 Skill 문서 수정만으로는 고칠 수 없다. 그때는
no_valid_fix 와 개발 요청서(development_request, kind needs_tool)를 내고, 계약을 복구하는 안은 임시 대안(interim)으로 둔다.
