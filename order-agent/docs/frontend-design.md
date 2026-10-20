# order-agent 프론트엔드 설계

> MVP 이후 구현: 현재 Skill·Harness·snapshot·trace 기능이 추가됐다. 최신 API·저장 방식·실패/트랜잭션·시연 후보·담당자 TODO는 [agent-evidence.md](agent-evidence.md)를 우선한다. 아래의 미구현/후속 표기는 작성 당시 단계 설명이다.

작성: 2026-10-20 / 상태: 1차 MVP 구현 반영

## 1. 목표와 범위

독립 Vue 프로젝트 `order-agent/frontend/`에서 캠핑용품 주문을 입력하고 Spring Boot의 실제 처리 결과를 확인한다. 기능은 상품 조회, 주문 제출, 결과·업무 단계 표시, 응답 유실 시 재조회다.

프로젝트 이름은 order-agent다. 화면은 1차 주문 데모이며 실제 결제·배송은 발생하지 않는다. 1차에는 Skill 편집·승인·Runner·snapshot·기술 trace 화면을 만들지 않는다. 후속 Agent 연결 후의 선택 Skill·실행 ID·실제 로드 정보 표시는 [skill-flow.md](skill-flow.md)에 따라 API 확장과 함께 추가한다. Vue·API 모듈·Dockerfile을 구현했으며 검증 결과는 verification.md에 기록한다.

API 데이터·오류·상태는 [api.md](api.md), 업무·DB는 [design.md](design.md), 백엔드 클래스는 [backend-structure.md](backend-structure.md)를 따른다.

## 2. 기술 선택

- Vue 3 Composition API + `<script setup>`, JavaScript, Vite를 사용한다. 기존 저장소의 Vue 3.5·Vite 8 버전 계열과 npm 잠금 파일을 기준으로 맞춘다.
- 단일 페이지 `/`로 시작한다. 여러 URL로 이동하지 않으므로 Vue Router를 추가하지 않는다.
- 화면 상태는 composable로 관리한다. 여러 페이지 사이에 공유할 상태가 없어 Pinia를 추가하지 않는다.
- HTTP는 브라우저 fetch를 사용한다. 공통 오류·timeout 처리만 작은 래퍼로 묶는다.
- 스타일은 공통 CSS와 컴포넌트 scoped CSS로 시작한다. 별도 UI 프레임워크·이미지 생성·외부 CDN에 의존하지 않는다.

처음부터 라이브러리를 늘리기보다, 한 화면의 데이터 흐름을 이해할 수 있게 하는 선택이다.

## 3. 화면 구성

데스크톱에서는 입력과 결과를 두 열로 배치하고 좁은 화면에서는 입력→결과 순서로 배치한다. 긴 주문 ID는 줄바꿈하고 입력·버튼이 가로로 넘치지 않게 한다.

```text
┌─────────────────────────────────────────────────────────┐
│ Order Agent · 캠핑용품 주문                              │
│ 외부 결제·배송이 발생하지 않는 데모입니다.                │
├──────────────────────────┬──────────────────────────────┤
│ 주문 입력                │ 주문 결과                    │
│                          │                              │
│ 상품 / 단가 / 재고        │ 상태 배지·요약 메시지         │
│ [랜턴 선택] [수량 2] 삭제 │ 주문 ID / 요청 ID             │
│ [+ 상품 추가]            │ 상품·수량·확정 금액           │
│                          │ 결제 결과·배송 예정일         │
│ 고객·결제 방식           │                              │
│ 배송 국가·빠른 배송      │ 처리 단계                    │
│ 예상 합계 60,000원        │ 접수 → 재고 → 결제 → 배송     │
│ [주문 실행]              │ → 알림 (실제 서버 기록)        │
│                          │                              │
│ 개발·시연 예시           │ [상태 다시 확인] [새 주문]     │
│ [정상] [품절] [결제거절]  │                              │
└──────────────────────────┴──────────────────────────────┘
```

결과가 없으면 “주문 실행 후 결과를 확인할 수 있습니다”라는 안내를 표시한다. 다른 주문을 작성해도 이전 결과를 즉시 지우지 않고 “직전 주문 결과”로 표시해 현재 입력과 구분한다. 새 POST를 시작할 때 결과 영역을 해당 요청의 대기 상태로 전환한다.

### 주문 입력

- 상품 선택: API에서 받은 상품명·가격·가용 재고를 표시한다. SKU는 보조 정보로 제공한다.
- 수량: SKU당 1~99, 최소 한 상품. 화면에서는 같은 SKU를 중복 선택하지 못하게 해 입력을 단순하게 한다. 서버는 중복 SKU 입력도 합산 검증한다.
- 품절 상품도 선택할 수 있다. “품절·시연 가능”을 명시하고 실제 주문은 서버가 REJECTED로 처리한다. 가용 재고보다 수량이 커도 경고만 표시해 재고 부족 시연을 허용한다.
- 고객: 정상 고객 c_1001, 결제 거절 시연 고객 c_declined를 선택한다. 실제 개인 이름·전화·카드번호는 받지 않는다.
- 결제 방식: card / prepaid. c_declined의 card만 거절된다는 시연 규칙을 별도 안내한다.
- 배송: KR / US / JP, express 체크박스. 배송비는 1차에 없으므로 별도 배송비를 계산하지 않는다.
- 예상 금액: 상품 조회 가격으로 계산한다. 최종 확정 금액은 주문 응답의 total_amount를 사용한다.

### 결과·단계

결과는 OrderResponse를 사용한다. 결제·배송·알림 참조는 보조 상세에 표시하고, 없는 값은 “미실행”으로 안내한다. shipment=null을 빈 객체로 간주해 날짜를 출력하지 않는다.

| 서버 주문 상태 | 사용자 표시 |
|---|---|
| CREATED | 주문 접수됨 |
| PROCESSING | 주문 처리 중 |
| SHIPPED | 주문 처리 완료 · 배송 예약됨 |
| REJECTED | 주문 거절 · 재고 부족 등 result_code 설명 |
| HOLD | 주문 보류 · 결제 거절 |
| FAILED | 처리 오류 · 상세 요약·재조회 제공 |

SHIPPED는 이 데모의 배송 예약 완료 상태다. 실제 배송이 끝났다고 안내하지 않는다.

steps는 sequence로 정렬해 표시한다. SUCCESS=완료, REJECTED=업무 거절, FAILED=오류, SKIPPED=미실행으로 텍스트·아이콘을 함께 보여준다. RELEASE는 “재고 예약 해제”다. 5단계로 고정하지 않고 서버가 반환한 모든 단계를 표시한다. 알 수 없는 단계·상태도 원래 값을 보조 표시해 화면 전체가 깨지지 않게 한다.

**실시간 진행과 결과 기록을 구분한다.** 1차 API는 기본 동기 응답이고 단계는 처리 트랜잭션 완료 후 저장된다. 제출 중에는 “처리 중”만 표시하며 시간에 맞춰 성공 단계를 만들어내지 않는다. 조회에 steps가 없으면 “아직 확인된 단계가 없습니다”로 표시한다. SSE·WebSocket은 추가하지 않는다.

## 4. 디렉터리·컴포넌트

```text
order-agent/frontend/
├── package.json / package-lock.json
├── index.html
├── vite.config.js
├── Dockerfile
├── .dockerignore
└── src/
    ├── main.js
    ├── App.vue
    ├── api/
    │   ├── client.js             fetch·timeout·공통 오류 변환
    │   ├── products.js           상품 조회
    │   └── orders.js             생성·ID 조회·요청 키 조회
    ├── composables/
    │   └── useOrder.js           입력·제출·응답 복구·polling
    ├── components/
    │   ├── OrderForm.vue         입력·검증 메시지·실행 버튼
    │   ├── OrderResult.vue       결과 요약·명세·다시 확인
    │   ├── OrderSteps.vue        실제 업무 단계 목록
    │   └── DemoPresets.vue       정상·품절·결제 거절 입력 채우기
    ├── utils/
    │   ├── format.js             원화·시각·상태 표시
    │   └── validation.js         클라이언트 입력 검증
    └── styles/main.css          색상·여백·반응형·기본 폼 스타일
```

App이 useOrder를 사용하고 결과를 props로 내려준다. 자식은 submit/update/recover/new-order/preset 이벤트를 올린다. 자식이 부모 props를 직접 변경하거나 각자 별도의 주문 API를 호출하지 않는다. 작은 상태 배지는 초기에는 독립 공통 컴포넌트로 분리하지 않아도 된다.

| 컴포넌트 | 주요 props | emit·역할 |
|---|---|---|
| OrderForm | form, products, productStatus, locked, fieldErrors | update:form, submit, reload-products |
| OrderResult | order, requestState, error | recover, new-order |
| OrderSteps | steps | 기록을 읽기 전용 표시 |
| DemoPresets | disabled | preset: 정상/품절/결제 거절 이름 |

타입 이름은 설계상 의미를 나타내며 TypeScript 도입을 전제로 하지 않는다. 필요하면 API 모듈에 JSDoc으로 응답 형태를 설명한다.

## 5. 상태와 요청 수명

상품 로딩과 주문 제출 상태는 분리한다. 주문 성공 후 상품 목록 갱신이 실패했다고 주문 결과를 실패로 바꾸지 않는다.

| 상태 데이터 | 내용 |
|---|---|
| products / productStatus | 상품 목록, loading/ready/error |
| form | 현재 수정 중인 주문 입력 |
| submittedRequest | 제출 시점에 복사·고정한 본문과 UUID 키 |
| order | 마지막으로 확인된 서버 주문 상세 |
| requestState | idle/submitting/polling/recoverable/settled |
| fieldErrors / requestError | 입력 오류, 통신·서버 오류를 분리 |

- idle: 최초 입력 또는 아직 제출하지 않은 새 주문.
- submitting: POST 응답 대기. 폼·시연 버튼·제출을 잠근다.
- polling: CREATED/PROCESSING 조회 중. 동일 요청의 재실행은 하지 않는다.
- recoverable: 네트워크 오류·timeout·확인 제한으로 결과가 미확정. 같은 요청 재조회/재전송만 제공한다.
- settled: 최종 주문 상태 또는 명확한 접수 전 입력 오류가 확인됨. 업무 결과와 HTTP 오류는 별도 필드로 표현한다.

정상 최종 상태뿐 아니라 REJECTED·HOLD·FAILED도 주문이 확정된 settled 상태다. UI의 settled를 업무 성공으로 해석하지 않는다.

### 멱등 키와 응답 복구

1. 유효한 입력을 제출할 때 crypto.randomUUID()로 Idempotency-Key를 만든다.
2. 요청 본문을 복사해 submittedRequest로 보존한다. 이후 재전송은 이 복사본을 사용한다.
3. POST timeout·통신 오류는 “결과 확인 필요”로 표시한다. 브라우저 요청 취소가 서버 처리를 취소한 것은 아니다.
4. 키 조회가 200이면 기존 주문을 사용한다. CREATED/PROCESSING이면 polling한다.
5. 키 조회가 404이면 같은 키·같은 복사본으로 재전송할 수 있다. 조회 자체가 실패한 경우에는 자동 POST하지 않는다.
6. 409이면 키·본문 불일치 오류를 표시하고 재조회한다. 자동으로 새 키를 발급하지 않는다.
7. 주문 결과가 확정된 후 “새 주문” 또는 입력 수정 시 이전 요청과 구분되는 새 제출을 준비한다.

**미확정 상태에서는 새 키로 주문을 자동 생성하지 않는다.** 폼과 시연 preset은 잠그고 복구 조작을 제공한다. 사용자가 결과 확인 없이 별도 새 주문을 선택하면 기존 주문이 처리됐을 수 있음을 알리는 확인 후에만 가능하게 한다. 1차는 해당 우회 버튼 없이 복구를 우선하는 단순한 UI로 시작한다.

주문 키·본문은 메모리에만 보관한다. 페이지 새로고침 후 자동 복구는 1차 범위 밖이다. 확인된 주문 ID는 복사 가능하게 해 수동 API 조회에 사용할 수 있다.

### 시간·취소·늦은 응답

- 최초 POST timeout은 15초로 시작한다. timeout은 실패 확정이 아니라 복구 상태다.
- 단건 GET timeout은 5초, 자동 조회는 최대 10초 안에서 1초 간격으로 순차 실행한다. 요청을 중첩하지 않고 전체 조회 한도에 맞춰 남은 timeout을 줄인다.
- 자동 조회 한도 초과 시 기존 확인 결과를 유지하고 수동 “상태 다시 확인”을 제공한다. 완료되지 않았다는 이유로 FAILED 상태를 만들어 넣지 않는다.
- 컴포넌트 unmount 시 AbortController와 타이머를 정리한다.
- 현재 요청의 식별자를 비교해 늦게 도착한 이전 주문·상품 응답이 현재 화면을 덮어쓰지 않게 한다.
- 확인된 최종 주문 후 상품 재고를 다시 조회한다. 그 조회 실패는 상품 영역의 별도 경고다.

## 6. API 연결과 오류 표시

| 시점 | API | 결과 처리 |
|---|---|---|
| 화면 진입 | GET /api/products | 목록 표시 또는 재시도 |
| 주문 실행 | POST /api/orders | 201/200 모두 본문의 status로 처리 |
| 응답 복구 | GET /api/orders/by-request/{request_id} | 존재 확인, 404일 때만 동일 요청 재전송 가능 |
| 진행 주문 조회 | GET /api/orders/{order_id} | 최종 상태까지 제한 polling |
| 최종 결과 확인 후 | GET /api/products | 현재 재고 갱신 |

client.js는 HTTP 오류를 status/code/message/order_id/field_errors로 정규화한다. 비JSON 프록시 오류·연결 실패·timeout도 안전한 오류로 변환한다. 서버가 201이 아닌 500을 반환했더라도 order_id가 있으면 상세 조회로 저장된 FAILED 상태를 확인한다.

- 400: 필드 옆 오류, 처음 오류 난 입력으로 focus. 수정 가능한 접수 전 오류로 처리한다.
- 404 PRODUCT_NOT_FOUND: 상품 갱신 후 수정 안내. 주문 조회의 ORDER_NOT_FOUND와 구분한다.
- 409: 요청 충돌 안내·기존 키 조회, 자동 재주문 금지.
- 500/503 또는 비JSON 오류: 접수 여부가 불명확할 수 있으므로 order_id 또는 키로 복구한다.
- field_errors 경로는 items[0].qty처럼 서버 형식을 UI 항목으로 매핑한다. 제출 복사본과 현재 폼이 같을 때만 해당 행에 표시한다.

## 7. 시연 입력

시연 버튼은 폼만 채운다. 클릭 즉시 주문을 만들지 않는다. 별도 실패 설정 API 없이 실제 주문 API로 실행한다.

| 버튼 | 채울 입력 | 기대 결과 |
|---|---|---|
| 정상 주문 | c_1001, 랜턴 2개, card, KR, express=false | 초기 재고 기준 SHIPPED, 60,000원 |
| 재고 부족 | c_1001, 머그컵 1개, card, KR | REJECTED, 결제·배송·알림 미실행 |
| 결제 거절 | c_declined, 랜턴 1개, card, KR | 재고가 있을 때 HOLD, 예약 해제 |

정상·결제 거절 preset도 재고가 소진됐으면 재고 부족이 먼저 발생한다. 버튼이 매번 초기 재고를 복원한다고 가정하지 않고 현재 재고를 표시한다. 실제 결과를 preset의 기대 상태로 덮어쓰지 않는다.

## 8. 시각·접근성

캠핑 서비스에 맞춰 짙은 녹색·차분한 배경·명확한 카드 구성을 사용한다. 첫 구현은 상품명과 정보 위주로 만들고 외부 이미지 없이 동작하도록 한다.

- label과 input을 연결하고 fieldset/legend로 결제·배송 입력을 묶는다.
- 상태는 색뿐 아니라 텍스트와 아이콘으로 구분한다.
- 처리·결과 안내는 aria-live, 폼은 aria-busy, 오류 필드는 aria-invalid를 사용한다.
- 버튼·폼은 키보드로 조작 가능하고 focus 스타일을 보존한다.
- disabled 이유와 통신 실패 뒤 복구 방법을 화면에 설명한다.
- 서버 메시지는 텍스트로 출력한다. v-html로 실행하지 않는다.

## 9. Docker와 독립성

Vue는 별도 frontend 이미지에서 Vite 개발 서버로 실행한다. 브라우저는 localhost:5174, 컨테이너 내부는 5173이다. `/api`는 Vite의 서버 측 프록시가 backend:8080으로 전달한다. 브라우저 코드에 backend Docker DNS나 DB 비밀번호를 넣지 않는다.

기존 루트 frontend·SkillOps API와 소스를 공유하지 않는다. Dockerfile·compose.yml을 생성하고 실제 기동을 확인했다. 1차 구성은 로컬 개발·시연용이며 정적 배포용 웹 서버는 이후 검토한다.

## 10. 구현·검증 순서

1. Vue·Vite scaffold와 공통 스타일·단일 화면 배치를 만든다.
2. 상품 조회와 입력·예상 합계·검증을 연결한다.
3. useOrder·API 모듈로 실제 주문 제출·결과·단계를 연결한다.
4. 멱등 키·복구·polling·늦은 응답 차단을 연결한다.
5. preset·접근성·반응형·Docker 연동을 확인한다.

순수 표시 변경에 불필요한 테스트를 늘리기보다, 금액·입력 검증과 중복 주문 방지·복구 상태 전이는 자동 검증한다. 구현 시 기존 버전과 호환되는 테스트 도구를 선택하고 의존성·lock 변경을 함께 검증한다. 브라우저에서 실제 백엔드 연결도 확인한다.

인수 조건:

- 초기 상품 로딩·실패·재시도, 품절 상품 선택이 가능하다.
- 랜턴 2개 60,000원, 재고 부족, 결제 거절 결과를 실제 응답으로 표시한다.
- 201 + HOLD/REJECTED를 성공 주문으로 표시하지 않는다.
- 중복 클릭과 응답 유실 시 새 키 재주문을 막는다.
- 접수 전 필드 오류와 미확정 통신 오류의 수정·복구 흐름이 다르다.
- polling은 중첩되지 않고 한도·unmount 시 종료된다.
- SKIPPED·RELEASE·알 수 없는 단계·null shipment·FAILED를 안전하게 표시한다.
- 재고 갱신 실패가 완료된 주문 결과를 지우지 않는다.
- 모바일·키보드·스크린리더 안내를 확인한다.
- npm ci, lint, build와 Compose 브라우저 연동을 통과한다.

프론트 구현·lint·단위 테스트·build·Chromium 브라우저 연동 검증을 수행했다. 결과와 제한은 [verification.md](verification.md)를 참고한다.
