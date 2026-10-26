# 주문 Agent 진단용 조회 API 명세

2026-10-23 · #72 공용 파서: SkillLoader는 service와 동일한 `shared/skill-parser`를 사용한다. 기존 조회 필드·오류 코드·partial 응답은 유지하며 YAML 안전 정책·100,000 코드 포인트 제한은 [공통 SKILL 명세](../../docs/contracts/skill-frontmatter.md)를 따른다. 기존 실행 필수값·본문·목록 검증은 유지한다.

2026-10-23 · #72: Python 진단기는 partial 목록의 원문을 기존 snapshot files API로 별도 조회해 해시·크기를 검증하고 증거만 저장한다. 이 서버의 조회 응답과 실행 로더는 변경하지 않았다. [진단 증거 조회 계약](../../docs/contracts/diagnosis-evidence.md).

2026-10-21 / 이슈 #39 구현 기준. 두 버전 비교와 연결 관계 분석은 MCP 담당자가 구현하고, 나머지 네 기능은 주문 Agent의 HTTP API로 제공한다. 기존 snapshot·파일·trace API를 유지하며 공용 MCP v1 변환은 호출 측 계약으로 구분한다.

## 1. 제공 범위와 구현 상태

| 화면 기능 | 제공 주체 | 인터페이스 | 현재 상태 |
|---|---|---|---|
| 실패한 주문 목록 | 주문 Agent | GET /api/executions/failed | 구현됨 |
| 실행 기록 자세히 | 주문 Agent | GET /api/executions/{execution_id}/trace | 구현됨 |
| Skill 목록 | 주문 Agent | GET /api/snapshots/{snapshot_id}/skills | 구현됨 |
| Skill 본문 읽기 | 주문 Agent | GET /api/snapshots/{snapshot_id}/skills/{skill_id} | 구현됨 |
| 두 버전 비교 | MCP | diff_skill | 두 snapshot의 Skill 조회 API를 조합해 비교 |
| 연결 관계 보기 | MCP | get_dependency_graph | snapshot의 Skill 목록·본문 API로 분석 |
| 업무 도구 목록(개발 요청서 판단) | 주문 Agent | GET /api/tools | 구현됨 (2026-10-26, 10절) |

snapshot manifest와 관련 파일 조회 API도 기존 구현을 재사용한다. 주문 Agent에 diff·그래프 API를 중복 구현하지 않는다. API 네 기능을 직접 HTTP 도구로 등록할지 기존 MCP 서버에서 감쌀지는 호출 담당자의 선택이며, 데이터 제공·분석 책임은 위 표를 따른다.

## 2. 공통 계약

- Base URL: 환경 설정 ORDER_AGENT_BASE_URL. 호스트 개발 환경은 http://localhost:8081.
- 형식: application/json, UTF-8. 시간은 UTC ISO 8601, 식별자는 UUID 문자열.
- 모든 조회는 읽기 전용이다. 주문 생성·재결제·snapshot 생성 없이 조회한다.
- Skill 조회는 snapshot_id를 필수로 한다. 현재 운영본을 원하면 공용 Registry에서 활성 snapshot 참조를 먼저 얻는다. 현재 주문 앱에는 활성 snapshot 조회 API가 없다.
- snapshot은 파일 세트, Skill version은 파일 안의 선언값이다. version=1이어도 서로 다른 후보의 내용은 다를 수 있어 두 버전 비교에는 snapshot ID·해시를 사용한다.
- 실행 trace의 run_id는 execution_id 기반 별칭이며 SkillOps 진단 run ID와 구분한다.
- 예시의 <...>는 치환용 문자열이다. 실제 UUID·해시로 교체해야 한다.

## 3. 실패한 주문 목록

`GET /api/executions/failed`

“실패”는 주문의 정상 업무 거절이 아닌 실행 실패로 정의한다. 대상은 mode=live이면서 completion=FAILED 또는 INTERRUPTED인 실행이다. 재고 부족 REJECTED·결제 거절 HOLD·정상 미선택은 이 목록에 단순히 주문 상태만 보고 넣지 않는다. 업무 거절 조회가 필요하면 별도 필터 계약을 추가한다.

요청 파라미터 없이 저장된 전체 실패 실행 목록을 반환한다. finished_at 내림차순(종료 시각 미기록은 뒤)·execution_id 내림차순으로 정렬한다. 실패가 없으면 items는 빈 배열이다. 목록 조회가 실패 이벤트 자동 전달을 의미하지는 않는다.

```json
{
  "items": [
    {
      "execution_id": "<실행 UUID>",
      "order_id": "<주문 UUID>",
      "snapshot_id": "<실패 snapshot UUID>",
      "snapshot_hash": "<SHA-256>",
      "completion": "FAILED",
      "final_status": "FAILED",
      "started_at": "2026-10-20T08:15:43.300Z",
      "finished_at": "2026-10-20T08:15:43.331Z",
      "error": {
        "category": "CONTRACT_VIOLATION",
        "skill_id": "payment",
        "phase": "static_validation",
        "missing_inputs": ["inventory.reserved"],
        "missing_dependencies": [],
        "message": "Skill에 필요한 입력 또는 선행 단계가 충족되지 않았습니다."
      }
    }
  ]
}
```

error는 실제 EXECUTION_ERROR 이벤트의 상세를 사용한다. 오류 종류나 과거 실행에 따라 없는 상세 필드는 null/생략하고 추측하지 않는다. snapshot 고정 전에 실패했으면 snapshot_id/hash는 null이다. 기존 GET /api/executions는 최근 20개 요약 조회로 유지한다. 전체 실패 목록은 새 GET /api/executions/failed에서 조회한다. 상세 이벤트가 없는 과거 실행은 저장된 error_category만 반환하고 누락 상세를 생성하지 않는다.

## 4. 실행 기록 자세히 — 기존 API

`GET /api/executions/{execution_id}/trace`

입력은 실행 UUID. 기존 GET /api/traces/{run_id}도 같은 자료를 조회하지만 신규 연계는 execution_id를 권장한다. 다음 예시는 핵심 필드를 발췌했으며 실제 응답에는 아래 표의 나머지 요약 필드도 포함된다.

```json
{
  "execution_id": "<실행 UUID>",
  "run_id": "r_<실행 UUID의 하이픈 제거 값>",
  "order_id": "<주문 UUID>",
  "snapshot_id": "<실패 snapshot UUID>",
  "snapshot_hash": "<SHA-256>",
  "agent_id": "camping-order-agent",
  "completion": "FAILED",
  "final_status": "FAILED",
  "raw_selection": "order-flow",
  "resolved_skill_id": "order-flow",
  "error_category": "CONTRACT_VIOLATION",
  "events": [
    {
      "sequence": 9,
      "kind": "EXECUTION_ERROR",
      "skill_id": "payment",
      "at": "2026-10-20T08:15:43.330Z",
      "data": {
        "category": "CONTRACT_VIOLATION",
        "skill_id": "payment",
        "phase": "static_validation",
        "missing_inputs": ["inventory.reserved"],
        "missing_dependencies": [],
        "message": "Skill에 필요한 입력 또는 선행 단계가 충족되지 않았습니다."
      }
    }
  ]
}
```

| 필드 | 내용 |
|---|---|
| mode, model_mode, model | 실행 환경과 fixed/openai·모델 이름 |
| api_calls, input_tokens, output_tokens | 호출 수와 관측 토큰. 미관측은 null |
| elapsed_ms, started_at, finished_at | 소요 시간·시작·종료. 실행 중 종료 시각은 null |
| events | sequence 오름차순. kind·skill_id·data·at 유지 |

주요 이벤트는 CANDIDATES_OFFERED, SKILL_SELECTED, SKILL_RESOLVED, SKILL_LOADED, SKILL_STARTED, SKILL_FINISHED, TOOL_REQUEST, TOOL_RESULT, EXECUTION_ERROR, EXECUTION_FINISHED다. 모든 이벤트를 원문 그대로 보존하고 MCP가 요약하더라도 원래 기록을 대체하지 않는다. raw_selection은 없는 Skill 이름도 보존한다. 실제 로드 버전·경로·해시는 SKILL_LOADED에서 확인한다. 반환된 trace가 FAILED인 것은 **조회 성공 200**이며 HTTP 조회 오류가 아니다.

새 실행의 SKILL_STARTED에는 step_no, version, hash, input을 기록한다. 실제로 존재하는 선언 입력만 포함한다. SKILL_FINISHED에는 step_no, version, status(ok/error), output, duration_ms를 기록한다. 업무 거절은 status=ok, business_stop=true와 final_status를 추가하고, 실행 오류는 status=error와 category를 기록한다. 도구 요청·실제 효과는 별도 TOOL_REQUEST/RESULT/ERROR 이벤트로 확인한다.

정적 계약 오류에서는 아직 단계가 시작되지 않았으므로 SKILL_STARTED/FINISHED를 만들지 않는다. 과거 이벤트에 새 필드를 소급 추가하거나 skipped 단계의 실행·시간을 추측하지 않는다. 단계별 steps 응답이 필요한 공용 MCP v1 변환은 별도 계약이며 기존 events를 대체하지 않는다.

## 5. Skill 목록

`GET /api/snapshots/{snapshot_id}/skills`

실행 당시 파일 세트의 메타데이터를 서버에서 파싱해 반환한다. 실행 당시 실제 모델에 제공한 후보 목록은 이 API가 아니라 trace의 CANDIDATES_OFFERED가 근거다.

```json
{
  "snapshot_id": "<snapshot UUID>",
  "snapshot_hash": "<SHA-256>",
  "items": [
    {
      "skill_id": "payment",
      "name": "payment",
      "version": 1,
      "description": "예약된 주문 금액을 결제한다",
      "owner": "주문운영팀",
      "path": "skills/payment/SKILL.md",
      "hash": "<파일 SHA-256>",
      "inputs": ["order.id", "order.total", "order.payment_method", "inventory.reserved"],
      "outputs": ["payment.id", "payment.status"],
      "depends_on": ["inventory-check"],
      "steps": [],
      "rules": [
        {"when": "order.payment_method != 'prepaid'", "then": "hold", "reason": "PREPAID_ONLY"}
      ]
    }
  ],
  "partial": false,
  "parse_errors": []
}
```

items는 skill_id 순으로 정렬한다. 현재 파일 수 제한 내에서는 전체 목록을 반환하며 페이지 분할하지 않는다. steps는 flow에서 읽고 단계 Skill은 빈 배열로 반환한다. rules(2026-10-26 추가 칸)는 실행 로더가 읽고 형식을 검증한 단계 Skill의 실행 규칙을 frontmatter 순서대로 `{when, then, reason}` 목록으로 반환하며, 규칙이 없거나 flow Skill이면 빈 배열이다(형식은 [skill-flow.md](skill-flow.md) 9절). 조건 키가 선언 입력인지는 이 API가 아니라 실행 전 흐름 검사가 본다. 기존 소비자는 모르는 칸을 무시한다. 같은 칸은 `GET /api/snapshots/{snapshot_id}/skills/{skill_id}` 응답에도 있다. 실행 로더와 동일하게 name·description·owner·양의 정수 version·본문을 검증한다. 단계 Skill의 inputs/outputs/depends_on은 필수 문자열 배열이며 flow의 선택 배열 미기재만 빈 배열로 반환한다. 필수 필드 누락·잘못된 형식·이름과 폴더 불일치·중복 YAML 키·잘못된 UTF-8은 정상 값으로 바꾸지 않고 해당 파일을 items에서 제외하며 parse_errors에 path/code/message를 기록한다. 따라서 이 API의 파싱 성공이 Skill 간 계약 검증 통과를 뜻하지는 않는다.

partial=true인 응답은 완전한 그래프의 근거로 취급하지 않는다. 파싱에 실패한 원문은 기존 파일 API로 읽을 수 있게 유지한다. 파일 변조처럼 snapshot 무결성이 깨진 경우는 부분 성공이 아니라 요청 오류다.

## 6. Skill 본문

`GET /api/snapshots/{snapshot_id}/skills/{skill_id}`

snapshot의 검증된 Skill 목록에서 skill_id에 해당하는 파일을 찾는다. name과 skill_id를 같은 개념으로 고정하지 않는다. 이름·버전 번호만으로 최신 파일로 대체하지 않는다.

```json
{
  "snapshot_id": "<snapshot UUID>",
  "snapshot_hash": "<SHA-256>",
  "skill_id": "payment",
  "name": "payment",
  "version": 1,
  "path": "skills/payment/SKILL.md",
  "hash": "<파일 SHA-256>",
  "size": 640,
  "encoding": "utf-8",
  "content": "<front matter를 포함한 SKILL.md 전체 원문>"
}
```

size는 원본 byte 수다. 반환 내용은 요약이나 재직렬화한 문서가 아닌 파일 원문이다. 파싱 실패로 메타데이터를 검증할 수 없으면 SKILL_METADATA_INVALID를 반환하고 manifest/path 기반 원문 조회를 안내한다. 관련 policy.md 등은 다음 기존 API로 읽는다.

## 7. MCP가 재사용할 기존 원본 조회 API

| API | 응답 |
|---|---|
| GET /api/snapshots/{snapshot_id} | snapshot_id, hash, created_at, metadata, files[{path, hash, size}] |
| GET /api/snapshots/{snapshot_id}/files?path={인코딩한 경로} | snapshot_id, path, hash, size, encoding, content |

파일 encoding은 utf-8 또는 base64다. 경로는 snapshot manifest에 있는 항목만 허용하며 호스트 절대 경로로 해석하지 않는다. snapshot 전체 해시는 메타데이터도 포함하므로 파일별 해시만 이어 붙여 임의 계산하지 않는다.

MCP diff_skill 권장 입력은 skill_id, before_snapshot_id, after_snapshot_id다. 같은 version 선언값의 서로 다른 후보도 비교할 수 있어야 한다. get_dependency_graph 권장 입력은 snapshot_id와 entry_skill_id다. “입력 없음” 형태를 유지하려면 진단 세션이 snapshot을 명시적으로 고정해 전달해야 하며 호출마다 운영 최신본을 읽으면 안 된다. MCP 응답의 변경 덩어리 ID·그래프 스키마는 담당자가 정의한다.

## 8. 오류·네트워크·연계 확인

기존 API 오류 DTO는 code, message, order_id, field_errors다. 상세 계약 오류는 HTTP 오류 DTO가 아닌 trace에서 읽는다. 기존 오류 응답의 실제 JSON 표기는 직렬화 설정과 계약 테스트로 고정하고, 신규 API도 같은 형식을 따른다.

| 상태 | 의미 |
|---|---|
| 400 | 잘못된 UUID·입력 형식, 기존 snapshot 무결성 오류 등. code로 구분 |
| 404 | EXECUTION_NOT_FOUND, SNAPSHOT_NOT_FOUND, SNAPSHOT_FILE_NOT_FOUND |
| 404 | 본문 API의 SKILL_NOT_FOUND |
| 400 | SKILL_METADATA_INVALID, INVALID_SKILL_NAME, 잘못된 UUID·필수 path 누락의 VALIDATION_ERROR |
| 5xx | 서버·DB 처리 실패. 현재 오류 코드와 HTTP 상태 유지 |
| 연결 실패/timeout | HTTP 응답과 별도 transport 오류로 전달 |

없는 Skill과 정상 미선택을 구분한다. 인증 도입 시 401/403 계약도 추가한다. GET 일시 연결 오류·429·일시 5xx만 전체 호출 예산 안에서 제한 재시도한다. 404·잘못된 입력·해시 불일치를 빈 결과로 바꾸지 않는다.

현재 호스트의 127.0.0.1:8081 노출은 별도 컨테이너 접근을 보장하지 않는다. 공용 Docker external network와 order-agent-backend 같은 고유 별칭을 합의하는 방식을 권장한다. 컨테이너 localhost는 자기 자신이다. base URL은 서버 설정으로 고정하고 고객이 임의 URL을 넣지 못하게 한다. timeout·인증·필드 마스킹·크기 제한은 배포 전 합의한다.

실패 이벤트 자동 전송과 Runner 연동은 이 조회 명세 범위 밖이다. 조사 시작에 execution_id를 전달하고 trace에서 snapshot 참조를 얻는다. 자동 이벤트 스키마·조회 순서·Runner 역할은 [연계 초안](mcp-order-api-draft.md)을 참고한다.

연결 확인 항목:

- 같은 execution의 snapshot_id/hash로 목록·본문·diff·그래프가 고정되는가?
- 결함본에서 payment의 missing_inputs에 inventory.reserved가 남는가?
- 기준/결함 파일이 달라도 원래 snapshot을 수정하지 않는가?
- 파싱 실패·없는 ID·네트워크 단절이 완전한 빈 결과로 숨겨지지 않는가?
- 실패 목록의 전체 반환·정렬·빈 배열 응답과 기존 API 응답이 계약 테스트로 검증되는가?

## 9. MCP v1과의 연결 계약

이 HTTP 응답은 공용 MCP v1 응답과 동일하지 않다. 호출 측이 HTTP를 직접 지원하거나 명시적으로 변환해야 한다. items→failed_runs/skills, skill_id→skill, content→full_text 등의 표기 변환은 가능하지만 events→steps의 완전한 이력·Registry 저장 정보는 단순 이름 변경으로 채울 수 없다. saved_at/saved_by/last_working_version은 현재 제공하지 않는다. snapshot 생성 시각을 Skill 버전 저장 시각으로 대신하지 않는다.

기존 두 MCP 도구는 snapshot manifest와 files API를 사용한다. 두 API의 경로와 snapshot_id/hash/files 및 path/hash/size/encoding/content 응답을 유지했다. diff_skill/get_dependency_graph의 현재 snapshot 입력과 v1의 버전 입력 차이는 MCP 담당과 별도 합의한다. 일반 주문·프론트 흐름과 Runner는 변경하지 않는다.

## 10. 업무 도구 목록

`GET /api/tools` (2026-10-26 추가, [근본 개선 계획](../../docs/plans/2026-10-26-behavior-edit-root-fix.md) 2.11절)

주문 Agent 서버 코드가 가진 업무 도구와, flow 정적 검증이 처음부터 공급된다고 보는 입력 키를 돌려준다. SkillOps Agent는 담당자 수정이 **어떤 업무 도구도 만들지 않는 값**을 요구하는지(개발 요청서) 판단할 때 이 응답을 읽고 사본을 두지 않는다. 요청 파라미터가 없고, DB·snapshot을 읽지 않는 읽기 전용 조회다. 시연 전용 API가 아니므로 `order-agent.demo-enabled=false`에서도 200이다.

```json
{
  "initial_input_keys": ["order.id", "order.items", "order.customer", "order.payment_method", "order.shipping"],
  "tools": [
    {"tool": "order.inspect", "skill": "order-intake", "description": "Read the accepted order and server-calculated total. Does not create another order.",
     "produces": ["order.id", "order.customer", "order.items", "order.payment_method", "order.shipping", "order.total"]},
    {"tool": "inventory.reserve", "skill": "inventory-check", "description": "Atomically reserve all order items or return OUT_OF_STOCK. Arguments: order_id, items[{sku,qty}].",
     "produces": ["inventory.available", "inventory.reservable_quantity", "inventory.reserved"]},
    {"tool": "payment.charge", "skill": "payment", "description": "Mock charge a reserved order. Arguments: order_id, amount (server total), method (card/prepaid).",
     "produces": ["payment.status", "payment.id"]},
    {"tool": "shipping.reserve", "skill": "shipping-reserve", "description": "Reserve shipment only for a paid and reserved order. Arguments: order_id, country, express.",
     "produces": ["shipping.id", "shipping.eta"]},
    {"tool": "notify.send", "skill": "notify", "description": "Record success notification only after shipment. Arguments: order_id, customer.",
     "produces": ["notify.sent", "notify.channel"]}
  ]
}
```

| 필드 | 내용 |
|---|---|
| initial_input_keys | 주문 접수 때 컨텍스트에 이미 있는 키. 실행 코드의 `OrderFlowPolicy.INITIAL_INPUT_KEYS` 그대로다. 서버가 계산한 `order.total`은 여기 없고 `order.inspect`의 produces로만 나온다 |
| tools[].tool | 업무 도구 이름. 서버 전용 재고 해제 `inventory.release`는 단계 Skill에 묶이지 않아 목록에 없다 |
| tools[].skill | 이 도구를 부를 수 있는 단계 Skill(1:1) |
| tools[].description | 모델에 보여 주는 도구 설명과 같은 문장 |
| tools[].produces | 도구가 **성공 경로**에서 출력(trace TOOL_RESULT의 `result.context`)에 넣는 키 전부. 업무 거절 경로는 그 일부만 낸다(결제 거절은 `payment.id` 없이 `payment.status`만) |

- 값의 원천은 `order-agent/backend/src/main/java/com/capstone4/orderagent/harness/ToolCatalog.java`의 BINDINGS다. produces가 `OrderToolService`의 실제 출력 키와 같은지는 `OrderToolServiceProducesTest`가, 응답 모양은 `ToolControllerTest`가 고정한다.
- 응답은 실행 중인 서버 코드 기준이며 snapshot의 SKILL.md(선언 inputs/outputs)와 무관하다. tools의 순서에는 의미가 없으니 읽는 쪽은 도구 이름으로 찾는다.
- 덮음 판정(2.11절): 키 k는 `initial_input_keys ∪ 모든 produces`에 그대로 있거나, 그 가운데 p에 대해 k가 `p + "."`로 시작하면 덮인다(예: `order.shipping.country` ← `order.shipping`). 판정은 호출 측(SkillOps Agent)이 하며 이 API는 목록만 준다.
- 읽지 못하면(연결 실패·5xx) 호출 측은 도구 빈틈 탐지를 건너뛰고 기존 흐름대로 진행한다. 빈 목록으로 바꿔 모든 키를 빈틈으로 보지 않는다.
