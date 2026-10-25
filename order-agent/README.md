# Order Agent — 캠핑용품 주문 · Skill 실행

기존 SkillOps와 독립된 Spring Boot·Vue·PostgreSQL 주문 데모다. `order-agent/` 안에서 별도의 Docker Compose 프로젝트로 실행한다.

**구현 범위:** 상품·주문 API, 재고·결제·배송·알림 Mock, 주문/단계 DB 저장, Vue 입력·결과 화면, 멱등 요청과 응답 복구. MVP 이후 Skill·Harness·snapshot·trace 저장/조회와 고정 응답 실행을 연결했다. OpenAI 모드는 선택적으로 구성할 수 있으나 실제 유료 호출은 미검증이다. Runner·Judge·진단/MCP·승인 기능은 후속 범위다. 실제 외부 결제·배송·메시지 발송은 없다.

## Trace·Snapshot 확인

화면 아래 **Trace · Snapshot** 영역에서 최근 실행을 선택한다. Trace 보기로 실제 Skill 로드·도구 요청/결과를 펼치고, Snapshot 보기로 실행 당시 파일과 SHA-256을 확인한다. 기존 MVP 주문에는 실행 기록을 소급 생성하지 않는다.

개발 패널의 정상본·결함본·수정본은 **미리 준비한 시연 파일 세트**이며 SkillOps의 자동 진단·수정 결과가 아니다. 상세 실행과 담당자 TODO는 [Agent 실행·증거 조회 문서](docs/agent-evidence.md)를 따른다.

팀 공유용 [주문 실패·SkillOps 연계 시나리오](docs/team-scenarios.md)에 정상 업무 거절과 Skill 결함, 현재 재현 범위, 진단할 증거와 수정 후 확인 기준을 정리했다.

담당자 협의용 [MCP 도구·주문 Agent API 매핑 초안](docs/mcp-order-api-draft.md)에 도구 6개 매핑, 실패 전달 스키마, Runner 역할과 네트워크·오류 처리 협의 사항을 정리했다.

이슈 #39로 구현한 [진단용 조회 API 명세](docs/diagnosis-read-api.md)에 네 조회 기능의 요청·응답과 MCP 담당 두 분석 기능의 경계를 정의했다. `GET /api/executions/failed`, `GET /api/snapshots/{id}/skills`, `GET /api/snapshots/{id}/skills/{skill_id}`를 추가했으며 기존 trace·snapshot·파일 조회 API도 유지한다.

[주문 Agent·Runner 연동 설계안](docs/runner-integration-design.md)은 2026-10-21의 과거 제안이며, 상단에 현재 구현과의 대응을 정리했다. 현행 명세·검증 결과와 과거 분석의 구분은 [백엔드 리뷰 안내](../docs/review/README.md)를 따른다.

## Docker로 실행

Docker Desktop을 실행한 뒤 저장소 루트에서:

```bash
cd order-agent
docker compose up --build -d --wait
```

로컬 데모용 DB 기본값으로 바로 실행할 수 있다. 자격 증명을 바꾸려면 최초 기동 전에 `.env.example`을 `.env`로 복사해 설정한다. `.env`는 Git과 Docker 이미지에서 제외된다.

- 화면: **http://localhost:5174** (단일 페이지 `/`)
- API: http://localhost:8081/api/products
- 기동 확인: http://localhost:8081/api/health

```bash
# order-agent/에서 실행
docker compose ps
docker compose logs -f backend

# 이미지에 소스가 포함되어 있으므로 코드 변경 후 재빌드
docker compose up --build -d --wait

# 종료: 주문과 재고는 유지
docker compose down
```

frontend·backend·db는 각각 컨테이너다. 기본 네트워크·볼륨은 `order-agent` 전용이며 기존 SkillOps와 공유하지 않는다. DB 호스트 포트는 기본 비공개다. 브라우저의 `/api`는 frontend의 Vite가 `backend:8080`으로 전달하고 백엔드는 `db:5432/order_demo`를 사용한다.

Vite 컨테이너는 로컬 개발·시연용이다. 프론트 5174·백엔드 8081은 127.0.0.1에만 노출한다. 로그인·권한 관리·운영 배포용 프론트 웹 서버는 이번 범위에 없다.

## 화면에서 시연

화면 하단의 예시 버튼은 입력만 채운다. 이후 **주문 실행**을 누른다.

| 예시 | 입력 | 결과 |
|---|---|---|
| 정상 주문 | c_1001, 랜턴 2개, card | SHIPPED, 60,000원, 재고 2개 감소 |
| 재고 부족 | 머그컵 1개 | REJECTED, 결제·배송·알림 미실행 |
| 결제 거절 | c_declined, 랜턴 1개, card | HOLD, 재고 예약 해제, 배송·알림 미실행 |

c_declined도 prepaid면 결제에 성공한다. 여러 상품은 **상품 추가**로 주문한다. 국내 일반 배송은 기준일 +2일, 해외는 +7일, 빠른 배송은 1일 단축한다. 화면의 “배송 예약 완료”는 실제 배송 완료를 의미하지 않는다.

| SKU | 상품 | 단가 | 최초 재고 |
|---|---|---:|---:|
| CAMP-LANTERN-LED | LED 캠핑 랜턴 | 30,000원 | 10 |
| CAMP-CHAIR-FOLD | 접이식 캠핑 의자 | 45,000원 | 5 |
| CAMP-MUG-STEEL | 스테인리스 캠핑 머그컵 | 12,000원 | 0 |

성공 주문의 재고는 다음 주문에도 반영된다. 재고가 소진되면 정상/결제 거절 예시도 먼저 재고 부족으로 종료될 수 있다. 앱 재시작·재빌드는 seed로 데이터를 덮어쓰지 않는다. 이번 구현 검증 과정에서도 데모 주문이 생성됐다.

데모 데이터를 **전부 삭제하고** 초기 재고로 돌아가려면 다음 명령을 사용한다. `-v`는 이 프로젝트의 주문·DB 볼륨을 삭제하므로 일반 종료에는 사용하지 않는다.

```bash
# order-agent/에서: 데모 주문·재고 전체 삭제
docker compose down -v
docker compose up -d --wait
```

## API

자세한 요청·응답은 [API 명세](docs/api.md)를 따른다.

```bash
curl http://localhost:8081/api/products

curl -i http://localhost:8081/api/orders \
  -H 'Content-Type: application/json' \
  -H "Idempotency-Key: $(uuidgen)" \
  -d '{"customer":"c_1001","items":[{"sku":"CAMP-LANTERN-LED","qty":2}],"payment_method":"card","shipping":{"country":"KR","express":false}}'
```

같은 요청을 복구할 때는 **같은 UUID 키·같은 입력**을 다시 사용한다. 예시처럼 매번 uuidgen을 실행하면 새 주문이다. 같은 키·동일 입력은 기존 주문을 200으로 반환하고 다른 입력이면 409다.

- GET `/api/products`
- POST `/api/orders` — 필수 `Idempotency-Key: UUID`
- GET `/api/orders/{order_id}`
- GET `/api/orders/by-request/{request_id}`
- GET `/api/health`

최초 업무 거절도 주문은 생성됐으므로 201이다. 프론트는 HTTP 코드만 보지 않고 응답의 status를 표시한다. 네트워크 오류는 주문 실패로 단정하지 않으며 기존 키로 조회한다. 화면의 단계는 저장된 업무 기록이고 SSE 실시간 이벤트가 아니다.

## 테스트

Docker 실행만 하려면 로컬 Java·Node가 필요 없다. 아래 소스 테스트에는 JDK 21·Node ^22.18 또는 >=24.12가 필요하다.

```bash
# order-agent/에서: 외부 DB가 필요 없는 Mock 단위 테스트
cd backend
./gradlew test
```

PostgreSQL 통합 테스트는 운영 데모 DB와 분리된 **임시 db-test**를 사용한다. 테스트가 주문 데이터를 초기화하는 대상은 고정된 order_agent_test DB뿐이다.

```bash
# order-agent/에서
docker compose --profile test up -d --wait db-test
cd backend
ORDER_DEMO_INTEGRATION_TESTS=true ./gradlew test
cd ..
docker compose --profile test rm -sf db-test
```

```bash
# order-agent/에서: 프론트 단위 검사·빌드
cd frontend
npm ci
npm run lint
npm test
npm run build

# 앱이 Docker에서 실행 중일 때 실제 Chromium 연동 검사
npx playwright install chromium
npm run test:e2e

# Agent 증거 조회만 검증(캠핑 의자 2개 소비)
npm run test:e2e -- tests/e2e/evidence.spec.js
```

전체 브라우저 테스트는 실제 데모의 랜턴 4개·의자 2개를 소비한다. 시작 시 해당 수량 이상 있어야 한다. 증거 조회 테스트만 실행하면 의자 2개만 필요하다. `npm test`는 DB를 변경하지 않는다. 브라우저 테스트 결과 이미지는 `frontend/test-results/`에 생성되며 커밋하지 않는다.

Python 표준 라이브러리만 사용하는 API smoke도 제공한다. 별도 Python 서버·런타임 의존성은 추가하지 않는다.

```bash
# order-agent/에서: 정상 주문으로 랜턴 2개 소비
python3 scripts/smoke.py
```

검증 결과·실제 제약은 [검증 기록](docs/verification.md)을 참고한다.

## 구조와 확장 경계

```text
order-agent/
├── backend/             Controller·Service·Repository·Entity·DTO·Mock
├── frontend/            Vue 컴포넌트·API·주문 상태/복구
├── compose.yml          독립 frontend/backend/db, 테스트 DB profile
├── compose.db-port.yml  필요할 때만 DB 호스트 5433 공개
├── scripts/smoke.py     실행 중인 API 검증
└── docs/                설계·명세·검증 기록
```

DB 관리 도구가 필요하면 `docker compose -f compose.yml -f compose.db-port.yml up -d --wait db`로 127.0.0.1:5433만 추가 공개할 수 있다.

실제 사용 환경 변수는 `ORDER_DEMO_DB_USER`, `ORDER_DEMO_DB_PASSWORD`, `ORDER_DEMO_DB_URL`이다. URL은 Compose가 backend에 전달한다. frontend의 서버 측 `API_PROXY_TARGET`은 기본 backend:8080으로 지정한다. 브라우저 번들에는 DB 설정을 전달하지 않는다. 기본 fixed 모드는 OpenAI 키가 필요 없다. 실제 모델 설정·실행 한도는 agent-evidence.md와 .env.example을 참고한다.

- [전체 설계·DB](docs/design.md)
- [백엔드 구조](docs/backend-structure.md)
- [프론트 설계](docs/frontend-design.md)
- [후속 주문 Agent·Skill 실행 흐름](docs/skill-flow.md)
- [장기 SkillOps 연동 설계](docs/order-service-test-design.md)

OrderProcessingService는 현재 Harness를 호출한다. 모델 응답을 기다리며 DB 잠금을 유지하지 않도록 업무 도구별 짧은 트랜잭션으로 분리했다. 진단 Agent·MCP·승인 기능은 다른 담당 구현에 연결하며 이 프로젝트에서 중복 구현하지 않는다.
