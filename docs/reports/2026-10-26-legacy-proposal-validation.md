# 기존 제안 저장 경로의 실제 실행 결과 대조

작성일: 2026-10-26

## 목적과 변경 결과

기존 `save_proposal`은 근거 실행 ID가 후보 workspace에 있는지 확인하지만, 합격 여부는 요청의 `results`로 계산했다. 실제 실행이 실패했어도 성공 집계를 제출하면 합격 후보로 저장할 수 있었다.

이번 보강은 **합격 후보가 인용한 모든 실행의 서버 채점 결과도 통과해야 한다**는 조건을 추가한다. 하나라도 `scenario_runs.passed=false`이면 제안 전체를 저장하지 않는다. 실패 후보만 제외하고 조용히 저장하지 않으며, 제출 결과와 실제 결과의 모순을 호출자에게 알린다.

## 처리 흐름과 모듈

```text
기존 Python → save_proposal → ProposalService.save
  → ProposalValidator
    → workspace에 속하는 실행 ID인지 확인
    → 기존 results 합격 조건과 valid 일치 확인
    → 합격 후보의 인용 실행이 실제로 모두 통과했는지 확인
  → 기존 status·원인 증거·추천 후보 검사 → 저장
```

- `RunLedger.Evidence`: 실행 ID·시나리오 ID·실제 passed를 담는 내부 타입.
- `RunLedger.evidenceOf` / `JpaRunLedger`: workspace 단위 쿼리 한 번으로 실제 결과를 읽는다. 실행마다 별도 조회하지 않는다.
- `ScenarioRunRecord`: 기존 저장 필드인 scenario_id·passed의 조회 함수를 추가한다. DB 컬럼은 추가하지 않는다.
- `ProposalValidator.requirePassingEvidence`: 합격 후보의 실제 실패를 거부한다. 기존 ID 검사와 같은 조회 결과를 재사용한다.
- 테스트 장부도 운영 구현처럼 실제 passed를 보존한다. 변경 함수에 짧은 기능 주석을 남겼다.

## 계약과 호환성

요청·성공 응답 필드, REST 경로, 승인 API, MCP 이름, DB 스키마는 유지한다. 새 MCP를 추가하지 않는다. 기존 Python 호출 코드도 수정하지 않는다. 기존 도구의 설명과 [MCP 명세](../contracts/mcp-tools-input-output.md)에 강화된 거부 조건을 반영했다.

```json
{
  "code": "INVALID_INPUT",
  "message": "후보 1 의 근거 실행 r_example 은 실제 채점에서 실패했습니다 (시나리오 g-01)",
  "retryable": false,
  "upstream_status": null,
  "snapshot_id": null
}
```

MCP 응답은 기존처럼 `isError=true`이며 위 JSON을 content에 담는다. 같은 잘못된 요청의 단순 재시도로 해결되지 않는다. AI 생산자는 실제 실행과 집계가 다른 원인을 확인해야 한다.

| 상황 | 동작 |
|---|---|
| 기존 정상 제안, 합격 후보의 인용 실행이 모두 성공 | 기존 형식으로 저장 |
| 제출 results는 성공, 인용 실행 하나 이상은 실제 실패 | INVALID_INPUT, 저장 없음 |
| valid를 생략하고 성공 results만 제출, 실제 실패 존재 | 동일하게 거부 |
| 실패 후보에 실패 실행을 포함 | 실패 후보로 보존 가능 |
| 원인 증거에 실패 실행을 포함 | 기존대로 허용 |
| 실행 ID가 없거나 다른 workspace의 실행 | 기존대로 거부 |
| 같은 thread_id가 이미 저장됨 | 기존 제안 반환, 재검증·덮어쓰기 없음 |

기존에 잘못 통과하던 요청이 거부되는 의도적인 동작 강화다. 저장된 과거 제안과 승인 상태는 변경하지 않는다.

## 남은 검증 한계

이 변경으로 기존 경로를 완전한 서버 검증 방식으로 전환한 것은 아니다.

- 기존 장부에는 회귀·재현·의도 역할, 요청한 추가 의도 조건, 필수 평가 목록·반복 계획이 없다.
- 따라서 인용 목록에서 실패 실행을 빼거나 필요한 평가를 아예 실행하지 않은 경우까지 탐지하지 못한다.
- `keeps_old_orders.passed/total` 집계, 특정 재현 대상과 의도 조건의 실행 여부를 서버가 완전히 재구성하지 않는다.
- 정적 검증 결과인 no_broken_links·metadata_valid·flow_valid도 이번 변경에서 재검증하지 않는다.
- 같은 workspace가 실행 후 변경됐는지 원문 해시로 결속하는 검증, 원인 증거의 인과성 검증도 추가하지 않았다.
- 기존 thread_id 재호출은 과거 저장 결과를 반환하므로 과거 판정의 소급 보강은 아니다.

더 강한 보증은 [새 평가 계약](../contracts/evaluation-backend.md)의 고정 계획·snapshot/hash·필수 suite·서버 저장 결과를 사용하는 경로에서 제공한다. 기존 경로의 호환성 보강과 새 경로로의 전환은 구분한다.

## 검증 결과

최종 코드에서 `cd service && ./gradlew test --offline --no-daemon --console=plain` 성공.

- service: 총 751개 중 **743개 통과**, 실패·오류 0, PostgreSQL 통합 테스트 8개는 이번 실행에서 환경 변수 미설정으로 skip.
- 공용 skill-parser: 9개 통과(이번 실행에서는 기존 결과 재사용).
- 새 검증 6개: 회귀·재현·의도 시나리오의 실제 실패 차단 3개, valid 생략 우회 차단 1개, JPA 장부 결과 매핑·단일 조회 1개, 실제 ProposalService를 거친 기존 MCP 오류 전달·저장 차단 1개.
- 기존 정상 저장·승인·불합격 후보 보존·원인 실패 증거·재저장과 거부 우선순위 테스트도 통과했다.
- 이번 보강 후 실DB·실서버·Python·실제 LLM 테스트는 다시 실행하지 않았다. 이전 #88 수용 검증과 이번 검증 결과를 혼동하지 않는다.

검증 당시에는 커밋하지 않았으며, 이후 사용자 요청으로 단위별 로컬 커밋을 진행했다. push·배포·원격 이슈 변경·팀 메시지 전송은 수행하지 않았다.
