# 현재 검증 결과 — Agent·snapshot·trace 확장

2026-10-20. 현재 백엔드 27개(기존 13 + Skill/evidence 통합 8 + 경계 3 + 재시도/예산 3), 프론트 단위 9개, lint/build 통과. 새 증거 조회 브라우저 테스트 3개 통과. API를 실제 Docker에 연결했다.

- 정상 6개 Skill 실제 로드·snapshot/run_id 참조, 관련 자원 조회
- 결함 계약의 업무 도구 실행 전 거절, 준비된 수정 후보 통과
- 이전 snapshot·파일 바이트·메타데이터 보존과 변조 거절
- unknown 원문 보존, 정상 미선택, 잘못된 도구 인자 거절·미결제 예약 해제
- 불완전 flow의 SHIPPED 방지, 결제 후 실패 시 확정 결과/trace 보존
- 일시 API 오류 재시도와 전체 호출 예산 공유, 인증·오선택 재시도 금지(대역 사용)
- 브라우저에서 trace·snapshot·파일 내용·후보 안내·실행 이력 조회
- backend 컨테이너 재생성 후 동일 실행의 이벤트 개수·완료 상태·snapshot 전체 해시·flow 원본 해시 보존 확인

실제 OpenAI 유료 응답은 미검증이며 기본 fixed 모드로 실행했다. Runner·Judge·진단/MCP 연결은 구현하지 않았고 담당자 TODO를 남겼다. 준비된 수정 후보가 통과했다고 SkillOps가 자동 수정한 것으로 해석하지 않는다. 최신 트랜잭션·실패·저장 정책은 [agent-evidence.md](agent-evidence.md)를 따른다.

아래는 1차 MVP 당시의 기록이다. "전체 처리 트랜잭션 롤백"과 "Skill/trace 미구현"은 당시 범위에 대한 설명이며 현재 동작이 아니다.

---

# 1차 MVP 검증 기록

검증일: 2026-10-20. 대상은 독립 order-agent이며 기존 SkillOps 서비스는 변경하지 않았다.

## 수행한 검증

| 구분 | 결과 |
|---|---|
| Java 컴파일·bootJar | 통과, Java 21 / Boot 4.1.0 |
| Mock 단위 테스트 | 2개 통과: 결제 승인/거절·가드, 배송 가드·고정 날짜 계산 |
| PostgreSQL 통합 테스트 | 11개 통과, profile의 전용 order_agent_test DB |
| 프론트 ESLint | 통과 |
| 프론트 Node 단위 테스트 | 9개 통과, 금액·수량 검증·중복 제출·복구·한도·늦은 응답 |
| 프론트 Vite build | 통과 |
| Docker 이미지·기동 | frontend/backend/db 3개 healthy |
| Chromium 브라우저 | 7개 통과, 실제 Docker API 연동 |
| 화면 점검 | 데스크톱 정상 결과와 모바일 전체 화면 스크린샷 확인 |
| 실제 API smoke | 정상·품절·결제 거절·같은 키 재요청·다른 본문 충돌·재고 일치 통과 |
| 데이터 보존 | db/backend/frontend 컨테이너 force-recreate 후 주문과 재고 보존 확인 |

PostgreSQL 통합 테스트는 주문 금액·결과·실제 단계 저장, 품절 시 후속 미실행, 결제 거절 시 재고 복원, 복수 상품 전체 예약, 중복 SKU 합산, 멱등·충돌·입력 오류, 내부 오류 롤백/FAILED 저장, 동시 마지막 재고, 동시 동일 요청 키, 중단 실행 복구를 확인했다.

브라우저 테스트는 정상 주문·품절·결제 거절, 서버 처리 후 응답 유실 복구, 미접수 확인 뒤 동일 키/본문 재전송, 잘못된 수량의 제출 차단·포커스, 모바일 가로 넘침 방지를 확인했다. 브라우저에서 HTTP 요청을 일부 차단한 테스트도 서버의 실제 주문 API와 연결했다. 모든 테스트의 응답을 fixture로 대체하지 않았다.

API smoke의 재생성 비교는 `/private/tmp/order-agent-state.json`에 주문 ID·당시 재고를 저장한 뒤 수행했다. 이 임시 검증 파일은 앱 의존성이나 제출 산출물이 아니다. 브라우저 이미지·빌드 결과·node_modules·Gradle 캐시는 Git에서 제외한다.

## 재현 명령

명령의 기준 디렉터리는 order-agent다.

```bash
docker compose up --build -d --wait
docker compose --profile test up -d --wait db-test
cd backend
ORDER_DEMO_INTEGRATION_TESTS=true ./gradlew test
cd ../frontend
npm ci
npm run lint
npm test
npm run build
npx playwright install chromium
npm run test:e2e
cd ..
python3 scripts/smoke.py
```

e2e·smoke는 실제 데모 재고를 소비한다. e2e는 시작 시 랜턴 최소 4개, smoke는 최소 2개가 필요하다. 재실행 전 상품 재고를 확인한다. 초기화가 필요하면 README의 데모 볼륨 삭제 절차를 사용한다. 테스트 DB는 `docker compose --profile test rm -sf db-test`로 제거하고 주문 데모 컨테이너는 남겨둘 수 있다.

## 남은 제한

- LLM·Skill·Harness·Runner·Judge·snapshot·기술 trace·진단/MCP 연동은 미구현이다. 후속 설계만 제공한다. API 키·실제 모델 호출은 사용하지 않았다.
- 단계 목록은 DB에 저장된 업무 결과이며 SSE 실시간 스트림이 아니다.
- Mock은 외부 부수 효과가 없어 처리 트랜잭션 전체 롤백이 가능하다. 실제 결제·모델 연결에는 짧은 트랜잭션·멱등 도구·보상 설계를 적용해야 한다.
- 1차는 단일 백엔드 인스턴스, 로컬 데모다. 인증·조회 권한·다중 인스턴스 장애 복구·취소/환불은 없다.
- 응답 유실 복구의 키·본문은 페이지 메모리에 보관한다. 브라우저 새로고침 후 자동 복구는 없다. 결과 미확정 상태에서 새 키로 자동 재주문하지 않는다.
- 초기 스키마는 idempotent 생성 SQL이며 운영 마이그레이션 도구는 없다. 기존 스키마가 달라지면 ddl-auto=validate가 실패하므로 스키마 변경 시 마이그레이션을 추가해야 한다.
- `/api/health`는 앱 readiness를 확인한다. DB가 기동 후 중단되는 모든 상황을 지속 검사하는 health API는 아니다.
