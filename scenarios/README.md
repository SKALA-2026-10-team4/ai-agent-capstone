# scenarios/

| 폴더 | 내용 | 스키마 |
|---|---|---|
| `golden/` | g-01 ~ g-06. 정상이어야 하는 주문. 수정안의 무회귀(기준 2) 판정에 전부 쓰인다 | `docs/contracts/scenario.schema.json` |
| `failures/` | F1 ~ F3. 담당자의 변경(diff) + 의도 + 깨지는 곳 + 기대 수정안 | 마크다운 |

골든 목록: g-01 단일 상품 정상 · g-02 복수 상품 · g-03 선입금 · g-04 재고 부족 → REJECTED 안내 · g-05 결제 실패 → 재시도 안내 · g-06 해외 배송.

재현 시나리오(f-NN)는 SkillOps Agent 가 실패 트레이스에서 생성하며, 파일로 두지 않고 `run_scenarios` 인자로 넘긴다.
