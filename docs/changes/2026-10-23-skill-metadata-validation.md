# #72 · SKILL 필수 정보 검사와 후보 판정

## 해결한 문제

owner·description·본문이 빠져도 service가 patch를 저장하고 연결 오류가 없다고 반환할 수 있었지만, 실제 주문 Agent는 INVALID_SKILL_FORMAT으로 거부했다. 저장 성공을 실행 가능한 수정안으로 오해하지 않도록 두 판정을 구분했다.

## 변경 내용

- service에 `SkillMetadataValidator`를 분리했다. name·version·owner·description·본문과 실행용 YAML 경계를 검사한다. 기존 공통 YAML 파서를 사용하되 실행용 100,000 코드 포인트 제한을 적용한다.
- `apply_patch` 응답 v1.2에 필수 `metadata_errors:[{skill,field,code,message}]`를 추가했다. 전체 workspace를 검사해 수정하지 않은 문서와 flow 밖 문서의 오류도 반환한다.
- 기존 patch 거부 조건은 유지한다. 해당 조건을 통과한 수정은 필수 정보 오류가 있어도 원인 재현·비교 실험을 위해 저장한다.
- Python은 `results.metadata_valid`를 별도로 계산한다. 모든 실행이 통과해도 필수 정보 오류가 있으면 `valid=false`이며 추천에서 제외한다. 오류 상세는 후보의 `metadata_errors`로 제안서에 보존한다.
- service의 제안서 합격 재계산과 생성 문장도 새 결과를 반영한다. 과거 제안서와 구버전 생산자 호환을 위해 `metadata_valid`가 없으면 기존 판정을 유지하고, 명시한 경우 Boolean true만 통과한다. 최신 Python은 해당 결과를 반드시 보낸다.
- 실제 주문 Agent 운영 코드는 바꾸지 않고 공유 문서 37개를 두 Java 모듈에서 읽는 회귀 테스트를 추가했다. 필수값 누락·null·타입·이름 불일치·버전 범위·본문·CRLF·YAML 오류를 확인한다.
- 새 함수에는 짧은 기능 주석을 추가했다. Python stub 및 실제 MCP 판정 스크립트도 새 응답을 사용한다.

## 명세 및 배포

- [MCP 입출력](../contracts/mcp-tools-input-output.md#8-수정-적용하기--apply_patch)
- [응답 JSON Schema](../contracts/apply-patch-result.schema.json)
- [SKILL 필수 정보 기준](../contracts/skill-frontmatter.md#필수-정보-검사-72--3단계)
- [필수 정보 오류 예제](../contracts/examples/apply-patch-metadata-error.json)
- [제안서 Schema](../contracts/proposal.schema.json)

service와 Python은 함께 갱신해야 한다. 새 Python은 `metadata_errors`가 누락·null·잘못된 형태인 구버전 서버 응답을 ContractError로 중단한다. 구버전 Python은 새 오류 목록을 판정에 사용하지 않으므로 새 service만 배포해서도 필수 정보 판정이 완성되지 않는다.

REST 경로·메서드·요청 DTO는 변경하지 않았다. 제안서 JSON에는 새 결과와 오류 상세가 추가될 수 있고, 생성 본문에는 필수 정보 판정 문장이 추가된다. DB 스키마와 주문 Agent 운영 로더는 변경하지 않았다.

## 남은 범위

두 서버의 파서 라이브러리를 물리적으로 통합한 작업은 아니다. 선택 목록의 null 처리, flow 단계 수·중첩·분석 완전성, 손상된 초기 snapshot의 진단 시작 경로는 별도 작업이다. `metadata_valid=true`만으로 실제 실행 성공을 보장하지 않는다. 실제 서버 배포·DB/LLM 연동 평가는 이번 로컬 검증에 포함하지 않는다.

## 검증 결과

- service: `./gradlew test --offline --no-daemon --console=plain` — 576개 통과.
- order-agent/backend: 같은 Gradle 명령 — 88개 통과, DB 통합 테스트 82개는 기존 환경 변수 조건에 따라 미실행.
- 두 Java 모듈에서 공통 SKILL 문서 37개를 각각 검증하고 YAML 길이 상한도 확인했다.
- Python: `.venv/bin/python -m pytest -q` — 479개 통과. 필수 정보 오류가 있는 후보의 전체 그래프 실행과 무효 제안서 저장까지 확인했다.
- 변경한 Python 파일의 Ruff 검사 통과.
- 두 JSON Schema, 두 MCP 응답 fixture, 제안서의 오류 목록 참조를 JSON Schema 검증기로 확인했다.
- `git diff --check` 통과.
