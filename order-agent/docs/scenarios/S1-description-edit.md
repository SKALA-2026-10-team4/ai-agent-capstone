# S1: 설명 수정 후 주문 Agent 회귀 검증

2026-10-21 · #56 · 주문 Agent 범위

## 범위

`inventory-check/SKILL.md`의 description만 바꾸어 실제 snapshot 저장소에 새 후보로 저장한 뒤, 같은 OrderHarness에서 기준본과 수정본을 각각 실행한다. fixture의 정상본·수정본 버튼을 쓰지 않는다. 테스트에서 원문을 편집하며 운영 Skill 파일은 변경하지 않는다.

UI 저장 이벤트, Runner/Judge, MCP, SkillOps Agent 자동 트리거 및 불필요한 진단 미생성은 이 테스트에서 확인하지 않는다. S1 전체 파이프라인 통과와 구분한다. fixed 모드로 계약·업무 회귀를 검사하며 자연어 정책 해석 능력을 검증하지 않는다.

## 변경 내용

- 기준: `description: 상품 전체 재고를 확인하고 예약한다`
- 수정: `description: 주문에 포함된 모든 상품의 재고를 확인한 뒤 전체 수량을 예약한다`
- inputs/outputs/depends_on/steps 및 본문은 유지한다.
- candidate metadata에 기준 snapshot ID를 남긴다.

## 시험 조건과 기대 결과

각 조건에서 기준본과 수정본을 독립 주문으로 실행한다. 매 주문 직전 전용 테스트 DB의 랜턴 재고를 같은 값으로 초기화한다.

| 고객 | 초기 재고 | 최종 상태 | 잔여 재고 | 결제 | 배송·알림 |
|---|---:|---|---:|---|---|
| c_1001 | 10 | SHIPPED | 9 | PAID | 수행 |
| c_1001 | 0 | REJECTED | 0 | NOT_ATTEMPTED | 미수행 |
| c_declined | 10 | HOLD | 10 | DECLINED | 미수행 |

공통 검증:

- 주문 금액은 랜턴 1개 가격인 30,000원이다.
- 실제 도구 요청 순서는 성공 시 접수→재고→결제→배송→알림이다. 재고 부족 시 재고 단계에서 중단하고, 결제 거절 시 재고 해제 도구까지 실행한 뒤 중단한다.
- 두 실행 모두 COMPLETED이며 실행 오류가 없다.
- trace의 snapshot ID와 inventory-check 로드 해시가 각 저장본과 일치한다.
- 수정 파일을 사용하는 DecisionProvider 호출이 실제 발생한다.
- 후보의 다른 파일은 기준본과 동일하고, 기준 snapshot 원문은 보존된다.
- HTTP 파일 조회에서도 저장된 수정 원문을 반환한다.

## 실행

저장소 루트에서 테스트 전용 DB를 기동한다. 운영 데모 DB는 대상이 아니다.

```bash
docker compose -f order-agent/compose.yml --profile test up -d --wait db-test
cd order-agent/backend
ORDER_DEMO_INTEGRATION_TESTS=true ./gradlew test --tests '*AgentEvidenceIntegrationTest'
```

S1만 실행하려면:

```bash
ORDER_DEMO_INTEGRATION_TESTS=true ./gradlew test --tests '*AgentEvidenceIntegrationTest.descriptionOnlyEditPreservesBusinessResultsAndLoadsEditedBytes'
```

구현: `backend/src/test/java/com/capstone4/orderagent/integration/AgentEvidenceIntegrationTest.java`.
JUnit 결과: `backend/build/test-results/test/TEST-com.capstone4.orderagent.integration.AgentEvidenceIntegrationTest.xml`.
HTML 보고서: `backend/build/reports/tests/test/index.html`.

## 결과

S1 3개 조건에서 기준·수정본 총 6회 실제 Harness 실행을 검증했다. 기존 검증을 포함한 AgentEvidenceIntegrationTest 11개가 모두 통과했다(실패·오류·건너뜀 0). 테스트 DB 기록은 다음 실행에서 초기화될 수 있으므로 영구 평가 증거 ID로 사용하지 않는다.
