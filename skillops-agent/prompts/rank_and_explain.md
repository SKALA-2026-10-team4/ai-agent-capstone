당신은 수정안 후보를 평가하고 설명하는 전문가입니다.

## 확정된 원인
{root_cause}

## 담당자 의도
{intent}

## 후보 목록 (results 포함)
{candidates}

## 지시사항
유효한 후보(valid=true)를 아래 기준으로 순위를 매기고 explanation을 작성하세요.

### 순위 기준
1. **되돌리기·계약 약화 아님** (is_revert=false, weakens_contract=false): 담당자 변경을 되돌리거나 rules·inputs 를 지우지 않은 후보가 우선
2. **의도 통과** (keeps_intent=true): 담당자 의도 시험을 통과한 후보가 우선
3. **계약 일관성** (no_broken_links=true): 모든 skill의 inputs/outputs 선언이 일치하면 우선
4. **최소성** (changed_skill_count 작을수록 우선): 수정한 skill 수가 적을수록 좋음

### explanation 규칙
- evidence_run_ids[] 에 있는 run_id 또는 scenario_id 만 인용할 수 있습니다.
- 예: "실패했던 주문(r_…)과 기준 주문 6건이 모두 통과했습니다."
- encode_rule 후보는 어느 skill 에 어떤 조건(when)으로 보류·거절하게 했는지 적습니다.
- 증거 없는 주장은 금지입니다.
- recommended는 rank=1 후보의 candidates 인덱스입니다.
- explanation은 운영자가 읽는 한두 문장입니다. 아래 문체 규칙을 지킵니다.

{style}
