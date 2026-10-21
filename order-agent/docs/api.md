# 주문 데모 REST API 명세 v1

> MVP 이후 구현: 현재 Skill·Harness·snapshot·trace 기능이 추가됐다. 최신 API·저장 방식·실패/트랜잭션·시연 후보·담당자 TODO는 [agent-evidence.md](agent-evidence.md)를 우선한다. 아래의 미구현/후속 표기는 작성 당시 단계 설명이다.

상태: 1차 MVP 구현·Docker/API 검증 완료 (2026-10-20). 검증 결과는 [verification.md](verification.md)를 참고한다.

호스트에서 백엔드 직접 접근: `http://localhost:8081` / prefix: `/api`

브라우저 화면: `http://localhost:5174`. 프론트는 상대 경로 `/api`로 호출하고, frontend 컨테이너의 Vite가 Docker 내부 `http://backend:8080`으로 프록시한다.

JSON 필드명은 snake_case로 통일한다. 금액은 KRW 원 단위 정수, 날짜는 YYYY-MM-DD, 시각은 UTC ISO-8601로 반환한다. 화면에서는 사용자 시간대로 표시한다. 모든 ID는 문자열이며 주문 ID와 request_id는 UUID다.

## 1. API 목록

| 메서드 | 경로 | 용도 |
|---|---|---|
| GET | `/api/health` | 백엔드 기동 확인 |
| GET | `/api/products` | 활성 상품·가격·현재 재고 |
| POST | `/api/orders` | 주문 접수·처리 |
| GET | `/api/orders/{order_id}` | 주문 결과·단계 조회 |
| GET | `/api/orders/by-request/{request_id}` | 응답 유실·중복 요청 복구 |

인증은 없는 로컬 데모다. 배포·실제 고객 데이터 사용은 범위 밖이다. 프론트는 상대 경로 `/api/...`만 사용하고 Vite가 `backend:8080`으로 전달한다. 기존 SkillOps 8080을 호출하지 않는다.

## 2. 기동 확인

`GET /api/health` → 200

```json
{"status": "ok", "service": "order-agent"}
```

이 endpoint의 성공만으로 DB·주문 처리까지 검증됐다고 간주하지 않는다.

## 3. 상품 조회

`GET /api/products` → 200

```json
{
  "items": [
    {"sku": "CAMP-LANTERN-LED", "name": "LED 캠핑 랜턴", "unit_price": 30000, "stock_quantity": 10},
    {"sku": "CAMP-CHAIR-FOLD", "name": "접이식 캠핑 의자", "unit_price": 45000, "stock_quantity": 5},
    {"sku": "CAMP-MUG-STEEL", "name": "스테인리스 캠핑 머그컵", "unit_price": 12000, "stock_quantity": 0}
  ]
}
```

품절 상품도 실패 시연을 위해 노출한다. 수량은 조회 시점 값이며 실제 주문 시 다시 확인한다.

## 4. 주문 생성

`POST /api/orders`

필수 헤더:

```http
Content-Type: application/json
Idempotency-Key: 2e90c83b-52be-4384-ad9a-9314434604fb
```

요청:

```json
{
  "customer": "c_1001",
  "items": [{"sku": "CAMP-LANTERN-LED", "qty": 2}],
  "payment_method": "card",
  "shipping": {"country": "KR", "express": false}
}
```

| 필드 | 검증 |
|---|---|
| Idempotency-Key | 필수 UUID, 입력 오류는 400 |
| customer | 1~64자, 앞뒤 공백 제거, 데모 식별자만 입력 |
| items | 1~20줄, 빈 배열 불가 |
| items[].sku | 필수 1~64자, 활성 SKU인지 확인 |
| items[].qty | 1~99 정수, 중복 SKU 합산 후에도 99 이하 |
| payment_method | card 또는 prepaid |
| shipping.country | KR / US / JP |
| shipping.express | boolean, 생략 시 false |

정규화는 고객 공백 제거, express 기본값, SKU별 수량 합산·정렬을 포함한다. 정규화된 업무 입력으로 request_hash를 계산한다. 상품 가격 등 요청에 없는 값은 hash에 넣지 않는다. 같은 키를 재요청했을 때 현재 가격으로 과거 주문을 다시 계산하지 않는다.

처리 결과가 SHIPPED·REJECTED·HOLD인 최초 주문은 모두 201이다. 201은 주문 생성 성공이며 결제 성공을 뜻하지 않는다. 최초 응답에는 `Location: /api/orders/{order_id}`를 제공한다.

같은 키·같은 입력은 200과 기존 주문을 반환한다. 다른 입력이면 409다. 기존 주문이 PROCESSING이라도 200으로 현재 상태를 반환하며 프론트는 조회 API로 결과를 확인한다. 뜻하지 않은 처리 예외는 500 오류 응답과 저장된 order_id를 반환한다. 이후 조회에서는 해당 FAILED 주문을 200으로 조회할 수 있다.

### 정상 응답 예시

아래 timestamp·날짜·ID는 형식 예시다. 실제 배송일은 구현한 Clock 기준으로 계산한다.

```json
{
  "order_id": "3eb1516b-054d-419b-97e7-c247e9039840",
  "request_id": "2e90c83b-52be-4384-ad9a-9314434604fb",
  "customer": "c_1001",
  "items": [
    {"sku": "CAMP-LANTERN-LED", "name": "LED 캠핑 랜턴", "qty": 2, "unit_price": 30000, "line_total": 60000}
  ],
  "total_amount": 60000,
  "currency": "KRW",
  "payment_method": "card",
  "shipping": {"country": "KR", "express": false},
  "status": "SHIPPED",
  "result_code": null,
  "message": "주문 처리가 완료되었습니다.",
  "payment": {"status": "PAID", "reference": "pay_mock_example"},
  "shipment": {"reference": "ship_mock_example", "estimated_delivery_date": "2026-10-22"},
  "notification": {"status": "SENT"},
  "steps": [
    {"sequence": 1, "step": "INTAKE", "status": "SUCCESS", "result_code": null, "message": "주문 접수 완료", "started_at": "2026-10-20T00:00:00Z", "completed_at": "2026-10-20T00:00:00Z"},
    {"sequence": 2, "step": "INVENTORY", "status": "SUCCESS", "result_code": null, "message": "재고 예약 완료", "started_at": "2026-10-20T00:00:00Z", "completed_at": "2026-10-20T00:00:00Z"},
    {"sequence": 3, "step": "PAYMENT", "status": "SUCCESS", "result_code": null, "message": "Mock 결제 완료", "started_at": "2026-10-20T00:00:00Z", "completed_at": "2026-10-20T00:00:00Z"},
    {"sequence": 4, "step": "SHIPPING", "status": "SUCCESS", "result_code": null, "message": "Mock 배송 예약 완료", "started_at": "2026-10-20T00:00:00Z", "completed_at": "2026-10-20T00:00:00Z"},
    {"sequence": 5, "step": "NOTIFICATION", "status": "SUCCESS", "result_code": null, "message": "Mock 알림 기록 완료", "started_at": "2026-10-20T00:00:00Z", "completed_at": "2026-10-20T00:00:00Z"}
  ],
  "created_at": "2026-10-20T00:00:00Z",
  "updated_at": "2026-10-20T00:00:00Z"
}
```

order_items.sku·name·unit_price는 주문 시점 값이다. `payment.reference`와 `shipment`는 실행되지 않았으면 각각 null이다. `steps`는 sequence 순으로 반환하며 진행 중에는 아직 저장되지 않은 단계가 없을 수 있다. 프론트는 단계가 없다는 이유로 성공을 추정하지 않는다.

### 업무 거절 응답

전체 응답 구조는 동일하며 다음 필드와 단계가 달라진다.

| 경우 | status / result_code | payment / shipment / notification | steps |
|---|---|---|---|
| 품절 | REJECTED / OUT_OF_STOCK | NOT_ATTEMPTED / null / NOT_ATTEMPTED | INTAKE SUCCESS, INVENTORY REJECTED, 나머지 SKIPPED |
| 결제 거절 | HOLD / PAYMENT_DECLINED | DECLINED / null / NOT_ATTEMPTED | INTAKE·INVENTORY SUCCESS, PAYMENT REJECTED, RELEASE SUCCESS, SHIPPING·NOTIFICATION SKIPPED |

SKIPPED 단계의 result_code는 UPSTREAM_REJECTED다. 정상·품절 주문에는 RELEASE 단계가 없으며, 결제 거절 시에만 재고 해제 단계로 추가한다. 단계 개수를 5개로 하드코딩하지 않는다.

## 5. 주문 조회·응답 복구

`GET /api/orders/{order_id}`와 `GET /api/orders/by-request/{request_id}`는 모두 같은 주문 상세 형식으로 200을 반환한다. 없으면 404다.

- 첫 POST의 응답이 끊겨도 주문이 이미 생성됐을 수 있다.
- 프론트는 키로 조회하고, 404면 같은 키·같은 본문으로 POST를 재시도할 수 있다.
- 동시에 접수되더라도 DB UNIQUE 제약으로 중복 주문을 막는다.
- 조회 결과가 CREATED/PROCESSING이면 화면에 진행 상태를 표시한다.
- request_id 형식 오류도 400으로 통일한다.

1차에서는 조회에 인증이 없고 로컬 데모 데이터만 사용한다. 실제 서비스 전환 시 고객별 조회 권한이 필요하다.

## 6. 오류 형식

업무 거절은 주문 응답이다. HTTP 형식·입력 오류·조회 실패·서버 오류는 다음 공통 형식이다.

```json
{
  "code": "VALIDATION_ERROR",
  "message": "주문 입력을 확인해주세요.",
  "order_id": null,
  "field_errors": [{"field": "items[0].qty", "message": "1 이상 99 이하로 입력해주세요."}]
}
```

| HTTP | code | 의미 |
|---|---|---|
| 400 | VALIDATION_ERROR | 수량·필수값·UUID·지원하지 않는 결제/국가 |
| 400 | MALFORMED_JSON | JSON 형식 또는 타입 오류 |
| 404 | PRODUCT_NOT_FOUND | 주문에 존재하지 않는/비활성 SKU 포함 |
| 404 | ORDER_NOT_FOUND | 주문 또는 request_id 조회 결과 없음 |
| 409 | IDEMPOTENCY_CONFLICT | 같은 키에 다른 정규화 입력 |
| 503 | PROCESSING_UNAVAILABLE | DB 잠금 timeout 등 일시 처리 불가 |
| 500 | INTERNAL_ERROR | 예상하지 못한 내부 오류 |

order_id는 접수된 주문을 안전하게 확인할 수 있을 때만 반환한다. field_errors는 필드 오류가 없으면 빈 배열이다. 내부 예외·스택·SQL·인증정보를 message에 넣지 않는다. 접수 이전 DB 오류는 주문이 존재한다고 가정하지 않는다. 오류 응답만 보고 새 키로 자동 재주문하지 않는다.

## 7. 프론트 구현 계약

- 응답에서 status를 읽어 완료·거절·보류·오류를 구분한다. HTTP 201만으로 성공 화면을 표시하지 않는다.
- 최초 상품 목록 실패 시 재시도 버튼을 제공한다.
- 주문 제출 전 클라이언트 검증은 보조이며 서버 검증이 최종 기준이다.
- 제출 중 중복 클릭을 막고, 네트워크 오류 뒤 같은 키로 복구한다.
- 결과 화면은 주문 ID·최종 금액·결제·배송·단계별 결과를 표시한다.
- 이번 API에는 Skill·execution_id·snapshot_id·trace_id가 없으므로 해당 UI를 가짜 데이터로 표시하지 않는다.
- 초기 화면에 “외부 결제·배송이 발생하지 않는 주문 데모”를 안내한다.
- 정상(c_1001 + card), 품절(CAMP-MUG-STEEL), 결제 거절(c_declined + card) 예시를 간단한 입력 채우기 버튼으로 제공한다. 실제 호출은 모두 같은 주문 API를 사용한다.

## 8. 인수 조건

백엔드는 위 형식·상태 코드·DB 결과를 테스트하고 프론트는 같은 응답 구조로 연결한다. Mock API 서버나 별도 Python 서버를 추가하지 않는다. 구현 중 명세를 변경하면 백엔드 DTO·프론트 API 모듈·이 문서를 함께 갱신한다.

단위·PostgreSQL 통합·Chromium 브라우저 테스트를 수행했다. 실제 실행 명령은 [README](../README.md), 결과와 제한은 [verification.md](verification.md)에 기록한다.

## 9. 구현에서 명확히 한 사항

- `customer`는 `c_`로 시작하는 영문·숫자·밑줄 데모 ID다. 앞뒤 공백은 제거한다. 화면은 c_1001/c_declined를 제공한다.
- 최초 PROCESSING 주문의 재시작 복구에는 RECOVERY/FAILED, PROCESS_INTERRUPTED 업무 기록을 남긴다. 중단 시 실제로 어느 도구까지 실행됐는지 알 수 없으므로 특정 도구가 실패했다고 만들지 않는다.
- 내부 처리 오류 후 롤백된 단계의 성공 기록은 재구성하지 않는다. 접수 기록·실패 단계·후속 미실행을 보여준다. 이 동작은 외부 부수 효과 없는 Mock 단계에만 적용한다.
- GET /api/health는 ApplicationRunner의 중단 주문 정리가 끝나고 앱이 요청 수락 상태가 된 후 200이다. 처리 준비 중에는 503이다. DB 지속 가용성을 검증하는 endpoint는 아니다.
- `estimated_delivery_date` 계산용 Clock은 UTC를 사용하며 화면의 timestamp만 브라우저 시간대로 표시한다.

## 진단용 조회 API (#39)

별도 요청 파라미터 없이 GET /api/executions/failed로 전체 운영 실패를 조회한다. 응답은 items 배열이며 실행·주문·snapshot ID/해시와 저장된 오류 상세를 포함한다. 최근 실패부터 정렬하고 업무 거절·시험 실행은 제외한다.

Skill 목록은 GET /api/snapshots/{snapshot_id}/skills, 본문은 GET /api/snapshots/{snapshot_id}/skills/{skill_id}로 조회한다. 파싱 실패 목록은 partial/parse_errors로 표시하고 원문은 기존 files API에서 확인할 수 있다. 모든 조회는 업무 상태를 변경하지 않는다.

상세 JSON 계약·오류 코드·MCP 변환 범위는 [진단용 조회 API 명세](diagnosis-read-api.md)를 참고한다. 기존 trace는 events 형식을 유지하고 새 단계 관찰 이벤트를 추가했다.
