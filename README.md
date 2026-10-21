# SkillOps Agent — Capstone 4조

업무 담당자가 SKILL.md 를 고쳐 깨진 에이전트 서비스를, 실행 증거로 진단하고 **검증된 수정안**을 제안하는 에이전트.
설계도: `docs/skillops-design.md` · 계약: `docs/contracts/` · **역할별 작업 가이드: `docs/roles/`** (각자 자기 파일 하나만 읽으면 된다)

## 전체 흐름

담당자가 Skill을 고쳐 저장하면, 주문 Agent가 저장 이벤트를 service로 넘기고, B 진단 Agent가 원인을 찾아 수정안을 검증한 뒤 제안서를 저장합니다. 운영자는 받은 제안서 화면에서 후보를 골라 승인하거나 거절합니다. 노드 색은 실행 단위입니다: 파랑은 화면(`frontend/`), 초록은 주문 Agent(`order-agent/`), 노랑은 SkillOps service(`service/`), 보라는 B 진단 Agent(`skillops-agent/`), 분홍은 OpenAI, 회색은 저장소입니다. 단계별 설명과 코드 위치는 `docs/architecture/skill-change-to-proposal.md`에 있습니다.

```mermaid
%%{init: {"flowchart": {"curve": "linear", "nodeSpacing": 40, "rankSpacing": 45}}}%%
flowchart TD
    %% 같은 줄의 좌우 순서는 노드를 선언한 순서를 따릅니다: 왼쪽 옆 노드, 주 흐름, 오른쪽 옆 노드
    %% G1~G6은 주 흐름이 한 세로줄에 서도록 좌우 균형을 맞추는 빈 칸이고, 화면에 보이지 않습니다
    UI1["<span style='font-size:1.5em'>①</span> Skill 편집·저장<br/>Skill 편집 화면"]
    OA1["<span style='font-size:1.5em'>②</span> 수정본 저장<br/>새 Skill 세트와 저장 이벤트를 함께 기록"]
    DB1[("주문 Agent DB<br/>Skill 세트 · 저장 이벤트 · 실행 기록")]
    OA2["<span style='font-size:1.5em'>③</span> 저장 이벤트 전달<br/>3초마다 하나씩"]
    G1[" "]
    SV1["<span style='font-size:1.5em'>④</span> 진단 요청·조회 중계"]
    G2[" "]
    AG1["<span style='font-size:1.5em'>⑤</span> 진단 접수<br/>변경 번호로 중복 확인, 진행 기록"]
    UI2["<span style='font-size:1.5em'>⑪</span> 진단 진행 보기<br/>검증·진단 기록 화면"]
    DB3[("B 진단 기록<br/>SQLite")]
    AG2["<span style='font-size:1.5em'>⑥</span> 증거 수집·사전 검증<br/>기준 세트와 수정본으로 기준 주문 시험"]
    G3[" "]
    END1(["진단 종료, 제안서 없음<br/>기준 세트 실패 · 회귀 통과 · 결과만 다름"])
    AG3["<span style='font-size:1.5em'>⑦</span> 원인 찾기<br/>가설을 세우고 바뀐 부분을 되돌려 재현"]
    OA3["조회 API<br/>Skill 세트 · Skill 원문 · 실행 기록"]
    G4[" "]
    AG4["<span style='font-size:1.5em'>⑧</span> 의도 정리·수정안 만들기"]
    LLM["OpenAI<br/>가설 · 의도 · 수정안 · 설명"]
    AG5["<span style='font-size:1.5em'>⑨</span> 수정안 검증 판정·순위<br/>합격이 없으면 최대 3번 다시 만듦"]
    G5[" "]
    SV3["<span style='font-size:1.5em'>⑩</span> 제안서 저장 (save_proposal)<br/>근거 실행 대조 · 합격 재계산 · 본문 작성"]
    SV2["MCP 도구<br/>diff_skill · get_dependency_graph<br/>create_workspace · apply_patch · run_scenarios<br/>시험한 실행은 시험 실행 장부에 기록"]
    DB2[("service DB<br/>시험 실행 장부 · 제안서 · 승인")]
    UI3["<span style='font-size:1.5em'>⑫</span> 제안서 읽기·승인/거절<br/>받은 제안서 화면"]
    G6[" "]
    OA4["모의 실행<br/>후보 Skill 세트로 주문 처리, 재고 복원"]
    SV4["<span style='font-size:1.5em'>⑬</span> 제안서 조회·결정 기록"]

    UI1 -->|"HTTP 저장 요청"| OA1
    OA1 -.-> DB1
    OA1 --> OA2
    OA1 ~~~ G1
    OA2 -->|"HTTP"| SV1
    SV1 ~~~ G2
    SV1 -->|"HTTP /diagnose"| AG1
    SV1 -->|"진행 기록 중계"| UI2
    AG1 -.-> DB3
    AG1 --> AG2
    AG1 ~~~ G3
    AG2 -->|"주문이 멈추지 않음"| END1
    AG2 -->|"실행 오류로 멈춤"| AG3
    AG2 -->|"<span style='font-size:1.5em'>⑥</span>~<span style='font-size:1.5em'>⑧</span> HTTP 조회"| OA3
    AG3 ~~~ G4
    AG3 --> AG4
    AG3 -->|"<span style='font-size:1.5em'>⑦</span>~<span style='font-size:1.5em'>⑨</span> AI 호출"| LLM
    AG4 --> AG5
    AG5 ~~~ G5
    AG5 -->|"MCP save_proposal"| SV3
    AG5 -->|"<span style='font-size:1.5em'>⑥</span>~<span style='font-size:1.5em'>⑨</span> MCP 호출"| SV2
    SV3 -.-> DB2
    SV3 -->|"받은 제안서에 나타남"| UI3
    SV3 ~~~ G6
    SV2 -->|"HTTP 후보 등록·모의 실행"| OA4
    UI3 -->|"HTTP 승인·거절"| SV4

    classDef ui fill:#DBEAFE,stroke:#3B82F6,color:#1E3A8A
    classDef oa fill:#DCFCE7,stroke:#22C55E,color:#14532D
    classDef svc fill:#FEF3C7,stroke:#F59E0B,color:#78350F
    classDef agent fill:#EDE9FE,stroke:#8B5CF6,color:#4C1D95
    classDef llm fill:#FCE7F3,stroke:#EC4899,color:#831843
    classDef store fill:#F1F5F9,stroke:#94A3B8,color:#334155
    classDef stop fill:#FFFFFF,stroke:#94A3B8,color:#475569,stroke-dasharray:4 3
    classDef ghost fill:none,stroke:none,color:transparent

    class UI1,UI2,UI3 ui
    class OA1,OA2,OA3,OA4 oa
    class SV1,SV2,SV3,SV4 svc
    class AG1,AG2,AG3,AG4,AG5 agent
    class LLM llm
    class DB1,DB2,DB3 store
    class END1 stop
    class G1,G2,G3,G4,G5,G6 ghost
```

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

## 로그인 · 승인 (service)

운영 화면 로그인과 수정안 승인만 service(8080)가 JWT(HS256, 2시간)로 보호한다. **그 밖의 API 와 MCP(`/mcp`)는 지금처럼 인증 없이 열려 있다.**

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
| 그 밖의 `/api/**`, `/mcp`, Swagger | 없음 | 다른 담당자의 API·MCP 는 인증 때문에 막히지 않는다 |

- 승인·거절은 저장된 제안서(`proposals`)의 상태를 바꾸고 `approvals` 테이블(계약 5 저장 공간, 담당 A)에 승인자·후보 번호·의도 확인·`approval_token` 을 남긴다. skillops-agent 로는 보내지 않는다. `used_at` 은 운영 반영(promote_workspace, 아직 없음)이 채운다.
- `@WebMvcTest` 에도 같은 보안 규칙이 자동 적용된다(`service/src/test/resources/META-INF/spring/…AutoConfigureWebMvc.imports`). 새 API 에 로그인이 필요하면 `auth/SecurityConfig` 에 한 줄 추가한다.
- 키·비밀번호 해시·서비스 토큰은 `.env.example` 참고. `SKILLOPS_JWT_SECRET` 을 비우면 기동마다 임시 키를 만든다(재시작하면 다시 로그인).

## 검증

```bash
cd service && ./gradlew build            # 컴파일 + 테스트
cd skillops-agent && uv run pytest && uv run ruff check .
cd frontend && npm run lint && npm run build
```

## 필요한 로컬 도구 (팀 공통)

Temurin JDK 21 · Node ≥ 22.18 (npm) · Python 3.11 + uv · Docker Desktop. Gradle 은 wrapper 가 받는다.
