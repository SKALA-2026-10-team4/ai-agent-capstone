# scenarios/

| 폴더 | 내용 | 스키마 |
|---|---|---|
| `golden/` | g-01. 운영 폴더 `skills/` 기준의 정상 주문이다. service 의 기본 실행기(`contract-only`)가 쓴다 | `docs/contracts/scenario.schema.json` |
| `order-agent/golden/` | g-01 정상 배송, g-02 재고 부족 거절(REJECTED), g-03 결제 거절 보류(HOLD). 주문 Agent 의 상품으로 쓴 기준 주문이며 `harness` 실행기와 자동 검증이 쓴다 | `docs/contracts/scenario.schema.json` |
| `failures/` | F1. 담당자의 변경(diff), 의도, 깨지는 곳, 기대 수정안 | 마크다운 |

service 는 `SKILLOPS_SCENARIOS_DIR`(기본 `../scenarios`) 아래 `golden/` 의 `.json` 을 읽는다. 주문 Agent 와 연동할 때는 이 값을 `../scenarios/order-agent` 로 둔다.

재현 주문(`f-<execution_id>`)은 파일로 두지 않는다. SkillOps Agent 가 `run_scenarios` 의 `scenario_ids` 나 `replay_run_ids` 로 실패한 실행 번호를 넘기면, service 가 주문 Agent 에서 원래 주문을 꺼내 다시 돌린다.
