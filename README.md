# SkillOps Agent — Capstone 4조

업무 담당자가 SKILL.md 를 고쳐 깨진 에이전트 서비스를, 실행 증거로 진단하고 **검증된 수정안**을 제안하는 에이전트.
설계도: `docs/skillops-design.md` · 계약: `docs/contracts/` · **역할별 작업 가이드: `docs/roles/`** (각자 자기 파일 하나만 읽으면 된다)

## 구성

| 폴더 | 역할 | 스택 | 포트 |
|---|---|---|---|
| `service/` | 주문 처리 서비스 + Spring AI harness + MCP Server (A · C-1 · C-2 · D) | Java 21 · Spring Boot 4.1.0 · Spring AI 2.0.0 · Gradle 9.7.1 | 8080 (`/api`, `/mcp`) |
| `skillops-agent/` | SkillOps Agent (B) | Python 3.11 · uv · Anthropic SDK · mcp · FastAPI | 8000 |
| `frontend/` | 편집기 · 트레이스 · 수정안 화면 (A) | Vue 3.5 · Vite 8 · Pinia · Vue Router | 5173 |
| `skills/` | live SKILL.md 6개 (D) | — | — |
| `scenarios/` | 골든 g-01~06 · 장애 F1~F3 (D) | — | — |
| `docker-compose.yml` | PostgreSQL 17 | Docker Compose | 5432 |

## 처음 한 번

```bash
cp .env.example .env            # OPENAI_API_KEY 채우기 (없어도 빌드·부팅·MCP ping 은 된다)
docker compose up -d --wait     # postgres 17
```

## 기동

```bash
# 1) service
cd service && ./gradlew bootRun
#    확인: curl localhost:8080/api/health  → {"status":"ok",...}
#    Swagger: http://localhost:8080/swagger-ui.html

# 2) skillops-agent
cd skillops-agent && uv sync && uv run uvicorn skillops_agent.main:app --reload --port 8000
#    MCP 배선 확인(service 가 떠 있을 때): uv run scripts/mcp_smoke.py

# 3) frontend
cd order-agent && docker compose up --build -d --wait   # 주문 Agent(:8081) — 운영 화면의 데이터 원천
cd frontend && npm install && npm run dev    # http://localhost:5173 · /api → order-agent 8081, /service → service 8080(로그인·승인)
#    서버 없이 화면만: npm run dev:mock    (order-agent 와 같은 형식의 메모리 mock: 정상·결함·수정본, 개선안 예시)
#    패키지를 바꾼 뒤 화면이 멈추면: npm run dev:mock -- --force
```

## 검증

```bash
cd service && ./gradlew build            # 컴파일 + 테스트
cd skillops-agent && uv run pytest && uv run ruff check .
cd frontend && npm run lint && npm run build
```

## 필요한 로컬 도구 (팀 공통)

Temurin JDK 21 · Node ≥ 22.18 (npm) · Python 3.11 + uv · Docker Desktop. Gradle 은 wrapper 가 받는다.
