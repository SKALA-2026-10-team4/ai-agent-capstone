# #88 새 평가 MCP의 인증 간소화

사용자 요청에 따라 새 MCP 4개를 기존 MCP처럼 JWT 없이 호출하도록 변경했다. REST 평가·검증 제안 API와 사람의 승인·반려 API는 기존 인증·권한 검사를 유지한다. 현재 계약은 [평가 계약의 MCP 절](../contracts/evaluation-backend.md#mcp)이다.

> develop 병합 후에는 JWT 불필요 정책을 유지하되, SKILLOPS_SERVICE_TOKEN 설정 시 X-Service-Token이 필요한 공통 MCP 필터를 보존했다. 미설정 시 무인증이다. [병합 보고서](2026-10-26-issue-88-develop-merge.md)에 현재 동작과 전체 재검증 결과를 기록했다.

## 변경 내용

- `EvaluationTools`의 `@PreAuthorize`와 SecurityContext 의존성을 제거했다.
- `submit_evaluation`, `get_evaluation`, `get_evaluation_report`, `save_proposal_v2`를 토큰 없이 호출할 수 있다.
- 도구 이름·요청·결과·서버 평가 및 제안 검증 규칙은 유지한다. 제안 저장은 승인이나 운영 반영을 수행하지 않는다.
- MCP 접수는 인증 정보와 관계없이 `mcp:anonymous` 범위를 사용한다. REST는 사용자 이름을 범위로 사용한다. 해당 이름은 REST 사용자 이름으로 사용하지 않는다.
- 모든 MCP 호출자가 키 범위를 공유하므로 요청마다 UUID 등 고유 키를 생성하고 재시도 때만 같은 키를 사용한다.
- 같은 MCP 키·같은 요청은 기존 작업을 재사용한다. 같은 키·다른 요청은 충돌 오류다. REST와 MCP에 같은 키를 보내면 각각 별도 작업으로 접수되므로 전송 경로를 바꾸어 재시도하지 않는다.
- 기존 JWT 방식으로 MCP에 접수한 작업도 ID로 조회할 수 있다. 인증 간소화 후 같은 키로 다시 접수하면 범위가 달라 새 작업이 생성될 수 있으므로 이전 작업 ID를 조회한다.
- Python 클라이언트는 수정하지 않았다. 기존의 토큰 없는 MCP 전송 방식으로 새 도구도 호출할 수 있지만, 새 도구를 호출하는 업무 흐름은 AI 담당자의 연동이 필요하다.

이 설정은 MCP에 접근 가능한 호출자에게 평가 접수·조회·검증 제안 저장을 허용하는 내부 개발 환경 정책이다.

## 검증

`cd service && ./gradlew test --offline --no-daemon --console=plain` 성공.

- service 총 757개 중 **749개 통과**, 실패·오류 0, PostgreSQL 통합 테스트 8개는 이번 실행에서 환경 변수 미설정으로 제외.
- 공용 파서 9개 성공 결과 재사용.
- 새 테스트 6개: 메서드 보안이 활성화된 Spring 빈에서 무인증 MCP 4개 호출, 인증 정보와 무관한 고정 접수 범위, 서버 검증 오류 보존, REST 제안 저장의 익명 401·OWNER 403·OPERATOR 성공.
- 기존 평가 REST와 사람의 승인·반려 권한 테스트도 전체 실행에 포함됐다.
- 스모크 스크립트를 무인증 MCP 호출·MCP 중복 접수·REST/MCP 키 분리 확인으로 갱신했다. Python 구문 검사와 네트워크 없는 응답 시뮬레이션으로 MCP 요청에 토큰을 보내지 않는지 확인했다.
- 갱신한 전체 스모크는 성공 REST 6회·실패 REST 6회·별도 MCP 접수 6회로 실제 fixed 실행 18회를 생성한다. MCP 재시도는 추가 작업을 만들지 않는다.
- 무인증 전환 후 실제 서버·DB 수용 검증도 완료했다. 아래 결과를 따른다. 이전 JWT 검증 JSON과 이번 증거는 별도 보존한다. 실제 LLM 실행은 하지 않았다.

구현은 `f7b2245`에 커밋했다. 아래 실서버 수용 검증 결과 문서·JSON은 후속 작업으로, 사용자 요청에 따라 별도 로컬 커밋에 기록한다. push·배포는 수행하지 않았다.


## 무인증 전환 후 실제 서버 수용 검증

2026-10-26, 소스 `f7b2245`로 성공했다. 별도 PostgreSQL 17 컨테이너와 service_eval/order_eval DB를 생성하고, service 18080·주문 Agent 18081을 루프백에 바인딩했다. 기존 Docker 8081 서버·DB를 사용하거나 변경하지 않았다.

```bash
skillops-agent/.venv/bin/python service/scripts/evaluation_contract_smoke.py \
  --service http://127.0.0.1:18080 \
  --order-agent http://127.0.0.1:18081 \
  --environment eval-88-anonymous-isolated-v1 \
  --output /private/tmp/evaluation-88-anon-smoke.json
```

| 검증 | 실제 결과 |
|---|---|
| JWT 없는 MCP 초기화·목록·새 도구 4개 | 성공, 전체 도구 11개 |
| submit_evaluation 접수·재접수 | 같은 MCP 키의 동일 작업 재사용 |
| 동일 키를 사용한 REST/MCP 접수 | 별도 evaluation 생성, 범위 분리 확인 |
| 실제 fixed 변경 전후 평가 | 기준 0/3 → 후보 3/3, 회귀 0, SERVER_VERIFIED |
| 실패 후보 평가·검증 제안 저장 | FAIL·NOT_VERIFIED, REST 409 및 무인증 MCP EVIDENCE_MISMATCH |
| 무인증 save_proposal_v2 | 성공, 승인 후 재저장도 같은 proposal_id·approved 반환 |
| REST 및 사람 승인 | OPERATOR 승인 성공, 아래 무권한 요청 차단 |
| 비로그인 REST 쓰기 7개 경로 | 전부 401 |
| OWNER REST 쓰기 7개 경로 | 전부 403 |
| REST 평가 조회 | 비로그인 401, OWNER 성공 |
| 서비스 재시작·새 로그인·새 무인증 MCP 세션 | 보고서 전체 일치, approved 상태와 완료 수 보존 |
| 재고·live 실패 목록 | 실행 전후 동일 |
| 실제 실행 DB 집계 | 18건, 모두 fixed/dry_run, api_calls 합계 0 |
| 기존 Python MCP 클라이언트 | 수정 없이 tools/list 11개·ping pong 성공 |

권한을 확인한 쓰기 경로는 평가 접수, 검증 제안 저장, 평가셋 검토, 취소, 재시도, 제안 승인, 제안 반려다. 승인·반려는 이미 승인된 제안을 대상으로도 권한 검사에서 차단되어 상태를 바꾸지 않았다. 각 요청에 유효한 본문·필수 헤더를 사용해 입력 오류와 인증 거부를 혼동하지 않았다.

접수 작업 3개는 전부 실행을 마쳐 status=SUCCEEDED였다. 그중 실패 후보의 **평가 판정**은 FAIL이다. 작업 완료 상태와 후보 합격을 구분한다.

추가 권한·재시작 검증은 임시 Python 점검 스크립트로 실제 HTTP를 호출했고, 실행 수·모델 사용량은 일회용 DB의 agent_executions 집계로 확인했다. 인증 토큰·주문 원문·전체 trace를 제외한 결과는 [수용 검증 JSON](2026-10-26-evaluation-mcp-anonymous-smoke.json)에 보관한다.

이번 검증은 실제 LLM 품질이나 AI 그래프 전체 연동을 검증한 것은 아니다. 검증용 service·주문 Agent 프로세스와 일회용 DB 컨테이너는 정리했다. 기존 사용자 컨테이너 3개는 그대로 유지했다.
