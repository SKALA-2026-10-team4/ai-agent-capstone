# S5: 본문 결제 정책 변경과 fixed 모드의 실행 한계

2026-10-21 · #56 · 주문 Agent 범위

## 판정 기준

목표 정책은 prepaid 주문만 결제하고 card 주문은 결제 없이 보류하며 배송·성공 알림도 하지 않는 것이다. 현재 fixed 모드에서 이 정책을 지원한다고 판정해서는 안 된다. 이 테스트는 실제 본문이 저장·로드·전달되지만 정책은 반영되지 않는 현상을 재현하는 기능 한계 검증이다.

| 수정본 결제 방식 | 의도한 결과 | fixed 모드의 관찰 결과 |
|---|---|---|
| card | HOLD, 결제·배송·성공 알림 미실행 | SHIPPED, PAID, 배송·알림 수행: 의도 불충족 |
| prepaid | SHIPPED, 결제·배송·알림 수행 | SHIPPED, PAID, 배송·알림 수행: 결과 일치만으로 본문 정책 적용을 증명할 수 없음 |

JUnit의 통과는 위 한계 재현이 성공했다는 뜻이다. S5의 새 정책 인수 기준이 통과했다는 뜻이 아니다.

## 실제 수정 및 실행

payment/SKILL.md의 본문 끝에 다음 정책을 추가한다. 입력·출력·의존·description·version은 그대로 유지한다.

```markdown
## 변경된 결제 정책

결제 방식이 prepaid인 주문만 결제합니다. card 주문은 결제 도구를 호출하지 않고 보류하며 배송과 성공 알림을 실행하지 않습니다.
```

- 실제 snapshot 저장소에 기준본과 수정본을 각각 저장한다. 준비된 fixture는 사용하지 않는다.
- 정상 고객 c_1001·랜턴 1개로 card/prepaid를 기준본과 수정본에서 각각 실행한다(총 4회).
- trial마다 전용 테스트 DB의 랜턴 재고를 10개로 초기화한다.
- 실제 로드 해시·snapshot ID와 수정 원문의 HTTP 조회를 확인한다.
- spy로 실제 provider 호출에 변경된 본문과 결제 방식이 전달됐는지 확인한다. provider 응답을 주입하거나 대체하지 않는다.
- 관찰된 결과는 모두 SHIPPED/PAID/SENT이며 재고는 9개다. card도 payment.charge가 요청되는 것을 확인한다.

## 현재 지원하지 못하는 이유

- FixedDecisionProvider는 본문을 해석하지 않고 skill→도구 매핑과 입력으로 요청을 만든다.
- payment의 허용 도구는 payment.charge 하나다. 모델 응답 프로토콜에 정책상 보류 결정을 표현하는 경로가 없다.
- payment skill의 선언된 출력은 payment.id/payment.status이며 결제하지 않는 정책 분기의 계약은 별도 정의되지 않았다.
- PaymentMockAdapter는 정상 고객의 card와 prepaid를 모두 승인한다. c_declined의 card 거절은 기존 업무 규칙이며 새 본문 정책을 구현한 것이 아니다.

실제 모델 모드는 본문을 전달받지만, 이를 호출하는 것만으로 정책상 보류 경로가 만들어지지는 않는다. 이번 시험에서는 유료 모델 호출을 수행하지 않았다.

## 정책 검증을 가능하게 하기 위한 후속 조건

1. 결제 실행 또는 정책상 보류를 표현하는 명시적 실행 결과·도구 계약을 설계한다.
2. 보류 시 재고 예약 해제·최종 상태·후속 실행 중단을 구현하고 실패 오류와 정상 업무 보류를 구분한다.
3. 결제하지 않는 분기의 출력 계약을 정의한다.
4. 실제 정책 실행기를 연결하고 card/prepaid 쌍으로 실제 도구 효과를 채점한다. 기대값은 모델 입력에 넣지 않는다.
5. fixed 모드의 정책 검증 결과는 UNSUPPORTED로 구분하고, 전체 주문 성공만으로 의도까지 통과시키지 않는다. 현재 앱에 이 상태를 추가한 것은 아니다.

## 재실행

저장소 루트에서:

```bash
docker compose -f order-agent/compose.yml --profile test up -d --wait db-test
cd order-agent/backend
ORDER_DEMO_INTEGRATION_TESTS=true ./gradlew test --tests '*AgentEvidenceIntegrationTest.bodyOnlyPrepaidPolicyIsDeliveredButNotEnforcedByFixedMode'
```

전체 증거 통합 테스트:

```bash
ORDER_DEMO_INTEGRATION_TESTS=true ./gradlew test --tests '*AgentEvidenceIntegrationTest'
```

운영 코드와 운영 데이터는 변경하지 않는다. SkillOps 자동 진단·Runner 의도 채점과 수정 후보 생성도 이번 범위에 포함하지 않는다.

## 실행 결과

기준·수정본에서 card/prepaid 총 4회 Harness 실행을 확인했다. 새 정책은 fixed 모드에서 반영되지 않아 정책 인수 기준은 미충족이다. 한계 재현 테스트 2개를 포함한 AgentEvidenceIntegrationTest 15개는 모두 통과했다(실패·오류·건너뜀 0).
