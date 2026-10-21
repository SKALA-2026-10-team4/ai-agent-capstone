# S4: 알림 누락으로 불완전한 업무 흐름 검출

2026-10-21 · #56 · 주문 Agent 범위

## 시험 흐름

기존 incompleteFlowIsNeverMarkedShipped 테스트를 실제 편집·비교·업무 효과·재요청 검증까지 확장한다. 준비된 fixture 대신 order-flow/SKILL.md에서 마지막 notify만 직접 삭제해 snapshot으로 저장한다. notify 파일 자체는 유지한다.

```yaml
# 기준
steps: [order-intake, inventory-check, payment, shipping-reserve, notify]
# 수정
steps: [order-intake, inventory-check, payment, shipping-reserve]
```

1. 정상 고객·랜턴 1개·card·국내 일반 배송을 기준 snapshot에서 실행하고 성공을 확인한다.
2. 전용 테스트 DB 재고를 10개로 초기화하고 알림 없는 snapshot으로 같은 조건을 실행한다.
3. 입력·선행 의존 검사는 통과하지만, 업무 실행 후 INCOMPLETE_FLOW로 실패하는 것을 확인한다.
4. 같은 요청 키·같은 입력으로 재전송해도 결제·배송이 중복되지 않는지 확인한다.
5. 전용 테스트 재고를 초기화하고, 알림을 수동 복원한 별도 snapshot으로 새 요청 키의 주문을 실행한다.

## 확인 기준

- 변경 파일은 order-flow 하나이며 나머지 파일과 기준 원문은 보존된다.
- trace의 snapshot ID는 수정본이며 FLOW_VALIDATED 이후 접수→재고→결제→배송이 실제 완료된다.
- notify.send 및 inventory.release 요청은 없다.
- 최종 completion=FAILED, error_category=INCOMPLETE_FLOW다. 완료 이벤트에 COMPLETED가 없어야 한다.
- 결제는 PAID이며 승인 참조가 보존된다. 배송 예약 참조도 보존된다.
- 알림은 NOT_ATTEMPTED이고 재고는 9개다. 결제된 주문의 재고를 자동으로 되돌리거나 이미 발생한 업무 효과가 사라졌다고 간주하지 않는다.
- 같은 요청을 재전송하면 기존 실패 실행·결제·배송 참조를 반환하고 trace 이벤트 수와 재고가 바뀌지 않는다.
- HTTP trace와 파일 조회에서 오류와 실제 수정 원문을 확인할 수 있다.
- 알림 복원본의 새 주문은 SHIPPED/SENT다. 원래 실패 주문과 결함 snapshot은 변경되지 않는다.

## 재실행

저장소 루트에서:

```bash
docker compose -f order-agent/compose.yml --profile test up -d --wait db-test
cd order-agent/backend
ORDER_DEMO_INTEGRATION_TESTS=true ./gradlew test --tests '*AgentEvidenceIntegrationTest.incompleteFlowIsNeverMarkedShipped'
```

전체 회귀:

```bash
ORDER_DEMO_INTEGRATION_TESTS=true ./gradlew test --tests '*AgentEvidenceIntegrationTest'
```

## 범위

실제 snapshot 저장소·OrderHarness·Mock 업무 도구·전용 PostgreSQL의 통합 검증이다. 운영 서비스와 데이터는 변경하지 않는다. fixed 모드에서 실제 흐름 실행과 완료 조건을 확인한다.

수정은 테스트에서 수동 복원한다. SkillOps 자동 트리거·진단·수정 후보 생성·Runner 평가는 포함하지 않는다. 실패 주문의 자동 복구·환불·배송 취소를 구현한 것은 아니다. 테스트 DB 기록은 다음 실행에서 초기화될 수 있다.

## 실행 결과

기준본 성공 → 알림 누락본 INCOMPLETE_FLOW → 동일 요청 재전송 시 중복 효과 없음 → 수동 복원본 새 주문 성공을 확인했다. 기존 테스트를 확장했으므로 전체 개수는 13개로 유지되며, S1~S4 포함 13개 모두 통과했다(실패·오류·건너뜀 0).
