# 행동 변경 수정본에 의미 있는 수정안을 내기 위한 근본 개선 계획

2026-10-26 · 브랜치 `feat/skill-behavior-rules` · 상태: **구현 완료 · 단위/통합 테스트 녹색 · 서버 E2E 미검증** (계획 v2 + 계약 보정 v3)

> v1 → v2: 독립 검토자가 코드와 대조해 20건의 필수 수정을 냈다(7절 진행 기록). 계약 예시가 g-02 와 충돌, service 가 총액을 모름, 새 오류 코드가 Agent 를 멈춤, 되돌리기 일괄 금지가 기존 QA 정답 5건을 깨뜨림, 새 진단 상태가 그래프 라우팅과 맞지 않음 등. 모두 아래에 반영했다.

## 0. 목표와 완료 판정

**목표.** 업무 담당자가 SKILL.md 명세 안에서 행동을 추가·수정했을 때(예: "선입금 주문만 결제한다", "해외 배송은 받지 않는다", "10만 원 이상 주문은 보류한다"), SkillOps Agent 가 **담당자 의도가 실제로 실행되게 만드는 수정안**을 제안한다. 되돌리기는 답이 아니다.

**완료 판정** (코드와 테스트로 확인):

1. 본문에 정책 문장만 추가한 수정본 + 담당자 의도 → SkillOps 가 "의도가 실행되지 않음(`INTENT_NOT_REALIZED`)"을 찾고, `rules` 를 추가한 `encode_rule` 후보를 만들어 합격시킨다. fixed 모드에서 결정적으로, LLM 없이도 결정적 후보가 하나 나온다.
2. 그 후보는 의도 범위 밖 기준 주문은 그대로, 범위 안 기준 주문은 의도대로 처리한다.
3. 의도가 있는 요청에서는 되돌리기 후보와, 규칙·의도가 쓰는 입력을 지운 후보를 **코드가** 불합격시킨다.
4. service 가 Agent 의 참/거짓이 아니라 장부의 실행 결과로 합격을 다시 계산한다.
5. QA 에 행동 변경 케이스(B 묶음)가 있고, 평가 때 RAG 정답 누수가 차단된다.

## 1. 층별 근본 문제

| 층 | 근본 문제 | 증거 |
|---|---|---|
| 1 무대 | 주문 Agent 가 SKILL.md 의 행동을 실행할 수단이 없다. fixed 모드는 본문을 읽지 않고, openai 모드도 skill 마다 도구 1개와 서버가 정한 인자만 허용하며 "보류/거절" 결정이 없다 | `FixedDecisionProvider`, `ToolCatalog.BINDINGS`, `OrderToolService.requireServerArguments:119`, `OrderHarness.runStep`, 문서 S5 |
| 2 탐지 | 진단은 기준 주문이 **오류로** 깨질 때만 시작한다. 오류 없이 결과가 다르면 멈추고, 의도대로 결과가 바뀌어야 하는 경우도 "이전과 같아야 한다"와 충돌한다 | `nodes._preflight`, QA L2, C-03 정답 이력 |
| 3 의도 | 담당자가 의도를 적을 곳이 없다. 조건 값 이름을 되돌린 실행에서만 모아 새 이름이 버려지고, 빈 조건은 통과다. 조건부 의도는 문법으로 못 쓴다 | `DiagnoseRequest`, `nodes._known_values:836`, `contracts.all_passed:410`, `Check.java` |
| 4 후보 | 실행 가능한 행동 수단이 없으니 통과하는 안이 되돌리기뿐이다. 되돌리기·입력 삭제 금지는 프롬프트 문장일 뿐이다 | `prompts/generate_candidates.md`, `_patches_from_spec`, QA X-01 |
| 5 판정 | service 가 Agent 가 보낸 results 를 센다. 통과 여부·시나리오·반복·patch 일치를 장부로 확인하지 않는다. `/mcp` 무인증 | `ProposalValidator.checkEvidence:94`, `ProposalRules.passes:33`, `SecurityConfig:85` |
| 6 평가 | 행동 변경 케이스가 없고, RAG 사례집에 평가 정답이 섞이며 제외가 적용되지 않는다. 채점 일부가 느슨하고 G-01 은 버그로 통과 불가 | `qa/cases`, `rag/cases.json`, `run_qa.py`, `qa_logic.py:387-428` |
| 0 환경 | Windows `core.autocrlf` 가 skill 파일을 CRLF 로 바꿔 bundle 해시가 깨진다(주문 Agent `INCOMPLETE_BUNDLE`, service 테스트 6건 실패) | 7절 기준선 기록 → `.gitattributes` 로 해결 |

**핵심 판단.** 1층을 고치지 않으면 합격 가능한 "의미 있는 안"이 존재하지 않는다. 그래서 단계 skill frontmatter 에 **선언형 실행 규칙 `rules`** 를 넣고, 담당자의 자연어 정책을 `rules` 로 옮기는 것을 SkillOps 가 제안할 근본 수정으로 삼는다.

## 2. 공용 계약 (모든 작업자가 따른다)

계약 파일: `docs/contracts/examples/condition-cases.json`(조건 문법), `docs/contracts/examples/skill-rules-cases.json`(rules 정적 검사), `docs/contracts/apply-patch-result.schema.json` v1.4, `docs/contracts/proposal.schema.json`(추가 칸). 기존 fixture(`skill-metadata-cases.json`, `flow-structure-cases.json`)는 **고치지 않는다**.

### 2.1 조건 문법 v2

`값이름 연산자 값` 한 줄. v1(`==`, `!=`, `in`)에 숫자 비교를 더한다. 연산자 앞뒤에 공백이 있어야 한다.

| 항목 | 규칙 |
|---|---|
| 연산자 | `==`, `!=`, `in`, `>`, `>=`, `<`, `<=` |
| 값 | `'글자'`, `true`, `false`, `null`, 숫자(`-?\d+(\.\d+)?`), `in` 뒤에만 `[..]` 목록(빈 목록 허용) |
| 숫자 비교 | 오른쪽은 **숫자만** 읽는다(글자·null·불리언은 unreadable). 실제 값이 숫자가 아니면 거짓 |
| 불리언 | `true`/`false` 는 숫자 1/0 과 같지 않다 |
| 값 찾기 | 문맥에 그 이름이 그대로 있으면 그 값. 없으면 점으로 나눈 **가장 긴** 앞부분이 문맥에 있고 그 값이 Map 이면 나머지 경로로 내려간다(더 짧은 앞부분으로 다시 시도하지 않음). 끝내 없으면 null |
| 구현 | service `Check` 확장(W2), 주문 Agent `RuleCondition` 신규(W1, `order-agent` 안에 둔다. `shared/skill-parser` 는 이번에 고치지 않는다), Python 은 **읽기(parse)만** 흉내(W3). 셋 다 `condition-cases.json` 으로 테스트 |

### 2.2 실행 규칙 `rules` (단계 skill frontmatter, 선택)

```yaml
rules:
  - when: "order.payment_method != 'prepaid'"
    then: hold            # hold | reject
    reason: PREPAID_ONLY  # [A-Z][A-Z0-9_]{0,39}
```

| 항목 | 규칙 |
|---|---|
| 위치 | 단계 skill 만. flow skill(`steps` 가 있는 skill)에 있으면 형식 오류 |
| 형식 | 누락·null·빈 목록 = 규칙 없음. 목록, 0~10개, 항목은 Map 이고 키는 정확히 `when`·`then`·`reason`(모두 필수, 다른 키 금지). `when` 은 조건 문법 v2 |
| 조건 키 | `when` 의 값 이름 k 는 그 skill 의 선언 input i 와 **같거나 `i + '.'` 로 시작**해야 한다. 예약 키(`order.final_status`, `order.shipments`)도 선언 input 이 아니면 거부 |
| 오류 | 형식 오류 → 주문 Agent `INVALID_SKILL_FORMAT`(로드 때), service `metadata_errors{field: rules, code: INVALID_SKILL_FORMAT}`. 키 미선언 → 주문 Agent `CONTRACT_VIOLATION`(details.reason=`RULE_KEY_NOT_INPUT`, `validateFlow` 때, 업무 도구 실행 전), service `flow_errors{code: CONTRACT_VIOLATION, reason: RULE_KEY_NOT_INPUT}` |
| 평가 | 단계의 실행 중 입력 검증 직후, 결정 요청·도구 호출 **전**에 harness 가 결정적으로(fixed·openai 모두) 평가한다. 평가 문맥 = 그 단계의 선언 입력 Map. 위에서부터 첫 참 규칙 하나만 적용, 매치되면 도구를 부르지 않는다 |
| 종료 | `Order.stopByRule(status, reason)` 신규: 주문 `HOLD`(hold) 또는 `REJECTED`(reject), `resultCode = reason`, **결제 상태는 건드리지 않는다**. 결제 전이면 예약 재고를 해제(`compensateUnpaid` 와 같은 정책). 결제 뒤 단계의 규칙이 매치되면 결제는 그대로 남는다(문서화). 그 단계와 뒤 업무 단계는 SKIPPED, 실행은 COMPLETED |
| trace 순서 | `RULE_MATCHED{skill_id, rule_index, when, then, reason}` → (재고 해제 시) `inventory.release` 의 TOOL_REQUEST/TOOL_RESULT → `SKILL_FINISHED{status: ok, business_stop: true, final_status, rule_reason, output: {}}` → `BUSINESS_STOP` → `EXECUTION_FINISHED`. service 변환기는 모르는 이벤트를 무시하므로 바꿀 필요 없다(g-03 과 같은 업무 종료 모양) |

### 2.3 담당자 의도 `intent`

저장 요청 → 저장 이벤트 → `/diagnose` 까지 같은 모양으로 흐른다.

```json
"intent": {
  "text": "선입금 주문만 결제하고 카드 주문은 결제하지 않는다",
  "expectations": [
    {"scope": "order.payment_method == 'card'",    "checks": ["order.final_status in ['HOLD', 'REJECTED']", "payment.status == null"]},
    {"scope": "order.payment_method == 'prepaid'", "checks": ["order.final_status == 'SHIPPED'"]}
  ]
}
```

| 칸 | 규칙 |
|---|---|
| `text` | 1~500자, intent 를 보낼 때 필수 |
| `expectations` | 0~10개. 비면 Agent 가 text·diff 로 만든다(출처 `inferred`). 담당자가 준 것은 출처 `owner` |
| `scope` | 조건 문법 v2 한 줄 또는 null. **시험 주문의 처음 값**으로 평가: `order.payment_method`, `order.customer`, `order.shipping.country`, `order.shipping.express`, `order.total`. `order.total` 은 service 가 `GET /api/products` 의 단가 × 수량 합으로 계산(`run_scenarios` 한 번에 한 번 조회) |
| `checks` | 조건 문법 v2, 1~10개. 실행 문맥(단계 입력·출력 합) + 예약 키 `order.final_status`, `order.shipments` |
| 전달 | `POST /api/snapshots/edits` 요청 선택 칸 `intent` → snapshot metadata 와 outbox payload 의 `intent`(없으면 칸 생략) → service 중계는 본문 그대로 → `DiagnoseRequest.intent`. 주문 Agent 는 모르는 JSON 칸을 거부하므로(`fail-on-unknown-properties`) W1 이 칸을 추가해야 W4·W5 가 보낼 수 있다(통합 때 함께 들어간다) |

### 2.4 시험 채점에서의 의도 (`run_scenarios`)

선택 인자 `intent_expectations` 를 더한다.

| 시험 주문 | 채점 |
|---|---|
| scope 가 null 인 expectation | 그 checks 를 **모든** 시나리오 채점에 더한다(AND) |
| scope 에 맞지 않는 기준 주문 | 기존 `expect` 그대로 |
| scope 에 맞는 기준 주문 | 기존 `expect` 를 **대신해** 맞는 expectation 들의 checks + 불변식(실행이 FAILED 아님, final_status 가 null 아님)으로 채점. 결과에 `intent_scoped: true` |
| 재현 주문 `f-<id>` | 불변식 + scope 에 맞는 checks + `extra_checks` |
| keeps_old_orders | 위 규칙으로 채점한 **모든** 기준 주문. total 은 전체 기준 세트 크기 |

### 2.5 의도 미실현의 탐지와 그래프

- 진단 상태 이름은 **늘리지 않는다**(그래프는 RUNNING 일 때만 진단을 잇고 끝 상태는 EVALUATED 다). 대신 `diagnosis.finding = "INTENT_NOT_REALIZED"`, 제안서 `root_cause.kind = "incomplete_edit"`.
- 사전 검증: 기준본 세트는 **의도 없이** 돌린다(의도를 붙이면 BASELINE_FAILED). 수정본 세트는 의도를 붙여 돌린다.
- 수정본이 오류 없이 돌고 의도 범위 시나리오만 떨어지면 → finding 을 남기고 `fetch_evidence → extract_intent` 로 바로 간다(새 라우트 `intent_gap`, 원인 실험 생략). root_cause = 담당자가 바꾼 skill(첫 변경 번호), `proof_run_ids` = 수정본 사전 검증 실행, `repro_scenario_ids` = 떨어진 의도 범위 기준 주문.
- 의도가 없고 오류 없이 결과만 다른 기준 주문이 있으면 → 멈추지 않고 그 주문을 `repro_scenario_ids` 로 원인 실험을 잇는다. 실행 오류가 없으면 오류 종류 대조는 건너뛴다. `f-<id>` 는 FAILED 실행에만 쓴다. 원인 실험으로도 확정 못 하면 지금처럼 `VALIDATION_FAILED`.
- 상태에 `repro_scenario_ids` 를 두고 ablate·verify 가 이것을 쓴다.

### 2.6 후보 종류와 코드 판정

| 항목 | 규칙 |
|---|---|
| `encode_rule` | 본문·의도의 정책을 `rules` 로 옮기는 후보. 필요하면 조건 키를 inputs 에 더한다 |
| 결정적 후보 | 담당자 expectations 가 있으면 LLM 과 별개로 코드가 `encode_rule` 후보 하나를 만든다: 바뀐 skill(없으면 scope 키를 input 으로 가질 수 있는 가장 이른 단계)에 `when: <scope>`, `then`: checks 에 HOLD 만 있으면 hold, REJECTED 만 있으면 reject, 둘 다면 hold, `reason`: 의도 text 에서 만든 코드(기본 `OWNER_POLICY`). scope 키가 input 이 아니면 input 에 더한다 |
| `is_revert` | 후보 적용 결과에서 담당자가 바꾼 파일 중 하나라도 기준본과 바이트가 같으면 true |
| `weakens_contract` | 후보가 rules 를 지우거나, rules 의 `when`·의도 scope 가 쓰는 input 을 지우면 true |
| 적용 범위 | 위 둘과 "빈 의도 = keeps_intent 거짓"은 **요청에 intent 가 있을 때만** 불합격 사유다. intent 없는 기존 흐름(QA 의 `revert_allowed`, 입력 삭제가 정답인 C-01·C-02·G-01)은 지금대로 |

### 2.7 service 재판정 (`save_proposal`)

| 칸 | 다시 계산하는 법 |
|---|---|
| 장부 | 실행마다 `scenario_id`, `passed`, `intent_scoped`, `checks_hash` 를 더 기록한다. **nullable 래퍼 타입**(`ddl-auto: update` 가 기존 표에 NOT NULL 열을 못 더함) |
| workspace | `WorkspaceRecord` 에 `applied_patches` 와 마지막 apply 결과(broken_links·metadata_errors·flow_errors 수)를 저장. `run_scenarios` 를 한 번 돌린 workspace 에 `apply_patch` 하면 `WORKSPACE_FROZEN`. (Agent 는 후보마다 새 workspace 를 쓰므로 영향 없음 — W3 가 확인) |
| patch 일치 | 제안서 후보 patches == workspace `applied_patches`, workspace `sourceSnapshotId == proposal.snapshot_id`, workspace 가 고정됨. bundle 해시 비교는 하지 않는다 |
| `fixes_failure` | `root_cause.repro_scenario_ids`(없으면 장부에서 `f-` 로 시작하는 시나리오)가 그 workspace 에서 `skillops.min-repeat`(기본 2)회 이상 돌았고 모두 통과 |
| `keeps_old_orders` | `skillops.golden-ids`(기본: 시나리오 폴더의 모든 기준 주문) 전부가 min-repeat 회 이상 돌았고 모두 통과. total = 그 세트 크기 |
| `keeps_intent` | 요청에 intent 가 있으면: expectations 가 1개 이상, 그 checks 로 채점한 실행이 모두 통과, `is_revert`·`weakens_contract` 거짓. intent 가 없으면 Agent 값을 쓰되 아래 오버라이드 규칙 적용 |
| `no_broken_links`·`metadata_valid`·`flow_valid` | workspace 의 마지막 apply 결과로 다시 계산 |
| 불일치 | Agent 값과 다르면 **서버 값으로 덮고** `server_overrides` 에 기록한다(지금은 거부 — `ProposalCharacterizationTest` 를 이 규칙으로 바꾼다). 합격 수가 바뀌면 상태·추천도 서버 기준 |
| 재현 판정 | `final_status == null` 은 불합격 |
| `/mcp` | `SKILLOPS_SERVICE_TOKEN` 이 있으면 `X-Service-Token` 필수. 비면 열고 기동 경고 |

### 2.8 기준 주문 (고정 목록 g-01..g-06)

| id | 주문 | 기대(지금 주문 Agent 로 유도) | 쓰는 B 케이스 |
|---|---|---|---|
| g-01 | 랜턴 1 card KR (30000) | SHIPPED | B-01 범위 안 |
| g-02 | 머그 1 card KR, 재고 0 | REJECTED (inventory) | B-01 범위 안(이미 결제 전 거절) |
| g-03 | 랜턴 1 card KR, c_declined | HOLD (결제 거절) | B-04 범위 안 |
| g-04 **신규** | 랜턴 1 prepaid KR | SHIPPED | B-01 범위 밖 |
| g-05 **신규** | 랜턴 1 card US | SHIPPED | B-02 범위 안 |
| g-06 **신규** | 랜턴 4 card KR (120000) | SHIPPED | B-03 범위 안 |

이 목록을 쓰는 곳: `scenarios/order-agent/golden/`(W4), `skillops-agent/skillops_agent/config.py:64` 와 `stub_tools.py:39`, `tests/test_graph.py` 실행 횟수(W3), `qa/qa_logic.py:23` 과 모든 QA 케이스의 `order_agent` 반응 표(W4). contract-only 실행기(`scenarios/golden`)는 g-01 만 있으므로 그 모드에서는 `SKILLOPS_GOLDEN_IDS=g-01` 로 둔다고 문서화한다.

### 2.9 B 묶음 (행동 변경 QA 케이스)

| 케이스 | 담당자 수정 | 의도 expectations | 정답 안 |
|---|---|---|---|
| B-01 | payment 본문에 "선입금 주문만 결제" | card: `final_status in ['HOLD','REJECTED']`, `payment.status == null` / prepaid: `final_status == 'SHIPPED'` | payment 에 `when: order.payment_method != 'prepaid'`, hold |
| B-02 | shipping-reserve 본문에 "해외 배송은 받지 않음" | `order.shipping.country != 'KR'`: `final_status == 'REJECTED'`, `payment.status == null` | order-intake 에 `when: order.shipping.country != 'KR'`, reject (order-intake 는 `order.shipping` 입력이 있음. shipping-reserve 에 걸면 결제가 남아 불합격) |
| B-03 | payment 본문에 "10만 원 이상은 보류" | `order.total >= 100000`: `final_status == 'HOLD'`, `payment.status == null` | payment 에 `when: order.total >= 100000`, hold |
| B-04 | payment 본문에 "결제 거절 이력 고객(c_declined)은 결제 시도 전 보류" | `order.customer == 'c_declined'`: `final_status == 'HOLD'`, `payment.status == null` | payment inputs 에 `order.customer` 추가 + rules, 또는 order-intake(이미 `order.customer` 입력)에 rules. 둘 다 정답(R2) |
| B-05 | payment 에 rules 를 직접 추가했는데 키(`order.customer`)를 inputs 에 안 넣음 → 계약 오류 | B-04 와 같음 | inputs 에 `order.customer` 추가 |

### 2.10 계약 보정 v3 (구현 검토 2건 반영)

| 번호 | 보정 | 이유(검토 결과) |
|---|---|---|
| V3-1 | **의도의 진실 원천은 주문 Agent 의 snapshot metadata 다.** service 재판정은 `proposal.snapshot_id` 의 snapshot metadata 에서 `intent` 와 `baseline_snapshot_id` 를 읽는다. metadata 에 intent(text)가 있으면 의도 요청이다. metadata expectations 가 있으면 그것(owner), 없으면 제안서 `intent.expectations`(inferred)를 쓰되 비어 있으면 keeps_intent 거짓. `proposal.snapshot_id` 는 필수, patches 가 0개인 후보는 불합격 | 제안서 칸(trigger.intent, baseline)을 비우면 되돌리기가 합격(검토 1-①) |
| V3-2 | **저장 때 의도 조건을 문법으로 검사한다.** 주문 Agent `validIntent` 가 scope·checks 를 조건 문법 v2 로 읽고, scope 의 값 이름은 2.3 의 다섯 키(또는 그 하위 경로 아님 — 정확히 다섯 키)만 허용. 어기면 `INVALID_SKILL_EDITS`(400). Agent `/diagnose` 는 같은 문제를 422 로 거부하지 않고 그 항목을 `dropped_checks` 와 `intent_note` 로 남긴 채 진행 | 잘못된 조건 하나가 전달 큐를 영구히 막음(검토 1-②, 2-9) |
| V3-3 | **null scope 판정.** null scope 의 checks 는 모든 기준 주문에 더해지고(기존 expect 와 AND) `intent_scoped=false` 로 표시된다. 그래서 사전 검증은 수정본 기준 세트를 **의도 없이 한 번, 의도와 함께 한 번** 돌리고, 의도와 함께일 때만 떨어지는 주문을 의도 미실현으로 본다. 행동을 바꾸는 의도는 scope 를 써야 하며(null 은 기존 동작과 함께 늘 지켜야 하는 불변 조건용), 추론 프롬프트도 그렇게 안내한다 | null scope 의도가 원인 실험으로 잘못 가 ERROR(검토 2-1) |
| V3-4 | **추론한 의도의 최소 요건.** inferred expectations 는 (a) scoped expectation 마다 기준 주문 1개 이상과 맞고 (b) 모든 scope 밖에 기준 주문이 1개 이상 남으며 (c) 기준본이 그 expectations 를 이미 모두 통과하면 버린다(빈 의도 취급 + note). service 는 의도 요청에서 **scoped expectation 마다** 맞은 실행이 1개 이상 있어야 keeps_intent 참(장부에 맞은 expectation 번호를 nullable 열로 기록) | 늘 참이거나 범위가 넓은 의도로 빈 통과(검토 1-④, 2-3) |
| V3-5 | **재현 근거 확인.** service 는 `repro_scenario_ids` 의 각 id 가 `root_cause.proof_run_ids` 가운데 실패한 실행을 하나 이상 가질 때만 받아들인다(장부의 scenario_id·passed). 아니면 그 후보 fixes_failure 거짓 + `server_overrides` | Agent 가 고른 repro 를 그대로 믿음(검토 1-③) |
| V3-6 | **결정적 후보 위치.** 결정적 encode_rule 후보는 최대 2개: ① 담당자가 바꾼 skill ② flow 순서상 가장 이른 단계 중 scope 키를 선언 input 으로 가진(또는 처음 입력 키라 더할 수 있는) 단계. 같으면 1개. 같은 when 의 규칙이 이미 있으면 규칙은 그대로 두고 빠진 input 만 더한 후보. reason 은 따옴표 없는 YES·NO·ON·OFF·TRUE·FALSE 를 쓰지 않는다(SnakeYAML 이 불리언으로 읽음. Y·N 은 글자) | B-02·B-05 가 LLM 없이 실패(검토 1-⑧, 2-4·5) |
| V3-7 | **약화 판정 하나로.** `weakens_contract` = 후보가 rules 를 지우거나 그 `when` 을 바꾸거나, rules·scope 가 쓰는 키를 덮던 input 을 지움(다른 input 이 여전히 덮어도). service·Agent·QA 가 같은 정의 | 세 곳의 정의가 달라 서버 덮어쓰기·QA 오채점(검토 1-⑥, 2-7) |
| V3-8 | **의도 없는 요청은 예전과 같게.** 되돌리기 순위 강등은 의도 요청에만. 결과 불일치 원인 실험이 대조 실행 흔들림·가설 없음으로 끝나면 ERROR 가 아니라 `VALIDATION_FAILED`. 재시도에서 LLM 실패는 ERROR 가 아니라 `no_valid_fix` 경로 | 의도 없는 흐름의 문서 밖 변화(검토 2-5·8) |

### 2.11 개발 요청서 (도구가 필요한 수정, 사용자 실험 1 반영)

담당자 수정이 **어떤 업무 도구도 만들지 않는 값**을 요구하면 SKILL.md 수정만으로는 고칠 수 없다. 이때 SkillOps 는 후보 생성(LLM 재시도)을 건너뛰고 **개발 요청서**와 **임시 대안**을 담은 `no_valid_fix` 제안서를 낸다.

| 항목 | 계약 |
|---|---|
| 도구 목록 원천 | 주문 Agent `GET /api/tools` → `{"initial_input_keys": [...], "tools": [{"tool", "skill", "description", "produces": [...]}]}`. `produces` 는 `ToolCatalog` 의 Binding 에 선언하고, 주문 Agent 테스트가 `OrderToolService` 의 실제 출력 키와 같음을 고정한다. Agent 는 사본을 두지 않고 이 API 를 읽는다(읽지 못하면 탐지를 건너뛰고 지금처럼 진행) |
| 덮음(covers) | 키 k 는 집합에 그대로 있거나, 집합의 p 에 대해 `k` 가 `p + '.'` 로 시작하면 덮인다(예: `order.shipping.country` ← `order.shipping`) |
| 빈틈 판정 | 담당자가 **새로 넣은** 것만 본다(기준본 대비). ① 바뀐 단계 skill 의 새 inputs·rules 키 k 가 `initial_input_keys ∪ 모든 도구 produces` 로 덮이지 않음 → `missing_value` ② 새 outputs 키 k 가 그 skill 의 도구 produces 에 없고 다른 어떤 도구도 만들지 않음 → `tool_output_missing` |
| 흐름 | 빈틈이 있으면 `diagnosis.finding = "NEEDS_TOOL"`. 사전 검증·원인 실험은 그대로(증거와 임시 대안 증명), 원인 확정 뒤 후보 생성 없이 `save_proposal` |
| 제안서 칸 | `development_request`: `{kind: "needs_tool", title, gaps: [{key, needed_by, field, kind}], options: [{type: "extend_tool", tool, skill, add_produces} 또는 {type: "new_tool", suggested_name, place_before, available_inputs, produces}], change_points: [파일 경로], interim: {summary, patch, proof_run_ids}, evidence_run_ids}`. extend_tool 은 같은 이름 앞부분(예: `shipping.`)을 이미 내는 도구, new_tool 의 이름은 마지막 낱말 + `.lookup`(예시 이름임을 문서에 밝힘). 임시 대안의 증명은 원인 실험 실행(그 변경을 되돌리면 통과) |
| service | `development_request` 를 받아 저장하고 형식을 검사한다(gaps 1개 이상, proof_run_ids 는 장부에 있는 실행). 제안서 본문에 "개발 요청"·"임시 대안" 절을 쓰고, 요약을 "SKILL.md 수정만으로는 해결할 수 없어 개발 요청서를 만들었습니다"로. 개발 요청이 없는 `no_valid_fix` 의 "담당자가 Skill 을 직접 고쳐야 합니다" 도 오해를 부르지 않게 고친다 |
| 화면 | 제안서에 "개발 요청서" 카드(마크다운으로 복사), 진단 finding `NEEDS_TOOL` = "도구 개발 필요" |
| 범위 밖 | 본문에만 적은 행동 변경(예: "해외는 빠른 배송으로")은 정적으로 알 수 없어 이번 탐지 대상이 아니다 |

## 3. 작업자 배정과 할 일

파일 소유가 겹치지 않는다. 남의 범위는 고치지 않고 2절 계약만 믿는다. 계약과 다르게 해야 하면 보고서에 "계약 변경 요청"으로 적는다.

| 작업자 | 소유 범위 | 층 |
|---|---|---|
| W1 주문 Agent | `order-agent/**` (backend, frontend, docs, skills, scripts) | 1, 3(저장 API) |
| W2 service | `service/**`, `docs/contracts/mcp-tools-input-output.md`, `docs/contracts/mcp-tools-and-rest.md` | 2(채점·D2), 3(문법), 5 |
| W3 SkillOps Agent | `skillops-agent/**` 중 `scripts/build_rag_data.py`·`rag/**` 를 뺀 전부 | 2(사전 검증·재현), 3, 4 |
| W4 QA·시나리오 | `qa/**`, `scenarios/**`, `skillops-agent/scripts/build_rag_data.py`, `skillops-agent/rag/**`, 루트 `README.md`, `.env.example` | 6, 기준 주문 |
| W5 운영 화면 | `frontend/**` | 3(의도 입력), 표시 |
| 조정 | `docs/plans/**`, `docs/contracts/*.json`, `docs/contracts/examples/**`, `.gitattributes`, 통합 검증 | 전체 |
| 고정(아무도 안 고침) | `shared/skill-parser/**`, `skills/**`(루트 자리표시 skill) | — |

### W1 · 주문 Agent
1. `RuleCondition`(2.1) + `condition-cases.json` 테스트.
2. `SkillLoader`: rules 읽기·형식 검증, flow 의 rules 금지(로드), 키 미선언 검사(`validateFlow`). `skill-rules-cases.json` 테스트.
3. `OrderHarness.runStep` 규칙 평가와 종료(2.2), `Order.stopByRule`, `OrderToolService`/`OrderLifecycleService` 규칙 종료 경로, 재고 해제.
4. DB 없이 도는 `OrderHarness` 규칙 경로 단위 테스트(hold·reject·미매치·결제 뒤 규칙). `TraceContractGolden` 에 규칙 경로 기대 줄(DB 테스트, 이 PC 에선 건너뜀).
5. `EditRequest.intent`(2.3 형식 검사) → snapshot metadata·outbox payload.
6. `order-agent/frontend` 의 `evidenceFormat.js` 등에 `RULE_MATCHED` 라벨.
7. `order-agent/docs/agent-evidence.md`, `skill-flow.md`, `skill-edit-save-api.md` 갱신.

### W2 · service
1. `Check` 를 문법 v2 로(숫자 비교, 하위 경로, 예약 키). `condition-cases.json` 테스트.
2. scope 문맥(처음 주문 값 + 상품 단가로 `order.total`), `ScenarioGrader` 예약 키 `order.final_status`, `order.shipments`.
3. `run_scenarios` 의 `intent_expectations`(2.4), 결과 `intent_scoped`.
4. D2: `OrderAgentTraceConverter.attachError` 가 skill 없는 오류에도 오류 이름을 실패 이유에 남김.
5. `apply_patch` 의 rules 정적 검사(2.2 오류 줄), `skill-rules-cases.json` 테스트. 결과 스키마 v1.4.
6. 장부·workspace 확장, 재판정·오버라이드·고정·patch 일치(2.7), `skillops.golden-ids`, `skillops.min-repeat`.
7. 재현 판정에서 `final_status == null` 불합격. `/mcp` 토큰.
8. 제안서 본문(`ProposalDocumentWriter`)에 의도 expectations·`server_overrides`·`incomplete_edit` 반영(문체 `ToneCheck` 통과).
9. MCP 도구 설명의 "g-01~g-06" 이 실제와 맞는지, 문서 갱신.

### W3 · SkillOps Agent
1. `DiagnoseRequest.intent`(2.3), `rag_exclude: list[str]`. 상태에 보관.
2. `_preflight`/라우팅/재현(2.5): 기준본은 의도 없이, 수정본은 `intent_expectations` 로. `intent_gap` 라우트, `repro_scenario_ids`, 결과 불일치 시 원인 실험 계속.
3. `extract_intent`: 담당자 expectations 그대로(owner), 없으면 생성(inferred). 값 이름 후보 = 수정본 snapshot 의 모든 선언 inputs/outputs + 예약 키(D1). 제안서 `intent.checks` 칸은 계속 채운다(scope 없는 조건).
4. `contracts.py`: 문법 v2 읽기 흉내(`condition-cases.json` 의 readable/unreadable), apply_patch v1.4 의 `rules` 필드·`RULE_KEY_NOT_INPUT` 수용, `skill_quality/rule_checker.py` 가 `rules` 를 모르는 키로 보지 않게.
5. 후보: `encode_rule` 종류·프롬프트(rules 문법·예시), 결정적 후보(2.6), `is_revert`·`weakens_contract`·빈 의도 판정(intent 있을 때만).
6. verify: 후보마다 기준 세트 + 재현 + `intent_expectations`, 반복 `VERIFY_REPEAT`. 순위에서 약화·되돌리기 제외, 의도 통과 우선.
7. 기준 목록 g-01..g-06(config, stub_tools, 테스트 기대 횟수).
8. `mcp_client` 가 `X-Service-Token` 전송. `rag.py` 가 요청 `rag_exclude` 적용, `rag_data.case_item` 이 `qa_cases` 칸을 통과시킴.
9. `scripts/`(build_rag_data.py 제외)의 가짜 LLM(run_demo, b_c_contract_smoke 등)이 새 스키마에서도 돌게.
10. 테스트 추가(LLM 없이 B-01 모양이 결정적 후보로 합격하는 그래프 테스트 포함), Windows 호환(`encoding="utf-8"`).

### W4 · QA·시나리오
1. 기준 주문 g-04..g-06(2.8). 기대값은 코드로 유도하고 근거를 케이스 `evidence` 처럼 적는다.
2. `qa_logic.py`: 기준 목록 g-01..g-06, 고칠 수 있는 케이스에서 합격안 0개 = FAIL, `root_cause_all` 버그, intent 판정(B 묶음).
3. 모든 기존 케이스 `order_agent` 표에 g-04..g-06 반응 추가(코드로 유도: 계약 오류 케이스는 같은 오류, 정상 케이스는 기대대로).
4. `run_qa.py`: 새 intent 형식·`rag_exclude` 를 `/diagnose` 로, `/mcp` 에 `X-Service-Token`, B 묶음 check 경로(의도 범위 기준 주문을 재현으로, `intent_expectations` 로 정답 안 시험).
5. B-01..B-05 케이스(2.9). 정답 rules 안, 되돌리기 불합격 안, 잘못된 위치 안(B-02 shipping-reserve) 포함.
6. `build_rag_data.py`: QA 가 만든 제안서(snapshot metadata `qa_case`)는 사례로 쌓지 않음. `rag/cases.json` 사례에 `qa_cases` 칸(S2→C-01·U-01·G-01·G-02, S3→D-01, S4→D-03 등). run_qa 는 케이스의 대응 사례 id 를 `rag_exclude` 로 보낸다.
7. 루트 README 기동 절(harness·`../scenarios/order-agent`), `.env.example` 의 `SKILLOPS_USE_STUB_MCP=false`, rules·intent 짧은 소개.
8. `qa/README.md` 갱신.

### W5 · 운영 화면
1. Skill 편집 저장에 "바꾸려는 업무 규칙(의도)" 한 줄 입력과 선택 고급 입력(`scope => check; check` 한 줄씩, 비우면 생략). `saveSkillEdits` 가 `intent` 전송(text 가 비면 칸 생략).
2. 진단 결과에 `finding: INTENT_NOT_REALIZED`, 원인 `incomplete_edit`, 후보 `encode_rule`·`is_revert`·`weakens_contract`, `server_overrides` 표시. trace `RULE_MATCHED` 문장(`eventView.js`).
3. `frontmatter.js`/`skillSet.js` 가 rules 를 보존·표시. mock 에 rules 예시.
4. 단위 테스트, lint.

## 4. 순서

1. 조정: 계약 파일 고정 → **완료**.
2. W1~W5 동시 실행.
3. 조정: 통합 검증(아래 5절), 계약 교차 확인(필드 이름 grep), 남은 위험 정리, 이 문서 갱신.

## 5. 검증

| 대상 | 명령 |
|---|---|
| service | `./gradlew build` (휴대용 JDK 21) |
| 주문 Agent | `./gradlew test` (DB 테스트는 건너뜀으로 기록) |
| SkillOps Agent | `PYTHONUTF8=1 pytest`, `ruff check .` |
| qa | `pytest`, `ruff check .` |
| frontend | `npm run test:unit`, `npm run lint` |
| E2E | 이 PC 에 Docker 가 없어 불가 → 남은 위험 |

## 6. 범위 밖

- openai 모드에서 본문 자연어를 그대로 규칙처럼 따르게 하는 것(확률적이라 판정 불가).
- 새 skill·새 도구를 만드는 수정안(L3), 승인 후 운영 반영(promote)·rollback, 원인 2개 조합 실험(D3).
- QA 정답표에 없는 합격안을 다시 돌려 확인하는 것(v1 W4.2, 검토에서 삭감).

## 7. 진행 기록

| 시각 | 내용 |
|---|---|
| 2026-10-26 | 계획 v1 작성. 휴대용 JDK 21 준비, 브랜치 생성 |
| 2026-10-26 | 기준선 테스트: service 697건 중 6건, 주문 Agent 252건 중 1건 실패(80건 건너뜀 = DB 필요). **원인은 전부 Windows `core.autocrlf` 의 CRLF 변환**(bundle 해시 불일치 → `INCOMPLETE_BUNDLE`, patch fixture 불일치). 로직 결함 아님. 루트 `.gitattributes`(`* text=auto eol=lf`) 추가 후 다시 체크아웃 |
| 2026-10-26 | LF 고정 후 기준선: **service 697/697 통과, 주문 Agent 254건 중 실패 0 (82건 DB 필요로 건너뜀)**. 이후 실패는 이번 변경 때문으로 본다 |
| 2026-10-26 | 계획 검증(독립 검토자, 코드 대조): 필수 수정 20건. 주요: ① 의도 예시가 g-02(결제 전 재고 거절)와 충돌 → `in ['HOLD','REJECTED']` ② service 가 총액을 모름 → 상품 단가로 계산 ③ Order 에 규칙 종료 메서드 없음 → `stopByRule` ④ B-02 를 shipping-reserve 에 걸면 결제가 남음 → order-intake ⑤ 새 오류 코드가 Python 계약 검사에서 ContractError → 스키마 v1.4·전용 fixture ⑥ 되돌리기 일괄 금지가 QA 5건(`revert_allowed`)과 입력 삭제가 정답인 3건을 깨뜨림 → intent 있을 때만 ⑦ 새 진단 상태는 라우팅과 안 맞음 → finding 으로 ⑧ 결과 불일치 진단에 재현 주문(f-)을 못 씀 → `repro_scenario_ids` ⑨ bundle 해시 비교는 잘못 지정 → applied_patches 비교 ⑩ 장부 새 열은 nullable ⑪ 소유 빈틈(scripts, run_qa 토큰, order-agent/frontend) |
| 2026-10-26 | 계획 v2 확정. 계약 파일 고정: `condition-cases.json` 보강, `skill-rules-cases.json` 신규(19 사례, 입력 공급이 정상인 자급 흐름), `apply-patch-result.schema.json` v1.4, `proposal.schema.json` 추가 칸 |
| 2026-10-26 | **W1~W5 동시 투입.** 각자 소유 범위만 수정, git 상태 변경 금지. 결과 보고가 오면 아래에 적는다 |
| 2026-10-26 | **W1 완료.** 주문 Agent 테스트 254 → **337건, 실패 0**(84건 DB 필요로 건너뜀), 주문 화면 75/75·lint 통과. `RuleCondition`·`SkillLoader` rules·`OrderHarness` 규칙 종료·`Order.stopByRule`·`EditRequest.intent` 완료, 공용 fixture 2개 전부 통과. 해석: flow 의 `rules: []`/null 은 허용(비지 않은 목록만 오류), 규칙 단계는 SKIPPED(code=reason), 재고 해제 trace 의 skill_id 는 규칙 단계. **남은 일:** `TraceContractGolden` 규칙 경로 기대 줄은 DB 실행 전 검증 필요, 주문 화면 `format.js` 의 상태 라벨이 규칙 종료에도 "재고 부족·결제 거절"로 보임, `SkillQueryService` 메타데이터에 rules 미포함 |
| 2026-10-26 | **W5 완료.** 운영 화면 단위 테스트 87 → **120건 통과**, lint·build 통과, mock 모드 화면 확인(콘솔 오류 없음). 의도 입력(한 줄 + 고급 `scope => check; check`), 규칙 목록 표시와 저장 전 검사(공용 fixture 2개로 테스트), finding·incomplete_edit·encode_rule·is_revert·weakens_contract·server_overrides 표시, `RULE_MATCHED` 문장. **통합 확인할 것:** `BUSINESS_STOP` 에 `rule_reason` 이 없어 보류 설명 문구가 일반 문구로 보임(W1), `/skills` 메타데이터에 rules 없음(파일 원문에서 읽음), `server_overrides` 응답 위치(W2) |
| 2026-10-26 | **W4 완료.** qa 테스트 42 → **152건 통과**, ruff 통과. g-04..g-06 추가, 기존 자동 케이스 28개 반응 표에 g-04..g-06(코드로 유도), B-01..B-05, `qa_logic` 개선(합격안 0개 FAIL, G-01 버그, 되돌리기·약화 코드 판정, 규칙 위치 CONFLICT, 새 지표 "의도 실현 안 제시율"), run_qa 의 intent·rag_exclude·토큰, RAG `qa_cases`·QA 제안서 제외, README·.env.example. **남은 위험:** 새 케이스와 g-04..g-06 반응은 서버 `check` 미실행(코드 유도), `--trigger save` 경로는 rag_exclude 를 못 보내 RAG 누수 여지, S6→U-01..U-03 대응은 확인 필요 |
| 2026-10-26 | **W3 완료.** SkillOps Agent 테스트 643 → **754건 통과**, ruff 통과. 완료 판정 1을 그래프 테스트로 고정: B-01 모양 수정본 + 담당자 expectations → LLM 이 후보를 안 내거나 실패해도 결정적 `encode_rule` 후보 1개가 합격. 새 모듈 `skill_rules.py`(rules 편집·결정적 후보·is_revert·weakens_contract). 의도 없는 요청의 도구 호출은 그대로, 단 오류 없는 결과 불일치는 이제 원인 실험으로 이어짐. **빈틈(목표 직결):** 의도 text 만 있고 expectations 가 없으면 본문만 고친 수정본이 여전히 VALIDATED → 후속 작업 F1 로 처리. 그 밖: intent_scoped 가 없으면 Agent 쪽 keeps_intent 가 비어 통과(service 재판정이 최종), 업그레이드 전 change_id 재전송은 409 |
| 2026-10-26 | **W2 완료.** service 테스트 697 → **812건, 실패 0**. `Check` 문법 v2, scope 문맥(상품 단가로 `order.total`), `run_scenarios` 의 `intent_expectations`·`intent_scoped`, D2(skill 없는 오류도 오류 이름), `apply_patch` rules 검사(fixture 19건 일치), **`save_proposal` 이 장부·workspace 로 모든 판정을 다시 계산하고 다르면 서버 값으로 덮어 `server_overrides` 에 기록**, `WORKSPACE_FROZEN`, 서버 쪽 `is_revert`, 재현 `final_status == null` 불합격, `/mcp` 토큰 필터, 제안서 본문 "서비스 재판정" 절. 추가 결정: `results.evidence_matches`(patch·snapshot·실행 불일치면 그 후보만 불합격), 의도 요청은 의도 범위 실행이 실제로 있어야 keeps_intent 참. **남은 위험:** 장부 새 열은 실제 PostgreSQL 에서 미검증 |
| 2026-10-26 | **통합 교차 확인(조정).** W3 검증 순서(후보마다 새 workspace, 회귀·재현 각 2회, 의도 없으면 추가 조건 없는 실행 따로) = W2 재판정 조건과 일치. `repro_scenario_ids` 가 없을 때 서버가 장부의 `f-` 로 돌아감 확인. `server_overrides` 위치(제안서 본문) = W5 읽는 곳과 일치. `RULE_KEY_NOT_INPUT` 의 `skill_id` = W4 B-05 가정과 일치. `proposal.schema.json` 에 `results.evidence_matches` 추가. 변경 파일 CRLF 0개. 전체 변경 141 파일 +5531/−910 |
| 2026-10-26 | **통합 테스트(5개 작업 합친 상태) 전부 녹색.** service 812/812 · 주문 Agent 337건 실패 0(84 DB 건너뜀) · SkillOps Agent 754 통과·ruff · qa 152 통과·ruff · 운영 화면 120 통과·lint·build · 주문 화면 75 통과 |
| 2026-10-26 | 후속 작업 투입: **F1**(SkillOps Agent) 의도 text 만 있을 때 사전 검증 전에 expectations 추론 → 본문만 고친 수정본도 의도 미실현을 찾게. **F2**(주문 Agent) `BUSINESS_STOP` 에 `rule_reason`, 주문 화면 상태 라벨을 규칙 종료에 맞게, `/skills` 메타데이터에 rules(소비자가 받아들일 때만) |
| 2026-10-26 | **F2 완료.** 주문 Agent 343건 실패 0(84 DB 건너뜀), 주문 화면 76 통과·lint. `BUSINESS_STOP` 에 규칙 종료일 때만 `rule_reason`(소비자 4곳 모두 추가 칸 허용 확인), 주문 상태 문구를 `result_code` 에 따라(OUT_OF_STOCK·PAYMENT_DECLINED 는 그대로, 규칙은 "업무 규칙(REASON) · 주문 거절/보류", NO_SELECTION 은 "Skill 미선택"), `/skills` 메타데이터에 `rules`(소비자 모두 허용 확인). 조정: 운영 화면 `skillSets.js` 의 낡은 주석 수정 |
| 2026-10-26 | **F1 완료 — 목표 빈틈 해결.** 담당자가 의도 text 만 쓰면 저장 경로에서 사전 검증 전에 LLM 1회로 expectations 를 추론(출처 `inferred`, 문법·키 검사 후 버린 줄은 이유와 함께 기록). 그래서 본문만 고친 B-01 도 `INTENT_NOT_REALIZED` → 합격 `encode_rule` 후보. LLM 실패 시 예전 동작 + `diagnosis.intent_note`. SkillOps Agent 754 → **767 통과**, ruff. **남은 위험:** 실제 모델로는 미시험(잘못 추론하면 올바른 수정본도 no_valid_fix 가능 → 운영자 의도 확인이 마지막 방어선), 기준 주문 표 `config.GOLDEN_ORDERS` 는 파일 사본(일치 테스트 있음) |
| 2026-10-26 | **구현 검토 1(service·주문 Agent·qa·화면, 독립 검토자).** 확인된 문제 8건. **높음:** ① 서버 재판정이 의도 유무·기준본·snapshot 을 Agent 가 쓴 제안서 칸에서 읽어, 그 칸을 비우면 되돌리기가 합격할 수 있음(ProposalJudge.Request) → 주문 Agent 의 snapshot metadata 를 진실 원천으로 ② 저장 때 의도 조건 문법을 검사하지 않아, 잘못 쓴 조건 하나가 진단 요청 전달 큐를 영구히 막음(SnapshotService.validIntent, SkillChangeDispatcher) — 조정자가 코드로 확인. **중간:** ③ fixes_failure 가 Agent 가 고른 repro id 를 믿음 ④ 늘 참인 의도 조건·어떤 기준 주문에도 안 맞는 scope 로 keeps_intent 가 빈 통과. **낮음:** ⑤ 화면이 YAML 1.1 불리언 낱말(YES/NO/ON/OFF)을 reason 으로 받아들임 ⑥ QA 약화 판정이 서버보다 엄격 ⑦ apply_patch·run_scenarios 동시 호출 경쟁 ⑧ B-02 의 결정적 후보가 바뀐 skill(shipping-reserve)에 걸려 LLM 없이는 실패. 검증 OK: B-01..B-05 정답이 채점 규칙으로 합격·되돌리기 불합격, 규칙 종료의 재고 해제 정확, 두 조건 구현 일치, 의도 칸 이름 전 구간 일치 |
| 2026-10-26 | **구현 검토 2(SkillOps Agent, 독립 검토자).** 확인된 문제 10건. **높음:** null scope 의도의 실패가 의도 밖으로 분류돼 원인 실험 → ERROR. **중간:** extract_intent 경로가 10/10 한도를 안 지켜 service 가 거부 → ERROR, 추론 의도가 늘 참·과대 범위여도 막는 장치 없음, B-05 모양에 결정적 후보 없음. **낮음:** 재시도 LLM 실패가 ERROR, 기존 rules 형식이 깨지면 KeyError, 약화·의도 시험 여부 정의가 서버와 다름, 의도 없는 흐름의 문서 밖 변화(되돌리기 순위 강등 등), `/diagnose` 가 잘못된 담당자 조건으로 요청 전체를 422, `_with_inputs` 가 블록 목록의 주석을 지움. 검증 OK: 서버와의 지문(checks_hash)·반복·patch·repro 계약, 기준본·대조·원인 실험은 의도 없이 실행, YAML 편집의 바이트 보존 |
| 2026-10-26 | **계약 보정 v3 확정(2.10 절 V3-1..V3-8)** + fixture `skill-rules-cases.json` 에 YAML 불리언 reason 2건(21 사례). 수정 작업자 4명 동시 투입: **X1** service(진실 원천·repro 확인·scoped 의도 모두 시험·약화 정의), **X2** 주문 Agent(저장 때 의도 문법 검사·전달 큐 막힘), **X3** SkillOps Agent(검토 2 의 10건), **X4** qa·화면(약화 정의 통일·QA 발행 metadata 에 intent·화면 reason/scope 검사) |
| 2026-10-26 | **X2 완료.** 주문 Agent 351건 실패 0(84 DB 건너뜀). 저장 때 의도 scope·checks 를 `RuleCondition` 으로 읽고 scope 키는 다섯 개만 → 어기면 400 `INVALID_SKILL_EDITS`. 전달기: service 가 거절(400·409·413·415·422)하면 `failed_at`(nullable 열, `ADD COLUMN IF NOT EXISTS`)을 적고 다시 고르지 않음 → 큐 막힘 해소, 설정 문제(401·404·5xx 등)는 계속 재시도, 상태 `FAILED` 추가. 조정: `docs/skill-save-auto-diagnosis.md` 전달 규칙·상태 표 갱신. **남은 일:** 운영 화면이 FAILED 에도 "자동으로 재전송" 문구(X4 끝난 뒤 수정), 400 응답 본문에 오류 상세가 안 실림(기존 예외 처리기 정책) |
| 2026-10-26 | **X4 완료.** qa 161 통과·ruff, 운영 화면 124 통과·lint·build. QA 약화 판정을 V3-7(when 만 비교)로, QA 직접 평가가 발행하는 snapshot metadata 에 `intent`(서버 진실 원천 V3-1 과 맞춤), 화면이 따옴표 없는 불리언 reason 거부, 의도 scope 키 다섯 개 검사. **조정자 정정:** X4 가 찾은 대로 SnakeYAML 은 Y·N 을 글자로 읽는다 → fixture 메모·계획 V3-6·화면 정규식을 서버와 같게(yes/no/true/false/on/off 의 세 표기만 불리언) 고치고 테스트 갱신. 진단 화면은 전달 `FAILED` 일 때 "거절됨" 제목·안내를 보이고 다시 조회하지 않음 |
| 2026-10-26 | **X1 완료.** service 812 → **834건, 실패 0**. V3-1: 의도·기준본을 주문 Agent snapshot metadata 에서 읽고 `trigger.intent`·제안서 기준본은 무시, `snapshot_id` 필수, patch 0개 후보 불합격, metadata 를 못 읽으면 저장 거부(Agent 값으로 대체하지 않음). V3-5: repro id 마다 proof 실행 중 실패가 있어야 함. V3-4: 장부 `intent_matches`(nullable) 로 scoped expectation 마다 맞은 실행 확인. V3-7: 약화 정의 통일. apply·run 경쟁은 workspace 별 잠금(한 프로세스 안에서만 — 여러 인스턴스는 남은 위험). 조정: `proposal.schema.json` 의 `snapshot_id` 를 필수 문자열로, repro 규칙 설명 추가 |
| 2026-10-26 | **X3 완료.** SkillOps Agent 767 → **833 통과**, ruff. null scope 의도: 의도 실행에서만 떨어진 주문을 의도 없이 다시 돌려 구분(V3-3). extract_intent 한도, 추론 의도 가드 (a)(b)(c)(V3-4, 서버와 같은 결과를 내는 `condition_holds` 추가), B-05·B-02 결정적 후보(최대 2개 위치, V3-6), 재시도 LLM 실패 → no_valid_fix 경로, 기존 rules 형식 오류에도 안 멈춤, 약화·의도 시험 여부 서버와 같게(V3-7), 의도 없는 요청의 순위·결과 불일치 종료 예전대로(V3-8), `/diagnose` 는 잘못된 담당자 조건을 버리고 진행(V3-2), 블록 inputs 주석 보존, 결정적 reason 이 불리언 낱말 회피. 결정: (b) 를 엄격히 읽어 추론 의도의 "보완 쌍"(카드면 보류 + 선입금이면 배송)은 과대 범위로 버림 — 담당자가 준 의도는 영향 없음 |
| 2026-10-26 | **최종 통합 테스트(빌드 캐시 비우고 처음부터) 11개 검사 전부 통과.** service **834/834** · 주문 Agent **351건 실패 0**(84 DB 건너뜀) · SkillOps Agent **833 통과**·ruff · qa **161 통과**·ruff · 운영 화면 **124 통과**·lint·build · 주문 화면 **76 통과**·lint. 변경 파일 CRLF 0개. 156 파일 +7008/−970 (새 파일 52개), 커밋하지 않음 |

## 8. 완료 판정 대비 결과와 남은 위험

| 완료 판정(0절) | 결과 | 근거 |
|---|---|---|
| 1. 본문만 고친 수정본 + 의도 → 의도 미실현 탐지 → `encode_rule` 합격 (LLM 없이도) | **충족(단위·그래프 테스트)** | `skillops-agent/tests/test_behavior_intent.py` — 담당자 expectations, 의도 text 만(추론), LLM 실패, B-02·B-05 위치 |
| 2. 의도 범위 밖은 그대로, 범위 안은 의도대로 | **충족(테스트)** | service `ScenarioRunnerIntentTest`(2.4 채점), 주문 Agent `OrderHarnessRuleTest`(규칙 종료·재고 해제) |
| 3. 의도 요청의 되돌리기·약화 후보를 코드가 불합격 | **충족(테스트)** | Agent `skill_rules.is_revert/weakens_contract`, service `ProposalJudge`(서버 쪽 재계산), QA `qa_logic.weakened` — 세 곳 같은 정의(V3-7) |
| 4. service 가 장부로 재판정 | **충족(테스트)** | `ProposalJudgeTest` — 의도 진실 원천(snapshot metadata), repro 근거, scoped 의도 전부 시험, patch·snapshot 일치, `server_overrides` |
| 5. QA B 묶음과 RAG 누수 차단 | **충족(정적)** | `qa/cases/B-01..B-05`, `rag/cases.json` 의 `qa_cases` → 요청별 `rag_exclude`, QA 제안서는 RAG 사례로 안 쌓임 |

**남은 위험 (서버를 띄워 확인해야 하는 것)**

1. **E2E 미실행.** 이 PC 에 Docker 가 없어 PostgreSQL·서버 셋을 함께 띄우지 못했다. 장부 새 열(`intent_scoped`, `checks_hash`, `intent_matches`), `skill_change_events.failed_at`, 주문 Agent DB 통합 테스트 84건(규칙 경로 `TraceContractGolden` 기대 줄 포함)은 실제 DB 에서 처음 돈다.
2. **QA `check` 미실행.** g-04..g-06 반응과 B 묶음 정답은 코드로 유도했다. 서버를 띄우고 `uv run run_qa.py check` 로 사실을 먼저 확인한 뒤 `eval` 을 돌린다.
3. **실제 LLM 미시험.** 의도 추론(`infer_expectations.md`)과 LLM 후보는 가짜 LLM 으로만 시험했다. 잘못 추론하면 올바른 수정본도 `no_valid_fix` 가 될 수 있고, 운영자의 의도 확인이 마지막 방어선이다.
4. **여러 service 인스턴스.** workspace 잠금은 한 프로세스 안에서만 유효하다.
5. **`--trigger save` 평가 경로**는 `rag_exclude` 를 보낼 수 없다(직접 경로는 막힘).
6. **openai 모드**에서 본문 자연어를 그대로 따르게 하는 것은 범위 밖이다. 이번 설계는 본문 정책을 `rules` 로 옮기는 것을 수정안으로 삼는다.

**다음에 할 일 (순서대로)**

```bash
docker compose up -d --wait                                  # service 용 PostgreSQL
cd order-agent && docker compose up --build -d --wait        # 주문 Agent :8081
cd order-agent/backend && ./gradlew test                     # DB 통합 테스트 84건 (README 의 db-test 절차)
cd service && SKILLOPS_FLOW_RUNNER=harness SKILLOPS_SCENARIOS_DIR=../scenarios/order-agent ./gradlew bootRun
cd skillops-agent && SKILLOPS_USE_STUB_MCP=false uv run uvicorn skillops_agent.main:app --port 8000
cd qa && uv run run_qa.py check                              # 정답표 사실 확인 (모델 비용 없음)
cd qa && uv run run_qa.py eval --only B-01 B-02 B-03 B-04 B-05 C-01 --repeat 3
```
| 2026-10-26 | **하드코딩 점검(사용자 질문).** ① 판단 로직에 케이스별 분기 없음(운영 코드 grep: 특정 skill 이름·케이스 값으로 갈라지는 곳 없음, `order_agent.py` 의 `"payment"` 는 develop 에 원래 있던 것). ② **문제: 프롬프트 예시가 QA 정답과 같다** — `infer_expectations.md`·`extract_intent.md`·`generate_candidates.md` 의 예시가 B-01(선입금만 결제, `PREPAID_ONLY`)·B-03(10만 원 이상 보류)의 scope·checks·rule 그대로 → LLM 평가에서 B-01·B-03 점수가 부풀려짐(RAG 누수와 같은 종류). ③ **순환성:** 결정적 후보는 의도 scope 를 규칙 when 으로 옮기므로, 담당자 expectations 가 주어지면 합격이 거의 자명하다. 어려운 부분(자연어 → 조건)은 LLM 추론이고 실제 모델로 미시험. 그래프 테스트도 같은 작업자가 고친 stub 위에서 돈다. ④ **고정값 사본:** 기준 주문 표 `config.GOLDEN_ORDERS`(파일 사본), `INITIAL_INPUT_KEYS`(세 번째 사본), scope 키 다섯 개(네 곳). ⑤ **행동 범위:** rules 는 "조건이면 보류/거절"만 표현한다 — 배송 방식·알림 채널·금액 변경 같은 행동 수정은 표현 불가. ⑥ B 묶음 정답을 설계와 같은 흐름에서 썼으므로 독립 평가셋이 아니다. ⑦ 기존 테스트 assert 삭제 점검: 기준 주문 3→6 개수 갱신과 문서화된 동작 변경(결과 불일치 → 원인 실험)뿐, 약화 없음. 8절 "충족" 판정 중 1번은 "기계적으로 충족, 의미 있는 부분은 미검증"으로 읽어야 한다 |
| 2026-10-26 | **실서버 환경 구성(Docker 없이).** 휴대용 PostgreSQL 17.10(포트 5433, DB `order_demo`·`skillops`), 주문 Agent jar(:8081, fixed 모드), service jar(:8080, `harness`·`../scenarios/order-agent`), SkillOps Agent(:8000, 실제 MCP, OpenAI 키는 git 무시 `.env`). MCP 도구 7개·ping 확인. ※ 사용자가 넣은 키가 git 추적 파일 `.env.example` 에 있어 `.env` 로 옮기고 `.env.example` 은 비움 |
| 2026-10-26 | **실서버 QA `check` 34개 전부 일치(어긋남 0).** 코드로 유도했던 g-04..g-06 반응, B-01..B-05 정답 rules 안 합격·되돌리기·잘못된 위치 불합격이 실제 주문 Agent·service 에서 처음 확인됨(LLM 비용 없음) |
| 2026-10-26 | **실서버 저장 → 자동 진단 경로 확인.** 주문 Agent 저장(201) → 전달기 → service 중계 → SkillOps Agent → VALIDATED(설명 문구만 바꾼 수정, LLM 비용 없음). 운영 화면(:5173, `/api`→8081·`/service`→8080 프록시, operator 로그인 확인)과 주문 화면(:5174)을 띄워 사용자가 직접 시험할 수 있게 함 |
| 2026-10-26 | **사용자 실험 1 (실서버, 실제 LLM).** 수정: `notify` inputs 에 `shipping.weather` 추가, 의도 입력 없음. 결과 `no_valid_fix`(후보 4개 모두 불합격). 원인: 날씨 값을 만드는 도구가 없다(`OrderToolService.java:173` 의 `shipping.reserve` 는 `shipping.id`·`shipping.eta` 만 냄) → 모든 주문이 notify 앞에서 CONTRACT_VIOLATION. 판정 자체는 옳다(#2 "shipping-reserve outputs 에 weather 선언"은 도구가 값을 안 줘 6개 중 2개만 통과 — 선언만으로 데이터가 생기지 않음을 실험이 보여 줌). **고칠 점:** ① 가설 단계가 이미 "SKILL.md 만으로 해결 불가(신뢰도 0.98)"라고 했는데 제안서는 "담당자가 Skill 을 직접 고쳐야 합니다"로 끝나 오해를 부름 ② 후보 생성 3회 재시도로 LLM 낭비 ③ 지금 쓸 수 있는 대안(의도 보류 = 되돌리기)과 개발 요청(어느 도구에 어떤 출력)이 없음 ④ LLM 의 encode_rule 후보("날씨가 있으면 보류")는 의미 없는 안 |
| 2026-10-26 | **개발 요청서 기능 설계(2.11) + 작업자 4명 투입.** D1 주문 Agent `GET /api/tools`(도구별 `produces` 를 ToolCatalog 에 선언, 실제 출력과 같음을 테스트로 고정 — Agent 에 사본을 두지 않기 위함), D2 SkillOps Agent 빈틈 탐지(`NEEDS_TOOL`)·후보 생성 건너뛰기·`development_request`, D3 service 검증·제안서 "개발 요청"·"임시 대안" 절·`no_valid_fix` 문구 교정, D4 화면 "개발 요청서" 카드와 마크다운 복사. 동시에 실서버에서 유형별 실험 X1..X5 진행 중(지금 코드 기준 능력 측정) |
| 2026-10-26 | **실서버·실제 LLM 실험 8건(X1..X5, Y1..Y3), 저장 경로·의도는 문장만.** ✅ **의미 있는 안 나옴:** X2 해외 배송 결제 보류(합격 3, 기준 6/6, 16초), X3 결제 거절 이력 고객 거절(합격 3), Y2 10만 원 이상 보류 — 담당자가 접수 단계(order-intake)에 썼지만 그 단계엔 총액이 아직 없어서 SkillOps 가 **다음 단계(inventory-check)에 규칙을 거는 안**을 합격시킴. ❌ **SkillOps 잘못으로 실패(고칠 수 있음):** X4(담당자가 규칙을 직접 썼는데 입력 누락) — "배송 예약을 하지 않고 보류"라는 말에 LLM 이 "결제도 안 됨(payment.status == null)"을 덧붙여, 입력만 더하는 올바른 최소 수정(기준 5/6·실패 해소)을 떨어뜨림. Y1(도구가 실제로 내는 이름으로 출력 변경, 의도 없음) — LLM 이 diff 에서 "inventory.reserved == null"(지운 값은 없어야 한다)을 지어내, 계약 복구 안(기준 6/6·실패 해소)을 떨어뜨림. 두 건 모두 **지어낸 의도 조건**이 원인이고, 프롬프트 예시가 모두 `payment.status == null` 을 포함한 편향(하드코딩 점검 ②)과 맞물림. ⚠️ **도구 필요(개발 요청서로 처리 중):** 사용자 실험 1(날씨), X1(도구가 안 내는 이름 `inventory.is_reserved`). ❌ **탐지 못함:** X5(해외는 빠른 배송으로 — 행동 변경), Y3(빠른 배송 주문 보류 — 기준 주문에 빠른 배송이 하나도 없음) → VALIDATED + "의도를 시험 조건으로 못 바꿈" 안내 |
| 2026-10-26 | **D1 완료.** 주문 Agent `GET /api/tools`(새 `ToolController`, DB·snapshot 안 읽음, DemoGuard 밖) — `initial_input_keys` 와 도구 5개의 `produces`. 실제 출력 키와 선언이 같음을 DB 없는 테스트(`OrderToolServiceProducesTest`)로 고정, 새 도구를 카탈로그에만 넣으면 테스트 실패. 주문 Agent 358건 실패 0(84 DB 건너뜀) |
| 2026-10-26 | **D4 완료.** 운영 화면 134 통과·lint·build. 제안서·진단 화면에 "개발 요청서" 카드(필요한 값, 방법, 바꿀 곳, 임시 대안 patch, 근거 실행)와 "마크다운으로 복사"(`utils/developmentRequest.js`), finding `NEEDS_TOOL` = "도구 개발 필요", mock `p_mockw1`(날씨 사례). 카드는 service 본문 아래에 둠(본문 절 이름은 D3 가 정함) |
| 2026-10-26 | **D3 완료.** service 855건 실패 0. `development_request` 검증(형식·근거 실행이 장부에 있는지), 있으면 status 는 늘 `no_valid_fix`(서버가 합격 후보를 찾았는데 함께 오면 모순이라 거부), 제안서 요약 "SKILL.md 수정만으로는 해결할 수 없어 개발 요청서를 만들었습니다", 새 절 "개발 요청"·"임시 대안". 일반 `no_valid_fix` 의 "담당자가 Skill 을 직접 고쳐야 합니다"를 "승인할 수 있는 안이 없습니다" + 시험 결과 안내로 교정. 조정: 스키마 설명에 거부 조건 추가 |
| 2026-10-26 | **D2 완료.** SkillOps Agent 866 통과·ruff. 새 모듈 `capability.py`(빈틈 판정·요청서·임시 대안 patch), `order_agent.get_tool_catalog()`(실패하면 None → 예전 흐름), finding `NEEDS_TOOL`, 새 라우트 `needs_tool` → 원인 확정 뒤 바로 `save_proposal`(LLM 은 가설 1회만). 빈틈이 없는 수정은 도구 호출·상태 칸이 예전과 같음. 날씨 사례 payload: 기존 `shipping.reserve` 확장 또는 새 `weather.lookup`(예시 이름)을 notify 앞에, 임시 대안 = notify inputs 에서 `shipping.weather` 제거(원인 실험 실행이 증명) |
| 2026-10-26 | **개발 요청서 실서버 확인(새 코드로 서버 재기동).** 통합 테스트: service 855 · 주문 Agent 358(실패 0, 84 DB 건너뜀) · SkillOps Agent 866→867 · qa 161 · 운영 화면 134 · 주문 화면 76, 모두 통과. 실서버: **사용자 날씨 사례 → `NEEDS_TOOL`, 10초, LLM 후보 0회**(이전: 3회 재시도·실패안 4개), 제안서 요약 "SKILL.md 수정만으로는 해결할 수 없어 개발 요청서를 만들었습니다", 개발 요청(기존 `shipping.reserve` 확장 / 새 `weather.lookup` 을 notify 앞에), 임시 대안 patch + 근거 실행. 회귀: 해외 배송 결제 보류(X2)는 그대로 합격안. 조정자 수정: 출력 이름 변경(X1)의 임시 대안 문구가 patch 와 달라("빼면" ↔ 실제는 원래 이름 복원) patch 에서 되살리는 키를 읽어 "원래 값 inventory.reserved 로 되돌리면"으로 쓰게 함(`capability.restored_keys`, 테스트 추가) |
