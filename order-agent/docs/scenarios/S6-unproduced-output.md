# S6: 도구가 제공하지 않는 출력을 Skill에 선언

2026-10-21 · #56 · 주문 Agent 범위

## 변경과 시험 흐름

inventory-check/SKILL.md의 outputs만 직접 편집해 실제 snapshot 저장소에 저장한다. 기존 출력은 유지하고 도구가 반환하지 않는 inventory.reservation_reference를 추가한다.

```yaml
# 기준
outputs: [inventory.available, inventory.reserved]
# 수정
outputs: [inventory.available, inventory.reserved, inventory.reservation_reference]
```

inventory.reservable_quantity는 현재 도구가 이미 반환하므로 결함 재현용 필드로 사용하지 않는다.

1. 기준 snapshot에서 정상 고객·랜턴 1개·card·국내 일반 배송 주문의 성공을 확인한다.
2. 전용 테스트 DB의 재고를 10개로 초기화하고 수정 snapshot으로 같은 조건을 실행한다.
3. 정적 계약 검사 통과 후 실제 재고 예약 도구의 출력을 확인한다.
4. 추가 선언과 실제 도구 결과가 달라 MISSING_SKILL_OUTPUT로 실패하는지 확인한다.
5. 도구 구현은 바꾸지 않고, 지원하지 않는 선언을 수동 제거한 별도 snapshot으로 새 주문의 성공을 확인한다.

## 확인 기준

- 수정 파일은 inventory-check 하나다. 다른 Skill과 기준 원문은 유지된다.
- FLOW_VALIDATED가 존재하며 실제 도구 요청은 order.inspect → inventory.reserve → inventory.release다.
- 재고 도구 결과에 inventory.reserved=true가 있지만 inventory.reservation_reference는 없다.
- 재고 skill은 성공 완료되지 않고 SKILL_FINISHED의 status=error, 오류는 MISSING_SKILL_OUTPUT다.
- 최종 completion과 주문 상태는 FAILED다. 결제·배송·알림은 실행하지 않는다.
- 재고 예약은 실제로 수행됐으나 미결제 오류 보상으로 해제돼 재고가 10개로 돌아온다.
- trace의 snapshot·파일 로드 해시는 수정본과 일치한다. HTTP trace·파일 조회에서도 오류와 수정 원문을 확인한다.
- 선언을 제거한 새 snapshot에서는 SHIPPED/SENT이며 재고가 9개다. 결함 snapshot은 유지된다.

## 재실행

저장소 루트에서:

```bash
docker compose -f order-agent/compose.yml --profile test up -d --wait db-test
cd order-agent/backend
ORDER_DEMO_INTEGRATION_TESTS=true ./gradlew test --tests '*AgentEvidenceIntegrationTest.declaredButUnproducedInventoryOutputFailsAtRuntimeAndReleasesReservation'
```

전체 증거 통합 테스트:

```bash
ORDER_DEMO_INTEGRATION_TESTS=true ./gradlew test --tests '*AgentEvidenceIntegrationTest'
```

## 범위

fixed 모드의 실제 snapshot·OrderHarness·Mock 도구·전용 PostgreSQL 통합 검증이다. 운영 코드와 운영 데이터는 변경하지 않는다. SkillOps 자동 호출·원인 진단·후보 생성·Runner 평가는 포함하지 않는다.

지원하지 않는 선언을 제거한 것은 수동 비교 실험이다. 새 필드가 필요한 업무 의도라면 실제 도구 출력 구현을 추가하고 별도 검증해야 한다. 선언 제거만으로 그 의도까지 충족했다고 판정하지 않는다.

## 실행 결과

기준본 성공 → 출력 선언 불일치로 실행 실패 → 미결제 예약 해제 → 선언 복원본 성공을 확인했다. S1~S6 관련 검증을 포함한 AgentEvidenceIntegrationTest 16개가 모두 통과했다(실패·오류·건너뜀 0). S5는 정책 구현 통과가 아닌 fixed 모드의 한계 재현 검증이다.
