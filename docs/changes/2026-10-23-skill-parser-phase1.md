# #72 · service SKILL 파싱 공통화 1단계

작성일: 2026-10-23 · 브랜치: `fix/#72_skill-parser-consistency`

## 해결한 문제

service의 `SkillHeader`는 줄과 쉼표로 목록을 나눠 여러 줄 목록을 빈 값으로 읽고, 따옴표를 실제 키에 남겼다. 특히 여러 줄 steps는 간이 실행에서 0단계 후 SHIPPED가 될 수 있었다.

이제 workspace와 snapshot 분석이 같은 `SkillFrontmatter`의 안전한 YAML 해석 기능을 사용한다. 간이 실행은 실제 목록을 읽고, 빈 steps는 실행 전에 거부한다.

## 변경 모듈

| 코드 | 변경 내용 |
|---|---|
| `service/.../skill/SkillFrontmatter` | frontmatter 경계·YAML 파싱·줄 위치 계산 공통 기능 신설. diff용 parse와 workspace용 requireFields 분리 |
| `service/.../snapshot/Frontmatter` | 공통 기능으로 위임. 기존 분석 예외·빈 맵·hunk 위치 동작 보존 |
| `service/.../workspace/SkillHeader` | 한 줄·여러 줄·따옴표 목록 지원, name/version/선택 목록 타입 검사, YAML 실패를 입력 예외로 변환 |
| `service/.../scenario/ContractOnlyFlowRunner` | 누락되거나 빈 steps로 실행 성공을 반환하지 않도록 검증 |

WorkspaceService와 LiveFolderSkillVersionStore는 기존 SkillHeader 호출을 통해 변경된 파싱을 사용한다. snapshot에서 복사한 원문은 재직렬화하지 않으며 CRLF·끝 개행도 보존한다. 새로 만들거나 변경한 함수에 기능 주석을 남겼다.

## API·계약 영향

- 기존 MCP 요청·성공 응답 필드는 유지했다.
- workspace의 목록 누락은 기존처럼 빈 값이지만, 명시적 null과 잘못된 타입은 거부한다.
- version을 선언하면 양의 Integer여야 한다. version 누락은 기존 저장소의 필수값 검사가 처리한다.
- name을 선언하면 비공백 문자열이어야 한다.
- SkillHeader도 snapshot 파서와 같은 정확한 구분선을 요구한다. 구분선 앞뒤 공백·선행 빈 줄·BOM은 허용하지 않는다.
- snapshot 기반 workspace 생성 중 잘못된 YAML/타입, 간이 실행의 빈 steps는 MCP `INVALID_INPUT`으로 전달된다.
- diff는 잘못된 YAML의 줄 변경을 보존하고 별도 파싱 오류를 반환하는 기존 정책을 유지한다.

갱신 명세: [frontmatter 계약](../contracts/skill-frontmatter.md), [MCP create_workspace/run_scenarios](../contracts/mcp-tools-input-output.md).

## 검증

실행 명령:

```bash
cd service
./gradlew test --offline --no-daemon --console=plain
```

service 전체 48개 테스트 클래스의 529개 테스트를 통과했다(실패·오류·건너뜀 0). 다음 회귀 검사를 추가했다.

- 한 줄·여러 줄·따옴표 목록과 CRLF에서 workspace/snapshot 해석 일치
- 잘못된 목록·version·중복 키·깨진 YAML 거부
- 여러 줄 steps/outputs로 다섯 단계 실행과 기준 주문 채점 성공
- steps 누락·빈 목록·null은 주문 입력 접근 전에 거부
- snapshot workspace 원문·줄바꿈 보존과 잘못된 version의 저장 방지

## 남은 작업

- 주문 Agent SkillLoader와 공통 라이브러리 연결
- owner/description/depends_on 등 전체 실행 계약 정합화
- patch 결과의 missing_dependencies와 메타데이터 진단 확장
- Python 후보 판정과 깨진 원문 진단 경로 개선

이번 단계는 service 내부 파싱과 간이 실행 검증 범위다. 주문 Agent의 기존 수정 중인 README와 Python·주문 Agent 실행 코드는 변경하지 않았다.
