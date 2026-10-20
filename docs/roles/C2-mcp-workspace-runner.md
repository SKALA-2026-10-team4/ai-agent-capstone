# C-2 · MCP 구성 — workspace · 시나리오 러너 · 쓰기 tool (1인)

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

**"판정하는 쪽"을 만든다.** 설계 원칙 "LLM 은 제안하고 harness 가 판정한다" 에서 판정이 실제로 일어나는 곳이 내 시나리오 러너다. 후보 수정안을 live 와 격리된 workspace 에 적용해 dry-run 으로 k회 돌리고 pass/fail 을 돌려준다. 그리고 Agent 가 "쓰는" tool 5개, Agent 를 깨우는 webhook, 승인 뒤 promote 까지. 내부 구조는 내가 정한다.

## 2. 담당 폴더와 연결부

| 폴더 | 이 폴더가 책임지는 것 | 연결부 |
|---|---|---|
| `service/…/workspace/` 중 **sandbox** | ① 버전 저장소에서 skill 본문을 받아 임시 디렉토리에 skill 세트 구성 → **A 의 "skill 세트 소스" 추상화를 구현**해 harness 가 그 경로를 읽게 함 ② unified diff 패치 적용 + 계약 일관성 violations 산출 ③ promote(승인 토큰 검증 → live 저장 → 스모크 → 실패 시 rollback) | ← C-1 버전 저장소(본문 읽기, promote 시 저장) · → A `harness/`(소스 구현) · ← A `registry/`(violations) · ← A `api/`(approve 가 토큰 발급 후 호출) |
| `service/…/scenario/` | 골든 fixture 로드 + 인자로 온 재현 시나리오 수용, **k회 dry-run 실행**(A 흐름 실행기를 workspace 소스 + DRY_RUN 모드로 호출), trace 를 `expect` 와 비교(skills_called 순서, final_status, shipments, assertions), **k회 모두 통과해야 pass**, assertion 평가(`==`, `!=`, `in`) | → A `harness/` · → D `adapter/` DRY_RUN 모드 · ← C-1 `trace/`(결과 trace) · ← D `scenarios/golden/` · ← B(재현 시나리오·의도 assertion) |
| `service/…/webhook/` | `POST http://localhost:8000/diagnose` 에 `failure`/`skill_saved` 이벤트. 비동기, 실패해도 주문 흐름에 영향 없음, URL 은 설정값 | ← A `api/`(skill 저장 시) · ← D `domain/`(주문 FAILED 시) · → B `main.py` |
| `service/…/mcp/` 중 **쓰기 tool 5개 + proposal 저장** | `create_workspace`, `apply_patch`, `run_scenarios`, `promote_workspace`, `rollback` / `save_proposal`(계약 4 검증, **evidence 비면 거부**, DB 저장) | ← B `ablation`·`verify`·`rank` · → A `api/`(proposals 조회). 파일은 tool 묶음 단위로 C-1 과 나눈다 |

## 3. 지금 있는 파일

| 파일 | 하는 일 | 연결부 |
|---|---|---|
| `…/workspace/package-info.java`, `…/scenario/package-info.java`, `…/webhook/package-info.java` | 패키지 책임 한 줄 | workspace 는 C-1 과 공유 |
| `…/mcp/PingTools.java` | `@McpTool` 작성 예시 | C-1 소유. 참고만 |
| `service/src/main/resources/application.yml` | MCP 서버 설정(C-1 과 공유). Agent URL 설정값은 여기에 추가 | 전원 |
| `scenarios/golden/g-01.json`, `docs/contracts/scenario.schema.json` | 러너 입력의 정답지 | D 가 작성 |
| `docs/contracts/proposal.schema.json`, `mcp-tools-and-rest.md` | `save_proposal` 검증 기준, tool 인자·반환 | — |

## 4. 다른 역할과 맞출 접점 (1일차 11시까지)

1. **skill 세트 소스 구현 방식** — A 가 interface 를 두고 내가 임시 디렉토리 구현을 끼운다. A 와.
2. **어댑터 모드 전달** — DRY_RUN 을 흐름 실행 호출의 인자로 넘기는지, 다른 방식인지. A·D 와.
3. **버전 저장소 API** — base 버전 본문 읽기, promote 시 저장. C-1 과.
4. **run_scenarios 결과 모양과 assertion 문법** — B 의 verify 가 그대로 의존한다. 계약 3·5.
5. **webhook payload** — 계약 5. B 와. 호출 시점은 A(저장)·D(실패)와.
6. **`mcp/` 파일 분담** — C-1 과 같은 파일을 만지지 않는다.

## 5. 마일스톤

- **1일차 11시**: 접점 1·2·3 확정.
- **1일차 15시**: `create_workspace` + `run_scenarios(g-01)` 이 dry-run 으로 pass. Python 에서 호출 확인.
- **1일차 18시**: B 의 ablation 이 내 tool 로 F1 원인 hunk 를 확정한다.
- **2일차 13시**: `apply_patch`(violations 포함), `save_proposal`(evidence 검증), `promote_workspace` → 스모크 → `rollback`, webhook 두 종류.
- **잘리는 순서**: `rollback` 자동화(수동 PUT 로 대체) → webhook `skill_saved`(failure 만 유지). `run_scenarios` 와 promote 는 빼지 않는다.
- **A 가 늦으면**: 러너는 흐름 실행기 대신 trace fixture 를 돌려주는 가짜 실행기로 먼저 테스트한다.
