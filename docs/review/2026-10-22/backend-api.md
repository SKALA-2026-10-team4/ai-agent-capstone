# Backend API 명세 — 2026-10-22 구현 기록

> **과거 검토 기록 — 기준 `ec39713`.** 이 문서의 “현재”, 미완료 항목, 테스트 수와 줄 번호는 작성 당시 기준입니다. 최신 구현·계약·#72 완료 상태는 [리뷰 문서 안내](../README.md)를 우선합니다. 아래 제안을 현재 API나 새 작업 승인으로 간주하지 않습니다.

기준: 2026-10-22, `ec39713`. [문서 안내](README.md) · [MCP](mcp-spec.md) · [구조 및 개선사항](architecture-review.md).

## 1. 주소·인증·직렬화

| 서버 | 개발 주소 | 역할 | JSON 규칙 |
|---|---|---|---|
| SkillOps Java `service` | `http://localhost:8080` | 로그인, 진단 중계, 제안서 조회·결정, MCP | DTO별 `@JsonNaming(SnakeCaseStrategy)` 또는 Map의 명시적 키 |
| 주문 Java `order-agent/backend` | `http://localhost:8081` | 상품·주문·실행 증거·snapshot·dry-run | 전역 `SNAKE_CASE`, 알 수 없는 DTO 필드 거부, 소수→정수 변환 거부, primitive null 거부 |
| Python `skillops-agent` | `http://localhost:8000` | 진단 접수·상태·파이프라인 실행 | Pydantic 및 dict의 snake_case |

`frontend`의 `/api/*`는 주문 Java로, `/service/*`는 `/service` 접두어를 제거한 뒤 SkillOps Java로 전달됩니다. 따라서 브라우저의 `/service/api/health`와 `/api/health`는 다른 서버를 조회합니다. 주문 Compose의 외부 `8081`은 컨테이너 내부 `8080`으로 연결됩니다. 독립 주문 화면은 `5174`, 통합 화면은 `5173`입니다.

- **JWT**: `Authorization: Bearer <access_token>`. HS256, 기본 TTL 2시간, refresh·logout API 없음. 로그아웃은 클라이언트 토큰 삭제입니다. `roles`가 `ROLE_` 권한으로 매핑됩니다.
- **서버 토큰**: `X-Service-Token`. Java 내부 저장 이벤트 수신과 Python의 health 이외 API에 사용합니다. 설정이 비어 있으면 검증을 생략합니다. JWT와 별개입니다.
- 주문 서버에는 로그인·권한 필터가 없습니다. SkillOps Java의 `/mcp`도 공개 상태입니다. 브라우저 라우터의 로그인 요구는 서버 권한 검사를 대신하지 않습니다.
- 시각은 주로 ISO 8601 UTC 문자열입니다. 배송 예정일은 `YYYY-MM-DD`, 제안서 본문의 사람이 읽는 시각은 Asia/Seoul입니다.
- HTTP 요청 성공과 업무 성공은 별개입니다. `201 + REJECTED`, `201 + HOLD`, dry-run의 `200 + FAILED`가 가능합니다.
- 아래 `UUID`는 문자열, `SHA256`은 64자리 16진수 문자열입니다. 예시의 `<...>`는 실제 응답의 일부가 아니라 치환할 참조입니다.

## 2. 전체 HTTP 목록

직접 선언된 애플리케이션 라우트는 **34개**입니다(주문 18, SkillOps Java 11, Python 5). MCP의 `/mcp`와 프레임워크 제공 Swagger/OpenAPI·오류 라우트는 별도입니다.

| 서버 | 메서드 | 경로 | 성공 | 인증 |
|---|---|---|---|---|
| 주문 | GET | `/api/health` | 200 또는 503 | 없음 |
| 주문 | GET | `/api/products` | 200 | 없음 |
| 주문 | POST | `/api/orders` | 201 또는 200 | 없음; Idempotency-Key 필수 |
| 주문 | GET | `/api/orders/{id}` | 200 | 없음 |
| 주문 | GET | `/api/orders/by-request/{key}` | 200 | 없음 |
| 주문 | GET | `/api/executions` | 200 | 없음 |
| 주문 | GET | `/api/executions/failed` | 200 | 없음 |
| 주문 | GET | `/api/executions/{id}/trace` | 200 | 없음 |
| 주문 | GET | `/api/traces/{runId}` | 200 | 없음 |
| 주문 | GET | `/api/snapshots/{id}` | 200 | 없음 |
| 주문 | GET | `/api/snapshots/{id}/files?path=...` | 200 | 없음 |
| 주문 | GET | `/api/snapshots/{id}/skills` | 200 | 없음 |
| 주문 | GET | `/api/snapshots/{id}/skills/{skillId}` | 200 | 없음 |
| 주문 | POST | `/api/snapshots` | 200 | 없음; demo-enabled 필요 |
| 주문 | POST | `/api/snapshots/candidates` | 200 | 없음; demo-enabled 필요 |
| 주문 | POST | `/api/snapshots/edits` | 201 | 없음 |
| 주문 | GET | `/api/skill-changes/{id}` | 200 | 없음 |
| 주문 | POST | `/api/dry-runs` | 200 | 없음; demo-enabled 필요 |
| SkillOps | GET | `/api/health` | 200 | 없음 |
| SkillOps | POST | `/api/auth/login` | 200 | 없음 |
| SkillOps | GET | `/api/auth/me` | 200 | JWT |
| SkillOps | GET | `/api/proposals` | 200 | JWT |
| SkillOps | GET | `/api/proposals/{proposalId}` | 200 | JWT |
| SkillOps | POST | `/api/proposals/{proposalId}/approve` | 200 | JWT + OPERATOR |
| SkillOps | POST | `/api/proposals/{proposalId}/reject` | 200 | JWT + OPERATOR |
| SkillOps | GET | `/api/approvals` | 200 | JWT |
| SkillOps | POST | `/internal/skill-changes` | 200 | 설정 시 X-Service-Token |
| SkillOps | GET | `/api/diagnoses` | 200 | JWT |
| SkillOps | GET | `/api/diagnoses/{threadId}` | 200 | JWT |
| Python | GET | `/health` | 200 | 없음 |
| Python | POST | `/diagnose` | 202 | 설정 시 X-Service-Token |
| Python | GET | `/runs` | 200 | 설정 시 X-Service-Token |
| Python | GET | `/runs/{thread_id}/events` | 200 | 설정 시 X-Service-Token |
| Python | POST | `/approve` | 200 | 설정 시 X-Service-Token; 현행 승인 흐름에서는 미사용 |

## 3. 주문·상품 API

근거: [Controller](../../..//order-agent/backend/src/main/java/com/capstone4/orderagent/controller/OrderController.java), [입력 DTO](../../../order-agent/backend/src/main/java/com/capstone4/orderagent/dto/order/CreateOrderRequest.java), [출력 DTO](../../../order-agent/backend/src/main/java/com/capstone4/orderagent/dto/order/OrderResponse.java).

### 3.1 GET /api/health

입력 없음. Readiness가 `ACCEPTING_TRAFFIC`이면 `200 {"status":"ok","service":"order-agent"}`, 아니면 `503 {"status":"starting","service":"order-agent"}`입니다. 외부 모델·전체 진단 파이프라인의 상태를 보증하지 않습니다.

### 3.2 GET /api/products

입력 없음. 활성 상품을 SKU 순으로 조회합니다.

```json
{"items":[{"sku":"CAMP-LANTERN-LED","name":"LED 캠핑 랜턴","unit_price":30000,"stock_quantity":10}]}
```

가격·재고의 현재 값이 반환됩니다. 프론트가 보낸 가격을 주문 금액으로 신뢰하지 않습니다.

### 3.3 POST /api/orders

헤더: `Content-Type: application/json`, **`Idempotency-Key: UUID` 필수**.

```json
{
  "customer":"c_1001",
  "items":[{"sku":"CAMP-LANTERN-LED","qty":2}],
  "payment_method":"card",
  "shipping":{"country":"KR","express":false},
  "snapshot_id":null
}
```

| 필드 | 타입·필수 | 검증·의미 |
|---|---|---|
| customer | string, 필수 | 비공백, 최대 64자, 앞뒤 공백을 허용한 `c_[A-Za-z0-9_]+`; 정규화 시 strip |
| items | array, 필수 | 1~20개, null 항목 불가 |
| items[].sku | string, 필수 | 비공백, 최대 64자, 등록된 활성 상품이어야 함; SKU 자체를 trim하지 않음 |
| items[].qty | integer, 필수 | 1~99. 같은 SKU는 합산하고 합산 결과도 99 이하 |
| payment_method | string, 필수 | `card` 또는 `prepaid` |
| shipping | object, 필수 | 내부 검증 적용 |
| shipping.country | string, 필수 | `KR`, `US`, `JP` |
| shipping.express | boolean | primitive 기본 false; 명시적 null은 거부 |
| snapshot_id | UUID 또는 null | 생략 시 실행 시점 published bundle 캡처; 지정 시 존재하는 불변 snapshot 사용 |

멱등 비교는 고객 공백 정리, SKU 정렬·중복 합산, 결제·배송·snapshot 참조를 반영한 SHA-256 요청 해시로 수행합니다. 같은 키·동일한 정규화 입력은 기존 주문을 `200`으로 반환하며 추가 실행하지 않습니다. 다른 입력이면 `409 IDEMPOTENCY_CONFLICT`입니다. 신규 접수는 `201`, `Location: /api/orders/<order_id>`입니다. 동시 최초 요청의 DB 유일 제약 충돌도 기존 주문 조회로 복구합니다.

주문 입력 검증 → 상품·가격 확정 → 주문 접수 커밋 → claim → Harness 실행 → 조회의 순서입니다. 모델 호출을 하나의 DB 트랜잭션으로 감싸지 않습니다. 실행 중인 주문이 재조회되면 CREATED/PROCESSING일 수 있습니다.

### 3.4 OrderResponse 전체 필드

| 필드 | 타입 | 의미 |
|---|---|---|
| order_id, request_id | UUID | 주문 ID, 요청 멱등 키 |
| customer | string | 정규화 고객 ID |
| items | Item[] | SKU 순의 확정 주문 상품 |
| items[].sku, name | string | 상품 ID·주문 당시 상품명 |
| items[].qty | integer | 확정 수량 |
| items[].unit_price, line_total | integer(long) | 주문 당시 단가·행 금액 |
| total_amount | integer(long) | 서버가 합산한 전체 금액 |
| currency | string | `KRW` |
| payment_method | string | card/prepaid |
| shipping | object | `{country:string, express:boolean}` |
| status | string | CREATED/PROCESSING/SHIPPED/REJECTED/HOLD/FAILED |
| result_code | string 또는 null | OUT_OF_STOCK/PAYMENT_DECLINED/NO_SELECTION/오류 범주 등 |
| message | string | status 기반 한국어 안내 |
| payment | object | `{status:NOT_ATTEMPTED\|PAID\|DECLINED, reference:string\|null}` |
| shipment | object 또는 null | `{reference:string, estimated_delivery_date:date}` |
| notification | object | `{status:NOT_ATTEMPTED\|SENT}` |
| steps | Step[] | DB에 저장한 업무 처리 기록. 실시간 스트림이 아님 |
| steps[].sequence | integer | 1부터 시작하는 업무 단계 번호 |
| steps[].step | string | INTAKE/INVENTORY/PAYMENT/SHIPPING/NOTIFICATION/RELEASE/RECOVERY |
| steps[].status | string | SUCCESS/REJECTED/SKIPPED/FAILED |
| steps[].result_code | string 또는 null | 업무 단계 코드 |
| steps[].message | string | 단계 안내 |
| steps[].started_at, completed_at | datetime | 업무 기록 시각 |
| created_at, updated_at | datetime | 주문 생성·갱신 시각 |
| execution_id | UUID 또는 null | Harness 실행 참조. 과거 주문에는 없을 수 있음 |
| trace_url | string 또는 null | `/api/executions/<execution_id>/trace` |

정상 예시의 총액은 랜턴 2개 × 30,000원 = 60,000원입니다. 재고 부족은 REJECTED, `c_declined + card`는 HOLD이며 미결제 예약을 해제합니다. prepaid는 이 고객도 Mock 결제에 성공합니다. SHIPPED는 Mock 배송 예약과 알림 기록까지 완료했다는 뜻입니다.

Harness 오류는 trace와 FAILED 주문을 남긴 뒤 신규 주문 요청에 `500 INTERNAL_ERROR` 또는 DB 원인에 `503 PROCESSING_UNAVAILABLE`를 반환할 수 있습니다. 이때 `order_id`로 조회하여 확정 상태를 확인해 주세요. 모든 FAILED 주문이 HTTP 500으로 재조회되는 것은 아닙니다. GET은 저장된 주문을 200으로 반환합니다.

### 3.5 GET /api/orders/{id}, GET /api/orders/by-request/{key}

각각 주문 UUID 또는 멱등 키 UUID로 조회하며 본문은 위 OrderResponse와 같습니다. 정식 하이픈 UUID 문자열인지 검사합니다. 잘못된 형식은 `400 VALIDATION_ERROR`, 없는 주문은 `404 ORDER_NOT_FOUND`입니다. timeout 뒤에는 동일 키로 조회하여 접수 여부를 확인해 주세요. 새 키 재전송은 새 주문을 만듭니다.

## 4. 실행 증거 API

근거: [EvidenceController](../../../order-agent/backend/src/main/java/com/capstone4/orderagent/controller/EvidenceController.java), [ExecutionRepository](../../../order-agent/backend/src/main/java/com/capstone4/orderagent/trace/ExecutionRepository.java).

### 4.1 GET /api/executions

쿼리 없음. `started_at DESC LIMIT 20`. 응답 `{items: ExecutionSummary[]}`. live/dry_run을 모두 포함하며, Summary는 아래 Trace에서 `events`를 뺀 형태입니다. 내부적으로 각 실행의 trace를 읽고 events를 제거하므로 현재 구현은 N+1 조회입니다.

### 4.2 GET /api/executions/failed

쿼리 없음. `{items: FailedExecution[]}`. **전체 live 실행 중 completion이 FAILED/INTERRUPTED인 것만** 반환합니다. 최신 종료 시각부터이며 제한·since 파라미터는 없습니다. 업무 거절과 dry-run 실패는 제외됩니다.

FailedExecution 필드: `execution_id`, `order_id`, `snapshot_id`, `snapshot_hash`, `completion`, `final_status`, `started_at`, `finished_at`, `error`. error는 마지막 EXECUTION_ERROR의 `data` 원문이며 category가 없으면 실행 행의 error_category를 보완합니다. 따라서 과거 기록이나 중단 실행에는 `category`만 있을 수 있습니다. 누락된 상세를 임의로 채우지 않습니다.

### 4.3 GET /api/executions/{id}/trace

UUID 실행 조회. 없으면 `404 EXECUTION_NOT_FOUND`. 전체 필드는 다음과 같습니다.

| 필드 | 타입·값 |
|---|---|
| execution_id | UUID |
| run_id | `r_` + execution UUID의 하이픈 제거 32자리 |
| order_id | UUID |
| snapshot_id, snapshot_hash | UUID/SHA256 또는 null; snapshot 고정 전 오류 가능 |
| agent_id | `camping-order-agent` |
| mode | `live` 또는 `dry_run` |
| model_mode | `fixed` 또는 `openai` |
| model | fixed-policy-v1 또는 모델 설정값 |
| completion | RUNNING/COMPLETED/FAILED/INTERRUPTED |
| raw_selection, resolved_skill_id | 모델이 고른 원문·해석된 ID, 없으면 null |
| error_category, final_status | 오류 범주·최종 주문 상태 또는 null |
| api_calls | integer, 모델 API 시도 수; fixed에서는 0 |
| input_tokens, output_tokens | 관측 토큰 합 또는 null |
| elapsed_ms | 전체 측정 시간 또는 null |
| started_at, finished_at | datetime, 종료 전 finished_at null |
| events | `{sequence:integer, kind:string, skill_id:string\|null, data:object, at:datetime}[]` |

events는 sequence 오름차순입니다. API·토큰 미관측과 0을 구분해 주세요. DB 업무 steps의 번호와 events.sequence, events.data.step_no는 서로 다른 번호입니다.

### 4.4 GET /api/traces/{runId}

이전 호출자를 위한 별칭입니다. `r_[a-fA-F0-9]{32}`만 받고 UUID로 변환한 뒤 위 trace 전체를 반환합니다. 잘못된 runId도 `404 EXECUTION_NOT_FOUND`입니다. Python 진단의 thread_id와 혼용하지 않습니다.

### 4.5 이벤트별 data 전체 계약

필드는 해당 이벤트 발생 시 실제 작성되는 칸입니다. 실패 위치·과거 버전에 따라 이벤트 자체가 없을 수 있습니다.

| kind | data 필드 | 발생 지점 |
|---|---|---|
| EXECUTION_STARTED | order_id, mode(model mode) | trace 시작; 최상위 mode(run mode)와 의미가 다름 |
| SNAPSHOT_FIXED | snapshot_id, hash | 실행 파일 고정 |
| CANDIDATES_OFFERED | candidates[{skill_id,name,description,version}] | 선택 후보 제공 |
| DECISION_INPUT | purpose, inputs, tool_definition, skill_hash | select/tool 결정 입력 기록 |
| MODEL_REQUEST | purpose, attempt | 실제 모델 API 요청 직전 |
| MODEL_RESPONSE | raw_response, input_tokens, output_tokens | fixed 포함 결정 응답 |
| DECISION_ERROR | category, attempt | 모델 결정 실패 |
| SKILL_SELECTED | raw_name, resolved_skill_id(null) | 모델 선택 원문 |
| SKILL_RESOLVED | raw_name, resolved_skill_id | 실제 해석된 flow |
| SKILL_LOAD_REJECTED | requested_name, category | 선택한 파일 로드 거절 |
| SKILL_LOADED | path, version, hash | flow 또는 단계의 실제 로드 |
| FLOW_VALIDATED | steps | 정적 검사 통과 |
| SKILL_STARTED | step_no, version, hash, input | 런타임 입력 검사 직전 |
| TOOL_REQUEST | tool, arguments; 보상 시 reason 추가 | 도구 요청, 실행 성공의 증거는 아님 |
| TOOL_RESULT | tool, arguments, result, elapsed_ms | 일반 도구 커밋. result={context,final_status} |
| TOOL_RESULT (보상) | tool, arguments, result={inventory.reserved:false} | 결제 거절·실패 보상; 재시작 복구는 arguments가 없을 수 있음 |
| TOOL_REPLAY | tool, arguments | 실행·도구·인자 해시의 기존 확정 결과 사용 |
| TOOL_ERROR | tool, arguments, category | 권한·인자·업무 도구 오류 |
| SKILL_COMPLETED | declared_output | 선언 outputs를 다음 문맥으로 전달 |
| SKILL_FINISHED | step_no,version,status,output,duration_ms; 업무 종료면 final_status,business_stop; 오류면 category | 실제 단계 종료 |
| BUSINESS_STOP | final_status | REJECTED/HOLD의 정상 업무 중단 |
| EXECUTION_ERROR | category; 가능하면 skill_id,phase,missing_inputs,missing_dependencies,message,reason | 실행 오류. 모든 선택 필드를 항상 주지는 않음 |
| EXECUTION_INTERRUPTED | category=PROCESS_INTERRUPTED | 재시작 복구 |
| EXECUTION_FINISHED | completion,final_status; 경로에 따라 error_category | 실행 종료 |

정적 검사 실패 시 SKILL_STARTED/FINISHED가 없는 것이 정상입니다. `OrderAgentTraceConverter`가 Judge용 합성 오류 Step을 만들 수 있지만, 원본 trace의 실제 실행 기록으로 해석해서는 안 됩니다.

## 5. Snapshot·Skill 조회·저장

근거: [SnapshotService](../../../order-agent/backend/src/main/java/com/capstone4/orderagent/workspace/SnapshotService.java), [SkillQueryService](../../../order-agent/backend/src/main/java/com/capstone4/orderagent/workspace/SkillQueryService.java).

### 5.1 공통 Manifest

```json
{
  "snapshot_id":"<UUID>",
  "hash":"<SHA256>",
  "created_at":"2026-10-22T00:00:00Z",
  "metadata":{"source":"candidate"},
  "files":[{"path":"skills/payment/SKILL.md","hash":"<SHA256>","size":123}]
}
```

snapshot hash는 정렬된 경로별 파일 해시와 metadata를 포함한 canonical JSON 해시입니다. 파일이 같더라도 metadata가 다르면 snapshot hash가 달라질 수 있습니다. 파일 크기는 바이트입니다. 조회 시 파일별 해시와 묶음 해시를 다시 검증하며 불일치는 `SNAPSHOT_TAMPERED`입니다.

### 5.2 GET /api/snapshots/{id}

UUID snapshot Manifest 조회. 없음 `404 SNAPSHOT_NOT_FOUND`, 변조 `400 SNAPSHOT_TAMPERED`.

### 5.3 GET /api/snapshots/{id}/files?path=...

`path` query 필수이며 Manifest 경로를 그대로 URL 인코딩하여 사용합니다. 호스트 파일 경로를 읽는 API가 아닙니다.

반환: `{snapshot_id, path, hash, size, encoding, content}`. UTF-8이면 encoding=`utf-8`, content=원문; 디코딩 불가이면 encoding=`base64`, content=Base64입니다. 없는 경로는 `404 SNAPSHOT_FILE_NOT_FOUND`입니다. 누락된 path는 `400 VALIDATION_ERROR`입니다.

### 5.4 GET /api/snapshots/{id}/skills

반환: `{snapshot_id, snapshot_hash, items: SkillMetadata[], partial:boolean, parse_errors:[{path,code,message}]}`. `skills/<id>/SKILL.md`만 열거하고 skill_id로 정렬합니다. 해석 실패 파일은 items에서 제외하고 parse_errors에 보존합니다. partial=true를 정상적인 전체 목록으로 처리하지 않습니다.

SkillMetadata 전체: `skill_id`, `name`, `version`(양의 정수), `description`, `owner`, `path`, `hash`, `inputs:string[]`, `outputs:string[]`, `depends_on:string[]`, `steps:string[]`.

### 5.5 GET /api/snapshots/{id}/skills/{skillId}

SkillMetadata에 `snapshot_id`, `snapshot_hash`, `size`, `encoding:"utf-8"`, `content`를 추가합니다. content는 frontmatter 포함 원문 전체입니다. id는 최대 64자, `[a-z][a-z0-9]*(?:-[a-z0-9]+)*`. 없음 `404 SKILL_NOT_FOUND`, 이름 불량 `400 INVALID_SKILL_NAME`, 파싱 불가 `400 SKILL_METADATA_INVALID`입니다. 관련 resource 파일은 files API를 사용합니다.

### 5.6 POST /api/snapshots

요청 `{ "fixture":"baseline" }`. 허용값 `baseline`, `broken-contract`, `repaired`. 준비된 시연용 묶음을 새 snapshot으로 저장하고 Manifest를 `200`으로 반환합니다. baseline은 published bundle, broken-contract는 inventory 출력 선언 일부 삭제, repaired는 출력 복구와 설명 추가입니다. AI가 만든 수정안이 아닙니다. 운영 bundle은 변경하지 않습니다.

demo-enabled=false이면 `404 SNAPSHOT_NOT_FOUND`. null/미지원 fixture는 `400 UNKNOWN_FIXTURE`입니다.

### 5.7 POST /api/snapshots/candidates

```json
{"files":{"skills/payment/SKILL.md":"---\n..."},"metadata":{"workspace_id":"w_example","source_snapshot_id":"<UUID>"}}
```

files는 경로→UTF-8 문자열 전체 묶음, metadata는 임의 JSON object입니다. 서버가 metadata.source=`candidate`를 덮어씁니다. 새 Manifest를 `200`으로 반환합니다. 경로·크기 검사는 하지만 Skill 계약 전체의 정상 여부는 저장 단계에서 강제하지 않습니다. 결함 재현용 snapshot도 저장할 수 있습니다.

저장 제한: 파일 1~256개, 각 파일 ≤1,000,000바이트, 총합 ≤5,000,000바이트. `skills/` 접두어, 경로 ≤500자, 역슬래시·NUL·빈 segment·`.`·`..` 금지. 위반은 INVALID_BUNDLE_SIZE/PATH. demo-disabled는 404입니다. null files는 빈 묶음 오류이고, Map 안 null 값은 별도 검증이 없어 500으로 이어질 수 있는 개선 대상입니다.

### 5.8 POST /api/snapshots/edits

```json
{
  "baseline_snapshot_id":"<UUID>",
  "baseline_hash":"<SHA256>",
  "changes":{"skills/inventory-check/SKILL.md":"---\n..."}
}
```

baseline ID·hash·비어 있지 않은 changes가 필요합니다. changes는 64개 이하이며 경로는 `skills/[a-z0-9][a-z0-9-]*/SKILL.md`, content는 null 불가입니다. 기존 파일 덮어쓰기와 새 Skill 파일 추가를 허용하고 삭제·resource 편집은 제공하지 않습니다. 실제 바이트가 달라진 경로만 changed_paths에 들어갑니다. **형식·계약이 깨진 Skill도 저장할 수 있지만 후속 진단이 처리할 수 있다는 보장은 없습니다.**

성공 201: 공통 Manifest + `baseline_snapshot_id`, `change_id`, `changed_paths:string[]`, `validation_status:"QUEUED"`. metadata에도 source=user-edit, change_id, baseline_snapshot_id/hash, changed_paths, validation_status가 저장됩니다.

하나의 트랜잭션에서 snapshot/files와 skill_change_events를 함께 저장합니다. HTTP 응답의 QUEUED는 이벤트가 저장됐다는 의미이며 Python 진단 완료를 뜻하지 않습니다. 조회 시 metadata.validation_status는 갱신되지 않는 초기값입니다. 실제 진행은 change delivery와 diagnoses를 조회합니다.

주요 오류: `INVALID_SKILL_EDITS`, `BASELINE_HASH_MISMATCH`, `NO_SKILL_CHANGES`, `INVALID_BUNDLE_SIZE`, `SNAPSHOT_NOT_FOUND`. 이 API는 demo-enabled 검사도 없고 인증도 없습니다.

### 5.9 GET /api/skill-changes/{id}

```json
{"change_id":"<UUID>","snapshot_id":"<UUID>","thread_id":null,"attempts":0,"last_error":null,"delivery_status":"PENDING"}
```

thread_id가 저장되면 delivery_status=`ACCEPTED`; 없으면 `PENDING`. attempts는 전달 시도 수, last_error는 마지막 전송 예외의 축약 안내입니다. ACCEPTED는 진단 접수이며 진단 성공이 아닙니다. 없음 `404 SKILL_CHANGE_NOT_FOUND`.

Dispatcher는 기본 3초 간격에 미전달 이벤트의 가장 오래된 1건을 `FOR UPDATE SKIP LOCKED`로 잡고 SkillOps Java에 전송합니다. 성공하면 thread_id를 기록하고 실패하면 시도 수·오류를 기록하여 다음 주기에 재시도합니다. 재시도 상한·dead-letter·next_retry_at은 없습니다.

## 6. POST /api/dry-runs

```json
{"snapshot_id":"<UUID>","order":{"customer":"c_1001","items":[{"sku":"CAMP-LANTERN-LED","qty":1}],"payment_method":"card","shipping":{"country":"KR","express":false}}}
```

snapshot_id와 order가 필수입니다. order에는 3절 CreateOrderRequest 검증이 적용되며, order.snapshot_id보다 **최상위 snapshot_id가 우선**합니다. demo-enabled가 필요합니다.

반환 200: `{execution_id, run_id, order_id, final_status, completion}`. 예: final_status=FAILED, completion=FAILED도 정상적으로 반환합니다. snapshot 조회·입력·접수 오류는 HTTP 오류로 반환됩니다. 실행 증거가 생기지 않았다면 500/503이 될 수 있습니다.

현재 구현은 새 임의 멱등 키로 **실제 orders에 dry_run 주문을 만들고 같은 Harness와 상품 재고를 사용한 뒤 예약을 해제**합니다. 주문·trace·Mock 결제/배송 참조는 남습니다. 인메모리 격리 환경이 아니며 멱등 키를 받거나 재조회하는 전용 API가 없습니다. 네트워크 timeout 뒤 POST를 자동 재시도하면 새 시험 주문이 추가됩니다. live 실패 목록에는 나오지 않지만 최근 실행 목록에는 포함됩니다.

정상 종료 시 cleanup에서 PAID 여부와 관계없이 남은 재고 예약을 해제합니다. 서버 중단·cleanup 실패 시 보장 범위는 [리뷰](architecture-review.md)의 dry-run 복구 항목을 확인해 주세요.

## 7. SkillOps Java 로그인·제안서·승인

근거: [SecurityConfig](../../../service/src/main/java/com/capstone4/skillops/auth/SecurityConfig.java), [API 패키지](../../../service/src/main/java/com/capstone4/skillops/api), [ApprovalService](../../../service/src/main/java/com/capstone4/skillops/approval/ApprovalService.java).

### 7.1 GET /api/health

항상 `200 {"status":"ok","service":"skillops-service"}`. DB·MCP downstream·Python의 상태를 확인하는 readiness 검사가 아닙니다.

### 7.2 POST /api/auth/login

요청 `{username:string, password:string}`. 둘 다 비공백. 성공:

```json
{"access_token":"<JWT>","token_type":"Bearer","expires_in":7200,"user":{"username":"operator","display_name":"운영자","roles":["OPERATOR"]}}
```

기본 데모 계정 owner/owner1234, operator/operator1234. 계정은 설정에 있고 사용자 DB·회원가입은 없습니다. 비밀번호는 BCrypt 비교입니다. 실패 `401 {code:"INVALID_CREDENTIALS",message:...}`. 임시 JWT 키로 기동한 경우 재시작하면 기존 토큰은 무효입니다.

### 7.3 GET /api/auth/me

JWT 필수. 토큰 claim에서 `{username,display_name,roles}`를 반환합니다. 사용자 저장소를 매 요청 재조회하는 구조는 아닙니다.

### 7.4 GET /api/proposals

JWT 필수. 최근 생성 100건 `{items:ProposalSummary[]}`. 페이지 파라미터 없음.

ProposalSummary 전체: `proposal_id`, `status`, `created_at`, `title`, `summary`, `root_cause_skill`, `recommended_candidate_no`, `valid_candidate_nos:integer[]`, `candidate_count:integer`.

status: `proposed`, `no_valid_fix`, `approved`, `rejected`. 합격안이 없는 no_valid_fix도 검토 후 닫기 위한 목록 대상입니다. 본문이 없던 과거 행은 title/summary가 null일 수 있습니다.

### 7.5 GET /api/proposals/{proposalId}

proposalId 정규식 `p_[A-Za-z0-9_]{1,62}`. JWT 필수. 성공 필드 전체:

```text
proposal_id, status, created_at, decided_at,
thread_id, valid_candidate_nos, recommended_candidate_no,
document, proposal, decision
```

`proposal`은 MCP save_proposal로 받은 원문 payload입니다([전체 필드](mcp-spec.md)). `decision`은 아래 ApprovalView 또는 null입니다. `document`는 저장 당시 작성한 고정 본문이며 아래 구조입니다.

```text
document = {title, summary, sections:Section[], tone_warnings:ToneWarning[]}
Section = {key, heading, paragraphs:string[], quote:string|null, items:string[],
           facts:Fact[], parts:Section[], candidate_no:integer|null}
Fact = {label:string, value:string}
ToneWarning = {where:string, phrase:string}
```

sections는 overview, what_happened, cause, 선택적 intent, candidates, decision, reference를 구성합니다. 후보 절에 재귀 parts가 있습니다. tone_warnings는 후보 설명의 표현 검사 결과이며 현재 화면에서 별도로 표시하지 않습니다. 승인 시 payload.intent.confirmed는 true로 갱신되지만 document는 저장 시점 그대로입니다. 최신 결정은 최상위 status/decision을 기준으로 해석해 주세요.

없음 `404 {code:"PROPOSAL_NOT_FOUND",message:...}`. 잘못된 ID의 검증 오류 본문은 공통으로 고정하지 않았습니다.

### 7.6 POST /api/proposals/{proposalId}/approve

JWT + OPERATOR. 요청 `{candidate_no:integer>=1, intent_confirmed:boolean}`; 둘 다 필수. 의도 확인이 false면 `400 INTENT_NOT_CONFIRMED`.

저장된 valid_candidate_nos에 포함된 후보만 승인할 수 있습니다. 제안서 상태 변경과 승인 행 추가를 하나의 트랜잭션으로 저장합니다. Proposal의 `@Version`으로 동시 결정을 제어합니다. **운영 파일 변경이나 Python 재개는 수행하지 않습니다.**

ApprovalView 전체:

```json
{"proposal_id":"p_example","decision":"approved","candidate_no":1,"intent_confirmed":true,"approval_token":"a_XXXXXXXXXX","approved_by":"operator","approver_name":"운영자","decided_at":"2026-10-22T00:00:00Z","used_at":null}
```

approval_token은 승인 기록 참조이며 현재 사용·승격 기능이 연결되지 않았습니다. used_at도 이 흐름에서는 null입니다.

오류: `404 PROPOSAL_NOT_FOUND`, `409 PROPOSAL_ALREADY_DECIDED`, `409 NO_VALID_FIX`, `400 CANDIDATE_NOT_VALID`, `400 INTENT_NOT_CONFIRMED`, 권한 관련 401/403.

### 7.7 POST /api/proposals/{proposalId}/reject

JWT + OPERATOR. 본문을 받지 않습니다. no_valid_fix도 거절하여 닫을 수 있습니다. ApprovalView의 decision=rejected, candidate_no=null, intent_confirmed=false, approval_token=null. 이미 결정했으면 409, 없으면 404입니다. 원문 제안서·후보·증거는 보존됩니다.

### 7.8 GET /api/approvals

JWT 필수. 결정 시각 내림차순 최근 50건 `{items:ApprovalView[]}`. 승인과 거절 모두 포함합니다. 페이지 파라미터 없음.

## 8. SkillOps Java 진단 중계

근거: [DiagnosisController](../../../service/src/main/java/com/capstone4/skillops/diagnosis/DiagnosisController.java).

### 8.1 POST /internal/skill-changes

X-Service-Token이 설정되어 있으면 일치해야 합니다. 요청 Map에서 `type == "skill_saved"`, change_id가 null이 아님을 검사하고 전체 요청을 Python POST /diagnose로 전달합니다. 대표 본문:

```json
{"type":"skill_saved","change_id":"<UUID>","baseline_snapshot_id":"<UUID>","candidate_snapshot_id":"<UUID>","changed_paths":["skills/inventory-check/SKILL.md"]}
```

성공 본문은 Python의 접수 결과 `{accepted,thread_id,duplicate,trigger}`입니다. Java는 Map 반환이므로 **HTTP 200**이며 Python의 202를 그대로 보존하지 않습니다. 토큰 불일치 401, 간단 입력 검사 실패 400. downstream 404는 404, 그 밖의 downstream 상태(409·422 포함)는 `502 DIAGNOSIS_UPSTREAM_ERROR`로 바뀝니다. 연결·timeout은 `502 DIAGNOSIS_UNAVAILABLE`입니다. connect 2초/read 5초.

### 8.2 GET /api/diagnoses

JWT 필요. Python GET /runs를 서버 토큰과 함께 호출하고 `{items:DiagnosisRun[]}`을 반환합니다. Java DB에 진단 상태를 별도 복제하지 않습니다.

### 8.3 GET /api/diagnoses/{threadId}

threadId UUID 바인딩, JWT 필수. Python GET /runs/{threadId}/events 결과를 그대로 반환합니다. 해당 경로의 events는 이벤트 배열만이 아니라 **실행 상태 전체 객체**입니다. 없음 404, upstream·통신 실패 502.

## 9. Python FastAPI

근거: [main.py](../../../skillops-agent/skillops_agent/main.py), [RunStore](../../../skillops-agent/skillops_agent/run_store.py), [상태 타입](../../../skillops-agent/skillops_agent/state.py).

### 9.1 GET /health

`200 {"status":"ok","service":"skillops-agent"}`. 인증 없음. LLM/MCP 연결을 검사하지 않습니다.

### 9.2 POST /diagnose

설정 시 X-Service-Token 필요. 성공 202이며 BackgroundTasks로 파이프라인을 실행합니다. 요청 모델의 추가 필드는 `extra="forbid"`로 거부됩니다.

| 필드 | 타입·기본 | 조건 |
|---|---|---|
| type | failure 또는 skill_saved, 필수 | 진단 시작 계기 |
| execution_id | string/null | UUID 또는 r_별칭을 UUID로 정규화 |
| run_id | string/null | execution_id 별칭; 둘 다 주면 같은 실행이어야 함 |
| baseline_snapshot_id | UUID 문자열/null | 파이프라인에서 필수이며 candidate metadata로 보완 가능 |
| candidate_snapshot_id | UUID 문자열/null | skill_saved는 필수; failure는 trace에서 확정 |
| changed_paths | string[]/null | 주면 양 snapshot의 실제 변경 경로 집합과 같아야 함 |
| change_id | string/null | 멱등 접수 키. UUID 형식 검증은 없음 |
| entry_skill_id | string, order-flow | 후보 생성 시 의존 그래프 조회에 사용 |
| diagnosis_only | boolean, false | 실행과 intent_checks가 함께 있어야 함 |
| intent_checks | string[], [] | diagnosis_only에서 별도 조건 검증 |
| skill | string/null | 이전 호출 호환용, 변경 파일 결정 근거로 사용하지 않음 |
| version | integer/null | 이전 호출 호환용, snapshot을 대신하지 않음 |

type=failure는 execution_id 또는 run_id 필수. skill_saved는 candidate_snapshot_id 필수입니다. 단, baseline 부재 등 일부 조건은 접수 후 fetch_evidence에서 ERROR가 됩니다. 지원되지 않는 resource 변경·Skill 추가/삭제·메타데이터 불량 역시 저장 API 접수 가능 범위와 다릅니다.

```json
{"accepted":true,"thread_id":"<UUID>","duplicate":false,"trigger":{"type":"failure","execution_id":"<UUID>","baseline_snapshot_id":"<UUID>"}}
```

실제 trigger에는 model_dump의 모든 기본 필드도 들어갑니다. 같은 change_id + 같은 정규화 요청 fingerprint는 기존 thread_id, duplicate=true이며 재실행하지 않습니다. 같은 change_id + 다른 요청은 `409 {detail:"change_id already refers to a different request"}`. change_id가 없으면 동일 요청도 매번 새 진단입니다. validation 오류 422, 토큰 오류 401.

### 9.3 GET /runs, GET /runs/{thread_id}/events

설정 시 서버 토큰 필요. 목록은 최근 created_at 순 100건 `{items:[...]}`. 상세의 thread_id는 Python에서 단순 문자열이며 UUID를 강제하지 않습니다. 없으면 404 `{detail:"Diagnosis not found"}`.

DiagnosisRun은 응답 DTO가 아닌 SQLite JSON 상태입니다. 실행 진행에 따라 다음 필드가 채워집니다.

| 구분 | 필드 |
|---|---|
| 접수·식별 | thread_id, change_id, trigger, created_at, pipeline_thread_id |
| 진행 | diagnosis_status, events[{at,node,msg,run_id}], error, step_count,max_steps |
| 증거 | trace,diff,changes,skill_versions,snapshot_id,baseline_snapshot_id,execution_id,preflight_run_ids |
| 가설·원인 | hypotheses[{change_no,summary,confidence}],ablation_runs[{control_run_ids,change_no,workspace_id,run_ids,confirmed}],root_cause,hypothesis_retry |
| 의도 | intent{text,checks,dropped_checks[{check,reason}],confirmed} |
| 후보 | candidates,candidate_retry,recommended(0부터 시작하는 배열 인덱스) |
| 저장 | proposal(원문+id,storage_status,valid_candidate_nos) |
| 이전 흐름 상태 | approval_status,approval_token,smoke_passed; 현행 그래프에서는 승인 재개·승격에 사용하지 않음 |
| 별도 진단 | diagnosis{explanation,failures,intent_checks?,evidence_run_ids?} |

root_cause에는 skill,change_no,hunk_id,from_version,to_version,summary,proof_run_ids가 들어갑니다. 현재 from_version/to_version은 모두 candidate 선언 version에서 채워지므로 양 snapshot의 실제 파일 버전 차이를 나타내지 않을 수 있습니다.

상태: QUEUED → RUNNING → VALIDATED / BASELINE_FAILED / VALIDATION_FAILED / NO_SKILL_CHANGES / INTENT_VIOLATION / EVALUATED / ERROR. VALIDATED는 등록된 golden 시험 통과, EVALUATED는 평가 또는 제안서 저장 종료입니다. EVALUATED만으로 유효 후보 존재를 판단하지 말고 proposal.status/valid_candidate_nos를 확인합니다.

기본 DB는 `skillops-agent/.data/runs.sqlite3`, 환경변수 SKILLOPS_RUN_DB로 변경 가능합니다. 재기동 때 QUEUED/RUNNING은 ERROR로 표시하고 자동 재개하지 않습니다.

### 9.4 POST /approve — 이전 경로

요청 `{proposal_id:string, status:"approved"|"rejected", token:string|null}`. 설정 시 서버 토큰 필요. 메모리 _pending_approvals에서 proposal_id를 pop하여 thread를 찾고 `Command(resume=...)`를 BackgroundTasks로 보냅니다. 응답 `{accepted:true,proposal_id,status}`. 없음/이미 소비됨 404.

현재 그래프는 save_proposal → END이며 wait_for_approval를 등록하지 않습니다. Java 승인도 이 API를 호출하지 않습니다. 따라서 이 API를 현재 운영 승인 계약으로 사용하면 안 됩니다. 실제 승인 계약은 7.6~7.8입니다.

## 10. 오류 계약·재시도·미구현 경계

### 10.1 주문 서버 오류

```json
{"code":"VALIDATION_ERROR","message":"주문 입력을 확인해주세요.","order_id":null,"field_errors":[{"field":"items[0].qty","message":"..."}]}
```

Bean Validation의 field 이름은 Java 속성명(paymentMethod 등)일 수 있습니다. snake_case 오류 경로로 전부 변환하지 않습니다.

| HTTP | code |
|---|---|
| 400 | VALIDATION_ERROR, MALFORMED_JSON |
| 404 | PRODUCT_NOT_FOUND, ORDER_NOT_FOUND |
| 409 | IDEMPOTENCY_CONFLICT |
| 503 | PROCESSING_UNAVAILABLE |
| 500 | INTERNAL_ERROR |

AgentException은 category 문자열이 `NOT_FOUND`로 끝나면 404, 그 밖에는 400입니다. 대표 조회/저장 범주는 EXECUTION_NOT_FOUND, SNAPSHOT_NOT_FOUND, SNAPSHOT_FILE_NOT_FOUND, SKILL_NOT_FOUND, SKILL_CHANGE_NOT_FOUND, INVALID_SKILL_NAME, SKILL_METADATA_INVALID, UNKNOWN_FIXTURE, INVALID_SKILL_EDITS, BASELINE_HASH_MISMATCH, NO_SKILL_CHANGES, SNAPSHOT_TAMPERED, INVALID_BUNDLE_PATH/SIZE, INCOMPLETE_BUNDLE, BUNDLE_READ_ERROR입니다. 실행 내부의 CONTRACT_VIOLATION·API_AUTH 등은 trace.error_category로 보존되지만 일반 주문 POST에서는 500 래퍼가 될 수 있습니다.

### 10.2 SkillOps Java 및 Python 오류

Java 보안 오류는 `{code,message}`: 401 UNAUTHORIZED(+WWW-Authenticate: Bearer), 403 FORBIDDEN. 인증·제안서 업무·중계 오류 외의 validation/예외 전체를 통일하는 전역 Advice는 없습니다. 모든 Java API 오류가 같은 모양이라고 가정하지 말아 주세요. Python은 FastAPI `{detail:...}`이며 요청 검증은 detail 배열일 수 있습니다. MCP 오류는 HTTP status가 아니라 isError와 content JSON을 봅니다.

### 10.3 재시도 표

| 경계 | 현재 처리 |
|---|---|
| Python → 주문 조회 4개 | 연결·timeout·429·5xx, 최대 3회, 0.3·0.6초 backoff. 단독 timeout 5초, MCPTools 공유 클라이언트에서는 30초 |
| Python → snapshot Manifest | 단순 httpx GET, 별도 재시도 래퍼 없음 |
| Java C-1 → snapshot 조회 | connect 2초/read 5초, 최대 3회, 300·600ms backoff |
| Java C-2 → 주문 실행·조회 | connect 2초/read 60초, 자동 재시도 없음; 캐시된 snapshot 404일 때만 재발행 후 실행 |
| 저장 이벤트 전달 | 기본 3초 주기, 성공할 때까지 같은 change_id 전달 |
| 주문 모델 결정 | API_TIMEOUT/RATE_LIMIT/TRANSIENT만 최대 2회, 모든 시도를 예산에 포함 |
| 브라우저 주문 timeout | 동일 요청 키로 GET 복구; 통합 화면 최대 약 60초 루프, 독립 주문 화면은 수동 재확인·같은 요청 재전송 제공 |

### 10.4 제공되지 않는 API

운영 promote·rollback·smoke check·workspace 조회 REST·시나리오 등록 REST·snapshot 전체 목록·주문 전체 목록·실패 자동 webhook 완성 경로는 없습니다. SpringDoc UI(`/swagger-ui.html`)·OpenAPI(`/v3/api-docs`), FastAPI(`/docs`, `/redoc`, `/openapi.json`)는 프레임워크 생성 경로입니다. 실제 공개 여부는 배포 설정으로도 확인해야 합니다.

### 10.5 실행 오류 범주 전체

주문 모듈 소스가 직접 생성하는 AgentException의 상수 범주를 아래에 정리했습니다. ‘발생 위치’는 내부 실행 위치이며, 같은 문자열이 모든 REST 경로에서 동일 HTTP 코드로 전달된다는 뜻은 아닙니다.

| 분류 | 범주 | 의미 |
|---|---|---|
| 모델 | API_AUTH, API_ERROR, API_RATE_LIMIT, API_TIMEOUT, API_TRANSIENT | 인증·분류되지 않은 호출 오류·속도 제한·응답 시간 초과·일시 오류 |
| 한도·중단 | BUDGET_EXHAUSTED, DEADLINE_EXCEEDED, TOKEN_LIMIT_EXCEEDED, EXECUTION_INTERRUPTED | 실행별 API 시도·전체 시간·관측 토큰·스레드 중단 |
| 모델 형식·선택 | MODEL_FORMAT_ERROR, ROOT_SKILL_MISMATCH, UNKNOWN_SKILL, UNSUPPORTED_SKILL | JSON/필수 응답 형식 불량, 시작 flow 불일치, 파일 없음, binding 미지원 |
| 선언 | INVALID_SKILL_NAME, INVALID_SKILL_FORMAT, INVALID_FLOW, CONTRACT_VIOLATION, MISSING_SKILL_OUTPUT, INCOMPLETE_FLOW | 이름·YAML·순서 불량, 입력/선행 부족, 실제 출력 누락, notify까지 완료되지 않음 |
| 도구·상태 | UNKNOWN_TOOL, TOOL_NOT_ALLOWED, INVALID_TOOL_ARGUMENTS, EXECUTION_NOT_RUNNING, ORDER_NOT_PROCESSING, IDEMPOTENCY_CONFLICT | 허용 도구·정확한 서버 인자·실행/주문 상태·기존 결과와 인자 일치 위반 |
| 조회 | EXECUTION_NOT_FOUND, SNAPSHOT_NOT_FOUND, SNAPSHOT_FILE_NOT_FOUND, SKILL_NOT_FOUND, SKILL_CHANGE_NOT_FOUND | 조회 참조 없음 |
| 조회 파싱·무결성 | SKILL_METADATA_INVALID, SNAPSHOT_TAMPERED | Skill 파싱 불가·snapshot 내용 불일치 |
| 게시 bundle | INVALID_BUNDLE_PATH, INVALID_BUNDLE_SIZE, INCOMPLETE_BUNDLE, BUNDLE_READ_ERROR | 경로·수/크기 제한·manifest 파일 집합/hash 불일치·파일 읽기 실패 |
| 편집·시연 | INVALID_SKILL_EDITS, BASELINE_HASH_MISMATCH, NO_SKILL_CHANGES, UNKNOWN_FIXTURE | 잘못된 편집 입력·기준 불일치·실제 변경 없음·fixture 불명 |

이 밖에 일반 RuntimeException은 Harness에서 EXECUTION_ERROR, ToolDispatcher에서 TOOL_ERROR로 분류될 수 있습니다. 기동 복구는 PROCESS_INTERRUPTED를 기록하고, 업무 종료 코드는 OUT_OF_STOCK/PAYMENT_DECLINED/NO_SELECTION입니다. 이들은 동일한 오류 계층이 아닙니다.

## 11. 호출 예시와 응답 유실 처리

아래 명령은 **사용 방법을 설명하는 예시**이며 이번 리뷰에서 운영 주문을 실행한 기록은 아닙니다. 토큰·ID는 실제 값으로 치환해 주세요.

```bash
# 읽기: 상품 / 실패 / 고정 원문
curl http://localhost:8081/api/products
curl http://localhost:8081/api/executions/failed
curl 'http://localhost:8081/api/snapshots/<snapshot-uuid>/files?path=skills%2Fpayment%2FSKILL.md'

# 같은 주문 복구에는 아래 키를 새로 생성하지 않고 계속 보관합니다.
curl -i http://localhost:8081/api/orders \
  -H 'Content-Type: application/json' \
  -H 'Idempotency-Key: <request-uuid>' \
  -d '{"customer":"c_1001","items":[{"sku":"CAMP-LANTERN-LED","qty":1}],"payment_method":"card","shipping":{"country":"KR","express":false}}'
curl 'http://localhost:8081/api/orders/by-request/<request-uuid>'

# 제안서: 로그인 후 받은 access_token을 사용합니다.
curl http://localhost:8080/api/auth/login \
  -H 'Content-Type: application/json' \
  -d '{"username":"operator","password":"<password>"}'
curl http://localhost:8080/api/proposals \
  -H 'Authorization: Bearer <access_token>'
curl 'http://localhost:8080/api/proposals/<proposal-id>/approve' \
  -H 'Authorization: Bearer <access_token>' \
  -H 'Content-Type: application/json' \
  -d '{"candidate_no":1,"intent_confirmed":true}'
```

주문 POST의 응답을 잃으면 같은 request UUID로 GET하여 접수를 확인합니다. 404 한 번으로 접수 실패를 단정하지 않고, 이미 order_id를 알고 있다면 해당 주문의 상태를 확인합니다. 명확히 미접수임을 확인하여 재전송할 때도 같은 키와 같은 정규화 입력을 유지합니다. dry-run에는 이 요청 멱등 키 계약이 아직 없으므로 같은 방식으로 안전하게 재전송할 수 있다고 가정하지 않습니다.
