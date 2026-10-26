# #88 develop 충돌 해결과 전체 검증

작성일: 2026-10-26 · 작업 브랜치: `feat/#88_skill-backend-contract`

브랜치 HEAD `5850783`에 develop의 `35c0a65`(#92 행동 규칙 변경)를 병합하던 충돌 10개를 해결했다. 사용자가 승인한 방향대로 기존 제안은 develop의 서버 재판정을 기준으로 통합하고, #88의 독립 평가·비교·검증 제안과 JWT 없는 MCP 도구를 보존했다.

## 통합 정책

| 상황 | 현재 처리 |
|---|---|
| AI 합격 주장과 실제 실행 결과가 다름 | ProposalJudge가 서버 판정으로 교정하고 server_overrides에 기록 |
| 합격 후보가 없음 | no_valid_fix로 보존, 기존 승인 API에서 승인 차단 |
| 실행 ID·workspace 참조가 잘못됨 | 기존 제안 저장 거부 |
| 필수 snapshot 누락·증거 조회 실패 | 기존 제안 저장 거부 |
| #88 평가 후보 실패 | 실패 보고서 보존, save_proposal_v2에서 제안 생성 거부 |
| #88 평가가 SERVER_VERIFIED | 검증 제안 생성 및 사람 승인 가능 |

실패 후보를 진단 기록으로 보존하는 것과 승인 대상으로 허용하는 것은 다르다. 기획의 재검증·근거 보존·검증된 안만 승인 요청이라는 방향을 유지한다. 이전 #88의 '실제 실패 인용 시 제안 전체 거부' 정책은 기존 경로에서 더 이상 사용하지 않는다.

## 파일별 해결

- `RunLedger`, `JpaRunLedger`, `ScenarioRunRecord`: develop의 Run/runsOf/find와 intent_scoped·checks_hash·intent_matches를 사용한다. 중복 Evidence/evidenceOf는 제거했다.
- `ProposalService`, `ProposalValidator`, `ProposalTools`: develop의 ProposalJudge·snapshot 필수·개발 요청서·server_overrides 계약을 사용한다.
- `JpaRunLedgerTest`, `TestFixtures`: 확장된 장부 구조와 결과 보존 테스트를 develop 기준으로 유지했다.
- MCP 계약 문서 2개: develop의 조건 문법 v2·apply_patch v1.4·재판정 명세와 #88의 추가 도구 4개·평가 계약을 함께 보존했다.
- 자동 병합된 `ProposalServiceTest`, `ProposalToolsTest`: 이전 생성자와 '실패면 예외' 가정을 제거하고, 실제 service·기존 MCP에서 불합격 판정으로 기록이 저장되는지 검사하도록 통합했다. 잘못된 증거 참조의 거부 테스트는 유지한다.

기존 7개 MCP와 #88의 4개 도구를 합해 총 11개다. #88 도구 자체에는 JWT를 요구하지 않는다. develop에 추가된 공통 필터는 SKILLOPS_SERVICE_TOKEN 설정 시 모든 /mcp 요청에 X-Service-Token을 요구하며, 비어 있으면 인증 없이 호출할 수 있다. REST 평가·검증 제안 및 사람 승인·반려의 JWT·역할 검사는 유지한다.

기존 제안의 합격 기준에는 develop의 최소 반복 수·기준 주문 세트·workspace 고정 및 patch 일치·snapshot metadata·의도 조건이 적용된다. #88 별도 평가의 고정 계획·반복·SERVER_VERIFIED 정책과 혼동하지 않는다.

## 전체 자동화 검증

| 대상 | 테스트 수 | 결과 |
|---|---:|---|
| service | 914 | 전부 통과, 실패·오류·skip 0 |
| 주문 Agent backend | 361 | 전부 통과, 실패·오류·skip 0 |
| 공용 skill-parser | 9 | 전부 통과, 실패·오류·skip 0 |
| SkillOps Python Agent | 867 | 전부 통과 |
| QA 단위 테스트 | 161 | 전부 통과 |
| 운영 화면 단위 테스트 | 134 | 전부 통과 |
| 주문 화면 단위 테스트 | 76 | 전부 통과 |
| 양쪽 화면 production build | 2개 앱 | 성공 |

테스트 합계는 2,522개다. backend 전체에는 service PostgreSQL 계약 테스트 8개와 주문 Agent의 조건부 실DB 통합 테스트를 포함한다. 기존 사용자 DB와 분리된 PostgreSQL 17 일회용 컨테이너에서 실행했다. service는 캐시로 통합 테스트가 생략되지 않도록 :cleanTest :test로 실제 재실행했다.

```bash
cd service
SKILLOPS_EVALUATION_INTEGRATION_TESTS=true \
SKILLOPS_EVAL_TEST_DB_URL=jdbc:postgresql://127.0.0.1:5434/eval_test \
./gradlew :cleanTest :test --offline --no-daemon --console=plain

cd ../order-agent/backend
ORDER_DEMO_INTEGRATION_TESTS=true OPENAI_API_KEY=not-set \
ORDER_AGENT_MODEL_MODE=fixed \
./gradlew test --offline --no-daemon --console=plain
```

Python은 기존 skillops-agent/.venv의 Python 3.11로 각 폴더에서 pytest를 실행했다. 화면 코드는 수정하지 않고 기존 단위 테스트와 빌드만 확인했다.

## 병합 코드의 실제 서버 수용 검증

같은 일회용 컨테이너에 통합 테스트 DB와 별개의 service_eval/order_eval DB를 만들었다. service 18080·주문 Agent 18081은 루프백 전용이며 fixed 모드·빈 서비스 토큰으로 실행했다. 기존 8081 서버와 사용자 DB는 변경하지 않았다.

- 무인증 MCP 초기화·목록·신규 4개 도구 호출 성공, 기존 Python MCP 클라이언트의 도구 11개·ping 성공.
- REST/MCP 접수 키 분리 및 MCP 중복 접수의 동일 작업 재사용 확인.
- 실제 전후 실행: 기준 0/3 → 후보 3/3, 회귀 0, SERVER_VERIFIED.
- 실패 평가: FAIL·NOT_VERIFIED, 검증 제안 REST 409·MCP EVIDENCE_MISMATCH.
- OPERATOR의 실제 사람 승인 성공, 무인증 재저장에서도 같은 제안 ID·approved 반환.
- 비로그인 REST 쓰기 7개 경로 401, OWNER 쓰기 7개 경로 403. 비로그인 평가 조회 401·OWNER 조회 성공.
- 실제 실행 18건 모두 fixed/dry_run, agent_executions.api_calls 합계 0. 재고·live 실패 목록은 실행 전후 동일.

권한 검사 경로는 평가 접수·검증 제안 저장·평가셋 검토·취소·재시도·사람 승인·반려다. 토큰 설정 시 공통 MCP 필터의 차단·허용은 전체 자동화 테스트에 포함돼 있으며, 이 실서버 스모크는 토큰 미설정 경로를 검증했다.

[수용 검증 JSON](2026-10-26-issue-88-develop-merge-smoke.json)은 인증 토큰·주문 원문·전체 trace를 제외한 요약이다. 테스트 당시 병합 작업 트리로 실행했으며 완성된 merge commit SHA를 뜻하지 않는다.

## 검증 범위와 후속 작업

- 실제 LLM 호출·품질 평가와 전체 AI 그래프 연동은 실행하지 않았다.
- 브라우저 E2E는 실제 주문·진단·모델 호출을 사용하는 별도 환경이 필요하여 실행하지 않았다. 화면 단위 테스트·빌드와 backend 실서버 수용 검증으로 구분한다.
- 기존 legacy 제안의 서버 재판정과 #88 v2 검증 제안의 정책 차이는 명세에 반영했다. AI 담당자와 신규 계약 연동 합의는 남는다.
- 충돌 해결·테스트를 마친 뒤 사용자 요청으로 병합 커밋을 작성한다. push는 수행하지 않는다.

검증용 service·주문 Agent와 일회용 PostgreSQL은 종료·제거했다. 기존 사용자 컨테이너 3개는 유지했다. 충돌 10개를 해결 표시하여 스테이징했고, 검증 당시에는 merge commit 작성 전 상태로 남겼고, 이후 사용자 요청으로 병합 커밋에 반영한다. 들어온 문서의 EOF 빈 줄 경고 1개도 정리했다.
