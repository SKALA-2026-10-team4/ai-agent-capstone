# #72 · flow 실행 구조 검사

## 해결한 문제

service의 진단용 그래프는 중복 단계를 계속 분석하고 단계 수 상한을 검사하지 않는다. 없는 단계나 읽을 수 없는 단계가 있어 complete=false가 되어도 입력 공급·선행 단계 오류 목록만 보면 정상처럼 보일 수 있었다. 실제 주문 Agent는 이러한 구조를 실행 전에 거부한다.

## 변경 내용

- `FlowStructureValidator`를 별도 모듈로 추가했다. 중복, 12단계 초과, 잘못된 단계 이름, 없는/읽을 수 없는 단계, 중첩 flow를 구조화한 오류로 반환한다. 분석 상태와 단계 노드가 불완전한 경우도 합격시키지 않는다.
- 진단용 `DependencyAnalyzer`와 `get_dependency_graph`의 출력은 유지한다. 중복과 읽기 실패를 그래프에서 계속 확인할 수 있다. complete=true는 실행 구조까지 유효하다는 뜻이 아니다.
- 이름 검사를 `SkillNames`로 분리해 필수 정보와 구조 검사가 같은 규칙을 사용하게 했다. 새 함수에 짧은 기능 주석을 추가했다.
- MCP `apply_patch` 응답 v1.3에 필수 `flow_errors:[{skill,code,reason,message}]`를 추가했다. 기존 입력·선행 단계·필수 정보 오류 목록은 유지한다.
- 구조 오류가 있어도 기존 거부 조건을 통과한 patch는 실험용으로 저장한다. 빈 entry·entry 분석 실패·변경한 단계의 parse error·기준 버전 불일치·원문 불일치는 기존처럼 전체 적용을 거부한다.
- Python 후보 검증은 `results.flow_valid`를 별도로 계산한다. 모든 실행·연결·필수 정보 검사가 통과해도 구조 오류가 있으면 무효이며 추천하지 않는다. 상세 오류는 후보의 `flow_errors`로 제안서에 보존한다.
- 원인 실험(ablation)은 오류 목록의 존재·형식을 검증하되, 목록이 비어 있지 않다는 이유로 저장된 patch를 적용 실패로 바꾸지 않는다.
- service의 제안서 판정과 본문에도 새 검사 결과를 반영했다. 과거 제안서와 구버전 생산자 호환을 위해 flow_valid 누락은 기존 판정을 유지하고, 명시한 결과는 Boolean true만 통과한다. 최신 Python은 결과를 반드시 보낸다.
- 실제 주문 Agent의 운영 로더는 변경하지 않고 공통 flow 사례 15개를 두 Java 모듈에서 검사한다. 단계 수 상한 12도 같은 fixture에서 대조한다.

## 명세와 배포

- [MCP v1.3 명세](../contracts/mcp-tools-input-output.md#8-수정-적용하기--apply_patch)
- [응답 JSON Schema](../contracts/apply-patch-result.schema.json)
- [flow 오류 예제](../contracts/examples/apply-patch-flow-error.json)
- [공통 flow 사례](../contracts/examples/flow-structure-cases.json)
- [SKILL 실행 구조 계약](../contracts/skill-frontmatter.md#flow-실행-구조-검사-72--4단계)
- [제안서 Schema](../contracts/proposal.schema.json)

service와 Python을 함께 갱신해야 한다. 새 Python은 구버전 서버의 flow_errors 누락·null·잘못된 형태를 ContractError로 중단한다. 구버전 Python은 새 오류를 판정에 사용하지 않으므로 service만 갱신해서는 구조 검사가 완성되지 않는다.

REST 경로·메서드·요청 DTO와 DB 스키마는 변경하지 않았다. 제안서 JSON에는 flow_valid와 오류 목록이 추가될 수 있으며 본문에는 실행 구조 판정 문장이 추가된다.

## 범위와 한계

flow 구조 판정은 필수 정보 판정과 독립적이다. flow_valid=true만으로 모든 문서 형식과 실제 업무 실행을 보장하지 않는다. entry 선택 목록 전체와 flow 밖 문서의 목록 검사, 필수 마지막 단계 완료·도구 바인딩, 목록 null 규칙 통일, 손상된 초기 snapshot 진단 시작 경로는 이번 범위에 포함하지 않는다. INCOMPLETE_ANALYSIS는 실제 실행기 오류 사유를 복제한 값이 아니라 service가 불완전한 분석의 합격을 막는 방어 조건이다.

실제 서버 배포와 DB·LLM 연동 평가는 수행하지 않았다.

## 검증 결과

- service: `./gradlew test --offline --no-daemon --console=plain` — 601개 통과.
- order-agent/backend: 같은 Gradle 명령 — 104개 통과. DB 통합 테스트 82개는 기존 환경 변수 조건에 따라 미실행.
- 공통 flow 사례 15개를 두 서버에서 검사했다. 12·13단계 경계, 중복, 없는 단계, 잘못된 이름·목록·YAML, 중첩 flow와 빈 entry를 확인했다.
- Python: `.venv/bin/python -m pytest -q` — 510개 통과. 실행 결과가 모두 통과해도 구조 오류 후보를 무효로 두고 오류 목록을 제안서에 보존하는 전체 그래프 테스트를 포함한다.
- 두 JSON Schema와 MCP 응답 예제 3개를 검증했다. 허용하는 코드·사유 조합 8개가 Python과 일치하고, 잘못된 조합은 양쪽이 거부하는지 확인했다. 제안서의 오류 목록 참조도 검증했다.
- 실제 MCP 판정 스크립트의 로컬 유효성 함수가 구조 오류를 반영하는지 확인했다. 서버 연결은 수행하지 않았다.
- 변경한 Python 파일의 Ruff 검사와 `git diff --check` 통과.
