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
아래 3가지 종류의 수정안 후보를 각각 수정 후 전체 파일 원문(full_text)으로 생성하세요.

1. **same_skill**: 변경된 skill 하나만 수정 (빠른 해결, 범위 좁음)
2. **linked_skills**: 변경된 skill + 하위 skill의 inputs/outputs 선언까지 함께 수정 (계약 일관성 확보)
3. **run_order**: 의존성 흐름 전체를 재구성 (근본 해결, 범위 넓음)

## 제약
- 담당자의 의도(intent.checks)가 수정안에 반영되어야 합니다.
- "되돌리기"는 후보가 될 수 없습니다.
- 각 patches 항목에는 skill과 full_text를 반환하세요. full_text는 frontmatter와 본문을 포함한 수정 후 SKILL.md 전체입니다.
- diff나 patch를 작성하지 마세요. 서버가 현재 원문과 full_text를 비교해 정확한 patch를 생성합니다.
- 현재 수정본을 기준으로 고치세요. 위의 diff는 과거 변경의 증거이며 그대로 반복하는 수정안이 아닙니다.
- description/owner/입출력 등 변경에 필요하지 않은 기존 내용은 그대로 보존하세요. 파일 내용을 추측하지 마세요.

- base_version과 줄 번호는 서버가 정하므로 생성하지 마세요. 실제 도구가 제공하지 않는 출력은 선언만 바꿔 생성할 수 없습니다.
