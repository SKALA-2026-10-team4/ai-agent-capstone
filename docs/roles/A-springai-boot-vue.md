# A · Spring AI · Boot · Vue (1인)

## 0. 전체 구조 (폴더만)

```
ai-agent-capstone/
├── service/                         Spring Boot 4.1 · 주문 처리 서비스 + Spring AI harness + MCP 서버 (:8080)
│   └── src/main/java/com/capstone4/skillops/
│       ├── api/                     REST 컨트롤러. Vue 가 부르는 유일한 입구 (/api/*)
│       ├── harness/                 SKILL.md 로더 + skill 실행기(Spring AI ChatClient). "skill 을 돌리는 엔진"
│       ├── registry/                frontmatter 레지스트리 + 계약 일관성 검사
│       ├── domain/                  주문·재고·결제·배송 도메인 (현업 서비스 본체)
│       ├── adapter/                 재고·결제·배송·알림 mock 어댑터 — harness 가 @Tool 로 호출
│       ├── trace/                   실행 트레이스 저장 (어떤 skill 이 무엇을 받고 무엇을 냈나)
│       ├── workspace/               SKILL.md 버전 저장소 + sandbox workspace (격리 복사본)
│       ├── scenario/                시나리오 러너 (dry-run k회, 기대값 비교)
│       ├── webhook/                 저장·장애 이벤트를 SkillOps Agent 에 POST
│       └── mcp/                     @McpTool 12개 — SkillOps Agent 만 쓰는 창구 (/mcp)
├── skillops-agent/                  Python 3.11 · SkillOps Agent (:8000)
│   ├── skillops_agent/              패키지 — 진단 파이프라인 ①~⑥, MCP 클라이언트, LLM 호출
│   ├── scripts/                     smoke 테스트, stub MCP 서버
│   └── tests/
├── frontend/                        Vue 3.5 + Vite · 담당자/운영자 화면 (:5173, /api → 8080 프록시)
│   └── src/                         views/ components/ stores/ router/ api/
├── skills/                          live SKILL.md 6개 — 서비스가 읽는 원본
├── scenarios/                       golden/ 정상 주문 6건 · failures/ 장애 F1~F3
├── docs/                            설계도, contracts/(계약 5개), roles/(이 문서들)
└── docker-compose.yml               PostgreSQL 17 (:5432)
```

**한 문장 아키텍처.** 담당자가 Vue 에서 `skills/` 의 SKILL.md 를 고치면(저장 = 버전) → service 의 harness 가 그 skill 들로 주문을 처리하고 모든 실행을 trace 로 남긴다 → 주문이 실패하거나 skill 이 저장되면 webhook 이 SkillOps Agent 를 깨운다 → Agent 는 **MCP tool 12개만으로** trace·diff 를 읽고, workspace 에 후보 수정안을 적용해 시나리오를 돌려 검증하고, 통과한 수정안을 proposal 로 저장한다 → 운영자가 Vue 에서 승인하면 service 가 workspace 를 live 로 promote 한다.

경계 두 개만 기억하면 된다: **Vue ↔ service 는 REST(`/api`)**, **Agent ↔ service 는 MCP(`/mcp`, JSON-RPC 2.0)**. 둘 다 `docs/contracts/` 에 있다.

## 1. 내 역할

**skill 을 돌리는 엔진을 만들고, 그 엔진을 REST 로 열고, 화면을 붙인다.** 다른 네 사람이 전부 내 "흐름 실행기" 위에서 일하므로, 그 호출 모양을 1일차 오전에 가장 먼저 확정한다. 파일을 어떻게 나눌지는 내가 정한다.

## 2. 담당 폴더와 연결부

| 폴더 | 이 폴더가 책임지는 것 | 연결부 |
|---|---|---|
| `service/` 루트 (`build.gradle`, `settings.gradle`, `application.yml`) | 의존성, Anthropic 모델, MCP 서버 전송, DB 접속 | 전원이 영향 받음. 의존성 추가는 공지 후 하루 두 번만 |
| `service/…/harness/` | ① SKILL.md 로더: frontmatter(YAML)+본문 파싱, 본문은 검사하지 않음 ② skill 1개 실행: 본문 → system prompt, 컨텍스트의 `inputs` 키 → user message, 어댑터 `@Tool` 주입, `outputs` 키 추출 ③ `order-flow.steps` 순서 실행, 앞 단계 FAILED 면 중단 | ← D `adapter/` 의 `@Tool` 빈을 받아 주입 · ← C-2 `workspace/` 가 "어느 디렉토리의 skill 세트를 읽을지" 를 넘겨줌(live 는 `skills/`) · → C-1 `trace/` 의 기록 API 를 step 마다 호출 · ← D `domain/` 이 live 실행을 요청 · ← C-2 `scenario/` 가 dry-run 실행을 요청 |
| `service/…/registry/` | 로드된 skill 정의 보관, steps·의존 그래프, **계약 일관성 검사**(steps 순서대로 `inputs ⊆ order.* ∪ 앞선 outputs`) | → C-1 `mcp/` 읽기 tool(list_skills, get_dependency_graph)이 조회 · → C-2 가 apply_patch 응답의 violations 에 사용 |
| `service/…/api/` | REST 전부(계약 5): skills 조회·저장·버전, orders 생성·trace, proposals 조회·승인·rollback | ← `frontend/` 만 호출 · 저장(PUT) → C-1 버전 저장소 · 저장 후 → C-2 `webhook/`(skill_saved) · 주문 생성 → D `domain/` · approve → C-2 promote |
| `frontend/` | 화면 3개: SKILL.md 편집기(저장=버전, 이전 버전 diff), 주문 생성+trace 타임라인, 수정안 비교(후보×5기준)·의도 확인·승인/롤백 | → `api/` 만 호출. 응답 모양은 계약 2(trace)·4(proposal). 1일차엔 계약 문서의 예시 JSON 을 mock 으로 |

## 3. 지금 있는 파일

| 파일 | 하는 일 | 연결부 |
|---|---|---|
| `service/build.gradle`, `settings.gradle` | Boot 4.1.0, Spring AI BOM 2.0.0, MCP server webmvc, JPA, springdoc | 전원 |
| `service/src/main/resources/application.yml` | `spring.ai.model.chat=anthropic`, 모델 `claude-opus-5-5`, MCP `protocol: STREAMABLE`(`/mcp`), Postgres env | 전원 |
| `…/SkillopsServiceApplication.java` | 진입점 | — |
| `…/api/HealthController.java`, `…/api/HealthControllerTest.java` | `/api/health`, `@WebMvcTest` 예시 | 컨트롤러·테스트 작성 패턴 |
| `…/harness/package-info.java`, `…/registry/package-info.java` | 패키지 책임 한 줄 | — |
| `frontend/vite.config.js` | `/api` → 8080 프록시 | api/ |
| `frontend/src/router/index.js`, `src/stores/counter.js`, `App.vue`, `main.js` | create-vue 뼈대(Router, Pinia) | — |

## 4. 다른 역할과 맞출 접점 (1일차 11시까지)

1. **skill 정의 타입** — 필드는 계약 1 의 frontmatter + 본문. C-1(list_skills), C-2(workspace), D(skills 작성)가 같은 걸 본다.
2. **흐름 실행 호출 모양** — "주문 입력 + skill 세트 소스 + 어댑터 모드(LIVE/DRY_RUN) → trace". D 가 live 로, C-2 가 dry-run 으로 부른다. 소스 추상화는 내가 interface 로 두고 C-2 가 workspace 구현을 끼운다.
3. **trace 기록 API** — step 마다 무엇을 넘길지(계약 2 의 step 필드) C-1 과.
4. **어댑터 주입 방식** — D 의 `@Tool` 빈을 ChatClient 에 어떻게 넘기고, 모드를 어떻게 전달할지 D 와.

## 5. 마일스톤

- **1일차 11시**: 위 접점 4개 확정(interface 만이라도 커밋).
- **1일차 18시**: `POST /api/orders` 가 live skills 6개로 끝까지 돌고 trace 가 DB 에 남는다. 화면은 편집기 + trace 뷰(mock JSON).
- **2일차 13시**: proposals REST + 수정안 화면이 실제 proposal 을 그리고, 승인이 promote 까지 간다.
- **부하 주의**: 다섯 역할 중 가장 많다. 밀리면 proposals REST 는 C-2 에게, 수정안 화면은 2일차 오후로. progressive disclosure 같은 로더 고급 기능은 만들지 않는다.
