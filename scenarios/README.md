# scenarios/

| 폴더 | 내용 | 스키마 |
|---|---|---|
| `golden/` | g-01. 운영 폴더 `skills/` 기준의 정상 주문이다. service 의 기본 실행기(`contract-only`)가 쓴다 | `docs/contracts/scenario.schema.json` |
| `order-agent/golden/` | g-01~g-06. 주문 Agent 의 상품으로 쓴 기준 주문이며 `harness` 실행기와 자동 검증이 쓴다. 아래 표 참고 | `docs/contracts/scenario.schema.json` |
| `failures/` | F1. 담당자의 변경(diff), 의도, 깨지는 곳, 기대 수정안 | 마크다운 |

service 는 `SKILLOPS_SCENARIOS_DIR`(기본 `../scenarios`) 아래 `golden/` 의 `.json` 을 읽는다. 주문 Agent 와 연동할 때는 이 값을 `../scenarios/order-agent` 로 둔다.

contract-only 실행기(`golden/`)에는 g-01 만 있다. 그 모드로 SkillOps Agent 를 돌릴 때는 `SKILLOPS_GOLDEN_IDS=g-01` 로 둔다.
Agent 의 기본 기준 목록은 g-01~g-06 이라 없는 주문을 찾다 멈춘다.

재현 주문(`f-<execution_id>`)은 파일로 두지 않는다. SkillOps Agent 가 `run_scenarios` 의 `scenario_ids` 나 `replay_run_ids` 로 실패한 실행 번호를 넘기면, service 가 주문 Agent 에서 원래 주문을 꺼내 다시 돌린다.

## 주문 Agent 기준 주문 (`order-agent/golden/`)

기대값은 실제 실행이 아니라 주문 Agent 코드로 유도했다. 게시본 Skill(`order-agent/skills`)과 규칙 모드(fixed)를 전제로 한다.
상품과 재고는 `order-agent/backend/src/main/resources/data.sql` 이다(랜턴 30000원 재고 10, 의자 45000원 재고 5, 머그컵 12000원 재고 0).
모의 실행은 끝나면 예약 재고를 모두 되돌리므로 기준 주문을 몇 번 돌려도 재고는 그대로다.

| id | 주문 | 기대 | 행동 변경 QA(B 묶음)에서 쓰는 곳 |
|---|---|---|---|
| g-01 | 랜턴 1 · card · KR · c_1001 (30000원) | SHIPPED, 다섯 단계 모두, 배송 1건 | B-01 의도 범위 안(카드) |
| g-02 | 머그컵 1 · card · KR · c_1001 | REJECTED, 재고 확인에서 업무 종료 | B-01 범위 안(결제 전에 이미 거절) |
| g-03 | 랜턴 1 · card · KR · c_declined | HOLD, 결제 거절로 업무 종료 | B-04·B-05 범위 안 |
| g-04 | 랜턴 1 · **prepaid** · KR · c_1001 | SHIPPED, 다섯 단계 모두, 배송 1건 | B-01 범위 밖(선입금) |
| g-05 | 랜턴 1 · card · **US** · c_1001 | SHIPPED, 다섯 단계 모두, 배송 1건 | B-02 범위 안(해외 배송) |
| g-06 | 랜턴 **4** · card · KR · c_1001 (**120000원**) | SHIPPED, 다섯 단계 모두, 배송 1건 | B-03 범위 안(10만 원 이상) |

g-04~g-06 을 유도한 근거는 다음과 같다. 위치는 모두 `order-agent/backend/src/main/java/com/capstone4/orderagent/` 아래다.

| 주장 | 근거 위치 |
|---|---|
| 주문 요청은 결제 방식 card·prepaid, 배송 국가 KR·US·JP, 수량 1~99 를 받는다 | `dto/order/CreateOrderRequest.java` |
| 랜턴 4개는 재고 10개 안이라 전량 예약된다 | `adapter/InventoryMockAdapter.java` 의 reserve, `service/InventoryReservations.java` 의 reserveAll |
| 결제는 재고가 예약됐고 방식이 card·prepaid 면 승인된다. 거절은 c_declined 고객의 card 결제뿐이라 g-04(prepaid)·g-05·g-06(c_1001)은 PAID 다 | `adapter/PaymentMockAdapter.java` 의 pay |
| 결제 금액은 서버가 단가 × 수량으로 계산한 합계라 g-06 은 120000 이다 | `entity/Order.java` 의 addItem, `harness/ToolCatalog.java` 의 payment 바인딩 |
| 배송은 결제·예약된 주문이고 국가가 KR·US·JP 면 예약된다. US 는 해외 7일로 계산할 뿐 막지 않는다 | `adapter/ShippingMockAdapter.java` 의 reserve |
| 알림까지 끝나면 SHIPPED 로 확정된다 | `service/OrderToolService.java` 의 complete, `harness/OrderHarness.java` 의 completeRun |
| 규칙 모드는 Skill 본문을 읽지 않으므로 세 주문 모두 g-01 과 같은 길을 간다 | `harness/FixedDecisionProvider.java` |

확인 조건(assertions)은 g-01 의 두 줄(`payment.status == 'PAID'`, `notify.sent == true`)을 그대로 두고, 그 주문을 다른 주문과 가르는 값을
하나씩 더했다. g-04 는 `order.payment_method == 'prepaid'`, g-06 은 `order.total == 120000` 이다. g-05 의 국가는 `order.shipping` 안의 값이라
조건 문법 v1(`==`, `!=`, `in`, 하위 경로 없음)으로 쓸 수 없어 더하지 않았다. 세 주문 모두 조건 문법 v1 만 쓰므로 service 의 `Check` 가 바뀌기 전에도 읽힌다.

g-01~g-06 은 정상 흐름이 같은 길을 가므로, Skill 계약을 깨는 수정은 여섯 주문에 같은 반응을 낸다. 실행 전 정적 검사에서 멈추는 수정은
여섯 주문 모두 같은 오류이고, 정상 배송 길에서만 걸리는 수정(알림 단계 삭제, 알림 outputs 변경 등)은 g-01·g-04·g-05·g-06 이 같은 반응이다.
QA 케이스의 `order_agent` 표(`qa/cases/*.yaml`)가 이 사실로 g-04~g-06 칸을 채웠다.
