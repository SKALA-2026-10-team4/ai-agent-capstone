# S2: 재고 출력 삭제로 결제 계약 충돌 재현

2026-10-21 · #56 · 주문 Agent 범위

## 시험 흐름

1. 게시된 파일 묶음을 기준 snapshot으로 저장한다.
2. inventory-check/SKILL.md의 출력 선언만 직접 편집한다. 미리 준비한 demo fixture는 사용하지 않는다.

```yaml
# 기준
outputs: [inventory.available, inventory.reserved]
# 수정
outputs: [inventory.available]
```

3. 기준본으로 랜턴 1개·card·정상 고객 주문이 SHIPPED인지 확인한다.
4. 전용 테스트 DB의 재고를 초기 조건인 10개로 되돌리고 수정 snapshot으로 같은 주문을 실행한다.
5. payment에서 static_validation 단계의 CONTRACT_VIOLATION과 missing_inputs=[inventory.reserved]를 확인한다.
6. 원래 출력 선언을 별도 snapshot으로 수동 복원하고 같은 주문의 성공을 확인한다.

## 확인 기준

- 실제 변경 파일은 inventory-check이고 실패 지점은 payment다. 나머지 파일은 기준본과 동일하다.
- 실패 trace는 수정 snapshot을 참조하며 completion=FAILED다.
- 업무 단계 시작·도구 요청·도구 결과가 하나도 없다. 결제는 NOT_ATTEMPTED이며 재고는 10개 그대로다.
- 실패 주문은 FAILED로 저장되고 HTTP trace 조회로 오류를 확인할 수 있다.
- HTTP 파일 조회는 출력이 삭제된 원문을 반환한다.
- 출력 복원 후 새 주문은 SHIPPED, 재고는 9개다.
- 기준본·결함본은 복원 후에도 원문이 보존된다.

## 재실행

저장소 루트에서 전용 DB를 기동한다.

```bash
docker compose -f order-agent/compose.yml --profile test up -d --wait db-test
cd order-agent/backend
ORDER_DEMO_INTEGRATION_TESTS=true ./gradlew test --tests '*AgentEvidenceIntegrationTest.deletedInventoryOutputFailsAtPaymentBeforeBusinessToolsAndRestorationPasses'
```

S1과 기존 검증도 함께 실행하려면:

```bash
ORDER_DEMO_INTEGRATION_TESTS=true ./gradlew test --tests '*AgentEvidenceIntegrationTest'
```

## 범위와 결과

실제 snapshot 저장소·Harness·전용 PostgreSQL을 사용하는 통합 테스트다. 주문 API 서비스가 실행 오류를 BusinessException으로 전달하더라도 상세 실패 원인은 보존된 trace에서 확인한다.

SkillOps 자동 트리거·MCP diff/graph·자동 ablation·후보 생성·Runner 회귀 검증은 이번 범위에 포함하지 않는다. 출력 복원은 테스트에서 수동으로 수행한 비교 실험이며 Agent의 자동 원인 확정 또는 수정안 생성 결과가 아니다. 테스트 기록은 다음 실행에서 초기화될 수 있다.

실행 결과: S2 포함 AgentEvidenceIntegrationTest 12개 통과(실패·오류·건너뜀 0). S2는 기준본 성공 → 수정본 계약 실패 → 수동 복원본 성공의 3회 Harness 실행을 확인했다.
