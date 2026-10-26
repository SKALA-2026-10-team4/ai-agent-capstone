# SkillOps Agent — Capstone 4조

업무 담당자가 SKILL.md 를 고쳐 깨진 에이전트 서비스를, 실행 증거로 진단하고 **검증된 수정안**을 제안하는 에이전트.
설계도: `docs/skillops-design.md` · 계약: `docs/contracts/` · 저장 후 자동 진단: `docs/skill-save-auto-diagnosis.md`

## 구성

| 폴더 | 역할 | 스택 | 포트 |
|---|---|---|---|
| `service/` | MCP Server 7개 도구, 로그인 · 제안서 · 승인 REST, 진단 요청 중계 (A · C-1 · C-2) | Java 21 · Spring Boot 4.1.0 · Spring AI 2.0.0 · Gradle 9.7.1 | 8080 (`/api`, `/mcp`) |
| `skillops-agent/` | SkillOps Agent 진단 그래프 (B) | Python 3.11 · uv · LangGraph · langchain-openai · mcp · FastAPI | 8000 |
| `frontend/` | 운영 화면: Skill 편집 · 버전 비교 · 진단 기록 · 제안서 승인 (A) | Vue 3.5 · Vite 8 · Pinia · Vue Router | 5173 |
| `order-agent/` | 주문 Agent: skill 실행 harness · snapshot · 실행 기록 · 주문 화면 | Java 21 · Spring Boot · Spring AI · Vue 3 · Docker Compose | 8081 · 5174 |
| `skills/` | 운영 SKILL.md 6개 (D) | — | — |
| `scenarios/` | 기준 주문(`golden/`, `order-agent/golden/` 의 g-01~g-06) · 장애 F1 (D) | — | — |
| `qa/` | SkillOps 평가셋과 실행기(`run_qa.py list·check·eval`) | Python 3.11 · uv | — |
| `docker-compose.yml` | service 용 PostgreSQL 17 | Docker Compose | 5432 |

## 처음 한 번

```bash
cp .env.example .env            # OPENAI_API_KEY 채우기 (없어도 빌드·부팅·MCP ping 은 된다)
docker compose up -d --wait     # postgres 17
```

Windows 에서는 저장소 파일을 LF 로 받아야 한다. skill 묶음(`order-agent/skills/bundle.json`)과 시험 fixture 가 바이트 해시로
검증되므로 `core.autocrlf` 가 CRLF 로 바꾸면 주문 Agent 가 `INCOMPLETE_BUNDLE` 로 거부하고 service 시험이 깨진다. 루트
`.gitattributes`(`* text=auto eol=lf`)가 이를 막는다. 그 파일이 생기기 전에 받은 작업 폴더라면 바뀐 내용을 커밋한 뒤 한 번 다시 받는다
(`git rm --cached -r . && git reset --hard`).

## 기동

```bash
# 1) service — 주문 Agent 와 연동해 실제로 모의 실행·채점한다(harness 실행기, 기준 주문 g-01~g-06)
cd service && SKILLOPS_FLOW_RUNNER=harness SKILLOPS_SCENARIOS_DIR=../scenarios/order-agent ./gradlew bootRun
#    확인: curl localhost:8080/api/health  → {"status":"ok",...}
#    Swagger: http://localhost:8080/swagger-ui.html
#    그냥 ./gradlew bootRun 은 contract-only 실행기다. 주문 Agent 를 부르지 않는 가짜 실행이고 기준 주문은 g-01 하나라,
#    그때는 skillops-agent 를 SKILLOPS_GOLDEN_IDS=g-01 로 띄운다.

# 2) skillops-agent — 실제 service MCP 에 붙는다(.env 의 SKILLOPS_USE_STUB_MCP=false)
cd skillops-agent && uv sync && uv run uvicorn skillops_agent.main:app --reload --port 8000
#    MCP 배선 확인(service 가 떠 있을 때): uv run scripts/mcp_smoke.py

# 3) frontend
cd order-agent && docker compose up --build -d --wait   # 주문 Agent(:8081) — 운영 화면의 데이터 원천
cd frontend && npm install && npm run dev    # http://localhost:5173 · /api → order-agent 8081, /service → service 8080(로그인·승인)
#    서버 없이 화면만: npm run dev:mock    (order-agent 와 같은 형식의 메모리 mock: 정상·결함·수정본, 개선안 예시)
#    패키지를 바꾼 뒤 화면이 멈추면: npm run dev:mock -- --force
```

## 업무 규칙(rules)과 의도를 함께 저장하기

주문 Agent 의 규칙 모드(fixed)는 SKILL.md 본문을 읽지 않는다. 그래서 본문에 "선입금 주문만 결제한다"를 적기만 하면 실행은 그대로다.
업무 규칙을 실제로 실행하려면 단계 skill frontmatter 에 선언형 실행 규칙 `rules` 를 둔다.

```yaml
rules:
  - when: "order.payment_method != 'prepaid'"   # 조건 문법: 값이름 연산자 값 (==, !=, in, >, >=, <, <=)
    then: hold                                   # hold(보류) 또는 reject(거절)
    reason: PREPAID_ONLY
```

- 단계 skill 에만 둔다. `when` 의 값 이름은 그 skill 의 `inputs` 가운데 하나(또는 그 하위 값, 예: `order.shipping.country`)여야 한다.
  아니면 실행 전 검사가 `CONTRACT_VIOLATION`(`RULE_KEY_NOT_INPUT`)으로 멈춘다.
- harness 가 단계마다 도구를 부르기 전에 위에서부터 평가하고, 처음 맞는 규칙 하나로 주문을 HOLD·REJECTED 로 끝낸다. 결제 상태는 건드리지
  않으므로 "결제하지 않는다"는 규칙은 결제 단계나 그 앞 단계에 둔다.
- Skill 을 저장할 때 바꾸려는 업무 규칙(의도)을 함께 보내면(`POST /api/snapshots/edits` 의 선택 칸 `intent`: `text` 와
  `expectations[{scope, checks}]`), SkillOps 가 의도가 실행되지 않는 수정본을 찾아 의도를 실행되게 만드는 `rules` 안을 제안한다.
  의도가 있으면 되돌리기와 규칙·입력을 지우는 안은 코드가 불합격시킨다.

자세한 계약은 `docs/plans/2026-10-26-behavior-edit-root-fix.md` 2절이고, 평가 케이스는 `qa/cases/B-*.yaml` 이다.

## 로그인 · 승인 (service)

운영 화면 로그인과 수정안 승인만 service(8080)가 JWT(HS256, 2시간)로 보호한다. **그 밖의 API 는 지금처럼 인증 없이 열려 있다.**
MCP(`/mcp`)는 `SKILLOPS_SERVICE_TOKEN` 을 두면 같은 값의 `X-Service-Token` 헤더가 있어야 열린다(비면 열려 있고 기동할 때 경고를 남긴다).
skillops-agent 와 `qa/run_qa.py` 는 같은 환경 변수로 그 헤더를 보낸다.

| 로컬 데모 계정 | 비밀번호 | 역할 | 할 수 있는 것 |
|---|---|---|---|
| `owner` | `owner1234` | OWNER · 업무 담당자 | 화면 조회, Skill 초안 |
| `operator` | `operator1234` | OPERATOR · 운영자 | 위 + 수정안 승인·거절 |

| API | 인증 | 설명 |
|---|---|---|
| `POST /api/auth/login` | 없음 | `{username, password}` → `{access_token, token_type, expires_in, user}` |
| `GET /api/auth/me` | 로그인 | 토큰의 사용자 |
| `GET /api/proposals` · `GET /api/proposals/{id}` | 로그인 | 저장된 제안서 목록, 본문(`document`)·제안서·결정까지 담은 상세 |
| `POST /api/proposals/{id}/approve` | OPERATOR | `{candidate_no, intent_confirmed}` → 승인 기록과 `approval_token`. 합격 후보만, 의도 확인이 있어야 하며, 같은 제안서는 한 번만 결정한다 |
| `POST /api/proposals/{id}/reject` | OPERATOR | 거절 기록. 쓸 수 있는 안이 없는 제안서를 닫을 때도 쓴다 |
| `GET /api/approvals` | 로그인 | 승인 기록(최근 50건) |
| `/mcp` | `X-Service-Token`(토큰을 둘 때) | SkillOps Agent 와 QA 실행기가 쓰는 MCP 도구 |
| 그 밖의 `/api/**`, Swagger | 없음 | 다른 담당자의 API 는 인증 때문에 막히지 않는다 |

- 승인·거절은 저장된 제안서(`proposals`)의 상태를 바꾸고 `approvals` 테이블(계약 5 저장 공간, 담당 A)에 승인자·후보 번호·의도 확인·`approval_token` 을 남긴다. skillops-agent 로는 보내지 않는다. `used_at` 은 운영 반영(promote_workspace, 아직 없음)이 채운다.
- `@WebMvcTest` 에도 같은 보안 규칙이 자동 적용된다(`service/src/test/resources/META-INF/spring/…AutoConfigureWebMvc.imports`). 새 API 에 로그인이 필요하면 `auth/SecurityConfig` 에 한 줄 추가한다.
- 키·비밀번호 해시·서비스 토큰은 `.env.example` 참고. `SKILLOPS_JWT_SECRET` 을 비우면 기동마다 임시 키를 만든다(재시작하면 다시 로그인).

## 검증

```bash
cd service && ./gradlew build            # 컴파일 + 테스트
cd skillops-agent && uv run pytest && uv run ruff check .
cd frontend && npm run lint && npm run test:unit && npm run build
cd order-agent/backend && ./gradlew test   # 통합 테스트까지: order-agent/README.md 의 db-test 절차
cd order-agent/frontend && npm test && npm run lint && npm run build
cd qa && uv run pytest && uv run ruff check .   # 평가셋 판정 규칙과 케이스 파일 (서버를 쓰는 check·eval 은 qa/README.md)
```

## 필요한 로컬 도구 (팀 공통)

Temurin JDK 21 · Node ≥ 22.18 (npm) · Python 3.11 + uv · Docker Desktop. Gradle 은 wrapper 가 받는다.
