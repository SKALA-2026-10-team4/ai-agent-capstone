# MCP 도구 입출력 안내

계약 5 · **v1 제안** (v0 초안을 고친 것) · 2026-10-20 · 작성 C-2 · 동결 회의 안건

> **2026-10-21 갱신 (#38).** B의 Agent(`skillops-agent/skillops_agent/tools.py`)가 계약 5 v0 이름으로 이미 만들어져 있어서, C-2 도구는 v0 이름을 따르기로 했습니다. 7번 `create_workspace`와 9번 `run_scenarios`는 실제 구현에 맞게 고쳤습니다. 8·10·11·12번은 구현할 때 v0 이름(`ws_id`, `approval_token` 등)으로 고칩니다. 오류는 C-1 도구와 같은 `{code, message, retryable, upstream_status, snapshot_id}` 모양입니다.

> **2026-10-21 갱신 (#52).** B가 Agent를 v1 이름으로 옮겼습니다(PR #52, `tools.py`의 `MCPTools`). 그래서 C-2 도구를 다시 v1 이름으로 맞췄습니다. 7번 `create_workspace`는 입력 `start_versions`, 결과 `workspace_id`를 쓰고, 9번 `run_scenarios`는 입력 `workspace_id`·`scenario_ids`·`extra_checks`·`repeat`, 결과 `{workspace_id, all_passed, results}`를 씁니다. 위 #38 메모의 "v0 이름을 따른다"는 이 갱신으로 대체됩니다. 8·10·11·12번도 구현할 때 v1 이름으로 합니다. 오류 모양(`INVALID_INPUT`, `INTERNAL_ERROR`)은 그대로이고, 오류 문구만 v1 입력 이름(`workspace_id`, `repeat`, `start_versions`)으로 바꿨습니다. 재현 주문(`replay_run_ids`)은 아직 받지 않아서 결과의 `replay_of_run_id`는 항상 `null`입니다. (이 부분은 바로 아래 #55 갱신에서 바뀌었습니다.)

> **2026-10-21 갱신 (#55, 실제 주문 Agent로 판정).** 시험 주문을 가짜 실행기가 아니라 **주문 Agent에서 실제로 모의 실행**해 채점할 수 있게 했습니다. 7번 `create_workspace`에 `snapshot_id`가 생겼고, 8번 `apply_patch`는 구현됐으며 결과의 `changed_skills`는 `[{skill, version}]` 모양입니다. 9번 `run_scenarios`는 실제 실행기(`harness`)와 재현 주문(`f-<execution_id>`, `replay_run_ids`)을 받고, 시험 주문 폴더는 `SKILLOPS_SCENARIOS_DIR`로 정합니다. 주문 Agent가 돌려준 오류는 그대로 전달됩니다(아래 공통 약속 2번). 이 갱신은 `skillops-agent/scripts/c2_real_judge.py` 실험으로 확인했습니다.

SkillOps Agent는 주문 처리 서비스의 데이터베이스를 직접 보지 않습니다. 서비스가 열어 둔 도구 12개에 값을 보내고, 돌려받은 결과만 보고 판단합니다. 이 문서는 도구마다 **무엇을 보내고, 무엇을 돌려받고, 그 사이에 서비스가 어떤 저장 공간을 읽고 쓰는지**를 정리한 것입니다.

## 이 문서를 읽는 사람

| 누구 | 이 문서에서 볼 곳 |
|---|---|
| B · Agent 구현 | 도구마다 "보내는 값", "돌려받는 값", "예시". 그리고 "도구 사이를 잇는 번호" |
| A · C-1 · C-2 · D · 서비스 구현 | 도구마다 "읽고 쓰는 저장 공간". 그리고 맨 끝의 "저장 공간 목록" |

## 읽는 법

| 표기 | 뜻 |
|---|---|
| 글자 | 따옴표로 감싼 글. 예: `"payment"` |
| 정수 | 소수점 없는 숫자. 예: `3` |
| 참·거짓 | `true` 또는 `false` |
| 목록 | 대괄호로 감싼 여러 개. 예: `["g-01", "g-02"]` |
| 묶음 | 중괄호로 감싼 이름과 값의 모음. 예: `{"payment": 1}` |
| 시각 | 국제 표준 형식으로 적은 글자. 예: `"2026-10-21T09:12:30+09:00"` |
| `null` | 값이 없다는 뜻 |
| `skills[].version` | `skills` 목록 안에 들어 있는 각 항목의 `version` |
| 꼭 필요: 예 | 반드시 넣어야 합니다. 빠지면 도구가 거부합니다 |
| 꼭 필요: 아니오 | 넣지 않아도 됩니다. 넣지 않았을 때 어떻게 되는지는 설명에 적었습니다 |
| 값 이름 | skill 사이를 오가는 값의 이름. `내놓은 쪽.무엇` 모양입니다. 예: `inventory.reserved`는 재고 확인 쪽이 내놓은 '예약 여부' |

## 공통 약속

1. **결과는 항상 중괄호 묶음입니다.** 목록을 돌려줄 때도 `{"skills": [...]}`처럼 이름을 붙여 감쌉니다. MCP 규칙에서 도구의 정리된 결과는 묶음이어야 하기 때문입니다.
2. **실패하면 `isError=true`와 오류 묶음을 돌려줍니다.** 모양은 C-1 도구와 같은 `{code, message, retryable, upstream_status, snapshot_id}`입니다. `message`에는 "무엇이 잘못됐고 어떻게 고치면 되는지"를 한 줄로 적습니다. 예: `"workspace_id w_A17 이 없습니다. create_workspace 로 먼저 만드세요"`. C-2 도구가 직접 만드는 `code`는 `INVALID_INPUT`(입력이 잘못됨. 고쳐서 다시 부름)과 `INTERNAL_ERROR`(서버 쪽 문제) 두 가지이고, 둘 다 `retryable`은 `false`입니다. 다만 주문 Agent에서 파일을 읽다가 실패하면(`create_workspace`에 `snapshot_id`를 넣었을 때) 주문 Agent가 알려 준 오류를 **그대로** 전달합니다. 그때는 `code`가 `SNAPSHOT_NOT_FOUND`, `ORDER_AGENT_UNAVAILABLE`처럼 주문 Agent 쪽 값이고, `retryable`(다시 불러 볼 만한지), `upstream_status`(주문 Agent가 돌려준 HTTP 상태), `snapshot_id`도 주문 Agent가 준 값입니다. `run_scenarios`가 실제 실행기로 돌다가 주문 Agent에 닿지 못하거나 주문 Agent가 오류(5xx)를 내면 `INTERNAL_ERROR`, 주문 Agent가 요청을 거절(4xx)하면 `INVALID_INPUT`입니다.
3. **이름은 줄여 쓰지 않습니다.** 끝말은 아래 규칙을 따릅니다.

| 끝말 | 뜻 | 예 |
|---|---|---|
| `_id` | 무엇을 가리키는 고유 번호(글자) | `run_id`, `workspace_id` |
| `_ids` | 고유 번호 목록 | `run_ids` |
| `_no` | 1부터 세는 순번(정수) | `change_no`, `step_no` |
| `_version` | skill 버전(정수) | `from_version` |
| `_at` | 시각 | `saved_at` |
| `_count` | 개수(정수) | `changed_skill_count` |

4. **고유 번호의 모양**

| 번호 | 모양 | 예 |
|---|---|---|
| 실행 번호 `run_id` | `r_` 로 시작 | `r_8KQ2` |
| 연습 공간 번호 `workspace_id` | `w_` 로 시작 | `w_A17` |
| 제안서 번호 `proposal_id` | `p_` 로 시작 | `p_0007` |
| 승인 번호 `approval_code` | `a_` 로 시작 | `a_5XQ9` |
| 시험 주문 번호 `scenario_id` | 기준 주문 `g-01`~`g-06`, 재현 주문은 `f-` 뒤에 실패한 실행의 `execution_id`를 붙임 | `g-01`, `f-5c7a8c4a-f846-4d1c-a81e-ea648893343a` |
| 주문 번호 `order_id` | 실제 주문은 `o_`, 모의 실행용 임시 주문은 `s_` 로 시작 | `o_1042` |

## 도구 사이를 잇는 번호

한 도구가 돌려준 번호를 다음 도구에 그대로 넣는 식으로 도구들이 이어집니다. Agent 구현에서 가장 먼저 맞춰야 할 부분입니다.

| 번호 | 처음 받는 곳 | 넣어 쓰는 곳 |
|---|---|---|
| `run_id` | 주문 실패 알림, `list_failed_runs`, `run_scenarios` 결과 | `get_trace`, `run_scenarios`의 `replay_run_ids`, `save_proposal`의 `proof_run_ids`·`evidence_run_ids` |
| `skill` + 버전 번호 | `list_skills`, `get_trace` | `diff_skill`, `get_skill`, `create_workspace`, `rollback` |
| `change_no` + `patch` | `diff_skill` | `apply_patch`의 `patches[].patch`, `save_proposal`의 `root_cause.change_no` |
| `workspace_id` | `create_workspace` | `apply_patch`, `run_scenarios`, `save_proposal`의 `candidates[].workspace_id`, `promote_workspace` |
| `scenario_id` | 기준 주문 파일(`g-01`~`g-06`), 재현 주문(`f-<execution_id>`: 주문 Agent가 알려 준 실패한 실행 번호로 만듦) | `run_scenarios` |
| `proposal_id` | `save_proposal` | 화면의 승인 API |
| `approval_code` | 화면의 승인 API | `promote_workspace` |

## v0에서 바뀐 것

### 비어 있던 곳을 메우려고 바꾼 것

| 비어 있던 곳 | 이렇게 메웠습니다 | 바뀐 도구 |
|---|---|---|
| 실패 알림만으로는 바뀌기 전 버전을 알 수 없었다 | skill마다 "마지막으로 주문이 정상 처리됐을 때의 버전"을 함께 돌려준다 | `list_skills`에 `last_working_version` 추가 |
| 재현 주문과 의도 확인 조건을 넘길 방법이 없었다 | 실패한 실행 번호를 넘기면 서비스가 그 주문을 꺼내 재현 주문을 만든다. 의도 확인 조건은 이번 시험에만 덧붙인다 | `run_scenarios`에 `replay_run_ids`, `extra_checks` 추가 |
| 변경 덩어리 하나만 넣는 형식이 없었다 | 비교 결과의 변경마다 "그 변경만 담은 수정 내용"을 붙인다. 그대로 `apply_patch`에 넣으면 된다 | `diff_skill`의 `changes[].patch` 추가 |
| 모의 실행의 실패가 새 진단을 일으킬 수 있었다 | 실패 목록과 실패 알림은 실제 운영 실행만 다룬다 | `list_failed_runs`, 실패 알림(webhook) |
| 운영 반영을 누가 부르는지 두 곳에 다르게 적혀 있었다 | 운영자가 승인하면 서비스가 부른다. Agent는 부르지 않는다 | `promote_workspace`, `rollback` |

### 이름만 바꾼 것

| 도구 또는 계약 | v0 이름 | v1 이름 | 바꾼 까닭 |
|---|---|---|---|
| 여러 도구 | `ws_id` | `workspace_id` | 줄임말 |
| 여러 도구 | `name` | `skill` | 다른 도구와 같은 이름으로 통일 |
| `diff_skill` | `hunks` / `index` / `header` | `changes` / `change_no` / `location` | 전문 용어 |
| `get_dependency_graph` | `steps` / `edges` / `keys` / `violations` | `run_order` / `links` / `value_names` / `broken_links` | 전문 용어 |
| `apply_patch` | `patches[].diff` / `applied` / `contract_violations` | `patches[].patch` / `changed_skills` / `broken_links` | 뜻이 드러나게 |
| `run_scenarios` | `k` / `runs` / `failures[].step` / `failures[].error` | `repeat` / `run_ids` / `failures[].skill` / `failures[].reason` | 한 글자 이름, 뜻이 드러나게 |
| `create_workspace` | `base` | `start_versions` | 뜻이 드러나게 |
| `promote_workspace` | `approval_token` / `promoted` | `approval_code` / `live_skills` | 뜻이 드러나게 |
| `rollback` | `version` | `to_version` | 어느 쪽 버전인지 드러나게 |
| 계약 2 실행 기록 | `workspace` / `ms` / `mode: dry-run` | `workspace_id` / `duration_ms` / `mode: dry_run` | 줄임말, 표기 통일 |
| 계약 4 제안서 | `hunk` / `confirmed_by` / `assertions` | `change_no` / `proof_run_ids` / `checks` | 전문 용어 |
| 계약 4 제안서 | `candidates[].id` / `evidence` / `recommended` | `candidate_no` / `evidence_run_ids` / `recommended_candidate_no` | 무엇이 들어가는지 드러나게 |
| 계약 4 제안서 | `results.functional` / `regression` / `intent` / `contract` / `footprint` | `fixes_failure` / `keeps_old_orders` / `keeps_intent` / `no_broken_links` / `changed_skill_count` | 기준 이름을 문장처럼 읽히게 |
| 계약 4 제안서 | `kind: local` / `contract` / `flow` | `same_skill` / `linked_skills` / `run_order` | 고치는 범위가 드러나게 |

도구 이름 12개는 설계 문서와 역할 문서 여러 곳에 이미 쓰여 있어서 그대로 두었습니다. 위의 이름 변경이 확정되면 `trace.schema.json`, `scenario.schema.json`, `proposal.schema.json`도 같은 이름으로 고칩니다.

C-1이 만들고 있는 실행 기록 저장소(`TraceRecorder`, `feat/#12_backend-trace-store`)는 지금 v0 이름(`workspace`, `ms`, `dry-run`)을 씁니다. 계약 2의 이름 변경은 C-1과 정한 뒤 반영합니다.

---

## 1. 실패한 주문 목록 보기 · `list_failed_runs`

최근에 실패한 주문 처리 기록을 모아서 보여 줍니다. 실제 손님 주문(운영 실행)만 보여 주고, 연습 공간에서 돌린 모의 실행의 실패는 넣지 않습니다. Agent는 알림을 받은 주문 말고도 같은 곳에서 실패한 주문이 더 있는지 확인할 때 이 도구를 씁니다.

부르는 쪽: Agent · 담당: C-1

### 보내는 값

| 필드 | 한글 이름 | 형식 | 꼭 필요 | 설명 |
|---|---|---|---|---|
| `since` | 언제부터 | 시각 | 아니오 | 이 시각 뒤에 실패한 것만 돌려줍니다. 비우면 가장 최근 것부터 50건까지 돌려줍니다. |

### 돌려받는 값

| 필드 | 한글 이름 | 형식 | 꼭 필요 | 설명 |
|---|---|---|---|---|
| `failed_runs` | 실패한 실행 목록 | 목록 | 예 | 가장 최근 실패가 맨 앞에 옵니다. 실패가 없으면 빈 목록입니다. |
| `failed_runs[].run_id` | 실행 번호 | 글자 | 예 | 주문을 한 번 처리할 때마다 붙는 고유 번호입니다. `get_trace`에 넣으면 자세한 기록을 볼 수 있습니다. |
| `failed_runs[].order_id` | 주문 번호 | 글자 | 예 | 실패한 주문의 번호입니다. |
| `failed_runs[].failed_skill` | 멈춘 skill | 글자 | 예 | 처리가 멈춘 skill의 이름입니다. |
| `failed_runs[].error` | 오류 내용 | 글자 | 예 | 사람이 읽을 수 있는 한 줄짜리 오류 설명입니다. |
| `failed_runs[].failed_at` | 실패 시각 | 시각 | 예 | 처리가 멈춘 시각입니다. |
| `failed_runs[].skill_versions` | 그때의 skill 버전 | 묶음 | 예 | 이 실행에 쓰인 skill별 버전입니다. skill 이름과 버전 번호의 묶음입니다. |

### 예시

```json
{ "since": "2026-10-21T09:00:00+09:00" }
```

```json
{
  "failed_runs": [
    {
      "run_id": "r_8KQ2",
      "order_id": "o_1042",
      "failed_skill": "payment",
      "error": "required input inventory.reserved not found",
      "failed_at": "2026-10-21T09:12:31+09:00",
      "skill_versions": { "order-flow": 1, "order-intake": 1, "inventory-check": 2, "payment": 1, "shipping-reserve": 1, "notify": 1 }
    }
  ]
}
```

### 읽고 쓰는 저장 공간

| 저장 공간 | 읽기·쓰기 | 무엇을 |
|---|---|---|
| `trace_run` | 읽기 | 운영 실행 가운데 최종 상태가 실패(FAILED)인 것과, 그 실행에서 멈춘 단계의 skill 이름과 오류 내용 |

### v0에서 바뀐 점

- 결과를 `failed_runs`로 감쌌습니다. `at`을 `failed_at`으로 바꾸고 `skill_versions`를 더했습니다.
- 운영 실행만 돌려줍니다.

---

## 2. 실행 기록 자세히 보기 · `get_trace`

주문 한 건이 처리된 과정을 단계별로 보여 줍니다. skill마다 무엇을 받아 무엇을 내놓았는지, 재고·결제·배송·알림 가운데 무엇을 불렀는지, 어디서 왜 멈췄는지가 모두 들어 있습니다. 실패 원인을 좁힐 때, 그리고 시험에서 떨어진 후보가 어디서 틀렸는지 볼 때 씁니다. 돌려주는 모양은 계약 2(실행 기록)와 같습니다.

부르는 쪽: Agent · 담당: C-1

### 보내는 값

| 필드 | 한글 이름 | 형식 | 꼭 필요 | 설명 |
|---|---|---|---|---|
| `run_id` | 실행 번호 | 글자 | 예 | 보고 싶은 실행의 번호입니다. |

### 돌려받는 값

| 필드 | 한글 이름 | 형식 | 꼭 필요 | 설명 |
|---|---|---|---|---|
| `run_id` | 실행 번호 | 글자 | 예 | 보낸 번호 그대로입니다. |
| `order_id` | 주문 번호 | 글자 | 예 | 처리한 주문의 번호입니다. 모의 실행이면 `s_`로 시작하는 임시 번호입니다. |
| `mode` | 실행 종류 | 글자 | 예 | `live`는 실제 손님 주문을 처리한 운영 실행, `dry_run`은 연습용 모의 실행입니다. |
| `workspace_id` | 연습 공간 번호 | 글자 | 아니오 | 모의 실행이 어느 연습 공간의 skill로 돌았는지 알려 줍니다. 운영 실행이면 `null`입니다. |
| `scenario_id` | 시험 주문 번호 | 글자 | 아니오 | 시험 주문으로 돌린 실행이면 그 번호, 아니면 `null`입니다. |
| `started_at` | 시작 시각 | 시각 | 예 | 처리를 시작한 시각입니다. |
| `finished_at` | 끝난 시각 | 시각 | 예 | 처리가 끝나거나 멈춘 시각입니다. |
| `skill_versions` | 쓰인 skill 버전 | 묶음 | 예 | 이 실행에 쓰인 skill별 버전입니다. 다시 재현하거나 버전을 비교할 때 기준이 됩니다. |
| `final_status` | 최종 상태 | 글자 | 예 | `SHIPPED` 정상 완료, `REJECTED` 업무 규칙에 따라 거절(재고 부족 등), `HOLD` 보류, `FAILED` 오류로 중단 가운데 하나입니다. |
| `steps` | 단계 기록 | 목록 | 예 | skill이 하나 돌 때마다 한 칸씩, 실행된 순서대로 들어 있습니다. |
| `steps[].step_no` | 단계 순번 | 정수 | 예 | 1부터 셉니다. |
| `steps[].skill` | skill 이름 | 글자 | 예 | 이 단계에서 돈 skill입니다. |
| `steps[].version` | skill 버전 | 정수 | 예 | 이 단계에서 쓰인 skill 버전입니다. |
| `steps[].input` | 받은 값 | 묶음 | 예 | 이 skill이 받은 값들입니다. 값 이름과 값의 묶음입니다. |
| `steps[].output` | 내놓은 값 | 묶음 | 예 | 이 skill이 내놓은 값들입니다. 오류로 멈췄으면 빈 묶음입니다. |
| `steps[].tools_called` | 부른 기능 | 목록 | 예 | 이 단계에서 부른 재고·결제·배송·알림 기능의 이름입니다. 이름은 D가 정합니다. |
| `steps[].status` | 단계 결과 | 글자 | 예 | `ok` 성공, `error` 오류, `skipped` 앞 단계가 실패해서 건너뜀 가운데 하나입니다. |
| `steps[].error` | 오류 내용 | 글자 | 아니오 | 결과가 `error`일 때 사람이 읽을 한 줄 설명입니다. 아니면 `null`입니다. |
| `steps[].duration_ms` | 걸린 시간 | 정수 | 예 | 1000분의 1초 단위입니다. |

### 예시

```json
{ "run_id": "r_8KQ2" }
```

```json
{
  "run_id": "r_8KQ2",
  "order_id": "o_1042",
  "mode": "live",
  "workspace_id": null,
  "scenario_id": null,
  "started_at": "2026-10-21T09:12:30+09:00",
  "finished_at": "2026-10-21T09:12:31+09:00",
  "skill_versions": { "order-flow": 1, "order-intake": 1, "inventory-check": 2, "payment": 1, "shipping-reserve": 1, "notify": 1 },
  "final_status": "FAILED",
  "steps": [
    { "step_no": 1, "skill": "order-intake", "version": 1, "input": { "order.id": "o_1042" }, "output": { "order.total": 39000 }, "tools_called": [], "status": "ok", "error": null, "duration_ms": 410 },
    { "step_no": 2, "skill": "inventory-check", "version": 2, "input": { "order.id": "o_1042", "order.items": [{ "sku": "SKU-001", "qty": 1 }] }, "output": { "inventory.예약가능수량": 12 }, "tools_called": ["reserve_stock"], "status": "ok", "error": null, "duration_ms": 520 },
    { "step_no": 3, "skill": "payment", "version": 1, "input": {}, "output": {}, "tools_called": [], "status": "error", "error": "required input inventory.reserved not found", "duration_ms": 12 },
    { "step_no": 4, "skill": "shipping-reserve", "version": 1, "input": {}, "output": {}, "tools_called": [], "status": "skipped", "error": null, "duration_ms": 0 }
  ]
}
```

### 읽고 쓰는 저장 공간

| 저장 공간 | 읽기·쓰기 | 무엇을 |
|---|---|---|
| `trace_run` | 읽기 | 실행 한 번의 전체 정보와 단계별 기록 |

### v0에서 바뀐 점

- `workspace`를 `workspace_id`로, `ms`를 `duration_ms`로 바꿨습니다. `mode` 값 `dry-run`은 `dry_run`으로 표기를 맞췄습니다.
- `steps[].step_no`와 `finished_at`을 더했습니다.

---

## 3. skill 목록 보기 · `list_skills`

지금 운영 중인 skill 여섯 개를 한 번에 보여 줍니다. skill마다 현재 버전, 관리하는 팀, 받는 값과 내놓는 값, 바로 앞에 실행되는 skill이 함께 나옵니다. 마지막으로 주문이 정상 처리됐을 때 쓰던 버전도 알려 주므로, 그 뒤에 어떤 skill이 바뀌었는지 바로 찾을 수 있습니다.

부르는 쪽: Agent · 담당: C-1

### 보내는 값

보내는 값은 없습니다. 빈 묶음 `{}`을 보냅니다.

### 돌려받는 값

| 필드 | 한글 이름 | 형식 | 꼭 필요 | 설명 |
|---|---|---|---|---|
| `skills` | skill 목록 | 목록 | 예 | 운영 중인 skill 전부입니다. 실행 순서대로 들어 있습니다. |
| `skills[].skill` | skill 이름 | 글자 | 예 | 폴더 이름과 같습니다. 예: `inventory-check` |
| `skills[].version` | 운영 버전 | 정수 | 예 | 지금 운영 중인 버전입니다. 가장 최근에 저장된 버전과 같습니다. |
| `skills[].last_working_version` | 마지막 정상 버전 | 정수 | 아니오 | 마지막으로 주문이 정상 처리됐을 때 쓰던 버전입니다. 운영 버전과 다르면 그 뒤에 바뀐 것이므로, 두 번호를 `diff_skill`에 넣어 무엇이 바뀌었는지 볼 수 있습니다. 정상 처리된 주문이 아직 없으면 `null`입니다. |
| `skills[].saved_at` | 저장 시각 | 시각 | 예 | 운영 버전이 저장된 시각입니다. |
| `skills[].saved_by` | 저장한 쪽 | 글자 | 예 | 담당자 이름, 승인된 수정안이 반영됐으면 `skillops`, 되돌렸으면 `rollback`입니다. |
| `skills[].owner` | 관리 팀 | 글자 | 예 | 이 skill을 고치는 업무 팀입니다. 예: `물류팀` |
| `skills[].description` | 한 줄 설명 | 글자 | 예 | skill이 하는 일입니다. |
| `skills[].inputs` | 받는 값 | 목록 | 예 | 이 skill이 받아야 하는 값 이름입니다. |
| `skills[].outputs` | 내놓는 값 | 목록 | 예 | 이 skill이 내놓는 값 이름입니다. |
| `skills[].depends_on` | 앞 skill | 목록 | 아니오 | 바로 앞에 실행돼야 하는 skill 이름입니다. 순서를 정하는 `order-flow`에는 없습니다. |
| `skills[].steps` | 실행 순서 | 목록 | 아니오 | `order-flow`에만 있는 칸입니다. 주문이 거치는 skill 이름을 순서대로 적은 목록입니다. |

### 예시

```json
{}
```

```json
{
  "skills": [
    {
      "skill": "inventory-check",
      "version": 2,
      "last_working_version": 1,
      "saved_at": "2026-10-21T09:05:10+09:00",
      "saved_by": "물류팀 김담당",
      "owner": "물류팀",
      "description": "주문 상품의 재고를 확인하고 예약한다.",
      "inputs": ["order.id", "order.items"],
      "outputs": ["inventory.예약가능수량"],
      "depends_on": ["order-intake"],
      "steps": null
    }
  ]
}
```

예시에는 skill 하나만 적었습니다. 실제로는 여섯 개가 모두 들어 있습니다.

### 읽고 쓰는 저장 공간

| 저장 공간 | 읽기·쓰기 | 무엇을 |
|---|---|---|
| `skill_versions` | 읽기 | skill마다 가장 최근 버전과 그 맨 위 칸 |
| `trace_run` | 읽기 | 최종 상태가 실패가 아닌 가장 최근 운영 실행의 skill 버전 |

### v0에서 바뀐 점

- 결과를 `skills`로 감쌌고 `name`을 `skill`로 바꿨습니다.
- `last_working_version`, `saved_at`, `saved_by`를 더했습니다.

---

## 4. 두 버전 비교하기 · `diff_skill`

같은 skill의 두 버전을 비교해서 바뀐 곳만 뽑아 줍니다. 바뀐 곳은 변경 하나하나로 나뉘고 각각 번호가 붙습니다. 변경마다 "그 변경만 담은 수정 내용"이 함께 오므로, 원인을 확인할 때 변경을 하나씩 다시 넣어 보는 데 그대로 쓸 수 있습니다.

부르는 쪽: Agent · 담당: C-1

### 보내는 값

| 필드 | 한글 이름 | 형식 | 꼭 필요 | 설명 |
|---|---|---|---|---|
| `skill` | skill 이름 | 글자 | 예 | 비교할 skill입니다. |
| `from_version` | 예전 버전 | 정수 | 예 | 비교의 기준이 되는 예전 버전입니다. 보통 `last_working_version`을 넣습니다. |
| `to_version` | 새 버전 | 정수 | 예 | 바뀐 뒤의 버전입니다. 보통 운영 버전을 넣습니다. |

### 돌려받는 값

| 필드 | 한글 이름 | 형식 | 꼭 필요 | 설명 |
|---|---|---|---|---|
| `skill` | skill 이름 | 글자 | 예 | 보낸 값 그대로입니다. |
| `from_version` | 예전 버전 | 정수 | 예 | 보낸 값 그대로입니다. |
| `to_version` | 새 버전 | 정수 | 예 | 보낸 값 그대로입니다. |
| `changes` | 변경 목록 | 목록 | 예 | 바뀐 곳 하나하나입니다. 파일 위쪽에 있는 변경부터 들어 있습니다. 바뀐 곳이 없으면 빈 목록입니다. |
| `changes[].change_no` | 변경 번호 | 정수 | 예 | 1부터 셉니다. 원인을 확정하면 이 번호를 제안서에 적습니다. |
| `changes[].part` | 바뀐 부분 | 글자 | 예 | `header`는 맨 위 칸(받는 값, 내놓는 값 등), `body`는 본문입니다. |
| `changes[].location` | 바뀐 줄 위치 | 글자 | 예 | 예를 들어 `@@ -5,1 +5,1 @@`는 "예전 버전 5번째 줄부터 1줄이 새 버전 5번째 줄부터 1줄로 바뀌었다"는 뜻입니다. |
| `changes[].before` | 바뀌기 전 글 | 글자 | 예 | 예전 버전의 해당 줄들입니다. 새로 추가만 된 곳이면 빈 글자입니다. |
| `changes[].after` | 바뀐 뒤 글 | 글자 | 예 | 새 버전의 해당 줄들입니다. 지워지기만 한 곳이면 빈 글자입니다. |
| `changes[].patch` | 이 변경만 담은 수정 내용 | 글자 | 예 | 예전 버전에 이 변경 하나만 넣는 수정 내용입니다. `apply_patch`에 그대로 넣으면 다른 변경은 빼고 이 변경만 적용됩니다. 형식은 통합 diff(어느 줄을 빼고 어느 줄을 넣을지 적은, 기계가 읽는 글)입니다. |

### 예시

```json
{ "skill": "inventory-check", "from_version": 1, "to_version": 2 }
```

```json
{
  "skill": "inventory-check",
  "from_version": 1,
  "to_version": 2,
  "changes": [
    {
      "change_no": 1,
      "part": "header",
      "location": "@@ -5,1 +5,1 @@",
      "before": "outputs:    [inventory.available, inventory.reserved]",
      "after": "outputs:    [inventory.예약가능수량]",
      "patch": "--- inventory-check/SKILL.md (v1)\n+++ inventory-check/SKILL.md (v2)\n@@ -5,1 +5,1 @@\n-outputs:    [inventory.available, inventory.reserved]\n+outputs:    [inventory.예약가능수량]\n"
    },
    {
      "change_no": 2,
      "part": "body",
      "location": "@@ -12,1 +12,1 @@",
      "before": "재고 수량이 주문 수량 이상이면 예약한다.",
      "after": "예약 가능 수량이 있으면 예약된 것으로 본다.",
      "patch": "--- inventory-check/SKILL.md (v1)\n+++ inventory-check/SKILL.md (v2)\n@@ -12,1 +12,1 @@\n-재고 수량이 주문 수량 이상이면 예약한다.\n+예약 가능 수량이 있으면 예약된 것으로 본다.\n"
    }
  ]
}
```

### 읽고 쓰는 저장 공간

| 저장 공간 | 읽기·쓰기 | 무엇을 |
|---|---|---|
| `skill_versions` | 읽기 | 두 버전의 원문 전체 |

### v0에서 바뀐 점

- `hunks`를 `changes`로, `index`를 `change_no`로, `header`를 `location`으로 바꿨습니다.
- `part`와 `patch`를 더했습니다. `patch`는 항상 `from_version`의 원문을 기준으로 만듭니다.

---

## 5. skill 본문 읽기 · `get_skill`

skill 하나의 SKILL.md 원문 전체를 가져옵니다. 버전 번호를 주면 그 시점의 내용을, 주지 않으면 지금 운영 중인 내용을 돌려줍니다. 바뀐 부분만으로는 앞뒤 사정을 알기 어려울 때, 또는 값을 받아 쓰는 skill이 실제로 어떻게 쓰여 있는지 볼 때 씁니다.

부르는 쪽: Agent · 담당: C-1

### 보내는 값

| 필드 | 한글 이름 | 형식 | 꼭 필요 | 설명 |
|---|---|---|---|---|
| `skill` | skill 이름 | 글자 | 예 | 읽을 skill입니다. |
| `version` | 버전 | 정수 | 아니오 | 읽을 버전입니다. 비우면 운영 버전을 돌려줍니다. |

### 돌려받는 값

| 필드 | 한글 이름 | 형식 | 꼭 필요 | 설명 |
|---|---|---|---|---|
| `skill` | skill 이름 | 글자 | 예 | 보낸 값 그대로입니다. |
| `version` | 버전 | 정수 | 예 | 실제로 돌려준 버전입니다. |
| `saved_at` | 저장 시각 | 시각 | 예 | 이 버전이 저장된 시각입니다. |
| `saved_by` | 저장한 쪽 | 글자 | 예 | `list_skills`의 같은 칸과 뜻이 같습니다. |
| `header` | 맨 위 칸 | 묶음 | 예 | 원문 맨 위의 정보를 풀어 놓은 것입니다. `description`, `inputs`, `outputs`, `depends_on`, `steps`, `owner`가 들어 있고, 뜻은 `list_skills`와 같습니다. |
| `full_text` | 원문 전체 | 글자 | 예 | SKILL.md 원문 그대로입니다. 맨 위 칸과 본문을 모두 포함합니다. 수정 내용의 줄 번호는 이 원문을 기준으로 셉니다. |

### 예시

```json
{ "skill": "payment" }
```

```json
{
  "skill": "payment",
  "version": 1,
  "saved_at": "2026-10-20T18:00:00+09:00",
  "saved_by": "재무팀 이담당",
  "header": {
    "description": "주문 금액을 결제한다. 결제 정책은 여기서 바꾼다.",
    "inputs": ["order.id", "order.total", "order.payment_method", "inventory.reserved"],
    "outputs": ["payment.id", "payment.status"],
    "depends_on": ["inventory-check"],
    "steps": null,
    "owner": "재무팀"
  },
  "full_text": "---\nname: payment\ndescription: 주문 금액을 결제한다. 결제 정책은 여기서 바꾼다.\n...\n---\n# 결제 절차\n1. 재고 예약(inventory.reserved)이 true 인 주문만 진행한다.\n..."
}
```

### 읽고 쓰는 저장 공간

| 저장 공간 | 읽기·쓰기 | 무엇을 |
|---|---|---|
| `skill_versions` | 읽기 | 해당 버전의 원문과 맨 위 칸 |

### v0에서 바뀐 점

- `name`을 `skill`로, `frontmatter`를 `header`로 바꿨습니다.
- v0의 `body`(본문만)를 `full_text`(원문 전체)로 바꿨습니다. 수정 내용의 줄 번호가 원문 전체를 기준으로 하기 때문입니다.
- `saved_at`, `saved_by`를 더했습니다.

---

## 6. 연결 관계 보기 · `get_dependency_graph`

어느 skill이 내놓은 값을 어느 skill이 받아 쓰는지 보여 줍니다. 받아야 할 값을 앞의 어떤 skill도 내놓지 않는 곳, 즉 연결이 끊긴 곳도 따로 알려 줍니다. 바뀐 부분이 다른 skill에 어떤 영향을 주는지, 고칠 때 어디까지 손대야 하는지 판단할 때 씁니다.

부르는 쪽: Agent · 담당: C-1 · 계산은 A의 skill 정보 검사 기능을 그대로 씁니다.

### 보내는 값

보내는 값은 없습니다. 빈 묶음 `{}`을 보냅니다. 지금 운영 중인 skill을 기준으로 계산합니다.

### 돌려받는 값

| 필드 | 한글 이름 | 형식 | 꼭 필요 | 설명 |
|---|---|---|---|---|
| `run_order` | 실행 순서 | 목록 | 예 | 주문이 거치는 skill 이름을 순서대로 적은 목록입니다. `order-flow`의 실행 순서와 같습니다. |
| `links` | 연결 목록 | 목록 | 예 | 앞 skill이 내놓은 값을 뒤 skill이 받아 쓰는 연결 하나하나입니다. |
| `links[].from_skill` | 내놓는 쪽 | 글자 | 예 | 값을 내놓는 skill입니다. 들어온 주문 자체에서 오는 값(`order.`으로 시작)이면 `order`라고 적습니다. |
| `links[].to_skill` | 받는 쪽 | 글자 | 예 | 그 값을 받아 쓰는 skill입니다. |
| `links[].value_names` | 오가는 값 | 목록 | 예 | 이 연결로 오가는 값 이름입니다. |
| `broken_links` | 끊긴 연결 | 목록 | 예 | 받아야 할 값을 앞의 어떤 skill도 내놓지 않는 곳입니다. 비어 있으면 연결이 모두 이어져 있다는 뜻입니다. |
| `broken_links[].skill` | 값을 못 받는 skill | 글자 | 예 | 필요한 값을 받지 못하는 skill입니다. |
| `broken_links[].missing_input` | 빠진 값 | 글자 | 예 | 받아야 하는데 아무도 내놓지 않는 값 이름입니다. |

### 예시

```json
{}
```

```json
{
  "run_order": ["order-intake", "inventory-check", "payment", "shipping-reserve", "notify"],
  "links": [
    { "from_skill": "order", "to_skill": "inventory-check", "value_names": ["order.id", "order.items"] },
    { "from_skill": "order-intake", "to_skill": "payment", "value_names": ["order.total"] }
  ],
  "broken_links": [
    { "skill": "payment", "missing_input": "inventory.reserved" }
  ]
}
```

### 읽고 쓰는 저장 공간

| 저장 공간 | 읽기·쓰기 | 무엇을 |
|---|---|---|
| `skill_versions` | 읽기 | skill마다 운영 버전의 맨 위 칸 |

### v0에서 바뀐 점

- `steps`를 `run_order`로, `edges`를 `links`로, `keys`를 `value_names`로, `violations`를 `broken_links`로 바꿨습니다.
- 연결의 양 끝 이름을 `from_skill`, `to_skill`로 바꿨습니다.

---

## 7. 연습 공간 만들기 · `create_workspace`

운영 중인 skill은 그대로 두고 복사본을 따로 만듭니다. skill마다 몇 번째 버전으로 시작할지 정할 수 있고, 정하지 않은 skill은 지금 운영 중인 버전으로 복사합니다. 원인을 확인할 때는 "담당자가 고치기 전 버전"으로, 후보를 시험할 때는 "지금 운영 중인 상태"로 만듭니다. 시험마다 새로 하나씩 만듭니다.

**주문 Agent에서 실제로 돌려 볼 연습 공간은 `snapshot_id`로 만듭니다.** 주문 Agent가 보관한 skill 묶음(`skills/` 아래 파일 전부, `resources/` 같은 SKILL.md가 아닌 파일 포함)을 그대로 복사하므로, 주문 Agent가 실행하는 글과 똑같은 파일로 시작합니다. 서비스의 운영 폴더(`skills/`)에 있는 SKILL.md는 주문 Agent가 실행하는 글과 달라서, 실제 실행기(9번 참고)는 `snapshot_id`로 만든 연습 공간만 받습니다.

부르는 쪽: Agent · 담당: C-2 · 구현됨(#37, #38, #52). `snapshot_id`는 #55에서 더했습니다.

### 보내는 값

| 필드 | 한글 이름 | 형식 | 꼭 필요 | 설명 |
|---|---|---|---|---|
| `start_versions` | 시작 버전 | 묶음 | 아니오 | skill 이름과 시작할 버전 번호의 묶음입니다. 적지 않은 skill은 운영 버전으로 시작합니다. 비우면 전부 운영 버전입니다. `snapshot_id`와 함께 쓸 수 없습니다. |
| `snapshot_id` | 시작 snapshot | 글자 | 아니오 | 주문 Agent가 보관한 skill 묶음의 번호(UUID)입니다. 주면 운영 폴더 대신 그 snapshot의 skill 파일 전부로 시작합니다. 실패한 주문에 기록된 `snapshot_id`나 시연용 snapshot 번호를 넣습니다. `start_versions`와 함께 쓸 수 없습니다. 둘 다 없으면 전부 운영 버전입니다. |

### 돌려받는 값

| 필드 | 한글 이름 | 형식 | 꼭 필요 | 설명 |
|---|---|---|---|---|
| `workspace_id` | 연습 공간 번호 | 글자 | 예 | 새로 만든 연습 공간의 번호입니다. 이 공간을 쓰는 다른 도구에 모두 이 번호를 넣습니다. |
| `skill_versions` | 시작한 버전 | 묶음 | 예 | 연습 공간 안의 skill 전부와 각각의 시작 버전입니다. `snapshot_id`로 만들었으면 각 SKILL.md 맨 위 칸의 `version` 값입니다. |
| `created_at` | 만든 시각 | 시각 | 예 | 연습 공간을 만든 시각입니다(UTC). |

### 예시

담당자가 고치기 전 버전으로 만드는 경우입니다.

```json
{ "start_versions": { "inventory-check": 1 } }
```

```json
{
  "workspace_id": "w_A17",
  "skill_versions": { "order-flow": 1, "order-intake": 1, "inventory-check": 1, "payment": 1, "shipping-reserve": 1, "notify": 1 },
  "created_at": "2026-10-21T00:13:05.123456Z"
}
```

주문 Agent의 snapshot으로 만드는 경우입니다. 결과 모양은 같습니다.

```json
{ "snapshot_id": "b6678449-9819-4d31-8c1b-13fc57892e5c" }
```

```json
{
  "workspace_id": "w_C31",
  "skill_versions": { "inventory-check": 1, "notify": 1, "order-flow": 1, "order-intake": 1, "payment": 1, "shipping-reserve": 1 },
  "created_at": "2026-10-21T04:11:52.402113Z"
}
```

그 snapshot이 주문 Agent에 없으면 오류를 돌려줍니다. `code`, `retryable`, `upstream_status`는 주문 Agent가 준 값이 그대로 들어갑니다.

```json
{ "code": "SNAPSHOT_NOT_FOUND", "message": "실행 자료를 확인할 수 없습니다: SNAPSHOT_NOT_FOUND", "retryable": false, "upstream_status": 404, "snapshot_id": "b6678449-9819-4d31-8c1b-13fc57892e5d" }
```

### 읽고 쓰는 저장 공간

| 저장 공간 | 읽기·쓰기 | 무엇을 |
|---|---|---|
| `skill_versions` | 읽기 | `start_versions`로 만들 때 시작 버전의 원문. 지금은 운영 폴더 `skills/`를 읽는 임시 구현이라 운영 버전만 줄 수 있습니다 |
| 주문 Agent의 snapshot | 읽기 | `snapshot_id`로 만들 때 `GET /api/snapshots/{snapshot_id}`와 파일 조회로 `skills/` 아래 파일을 전부 읽습니다. 주문 Agent에는 아무것도 쓰지 않습니다 |
| `workspaces` | 쓰기 | 연습 공간 한 줄(번호, 만든 시각, 상태 "사용 중", `skills` 칸에 skill별 시작 버전과 원문). `snapshot_id`로 만들었으면 `source_snapshot_id` 칸에 그 번호, `resources` 칸에 SKILL.md가 아닌 파일의 글을 함께 둡니다 |

### v0에서 바뀐 점

- `base`를 `start_versions`로, `ws_id`를 `workspace_id`로 바꿨습니다.
- `skill_versions`, `created_at`을 더했습니다.
- (#55) `snapshot_id`를 더했습니다. 주문 Agent에서 실제로 돌릴 연습 공간은 이 값으로 만듭니다.

---

## 8. 수정 적용하기 · `apply_patch`

연습 공간 안의 SKILL.md를 고칩니다. 고친 뒤에는 skill 사이의 연결이 끊기지 않았는지 검사해서, 끊긴 곳이 있으면 목록으로 알려 줍니다. 운영 중인 skill에는 아무 영향이 없습니다. 원인을 확인할 때는 변경 하나만 넣고, 후보를 시험할 때는 후보 수정안을 넣습니다. 고치는 대상은 SKILL.md뿐이고, `snapshot_id`로 만든 연습 공간의 `resources/` 같은 다른 파일은 그대로 둡니다.

부르는 쪽: Agent · 담당: C-2 · 구현됨(#55)

### 보내는 값

| 필드 | 한글 이름 | 형식 | 꼭 필요 | 설명 |
|---|---|---|---|---|
| `workspace_id` | 연습 공간 번호 | 글자 | 예 | 고칠 연습 공간입니다. |
| `patches` | 수정 내용 목록 | 목록 | 예 | 넣을 수정 내용입니다. 적힌 순서대로 적용합니다. |
| `patches[].skill` | 고칠 skill | 글자 | 예 | 고칠 skill 이름입니다. |
| `patches[].base_version` | 기준 버전 | 정수 | 예 | 이 수정 내용을 만들 때 기준으로 삼은 버전입니다. 연습 공간 안 그 skill의 시작 버전과 같아야 합니다. 다르면 엉뚱한 줄을 고칠 수 있어서 거부합니다. |
| `patches[].patch` | 수정 내용 | 글자 | 예 | `diff_skill`이 돌려준 `changes[].patch`를 그대로 넣거나, 같은 형식으로 Agent가 만든 수정 내용입니다. 통합 diff(`@@ -줄,개수 +줄,개수 @@`로 시작하는 덩어리)여야 하고, `---`·`+++` 머리줄은 없어도 됩니다. |

### 돌려받는 값

| 필드 | 한글 이름 | 형식 | 꼭 필요 | 설명 |
|---|---|---|---|---|
| `workspace_id` | 연습 공간 번호 | 글자 | 예 | 보낸 값 그대로입니다. |
| `changed_skills` | 고친 skill | 목록 | 예 | 실제로 고쳐진 skill입니다. 항목마다 `{skill, version}`입니다. |
| `changed_skills[].skill` | skill 이름 | 글자 | 예 | 고쳐진 skill의 이름입니다. |
| `changed_skills[].version` | 고친 뒤 버전 | 정수 | 예 | 시작 버전에 그 skill에 수정이 들어간 횟수를 더한 값입니다. 처음 한 번 고치면 시작 버전 + 1입니다. |
| `broken_links` | 끊긴 연결 | 목록 | 예 | 고친 뒤 연습 공간 안에서 끊긴 연결입니다. 모양과 뜻은 `get_dependency_graph`의 `broken_links`와 같습니다. 비어 있으면 연결이 모두 이어져 있습니다. |

수정 내용 가운데 하나라도 적용하지 못하면 전부 취소하고 오류를 돌려줍니다. 연습 공간은 보내기 전 상태 그대로 남습니다. 같은 skill에 수정을 여러 번 넣으면 앞의 수정이 들어간 글 위에 차례로 적용합니다. 끊긴 연결이 있어도 수정은 들어갑니다. 그 수정을 쓸지는 부른 쪽이 정합니다. 다만 고치고 나면 SKILL.md 맨 위 칸을 읽을 수 없게 되는 수정은 거부합니다.

**한 skill의 변경을 여러 개 한꺼번에 넣을 때.** 변경마다 따로 받은 수정 내용을 이어 붙여 `patches` 한 칸으로 보낼 수 있습니다. 변경의 줄 번호는 모두 "바뀌기 전 파일" 기준이라 그대로 이으면 됩니다. 다만 변경마다 붙어 온 `---`·`+++` 머리줄은 **첫 변경 것만 남기고** 지워야 합니다. 중간에 머리줄이 또 나오면 "지우는 줄"로 읽혀 `"2번째 줄 근처가 수정 내용과 맞지 않아 적용하지 못했습니다"`(`INVALID_INPUT`)로 거부됩니다. 참고로 지금 C-1이 구현한 `diff_skill`은 `changes[].patch` 대신 `hunks[].diff`를 돌려주고, 덩어리마다 `---`·`+++` 머리줄이 붙어 옵니다(4번과의 이름 맞춤은 아직입니다).

### 예시

원인 확인 단계에서 변경 1번만 넣는 경우입니다.

```json
{
  "workspace_id": "w_A17",
  "patches": [
    {
      "skill": "inventory-check",
      "base_version": 1,
      "patch": "--- inventory-check/SKILL.md (v1)\n+++ inventory-check/SKILL.md (v2)\n@@ -5,1 +5,1 @@\n-outputs:    [inventory.available, inventory.reserved]\n+outputs:    [inventory.예약가능수량]\n"
    }
  ]
}
```

```json
{
  "workspace_id": "w_A17",
  "changed_skills": [{ "skill": "inventory-check", "version": 2 }],
  "broken_links": [
    { "skill": "payment", "missing_input": "inventory.reserved" }
  ]
}
```

### 읽고 쓰는 저장 공간

| 저장 공간 | 읽기·쓰기 | 무엇을 |
|---|---|---|
| `workspaces` | 읽기·쓰기 | 연습 공간이 있고 아직 사용 중인지 확인하고, `skills` 칸의 원문을 읽어 고친 원문으로 바꿈 |

### v0에서 바뀐 점

- `ws_id`를 `workspace_id`로, `patches[].diff`를 `patches[].patch`로 바꿨습니다.
- 결과의 `applied`를 `changed_skills`로, `contract_violations`를 `broken_links`로 바꿨습니다.
- (#55) `changed_skills`는 이름만이 아니라 `{skill, version}` 목록입니다. 구현을 마쳤습니다.

---

## 9. 시험 주문 돌리기 · `run_scenarios`

연습 공간의 skill로 시험 주문을 모의 실행하고, 결과가 기대한 대로인지 채점합니다. 모의 실행이라 실제 결제나 배송은 일어나지 않습니다. 같은 주문을 정해진 횟수만큼 돌려서 모두 통과해야 합격입니다. AI는 고칠 방법을 제안만 하고, 합격 여부는 이 도구의 실행 결과로 정합니다.

부르는 쪽: Agent · 담당: C-2 · 구현됨(#37, #38, #52). 실제 실행기와 재현 주문(`replay_run_ids`)은 #55에서 더했습니다.

### 실행기는 둘 중 하나입니다

서비스를 켤 때 설정 `skillops.flow-runner`(환경 변수 `SKILLOPS_FLOW_RUNNER`)로 정합니다. 도구를 부르는 쪽은 어느 쪽인지 고를 수 없습니다.

| 실행기 | 설정값 | 어떻게 돌리나 | 쓸 곳 |
|---|---|---|---|
| 간이 실행기 | `contract-only` (기본) | SKILL.md 맨 위 칸(받는 값, 내놓는 값)만 보고 흉내 냅니다. 주문 Agent 없이 돌고, 재현 주문은 받지 않습니다 | 주문 Agent 없이 하는 개발과 단위 확인 |
| 실제 실행기 | `harness` | 연습 공간의 파일 전부를 후보 snapshot으로 주문 Agent에 올리고, 시험 주문마다 주문 Agent에서 모의 실행(`POST /api/dry-runs`)한 뒤 실행 기록(trace)을 읽어 채점합니다 | 후보가 실제 주문 Agent에서 고쳐지는지 판정 |

실제 실행기에서 알아 둘 점은 아래와 같습니다.

| 항목 | 내용 |
|---|---|
| 연습 공간 | `snapshot_id`로 만든 연습 공간만 받습니다(7번). 운영 폴더에서 만든 연습 공간은 `INVALID_INPUT`입니다 |
| 실제 주문에 주는 영향 | 없습니다. 모의 실행은 도는 동안 재고를 잡았다가 끝나면 되돌립니다. 모의 실행의 실패는 `list_failed_runs`(주문 Agent의 실패 목록)에 나오지 않습니다 |
| 돌리는 순서 | 재고를 잡기 때문에 시험 주문을 한 번에 하나씩 차례로 돌립니다 |
| 같은 파일 묶음 | 같은 연습 공간 파일을 다시 올리지 않고 먼저 올린 후보 snapshot을 씁니다 |
| 연결이 끊긴 skill이 있을 때 | 주문 Agent는 주문을 처리하기 전에 흐름 전체의 연결부터 검사합니다. 끊긴 곳이 하나라도 있으면, 그 주문이 어떤 길로 가든(재고가 없어 거절될 주문도) 단계 없이 `FAILED`로 끝납니다. 떨어진 이유에는 문제가 난 skill의 `CONTRACT_VIOLATION`과 빠진 값 이름이 나옵니다 |
| 주문 Agent에 닿지 못할 때 | `INTERNAL_ERROR`입니다 |

### 기준 주문 폴더

기준 주문은 서비스가 켜질 때 `<skillops.scenarios-dir>/golden/*.json`을 모두 읽어 둡니다. 폴더는 설정 `skillops.scenarios-dir`(환경 변수 `SKILLOPS_SCENARIOS_DIR`, 기본 `../scenarios`)로 바꿉니다. 파일 모양은 계약 3(`docs/contracts/scenario.schema.json`)과 같고, `expect`에서 비워 둔 칸은 채점하지 않습니다.

| 폴더 | 내용 | 어느 실행기에 맞나 |
|---|---|---|
| `scenarios/golden/` (기본) | D가 쓰는 기준 주문(지금은 g-01 하나, 목표는 g-01~g-06). 주문 Agent에 없는 상품(`SKU-APPLE-1KG` 등)을 쓰는 간이 실행기용 주문 | 간이 실행기 |
| `scenarios/order-agent/golden/` | 주문 Agent의 시연 규칙에 맞춘 g-01~g-03. g-01 랜턴 1개 정상 주문(`SHIPPED`, 배송 1건), g-02 머그컵 재고 없음(`REJECTED`), g-03 결제가 거절되는 고객 `c_declined`(`HOLD`) | 실제 실행기 |

주문 Agent에서 돌릴 때는 서비스를 이렇게 켭니다.

```bash
cd service
SKILLOPS_FLOW_RUNNER=harness SKILLOPS_SCENARIOS_DIR=../scenarios/order-agent ./gradlew bootRun
```

주문 Agent의 주소는 설정 `skillops.order-agent.base-url`(환경 변수 `ORDER_AGENT_BASE_URL`, 기본 `http://localhost:8081`)입니다.

### 재현 주문

주문 Agent에서 **실제로 실패했던 주문**을 그대로 다시 돌립니다. 실패한 실행의 `execution_id`(주문 Agent의 `GET /api/executions/failed`가 알려 줍니다)로 원래 주문(손님, 상품과 수량, 결제 방법, 배송)을 꺼내 모의 실행합니다. 넣는 방법은 둘 중 하나입니다.

| 넣는 곳 | 넣는 값 | 결과의 `scenario_id` |
|---|---|---|
| `scenario_ids` | `f-<execution_id>` (하이픈이 있는 UUID) | 넣은 그대로 |
| `replay_run_ids` | `execution_id` 또는 `run_id`(`r_` + 32자리 16진수) | `f-<execution_id>` |

재현 주문의 합격 기준은 **오류로 멈추지 않는 것**입니다. 최종 상태가 `FAILED`가 아니면(`SHIPPED`, `REJECTED`, `HOLD` 무엇이든) 통과이고, `extra_checks`는 재현 주문에도 똑같이 붙습니다. 결과는 `scenario_ids`에 넣은 순서대로, 그 뒤에 `replay_run_ids` 순서로 나옵니다. 재현 주문은 실제 실행기에서만 돌릴 수 있습니다.

### 보내는 값

| 필드 | 한글 이름 | 형식 | 꼭 필요 | 설명 |
|---|---|---|---|---|
| `workspace_id` | 연습 공간 번호 | 글자 | 예 | 어느 연습 공간의 skill로 돌릴지입니다. |
| `scenario_ids` | 시험 주문 번호 | 목록 | 아니오 | 돌릴 시험 주문의 번호입니다. 기준 주문은 `g-01`~`g-06`(주문 Agent용 폴더는 `g-01`~`g-03`), 재현 주문은 `f-<execution_id>`입니다. `scenario_ids`와 `replay_run_ids` 가운데 하나는 넣어야 합니다. |
| `replay_run_ids` | 재현할 실행 번호 | 목록 | 아니오 | 주문 Agent에서 실패했던 실행의 `execution_id` 또는 `run_id`입니다. 실제 실행기에서만 받습니다. |
| `extra_checks` | 추가 확인 조건 | 목록 | 아니오 | 이번 시험에만 덧붙이는 확인 조건입니다. 모든 시험 주문에 똑같이 붙으므로, 어떤 주문에서도 성립해야 하는 조건만 넣습니다. 담당자의 의도를 지켰는지 보는 조건을 여기에 넣습니다. |
| `repeat` | 반복 횟수 | 정수 | 아니오 | 같은 주문을 몇 번 돌릴지입니다(1~5). 비우면 2번입니다. AI가 처리하는 단계가 있어서 한 번의 통과는 우연일 수 있기 때문에 여러 번 돌립니다. |

확인 조건은 `값 이름 비교 값` 모양의 한 줄입니다. 비교는 `==`(같다), `!=`(다르다), `in`(목록 안에 있다) 세 가지만 씁니다. 예: `payment.status == 'PAID'`, `inventory.예약가능수량 != null`

입력은 주문을 하나라도 돌리기 전에 모두 검사합니다. 잘못된 확인 조건, 없는 시험 주문이나 연습 공간, 범위를 벗어난 `repeat`, 주문 Agent에 없는 재현할 실행은 `INVALID_INPUT` 오류로 돌려주고 주문은 돌리지 않습니다.

### 돌려받는 값

| 필드 | 한글 이름 | 형식 | 꼭 필요 | 설명 |
|---|---|---|---|---|
| `workspace_id` | 연습 공간 번호 | 글자 | 예 | 보낸 값 그대로입니다. |
| `all_passed` | 모두 합격 | 참·거짓 | 예 | 돌린 시험 주문이 전부 합격이면 `true`입니다. |
| `results` | 주문별 결과 | 목록 | 예 | 시험 주문 하나마다 한 칸입니다. |
| `results[].scenario_id` | 시험 주문 번호 | 글자 | 예 | 기준 주문이면 `g-NN`, 재현 주문이면 `f-<execution_id>`입니다. |
| `results[].replay_of_run_id` | 재현한 실행 번호 | 글자 | 아니오 | 재현 주문이면 원래 실패했던 실행 번호(넣은 값 그대로), 아니면 `null`입니다. |
| `results[].passed` | 합격 | 참·거짓 | 예 | 반복한 실행이 모두 통과했으면 `true`입니다. |
| `results[].run_ids` | 실행 번호 | 목록 | 예 | 반복할 때마다 생긴 모의 실행의 번호입니다. 제안서의 근거로 이 번호를 적습니다. 실제 실행기에서는 `r_` 뒤에 주문 Agent의 `execution_id`에서 하이픈을 뺀 글자가 붙고, 그 `execution_id`로 `GET /api/executions/{execution_id}/trace`에서 실행 기록을 볼 수 있습니다. |
| `results[].failures` | 떨어진 이유 | 목록 | 예 | 통과하지 못한 실행마다 한 칸입니다. 합격이면 빈 목록입니다. |
| `results[].failures[].run_id` | 실행 번호 | 글자 | 예 | 통과하지 못한 실행입니다. |
| `results[].failures[].skill` | 문제가 난 skill | 글자 | 아니오 | 문제가 처음 드러난 skill입니다. 최종 결과만 다른 경우처럼 특정할 수 없으면 `null`입니다. |
| `results[].failures[].reason` | 이유 | 글자 | 예 | 사람이 읽을 한국어 문장입니다. 여러 개면 `; `로 잇습니다. 예: `최종 상태 기대 SHIPPED, 실제 FAILED; 확인 조건 실패: inventory.예약가능수량 != null (실제 null)` |

**합격 기준.** 기준 주문은 기대 결과(거친 skill 순서, 최종 상태, 배송 건수, 확인 조건)와 모두 맞아야 하고, `extra_checks`도 통과해야 합니다. 재현 주문은 오류로 멈추지 않고 `extra_checks`를 통과하면 합격입니다. `extra_checks`는 모든 주문에 붙으므로, 한 후보가 의도 확인 조건 때문에 기준 주문까지 불합격으로 나올 수 있습니다. 그때 `failures[].reason`에는 `확인 조건 실패: …`만 있고 다른 이유는 없습니다.

모의 실행이 실패해도 실패 알림은 보내지 않고, `list_failed_runs`에도 나오지 않습니다.

### 예시

후보를 시험하는 경우입니다. 예시에는 결과 두 칸만 적었습니다.

```json
{
  "workspace_id": "w_B22",
  "scenario_ids": ["g-01", "g-02"],
  "extra_checks": ["inventory.예약가능수량 != null"],
  "repeat": 2
}
```

```json
{
  "workspace_id": "w_B22",
  "all_passed": false,
  "results": [
    { "scenario_id": "g-01", "replay_of_run_id": null, "passed": true, "run_ids": ["r_D301", "r_D302"], "failures": [] },
    { "scenario_id": "g-02", "replay_of_run_id": null, "passed": false, "run_ids": ["r_D303", "r_D304"], "failures": [
      { "run_id": "r_D303", "skill": "payment", "reason": "payment 단계 오류: required input inventory.reserved not found; 최종 상태 기대 SHIPPED, 실제 FAILED" }
    ] }
  ]
}
```

실제 실행기에서 실패했던 주문을 재현하는 경우입니다. 연습 공간은 `snapshot_id`로 만들어 `apply_patch`로 후보를 넣은 상태입니다. 결과는 `c2_real_judge.py` 실험에서 후보 (나) repaired를 돌린 것입니다.

```json
{
  "workspace_id": "w_L0yTjI63",
  "scenario_ids": ["g-01", "g-02", "g-03", "f-5c7a8c4a-f846-4d1c-a81e-ea648893343a"],
  "extra_checks": ["inventory.reservable_quantity != null"],
  "repeat": 1
}
```

```json
{
  "workspace_id": "w_L0yTjI63",
  "all_passed": true,
  "results": [
    { "scenario_id": "g-01", "replay_of_run_id": null, "passed": true, "run_ids": ["r_8e45845774f141f59a5420bcbe2fdbc0"], "failures": [] },
    { "scenario_id": "g-02", "replay_of_run_id": null, "passed": true, "run_ids": ["r_62037bf3ca86416fa80d1bb96d52b852"], "failures": [] },
    { "scenario_id": "g-03", "replay_of_run_id": null, "passed": true, "run_ids": ["r_c39fbef1932641bc9fd751f156b7e229"], "failures": [] },
    { "scenario_id": "f-5c7a8c4a-f846-4d1c-a81e-ea648893343a", "replay_of_run_id": "5c7a8c4a-f846-4d1c-a81e-ea648893343a", "passed": true, "run_ids": ["r_690d2a05a07749c9addc252e9964c395"], "failures": [] }
  ]
}
```

잘못된 확인 조건을 보낸 경우의 오류입니다.

```json
{ "code": "INVALID_INPUT", "message": "확인 조건을 읽을 수 없습니다: payment.status = PAID (예: payment.status == 'PAID')", "retryable": false, "upstream_status": null, "snapshot_id": null }
```

### 읽고 쓰는 저장 공간

| 저장 공간 | 읽기·쓰기 | 무엇을 |
|---|---|---|
| `workspaces` | 읽기 | 어떤 skill 원문으로 돌릴지(`skills` 칸). 실제 실행기는 `resources` 칸과 `source_snapshot_id` 칸도 읽습니다 |
| `scenarios` | 읽기 | 서비스가 켜질 때 `<SKILLOPS_SCENARIOS_DIR>/golden/*.json`에서 불러 둔 기준 주문의 내용과 기대 결과. 재현 주문은 저장하지 않고 부를 때마다 주문 Agent의 원래 주문으로 만듭니다 |
| 주문 Agent (실제 실행기만) | 읽기·쓰기 | 후보 snapshot 올리기(`POST /api/snapshots/candidates`), 모의 실행(`POST /api/dry-runs`), 실행 기록 읽기(`GET /api/executions/{execution_id}/trace`), 재현할 원래 주문 꺼내기. 모의 실행 기록은 주문 Agent 쪽에 남고 실제 재고와 실패 목록은 바뀌지 않습니다 |
| `trace_run`, `orders`, `order_items` | 읽기 | 간이 실행기 설계에서 재현할 원래 실행의 주문 내용을 읽는 곳. 실제 실행기는 이 저장 공간 대신 주문 Agent에서 읽습니다 |
| `scenario_results` | 쓰기 | 설계상 모의 실행마다 합격 여부와 떨어진 이유를 남기는 곳. 아직 저장하지 않고 결과를 도구 응답으로만 돌려줍니다 |

### v0에서 바뀐 점

- `ws_id`를 `workspace_id`로, `k`를 `repeat`로, `runs`를 `run_ids`로 바꿨습니다. `failures[]`의 `step`은 `skill`로, `error`는 `reason`으로 바꿨습니다.
- `extra_checks`, `all_passed`, `replay_of_run_id`를 더했고, 결과를 `results`로 감쌌습니다.
- `replay_run_ids`는 #55에서 더했습니다. 재현 주문의 `replay_of_run_id`는 원래 실패했던 실행 번호입니다.
- (#55) 실행기를 설정으로 고르게 했습니다(간이 `contract-only`, 실제 `harness`). 기준 주문 폴더는 `SKILLOPS_SCENARIOS_DIR`로 바꿉니다.

---

## 10. 제안서 저장하기 · `save_proposal`

진단 결과를 수정 제안서로 저장합니다. 제안서에는 확정된 원인, 담당자의 의도, 후보 수정안과 각각의 시험 결과, 추천 순위가 들어갑니다. 근거로 삼은 시험 실행 번호가 없거나 실제와 맞지 않으면 저장을 거부합니다. 시험을 거치지 않은 수정안이 운영자 화면에 올라가는 일을 막기 위해서입니다. 돌려주는 모양은 계약 4(제안서)와 같습니다.

부르는 쪽: Agent · 담당: C-2

### 보내는 값

제안서 번호, 저장 시각, 상태는 서비스가 붙이므로 보내지 않습니다.

| 필드 | 한글 이름 | 형식 | 꼭 필요 | 설명 |
|---|---|---|---|---|
| `proposal` | 제안서 | 묶음 | 예 | 아래 칸들을 담은 묶음입니다. |
| `proposal.trigger` | 진단을 시작한 알림 | 묶음 | 예 | 어떤 알림으로 진단을 시작했는지입니다. |
| `proposal.trigger.type` | 알림 종류 | 글자 | 예 | `failure`는 주문 실패 알림, `skill_saved`는 skill 저장 알림입니다. |
| `proposal.trigger.run_id` | 실패한 실행 번호 | 글자 | 아니오 | 주문 실패 알림이면 그 실행 번호, 아니면 `null`입니다. |
| `proposal.trigger.skill` | 저장된 skill | 글자 | 아니오 | skill 저장 알림이면 그 skill 이름, 아니면 `null`입니다. |
| `proposal.trigger.version` | 저장된 버전 | 정수 | 아니오 | skill 저장 알림이면 그 버전, 아니면 `null`입니다. |
| `proposal.root_cause` | 확정된 원인 | 묶음 | 예 | 원인 확인 단계에서 실행으로 확정한 원인입니다. |
| `proposal.root_cause.skill` | 원인 skill | 글자 | 예 | 원인이 된 변경이 들어 있는 skill입니다. |
| `proposal.root_cause.from_version` | 예전 버전 | 정수 | 예 | 비교한 예전 버전입니다. |
| `proposal.root_cause.to_version` | 새 버전 | 정수 | 예 | 비교한 새 버전입니다. |
| `proposal.root_cause.change_no` | 원인 변경 번호 | 정수 | 예 | `diff_skill`이 매긴 변경 번호 가운데 원인으로 확정된 것입니다. |
| `proposal.root_cause.summary` | 원인 요약 | 글자 | 예 | 운영자가 읽을 한두 문장입니다. |
| `proposal.root_cause.proof_run_ids` | 원인 증거 | 목록 | 예 | 이 변경만 넣었을 때 실패가 다시 나타난 모의 실행 번호입니다. 하나 이상 있어야 합니다. |
| `proposal.intent` | 담당자의 의도 | 묶음 | 예 | 담당자가 이 변경으로 바꾸려던 업무 규칙입니다. 맞는지는 승인 화면에서 담당자가 확인합니다. |
| `proposal.intent.text` | 의도 한 문장 | 글자 | 예 | 담당자의 의도를 한 문장으로 정리한 것입니다. |
| `proposal.intent.checks` | 의도 확인 조건 | 목록 | 예 | 의도가 지켜졌는지 보는 확인 조건입니다. 시험할 때 `extra_checks`에 넣은 것과 같아야 합니다. |
| `proposal.candidates` | 후보 수정안 | 목록 | 예 | 시험한 후보 전부입니다. 떨어진 후보도 넣습니다. |
| `proposal.candidates[].candidate_no` | 후보 번호 | 정수 | 예 | 1부터 셉니다. |
| `proposal.candidates[].kind` | 고치는 범위 | 글자 | 예 | `same_skill`은 바뀐 skill만 고침, `linked_skills`는 값을 주고받는 skill까지 함께 맞춤, `run_order`는 실행 순서를 다시 짬입니다. |
| `proposal.candidates[].workspace_id` | 시험한 연습 공간 | 글자 | 예 | 이 후보를 시험한 연습 공간입니다. 승인되면 이 공간이 운영에 반영됩니다. |
| `proposal.candidates[].patches` | 수정 내용 | 목록 | 예 | 이 후보의 수정 내용입니다. 모양은 `apply_patch`의 `patches`와 같습니다. |
| `proposal.candidates[].results` | 기준별 결과 | 묶음 | 예 | 아래 다섯 기준의 결과입니다. |
| `proposal.candidates[].results.fixes_failure` | 실패가 고쳐졌나 | 참·거짓 | 예 | 재현 주문이 모두 합격했으면 `true`입니다. |
| `proposal.candidates[].results.keeps_old_orders` | 잘 되던 주문이 그대로인가 | 묶음 | 예 | 기준 주문 가운데 합격한 수(`passed`)와 전체 수(`total`)입니다. |
| `proposal.candidates[].results.keeps_intent` | 의도가 지켜졌나 | 참·거짓 | 예 | 의도 확인 조건을 모두 통과했으면 `true`입니다. |
| `proposal.candidates[].results.no_broken_links` | 연결이 모두 이어졌나 | 참·거짓 | 예 | `apply_patch`가 돌려준 끊긴 연결이 비어 있었으면 `true`입니다. |
| `proposal.candidates[].results.changed_skill_count` | 손댄 skill 수 | 정수 | 예 | 이 후보가 고친 skill의 개수입니다. 적을수록 좋습니다. |
| `proposal.candidates[].evidence_run_ids` | 근거 실행 번호 | 목록 | 예 | 이 후보를 시험한 모의 실행 번호입니다. 하나 이상 있어야 합니다. |
| `proposal.candidates[].rank` | 순위 | 정수 | 아니오 | 합격한 후보에만 1부터 붙입니다. 떨어진 후보는 `null`입니다. |
| `proposal.candidates[].explanation` | 설명 | 글자 | 예 | 운영자에게 보여 줄 설명입니다. 근거로는 `evidence_run_ids`에 있는 실행 번호만 인용합니다. |
| `proposal.recommended_candidate_no` | 추천 후보 번호 | 정수 | 아니오 | 추천하는 후보의 번호입니다. 합격한 후보가 없으면 `null`이고, 그러면 제안서 상태가 "쓸 수 있는 수정안 없음"이 됩니다. |

### 돌려받는 값

| 필드 | 한글 이름 | 형식 | 꼭 필요 | 설명 |
|---|---|---|---|---|
| `proposal_id` | 제안서 번호 | 글자 | 예 | 저장된 제안서의 번호입니다. 운영자 화면과 승인 API가 이 번호를 씁니다. |
| `status` | 제안서 상태 | 글자 | 예 | `proposed`는 승인을 기다림, `no_valid_fix`는 쓸 수 있는 수정안이 없음입니다. |
| `valid_candidate_nos` | 합격 후보 번호 | 목록 | 예 | 서비스가 다시 계산한 합격 후보입니다. 실패가 고쳐지고, 잘 되던 주문이 모두 통과하고, 의도가 지켜진 후보만 들어갑니다. |

**저장을 거부하는 경우**

- `proof_run_ids`나 `evidence_run_ids`가 비어 있을 때
- 근거로 적은 실행 번호가 없거나, 모의 실행이 아니거나, 그 후보의 연습 공간에서 돌린 것이 아닐 때
- `recommended_candidate_no`가 합격 후보가 아닐 때

### 예시

후보 두 개 가운데 하나만 적었습니다.

```json
{
  "proposal": {
    "trigger": { "type": "failure", "run_id": "r_8KQ2", "skill": null, "version": null },
    "root_cause": {
      "skill": "inventory-check",
      "from_version": 1,
      "to_version": 2,
      "change_no": 1,
      "summary": "재고 확인 skill이 '예약 여부'를 더 이상 내놓지 않아 결제 skill이 멈췄습니다.",
      "proof_run_ids": ["r_C201", "r_C202"]
    },
    "intent": {
      "text": "재고 수량이 아니라 예약 가능 수량으로 판단하고 싶다.",
      "checks": ["inventory.예약가능수량 != null"]
    },
    "candidates": [
      {
        "candidate_no": 1,
        "kind": "linked_skills",
        "workspace_id": "w_B22",
        "patches": [{ "skill": "inventory-check", "base_version": 2, "patch": "--- ...\n+++ ...\n@@ -5,1 +5,1 @@\n-outputs:    [inventory.예약가능수량]\n+outputs:    [inventory.예약가능수량, inventory.reserved]\n" }],
        "results": { "fixes_failure": true, "keeps_old_orders": { "passed": 6, "total": 6 }, "keeps_intent": true, "no_broken_links": true, "changed_skill_count": 1 },
        "evidence_run_ids": ["r_D301", "r_D302", "r_D313", "r_D314"],
        "rank": 1,
        "explanation": "재고 확인 skill이 '예약 가능 수량'과 함께 '예약 여부'도 내놓게 했습니다. 실패했던 주문(r_D313, r_D314)과 기준 주문 6건이 모두 통과했습니다."
      }
    ],
    "recommended_candidate_no": 1
  }
}
```

```json
{ "proposal_id": "p_0007", "status": "proposed", "valid_candidate_nos": [1] }
```

### 읽고 쓰는 저장 공간

| 저장 공간 | 읽기·쓰기 | 무엇을 |
|---|---|---|
| `trace_run`, `scenario_results` | 읽기 | 근거 실행이 실제로 있는지, 어느 연습 공간에서 돌았는지, 합격했는지 |
| `proposals` | 쓰기 | 제안서 한 줄(알림, 원인, 의도, 추천 후보, 상태) |
| `proposal_candidates` | 쓰기 | 후보마다 한 줄 |

### v0에서 바뀐 점

- 제안서 번호와 저장 시각은 서비스가 붙입니다. `intent.confirmed`는 Agent가 보내지 않고 승인할 때 채워집니다.
- 이름 변경은 위의 "이름만 바꾼 것" 표의 계약 4 부분을 보세요.
- 결과에 `status`와 `valid_candidate_nos`를 더했습니다.

---

## 11. 운영에 반영하기 · `promote_workspace`

시험을 통과한 연습 공간의 내용을 실제 운영 중인 skill로 옮깁니다. 운영자가 승인할 때 발급되는 승인 번호가 있어야만 실행되므로, Agent 혼자서는 운영을 바꿀 수 없습니다. 반영한 직후 기준 주문 하나를 모의 실행해 점검하고, 실패하면 곧바로 반영 전 버전으로 되돌립니다.

부르는 쪽: **서비스** (운영자가 화면에서 승인했을 때). Agent는 부르지 않습니다. · 담당: C-2

### 보내는 값

| 필드 | 한글 이름 | 형식 | 꼭 필요 | 설명 |
|---|---|---|---|---|
| `workspace_id` | 연습 공간 번호 | 글자 | 예 | 반영할 연습 공간입니다. |
| `approval_code` | 승인 번호 | 글자 | 예 | 승인 API가 발급한 번호입니다. 한 번만 쓸 수 있습니다. |

### 돌려받는 값

| 필드 | 한글 이름 | 형식 | 꼭 필요 | 설명 |
|---|---|---|---|---|
| `workspace_id` | 연습 공간 번호 | 글자 | 예 | 보낸 값 그대로입니다. |
| `proposal_id` | 제안서 번호 | 글자 | 예 | 승인된 제안서입니다. |
| `live_skills` | 반영된 skill | 목록 | 예 | 운영에 새로 저장된 skill과 그 새 버전입니다. 점검이 실패해 되돌렸으면 되돌린 뒤의 버전입니다. |
| `live_skills[].skill` | skill 이름 | 글자 | 예 | 반영된 skill입니다. |
| `live_skills[].version` | 새 버전 | 정수 | 예 | 반영되면서 붙은 새 버전 번호입니다. |
| `check` | 반영 직후 점검 | 묶음 | 예 | 반영한 직후 운영 중인 skill로 기준 주문 `g-01`을 모의 실행한 결과입니다. |
| `check.scenario_id` | 점검 주문 번호 | 글자 | 예 | 점검에 쓴 시험 주문입니다. |
| `check.run_id` | 점검 실행 번호 | 글자 | 예 | 점검 실행의 번호입니다. |
| `check.passed` | 점검 통과 | 참·거짓 | 예 | 점검이 통과했으면 `true`입니다. |
| `rolled_back` | 되돌림 | 참·거짓 | 예 | 점검이 실패해서 반영 전 버전으로 되돌렸으면 `true`입니다. |

**거부하는 경우:** 승인 번호가 없거나 이미 쓴 번호일 때, 승인된 후보의 연습 공간과 `workspace_id`가 다를 때

### 예시

```json
{ "workspace_id": "w_B22", "approval_code": "a_5XQ9" }
```

```json
{
  "workspace_id": "w_B22",
  "proposal_id": "p_0007",
  "live_skills": [{ "skill": "inventory-check", "version": 3 }],
  "check": { "scenario_id": "g-01", "run_id": "r_E401", "passed": true },
  "rolled_back": false
}
```

### 읽고 쓰는 저장 공간

| 저장 공간 | 읽기·쓰기 | 무엇을 |
|---|---|---|
| `approvals` | 읽기·쓰기 | 승인 번호 확인, 쓴 시각 기록 |
| `proposal_candidates` | 읽기 | 승인된 후보의 연습 공간이 맞는지 |
| `workspaces` | 읽기 | 반영할 원문(`skills` 칸) |
| `skill_versions` | 쓰기 | 새 버전 저장. 운영 원본 파일 `skills/<이름>/SKILL.md`도 함께 덮어씁니다 |
| `trace_run`, `scenario_results` | 쓰기 | 반영 직후 점검 실행의 기록과 결과 |
| `proposals` | 쓰기 | 상태를 "반영됨" 또는 "되돌림"으로 바꿈 |
| `workspaces` | 쓰기 | 상태를 "반영됨"으로 바꿈 |

### v0에서 바뀐 점

- `approval_token`을 `approval_code`로, `promoted`를 `live_skills`로 바꿨습니다.
- `proposal_id`, `check`, `rolled_back`을 더했고, 부르는 쪽을 서비스로 정했습니다.

---

## 12. 이전 버전으로 되돌리기 · `rollback`

skill 하나를 지정한 예전 버전의 내용으로 되돌립니다. 예전 내용을 그대로 복사해 새 버전으로 저장하기 때문에 버전 번호는 계속 올라가고, 되돌린 기록도 남습니다. 예를 들어 3번 버전에서 1번으로 되돌리면 1번의 내용이 4번 버전으로 저장됩니다. 운영에 반영한 뒤 문제가 생겼을 때 쓰는 안전장치입니다.

부르는 쪽: **서비스** (반영 직후 점검이 실패했을 때 자동으로, 또는 운영자가 화면에서). Agent는 부르지 않습니다. · 담당: C-2

### 보내는 값

| 필드 | 한글 이름 | 형식 | 꼭 필요 | 설명 |
|---|---|---|---|---|
| `skill` | skill 이름 | 글자 | 예 | 되돌릴 skill입니다. |
| `to_version` | 되돌릴 버전 | 정수 | 예 | 이 버전의 내용으로 되돌립니다. 지금 운영 버전보다 작아야 합니다. |

### 돌려받는 값

| 필드 | 한글 이름 | 형식 | 꼭 필요 | 설명 |
|---|---|---|---|---|
| `skill` | skill 이름 | 글자 | 예 | 보낸 값 그대로입니다. |
| `restored_version` | 되살린 버전 | 정수 | 예 | 내용을 가져온 예전 버전입니다. 보낸 `to_version`과 같습니다. |
| `new_version` | 새 버전 | 정수 | 예 | 되살린 내용이 저장된 새 버전 번호입니다. 이제 이 버전이 운영 버전입니다. |

### 예시

```json
{ "skill": "inventory-check", "to_version": 1 }
```

```json
{ "skill": "inventory-check", "restored_version": 1, "new_version": 4 }
```

### 읽고 쓰는 저장 공간

| 저장 공간 | 읽기·쓰기 | 무엇을 |
|---|---|---|
| `skill_versions` | 읽기·쓰기 | 예전 버전의 원문을 읽고, 새 버전으로 저장. 운영 원본 파일도 함께 덮어씁니다 |

### v0에서 바뀐 점

- `version`을 `to_version`으로 바꿨습니다. 결과를 `restored_version`과 `new_version`으로 나눴습니다.
- 되돌리기도 새 버전으로 저장한다는 규칙을 정했습니다.

---

## 저장 공간 목록

도구 12개가 읽고 쓰는 저장 공간(데이터베이스 테이블)은 아래 10개입니다. 실행 기록은 C-1이 `feat/#12_backend-trace-store`에서 만든 실제 구조(`trace_run` 한 테이블에 단계 기록을 묶어 저장)를 따랐습니다. "관리 담당"은 그 저장 공간의 칸 구성과 저장 기능을 책임지는 사람입니다. 다른 사람은 관리 담당이 만든 기능을 불러서 씁니다.

| 저장 공간 | 한글 이름 | 들어 있는 것 | 관리 담당 | 쓰는 때 | 읽는 도구 |
|---|---|---|---|---|---|
| `skill_versions` | skill 버전 보관함 | skill을 저장할 때마다 한 줄. 원문 전체, 맨 위 칸, 저장 시각, 저장한 쪽 | C-1 | 화면에서 저장할 때(A), 운영 반영과 되돌리기(C-2) | `list_skills`, `get_skill`, `diff_skill`, `get_dependency_graph`, `create_workspace`, `rollback` |
| `orders` | 주문 | 손님 주문 한 건. 손님, 결제 방법, 배송 국가, 금액, 최종 상태 | D | 주문이 들어올 때 | `run_scenarios`(재현 주문) |
| `order_items` | 주문 상품 | 주문에 담긴 상품과 수량 | D | 주문이 들어올 때 | `run_scenarios`(재현 주문) |
| `trace_run` | 실행 기록 | 주문을 한 번 처리할 때마다 한 줄. 운영과 모의 구분, 연습 공간, 시험 주문, skill 버전, 최종 상태가 들어 있고, 단계별 기록(받은 값, 내놓은 값, 부른 기능, 결과, 오류, 걸린 시간)은 한 칸에 묶어 둡니다 | C-1 | A의 실행기가 처리를 끝낼 때 한 번에 | `list_failed_runs`, `get_trace`, `list_skills`, `run_scenarios`, `save_proposal` |
| `scenarios` | 시험 주문 | 기준 주문 6건(D가 쓴 파일에서 불러옴)과 재현 주문. 주문 내용과 기대 결과 | C-2 | 서비스가 켜질 때(기준 주문), `run_scenarios`가 재현 주문을 만들 때 | `run_scenarios` |
| `scenario_results` | 채점 결과 | 모의 실행 하나마다 합격 여부와 떨어진 이유 | C-2 | `run_scenarios`가 채점할 때, 반영 직후 점검 | `save_proposal` |
| `workspaces` | 연습 공간 | 연습 공간 하나에 한 줄. 만든 시각, 상태(사용 중, 반영됨, 버림), 그리고 skill별 시작 버전과 원문을 한 칸(`skills`)에 묶어 둡니다. 수정이 들어가면 원문이 바뀝니다 | C-2 | 만들 때, 수정을 넣을 때, 반영할 때 | `apply_patch`, `run_scenarios`, `promote_workspace` |
| `proposals` | 수정 제안서 | 제안서 하나에 한 줄. 알림, 원인, 의도, 의도 확인 여부, 추천 후보, 상태 | C-2 | 저장할 때, 승인·반영·되돌림으로 상태가 바뀔 때 | `promote_workspace`, 화면 |
| `proposal_candidates` | 후보 수정안 | 후보 하나에 한 줄. 고치는 범위, 연습 공간, 수정 내용, 기준별 결과, 근거 실행 번호, 순위, 설명 | C-2 | 제안서를 저장할 때 | `promote_workspace`, 화면 |
| `approvals` | 승인 기록 | 승인 한 번에 한 줄. 승인 번호, 제안서와 후보 번호, 의도 확인 여부, 승인 시각, 사용 시각 | A | 화면에서 승인할 때(A), 운영 반영이 번호를 쓸 때(C-2) | `promote_workspace` |

재고표, 손님 목록, 국가별 배송 기간 같은 가짜 외부 데이터는 D가 정합니다. 도구가 직접 읽지 않으므로 이 목록에서는 뺐습니다.

## 다음 단계

저장 공간마다 칸 이름, 형식, 고유 번호(기본 키), 다른 저장 공간과의 연결(외래 키)을 정하는 상세 ERD를 이어서 설계합니다. 이 문서의 필드 이름이 확정되면 그 칸 이름도 같은 이름을 씁니다.
