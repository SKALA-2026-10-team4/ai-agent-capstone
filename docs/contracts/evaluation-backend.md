# 계약 6 · 백엔드 평가·비교 v1.0

2026-10-26 · #88 구현 기준. 구현은 `service/.../evaluation/`, `mcp/EvaluationTools.java`, `proposal/VerifiedProposal*.java`에 있다. [설계 초안](../evaluation-backend-contract-design.md) 중 아래 지원 범위를 구현했다. 초안의 모든 기능이 구현된 것으로 해석하지 않는다.

## 지원 범위

- 불변 snapshot 두 개와 등록된 평가셋을 고정하고 비동기 평가한다. Python·LLM·자동 수정 경로는 호출하지 않는다.
- `CONTRACT_ONLY/NONE`: 고정 모의 실행. 결과가 PASS여도 승인 근거는 아니다.
- `ORDER_HARNESS/FIXED`: **명시적으로 설정한 격리 주문 서버**에서 실제 dry-run과 trace로 업무 기능을 검증한다.
- 실모델 OPENAI는 내부 예산·중단 제어 미연결로 422 `BUDGET_ENFORCEMENT_UNSUPPORTED`다. 별도 description 선택기·STEP 선택 지표도 연결하지 않았다.
- 신규 Skill의 arm별 기대를 저장·비교할 수 있지만, NEW_SKILL을 포함한 후보를 호출 품질 검증 완료로 승인하는 기능은 422 `EVALUATION_CAPABILITY_UNSUPPORTED`다.
- 안전성은 `UNMEASURABLE`로 반환한다. `SERVER_VERIFIED`는 `order-functional-v1+fixed-policy-v1` 정책의 **fixed 기능 검증**이며 실모델 품질·안전성 검증을 뜻하지 않는다.

## 인증과 REST

새 REST는 Bearer JWT가 필요하다. 조회는 로그인 사용자, 등록·검토·접수·취소·재실행·제안 생성은 OPERATOR만 가능하다. 기존 인증·승인 API 모양은 유지한다.

| 경로 | 입출력 |
|---|---|
| POST `/api/evaluation-sets` | SetInput → SetView(200), 처음에는 reviewed=false |
| GET `/api/evaluation-sets/{id}` | `{metadata:SetView, definition:SetInput}` |
| POST `/api/evaluation-sets/{id}/review` | 검토자 이름을 인증 주체에서 기록 → SetView |
| POST `/api/evaluations` | Submit, `Idempotency-Key` 필수 → Accepted(202) |
| GET `/api/evaluations?page=0&limit=20` | `{items:[View], next_page:int|null}`. limit 1~50 |
| GET `/api/evaluations/{id}` | 최신 시도 View |
| GET `/api/evaluations/{id}/attempts` | 시도 목록 `[{attempt_id,parent_attempt_id,status,reason,created_at}]` |
| GET `/api/evaluations/{id}/attempts/{attempt}/cases?offset=0&limit=20` | `{items:[Trial],next_offset:int|null}`. DB 범위 조회, limit 1~50 |
| GET `/api/evaluations/{id}/attempts/{attempt}/report` | Report. 실행 중에는 부분 결과·INCONCLUSIVE |
| POST `/api/evaluations/{id}/attempts/{attempt}/cancel` | 취소 접수 Accepted(202) |
| POST `/api/evaluations/{id}/attempts/{attempt}/retries` | `Idempotency-Key` 필수 → 새 Accepted(202) |
| POST `/api/verified-proposals` | VerifiedInput → Saved(200). 실제 서버 검증 완료 후보만 허용 |

Accepted는 `{evaluation_id,attempt_id,status,status_url}`다. 중복 접수는 같은 첫 시도 ID를 반환하며 status는 현재 상태일 수 있다. 사용자와 키가 같고 입력이 같으면 재사용, 입력이 다르면 409다. 접수 시 snapshot 원문·해시·정적 검사를 읽고 고정한 뒤 202를 반환하며 실제 사례 실행은 DB 작업자가 수행한다.

View는 `{evaluation_id,attempt_id,status,reason,planned,completed,baseline_snapshot_id,candidate_snapshot_id,set_hash,runner_kind,model_mode,created_at}`다. 목록은 원문·trace를 반환하지 않으며 작업별 완료 수를 일괄 집계한다. page 기반 목록은 동시 신규 접수에 따라 다음 페이지 위치가 바뀔 수 있다.

## 요청 타입

모든 JSON 키는 snake_case다. [요청 JSON Schema](evaluation-requests.schema.json)는 SetInput·Submit·VerifiedInput을 정의한다.

SetInput:

| 필드 | 의미 |
|---|---|
| name | 1~128자 |
| target_skill | nullable. recall/precision 대상 Skill |
| cases | 1~20개, 등록된 내용은 수정하지 않음 |
| cases[].case_id | 중복 없는 `[A-Za-z0-9_-]{1,64}` |
| cases[].suite | REGRESSION / REPRODUCTION / INTENT / NEW_SKILL |
| cases[].order | 고정 주문 입력. customer/items/payment_method/shipping만 지원 |
| cases[].baseline_expectation / candidate_expectation | 기존 Scenario.Expect: skills_called, final_status, shipments, assertions. 적어도 하나의 업무 조건 필요 |
| cases[].baseline_selection / candidate_selection | nullable `{mode:EXACT|FORBID,skill:string}`. **entry 선택** 원문을 검사. none은 관측된 실제 미선택 |
| cases[].intent_checks | Check 문법 문자열 목록. INTENT suite는 비어 있을 수 없음 |

NEW_SKILL 이외의 사례는 양쪽 업무·선택 기대가 같아야 한다. SetView는 `{evaluation_set_id,version:1,sha256,reviewed,reviewed_by}`다. 내용별 새 ID/버전 1을 생성하며 같은 해시는 기존 ID를 재사용한다. DB 낙관적 잠금 version과 이 계약의 평가셋 version은 다르다. 해시는 객체 키 정렬·배열 순서·null 보존 JSON의 UTF-8 SHA-256이다. 검토는 사람의 라벨 확인 기록이며 AI 코드가 검토자 이름을 지정할 수 없다.

Submit 예시:

```json
{
  "baseline_snapshot_id": "11111111-1111-4111-8111-111111111111",
  "candidate_snapshot_id": "22222222-2222-4222-8222-222222222222",
  "evaluation_set_id": "33333333-3333-4333-8333-333333333333",
  "evaluation_set_version": 1,
  "runner_kind": "ORDER_HARNESS",
  "model_mode": "FIXED",
  "repeat": 2,
  "purpose": "CANDIDATE_VERIFICATION",
  "environment_ref": "isolated-order-v1",
  "limits": {"wall_time_ms": 120000, "max_total_tokens": 0, "max_cost_usd": "0"}
}
```

purpose는 CHANGE_COMPARISON / CAUSE_ANALYSIS / CANDIDATE_VERIFICATION이다. repeat는 1~5. 후보 검증은 REGRESSION·REPRODUCTION·INTENT suite가 모두 필요하며 NEW_SKILL을 포함하면 아직 접수를 거부한다. 모델은 실제 trace의 `fixed-policy-v1`, snapshot ID/hash, dry_run 모드, 실행 ID, api_calls=0을 대조한다. 불일치는 INTERRUPTED이며 비용·성공으로 확정하지 않는다.

후보 검증에서 baseline은 문제가 있는 진단 대상 snapshot, candidate는 고친 snapshot이다. 기준본의 REPRODUCTION 사례가 실제로 실패해야 실패 해결을 검증할 수 있다. 기준본부터 성공한 작업은 비교 결과 PASS일 수 있어도 SERVER_VERIFIED 후보로 저장할 수 없다.

## 상태·저장·재시작

상태: QUEUED, RUNNING, CANCEL_REQUESTED, SUCCEEDED, BLOCKED, FAILED, STOPPED, CANCELLED, INTERRUPTED.

SUCCEEDED는 작업 완료, Report.verdict의 PASS/FAIL은 검사 판정이다. 업무 실패가 있어도 작업 자체는 SUCCEEDED일 수 있다. 문서 오류는 BLOCKED, 예산 시간 초과는 STOPPED, 응답·trace·모드가 모호하거나 worker lease가 만료되면 INTERRUPTED다. 원문·정적 오류·먼저 완료된 trial은 보존한다. 늦은 외부 응답은 증거로 남길 수 있지만 중단 상태를 성공으로 바꾸지 않는다.

DB 작업자는 한 작업을 선점하며 lease/fencing token으로 완료 덮어쓰기를 막는다. 재시작 후 QUEUED는 실행 가능하고, 만료된 RUNNING/CANCEL_REQUESTED는 INTERRUPTED로 남긴다. 외부 실행의 멱등 조회·확인이 없으므로 INTERRUPTED의 자동 재실행은 금지한다. 나머지 취소·중단·실패 상태의 최신 시도는 동일 입력으로 최대 총 3개 attempt까지 재접수할 수 있다. 이전 시도를 덮어쓰지 않는다.

취소는 **다음 실행을 막는 요청**이다. 이미 시작된 주문의 즉시 강제 중단을 보장하지 않는다. wall_time_ms는 1,000~300,000이며 각 attempt에서 새 실행 전·응답 후 확인한다. HTTP 요청이 이미 진행 중이면 상위 timeout까지 늦게 종료할 수 있고 결과를 보존한 뒤 STOPPED 처리한다. 고정 모드는 모델 호출 0회·관측 비용 0이며 토큰은 미관측 null이다. 전체 실모델 토큰·비용 강제를 구현한 것으로 표시하지 않는다.

테이블: evaluation_sets(불변 정의·검토), evaluation_jobs(고정 계획·멱등 키), evaluation_attempts(상태·lease·부모), evaluation_trials(실제 실행·trace·판정), evaluation_reports(종료 보고서), verified_proposal_receipts(시도당 제안 연결). 기존 ddl-auto:update 방식으로 추가되며 기존 표·레코드 해석은 바꾸지 않는다. 실제 run_id의 중복 귀속은 DB unique 제약으로 거부한다.

## 결과와 집계

Trial은 `{trial_id,attempt_id,arm,case_id,suite,repeat_index,run_id,execution_id,task,selection,intent,raw_selection,reasons,error_code,elapsed_ms,input_tokens,output_tokens,cost_usd,trace}`다. arm은 BASELINE/CANDIDATE, repeat_index는 0부터 시작한다. 판정은 PASS/FAIL/NOT_APPLICABLE/UNMEASURABLE이며 미실행 trial은 생성하지 않고 planned 대비 누락으로 남긴다.

Report는 작업·시도 ID, status, verdict, verification_status, planned/completed/paired, regression_trials, regression_case_ids, baseline/candidate Metrics, reasons, policy_version, grader_version, safety와 input 출처를 제공한다. input에는 양쪽 snapshot ID/hash, 평가셋 ID/version/hash, 환경, 실행기·모드·repeat가 있다. completed는 실제 업무 PASS/FAIL이 확인된 수이며 응답 불명·미관측을 포함하지 않는다. 종료 보고서는 DB에 저장한다. 중단 후 도착한 증거는 사례 조회에서 확인하며, 이미 확정된 중단 보고서를 성공으로 재계산하지 않는다.

Metrics의 비율은 `{numerator,denominator,value}`이고 분모 0은 null이다. 업무 성공률과 선택 정확도는 관측된 PASS/FAIL로 계산하고 coverage는 planned/completed/paired로 함께 확인한다. recall/precision은 target_skill의 기대가 명확한 선택 라벨만 사용한다. 없는 Skill 선택은 FORBID에서도 FAIL, trace 부재는 none이 아닌 UNMEASURABLE이다. 전후 대응은 case_id+repeat_index이며 NEW_SKILL의 기대 차이는 기존 회귀에 섞지 않는다.

성공률 회차 목록은 모든 사례를 측정한 회차만 포함하고 두 회차 미만이면 표본분산은 null이다. 토큰·비용 중 미관측 값이 있으면 해당 전체 합계는 null이고 usage_complete=false다. baseline/candidate를 합치거나 미측정을 0으로 치환하지 않는다.

## 제안서 v2와 호환

VerifiedInput: `{evaluation_id,attempt_id,summary,intent_text}`. 설명·의도는 각 1~2000자. 생산자가 results/valid/patches를 제출하는 계약이 아니다.

서버는 검토된 라벨, actual fixed harness, CANDIDATE_VERIFICATION 목적, 서로 다른 기준·후보 bundle hash, 필수 사례·회차 완료, 기준본 실패 재현, 후보 전체 성공, 정적 검증 통과를 확인한 뒤 저장한다. 실패한 원인 증거를 일괄 거부하지 않고 후보 성공 근거와 구분한다. 반환은 `{proposal_id,status,valid_candidate_nos:[1],verification_status:SERVER_VERIFIED}`다.

시도당 제안 한 개이며 동일 입력은 재사용, 다른 설명은 409다. 기존 제안서·승인 저장소를 재사용하지만 payload schema_version=2, kind=snapshot이며 실행된 후보 snapshot/hash를 가리킨다. patches는 빈 목록이고 원인 분석은 NOT_PERFORMED다. 본문도 원인 실험·LLM 평가를 했다고 서술하지 않는다. 같은 입력을 승인 후 다시 보내면 기존 proposal_id와 현재 approved 상태를 반환한다.

기존 사람의 승인·반려·동시 결정 방어를 사용하며 운영 배포는 하지 않는다. 기존 save_proposal은 요청 형식을 유지하며, 합격 후보가 인용한 실행의 실제 `scenario_runs.passed`도 대조한다. 실제 실패를 성공으로 제출하면 `INVALID_INPUT`으로 거부한다. 다만 **필수 평가 누락·회귀 집계·의도 조건을 재구성하는 데 필요한 정보가 부족해 제출 results의 완전성 검증 한계는 남는다.** 새 v2를 추가한 것만으로 기존 Python 제안서가 서버 검증 방식으로 전환되지는 않는다.

## MCP

기존 7개에 아래 4개가 추가되어 tools/list는 11개다. 기존 도구의 입출력·apply_patch v1.3은 유지한다. 새 도구도 기존 MCP처럼 JWT 없이 호출한다. REST의 JWT·역할 검사와 사람의 승인·반려 권한은 유지한다. 기존 Python MCP 클라이언트는 아직 새 도구를 호출하지 않는다.

| 도구 | 입력 | 권한·반환 |
|---|---|---|
| submit_evaluation | `{request:Submit,idempotency_key:string}` | 인증 없음, Accepted |
| get_evaluation | `{evaluation_id}` | 인증 없음, View |
| get_evaluation_report | `{evaluation_id,attempt_id}` | 인증 없음, Report |
| save_proposal_v2 | `{request:VerifiedInput}` | 인증 없음, Saved |

결과는 기존 content[0].text의 JSON이다. 평가 도메인 오류는 isError=true와 code/message/retryable/upstream_status/snapshot_id 형식이다. MCP는 인증 없이 평가 접수·조회·검증 제안 저장을 허용한다. 제안 저장은 사람의 승인이나 운영 반영을 수행하지 않는다.

MCP 접수의 멱등 키 범위는 로그인 여부와 관계없이 고정된 `mcp:anonymous`다. 모든 MCP 호출자가 이 범위를 공유하므로 UUID 등 충돌하지 않는 키를 사용한다. 같은 키·같은 입력은 재사용하고 다른 입력은 409 의미의 도메인 충돌 오류로 반환한다. REST는 로그인 사용자 이름을 범위로 사용하며, 동일 키를 REST와 MCP에 전달해도 동일 작업을 재사용하지 않는다. 이미 접수된 REST 작업은 평가 ID로 MCP에서 조회할 수 있다. REST 사용자 이름으로 `mcp:anonymous`는 사용하지 않는다.

REST 입력 오류는 400 INVALID_EVALUATION_INPUT, 미지원은 422 EVALUATION_CAPABILITY_UNSUPPORTED/BUDGET_ENFORCEMENT_UNSUPPORTED, 없는 리소스는 404, 멱등·상태·근거 충돌은 409다. 저장 경합은 EVALUATION_WRITE_CONFLICT(409,retryable=true), 상위 조회 오류는 원래 코드와 retryable을 보존한다. REST 오류 공통 필드는 `{code,message,retryable}`다.

## 실행과 검증

기본 fixed harness는 꺼져 있다. `SKILLOPS_EVALUATION_FIXED_HARNESS_ENABLED=true`, `SKILLOPS_EVALUATION_ISOLATED_ORDER_AGENT_URL`, `SKILLOPS_EVALUATION_ENVIRONMENT_REF`를 지정하고 해당 서버를 fixed·전용 DB/재고로 운영해야 한다. 실제 trace 모드를 사후 대조하지만 잘못 설정된 상위 서버의 유료 호출 자체를 사전에 강제 차단하는 기능은 아니다.

`service/scripts/evaluation_contract_smoke.py`는 별도 루프백 포트만 허용하며 실제 dry-run·제안서·승인을 생성한다. 정상/실패 후보, REST/MCP 멱등 접수, MCP의 무인증 호출·접수 키 분리, 재고 복원·live 실패 목록 분리를 확인한다. 재현 명령과 검증 결과는 [구현 보고서](../reports/2026-10-26-evaluation-backend-implementation.md)에 기록한다.

무인증 전환 후 실서버 수용 검증(소스 `f7b2245`)도 완료했다. 새 MCP 4개·REST 권한 유지·재시작 보존·고정 실행 18건을 확인했으며, 결과와 한계는 [MCP 인증 간소화 기록](../reports/2026-10-26-evaluation-mcp-anonymous.md#무인증-전환-후-실제-서버-수용-검증)을 따른다.
