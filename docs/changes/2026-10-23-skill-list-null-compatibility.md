# #72 · SKILL 목록 null 호환성 적용 결과

작성일: 2026-10-23 · 상태: 구현 및 로컬 검증 완료, 미배포

## 해결한 문제

실제 주문 Agent와 snapshot 분석기는 허용하지만 workspace 생성이 거부하던 정상 문법 6개를 해결했다. 기존 상태에서는 시작 버전만 읽으려 해도 SkillHeader.parse의 목록 검사에서 중단돼 진단용 workspace를 만들 수 없었다.

일반 단계의 `steps: null`, `steps: ~`, `steps:`를 빈 목록으로 해석한다. steps가 비어 있지 않은 flow의 inputs·outputs null도 빈 목록으로 해석한다. flow의 inputs·outputs·depends_on 모두 null인 사례도 복사할 수 있다. depends_on을 SkillHeader에 추가하거나 별도 치환하지 않았다.

## 구현

실행에 영향을 주는 운영 코드 변경은 `SkillHeader.java` 한 파일이다.

- name·version 검사는 유지하고 steps를 먼저 읽는다.
- steps null에는 AS_EMPTY 정책을 사용한다. inputs·outputs는 steps가 비어 있지 않을 때만 AS_EMPTY, 일반 단계에서는 REJECT 정책을 쓴다.
- 기존 목록 함수에서 정책을 처리해 workspace 생성·운영 폴더 조회·간이 실행 호출자가 같은 규칙을 사용하게 했다.
- 누락 목록은 workspace 어댑터의 기존 빈 목록 처리를 유지했다. 실제 일반 단계의 필수 목록 검사는 기존 snapshot 분석기와 주문 Agent가 계속 수행한다.
- 잘못된 타입·항목, 일반 단계의 명시적 null 입출력은 계속 거부한다.
- 함수·정책 주석을 갱신했다. 원문과 YAML Map 자체를 변경하지 않는다.

## 검증 과정과 결과

### 수정 전 실패를 확인한 뒤 구현

공통 호환성 fixture의 headerRejectedField와 테스트의 거부 예상 분기를 제거했다. 정상 목록 문법 20개 모두에 workspace 생성 성공을 요구하는 테스트를 먼저 실행해, 기존 코드에서 정확히 6개가 실패하는 것을 확인했다.

실패 사례는 step-null-steps, step-tilde-steps, step-implicit-null-steps, flow-inputs-null, flow-outputs-null, flow-all-null이다. 수정 후에는 이 여섯 사례를 포함한 20개가 모두 통과했다. 고정한 문서의 원문·해시·해석 기대값은 변경하지 않았다.

### 확인한 유지 조건

| 검사 | 결과 |
|---|---|
| 기존 정상 문서 6개 | 양쪽 로더의 해석 값·본문·파일 해시 유지 |
| 정상 목록 문법 20개 | SkillHeader·snapshot 분석·snapshot workspace 생성 모두 통과 |
| 원문 보존 | 실제 파일 바이트와 고정 원문·SHA-256 일치; workspace 복사 시 CRLF·끝 줄바꿈 보존 |
| 운영 폴더 조회 | 세 null 표현의 단계·flow 버전 조회와 원문 읽기 통과 |
| 정상 다섯 단계 간이 실행 | 세 null 표현에서도 다섯 단계 모두 실행하고 g-01 기대 결과 유지 |
| 일반 단계 inputs·outputs의 명시적 null | steps 누락·[]·null에서도 SkillHeader 및 간이 실행이 계속 거부 |
| 일반 단계 inputs·outputs·depends_on의 누락·null | snapshot 분석 불완전 및 UNREADABLE_SKILL 판정; 실제 주문 Agent는 INVALID_SKILL_FORMAT으로 계속 거부 |
| scalar·map·잘못된 항목·문자열 null | 일반 단계와 flow에서도 계속 거부 |
| 빈 root steps | 누락·null·~·값 생략·[] 모두 간이 실행 또는 실제 flow 정적 검증에서 거부; 0단계 성공으로 처리하지 않음 |

두 Java 모듈에서 `./gradlew test --offline --no-daemon --console=plain`을 실행했다.

- service: 663개 통과, 실패·미실행 0개.
- order-agent/backend: 140개 통과, 실패 0개. DB 통합 테스트 82개는 기존 환경 변수 조건에 따라 미실행.
- Python 코드를 변경하지 않아 Python 테스트는 이번에 다시 실행하지 않았다.

실제 서버 간 HTTP 연결·DB 통합·LLM·실제 주문 업무 도구 호출은 실행하지 않았다. 간이 실행의 g-01 통과를 실제 주문 실행 성공의 근거로 사용하지 않는다.

## 명세와 호환성

- [설계 및 호출 관계](../skill-list-null-compatibility-design.md)
- [SKILL null 해석 명세](../contracts/skill-frontmatter.md#목록-null-호환성-72--5단계)
- [MCP create_workspace·실행 설명](../contracts/mcp-tools-input-output.md#7-연습-공간-만들기--create_workspace)
- [공통 호환성 fixture](../contracts/examples/skill-document-compatibility.json)
- [수정 전 관찰 기록](2026-10-23-skill-document-compatibility-baseline.md)

REST/MCP 요청·응답 필드, DB 스키마, 실제 SKILL 문서와 버전을 추가·삭제·수정하지 않았다. apply_patch v1.3의 JSON Schema도 그대로다. 이번 변경 자체에 Python이나 주문 Agent 운영 코드의 동시 수정은 필요하지 않으며, 기존 v1.3 응답 계약과 소비자 조합은 유지해야 한다.

## 남은 범위

이 수정은 정상 null 문법의 workspace 호환성을 해결한다. workspace 어댑터는 기존처럼 일반 단계의 누락 목록을 빈 목록으로 읽으므로 모든 실행 검증과 동일한 파서가 된 것은 아니다. 전체 필수 목록 검증 통합, entry·flow 밖 문서의 선택 목록 전체 검사, 손상된 초기 snapshot으로 진단을 시작하는 경로는 별도 작업이다.

steps를 먼저 읽으므로 여러 목록 필드가 동시에 잘못된 경우 첫 오류 필드가 달라질 수 있다. 단일 필드 오류의 예외 종류와 문구, 기존 name·version 검사 순서는 유지했다.
