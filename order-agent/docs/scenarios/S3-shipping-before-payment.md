# S3: 배송을 결제 앞으로 이동한 흐름의 계약 충돌

2026-10-21 · #56 · 주문 Agent 범위

## 시험 흐름

게시된 묶음을 기준 snapshot으로 저장하고 order-flow/SKILL.md의 steps만 직접 수정한다. 준비된 demo fixture는 사용하지 않는다.

```yaml
# 기준
steps: [order-intake, inventory-check, payment, shipping-reserve, notify]
# 수정
steps: [order-intake, inventory-check, shipping-reserve, payment, notify]
```

1. 기준 snapshot에서 정상 고객·랜턴 1개·card·국내 일반 배송 주문의 성공을 확인한다.
2. 전용 테스트 DB 재고를 10개로 초기화하고 수정 snapshot에서 같은 조건의 주문을 실행한다.
3. shipping-reserve의 정적 계약 오류로 차단되는지 확인한다.
4. 별도 snapshot에서 순서를 수동 복원하고 새 주문이 성공하는지 확인한다.

## 기대 결과

- 변경 파일은 order-flow 하나이고 나머지 파일은 기준본과 동일하다.
- 실패 위치는 shipping-reserve, category=CONTRACT_VIOLATION, phase=static_validation이다.
- missing_inputs=[payment.id, payment.status], missing_dependencies=[payment]다.
- 업무 단계 시작·도구 요청·도구 결과·전체 flow 검증 완료 이벤트가 없다.
- 수정된 flow가 실제 로드됐고 trace의 snapshot ID·파일 해시가 저장본과 일치한다.
- 주문은 FAILED, 결제·알림은 NOT_ATTEMPTED, 배송 참조는 없으며 재고는 10개 그대로다.
- HTTP trace와 파일 조회로 오류와 수정 원문을 확인할 수 있다.
- 순서 복원 후 주문은 SHIPPED이고 실제 도구 요청은 접수→재고→결제→배송→알림이다. 재고는 9개다.
- 기준본과 결함본 원문은 복원 후에도 유지된다.

## 재실행

저장소 루트에서:

```bash
docker compose -f order-agent/compose.yml --profile test up -d --wait db-test
cd order-agent/backend
ORDER_DEMO_INTEGRATION_TESTS=true ./gradlew test --tests '*AgentEvidenceIntegrationTest.shippingBeforePaymentFailsStaticValidationAndRestoredFlowPasses'
```

전체 증거 통합 테스트:

```bash
ORDER_DEMO_INTEGRATION_TESTS=true ./gradlew test --tests '*AgentEvidenceIntegrationTest'
```

## 범위

실제 snapshot 저장소·OrderHarness·전용 PostgreSQL에서 실행한다. 운영 파일·주문·재고는 변경하지 않는다. fixed 모드에서 선언된 순서·입출력·선행 의존과 실제 Mock 업무 효과를 검증한다.

순서 복원은 수동 비교 실험이다. SkillOps 자동 호출·원인 진단·후보 생성·Runner 연결은 포함하지 않는다. 변경 의도가 결제 전 배송 가능 지역 조회라면 조회 전용 도구와 단계의 별도 설계가 필요하며 이번 시험은 그 의도까지 구현하거나 검증하지 않는다.

## 실행 결과

S3의 기준본 성공 → 순서 변경본 계약 실패 → 수동 복원본 성공을 확인했다. S1·S2와 기존 검증을 포함한 AgentEvidenceIntegrationTest 13개가 모두 통과했다(실패·오류·건너뜀 0).
